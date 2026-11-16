package com.rift.music.feature.spotifyimport

import android.content.Context
import com.rift.music.db.MusicDatabase
import com.rift.music.db.entities.ArtistEntity
import com.rift.music.db.entities.PlaylistEntity
import com.rift.music.db.entities.PlaylistSongMap
import com.rift.music.db.entities.Song
import com.rift.music.db.entities.SongEntity
import com.rift.music.models.MediaMetadata
import com.rift.music.models.toMediaMetadata
import com.rift.music.utils.reportException
import com.music.innertube.YouTube
import com.music.innertube.models.SongItem
import com.music.spotify.PublicSpotifyMetadataClient
import com.music.spotify.SpotifyMetadataClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.time.LocalDateTime

/**
 * Robust, memory-bounded importer for public Spotify playlists.
 * Handles large playlists (4,000+ tracks) incrementally in paged batches with:
 * - Durable checkpoint persistence surviving process death and device restarts.
 * - Resuming from the exact last persisted batch without duplicate tracks or missing items.
 * - Atomic database transactions per batch committing tracks and checkpoints together.
 * - Mutual exclusion preventing concurrent workers from processing the same import.
 * - Bounded concurrency against YouTube Music search and resolution.
 */
class SpotifyPlaylistImporter(
    private val database: MusicDatabase,
    private val context: Context? = null,
    private val spotifyClient: SpotifyMetadataClient = PublicSpotifyMetadataClient(),
) {
    /**
     * Comprehensive import workflow emitting structured [SpotifyImportState] updates.
     */
    suspend fun importPlaylist(
        link: String,
        onProgress: suspend (SpotifyImportState) -> Unit,
    ): SpotifyImportState {
        val playlistId = extractPlaylistId(link)
            ?: throw IllegalArgumentException("Invalid Spotify playlist link or ID")

        if (!SpotifyImportLock.tryAcquire(playlistId)) {
            throw IllegalStateException("An import for playlist $playlistId is already running.")
        }

        val dbPlaylistId = "SPOTIFY_PLAYLIST_LINK_$playlistId"

        try {
            // Check for an existing durable checkpoint to support resuming
            val existingCheckpoint = context?.let { SpotifyImportCheckpointStore.getCheckpointForPlaylist(it, playlistId) }
                ?: run {
                    val entity = database.playlist(dbPlaylistId).first()?.playlist
                    entity?.description?.let { SpotifyImportCheckpoint.fromJsonString(it) }
                }

            val isResuming = existingCheckpoint != null &&
                !existingCheckpoint.isCancelled &&
                existingCheckpoint.status != SpotifyImportStatus.COMPLETED &&
                existingCheckpoint.status != SpotifyImportStatus.COMPLETED_WITH_ERRORS &&
                existingCheckpoint.nextOffset > 0

            val resumeCheckpoint = if (isResuming) existingCheckpoint else null

            var startOffset = resumeCheckpoint?.nextOffset ?: 0
            var totalTracks = resumeCheckpoint?.totalTracks ?: 0
            var importedCount = resumeCheckpoint?.importedCount ?: 0
            var skippedCount = resumeCheckpoint?.skippedCount ?: 0
            var failedCount = resumeCheckpoint?.failedCount ?: 0
            var resolvedPlaylistName = resumeCheckpoint?.playlistName ?: "Spotify playlist"
            var resolvedCoverUrl: String? = resumeCheckpoint?.artworkUrl
            var batchIndex = (resumeCheckpoint?.lastBatchIndex ?: -1) + 1

            val existingSongCount = database.playlistSongs(dbPlaylistId).first().size
            var currentPosition = if (isResuming) existingSongCount else 0

            Timber.tag(TAG).i(
                "Starting Spotify import for $playlistId: isResuming=$isResuming, startOffset=$startOffset, currentPosition=$currentPosition"
            )

            var currentState = if (resumeCheckpoint != null) {
                resumeCheckpoint.toState().copy(
                    status = SpotifyImportStatus.FETCHING_PLAYLIST,
                    currentStage = "Resuming from track $startOffset...",
                )
            } else {
                SpotifyImportState(
                    status = SpotifyImportStatus.FETCHING_PLAYLIST,
                    playlistId = playlistId,
                    currentStage = "Fetching playlist details...",
                    isIndeterminate = true,
                )
            }
            onProgress(currentState)

            // Pre-create PlaylistEntity so foreign key constraints on playlist_song_map are satisfied
            val preExisting = database.playlist(dbPlaylistId).first()
            if (preExisting == null) {
                val preNow = LocalDateTime.now()
                database.insert(
                    PlaylistEntity(
                        id = dbPlaylistId,
                        name = resolvedPlaylistName,
                        bookmarkedAt = preNow,
                        lastUpdateTime = preNow,
                        thumbnailUrl = resolvedCoverUrl,
                        remoteSongCount = totalTracks,
                        description = "Importing from Spotify...",
                        isEditable = true,
                    )
                )
            }

            val semaphore = Semaphore(3)
            var hasEmittedAnyBatch = isResuming

            spotifyClient.getPlaylistTracksPaged(playlistId, startOffset = startOffset, pageSize = 100).collect { batch ->
                currentCoroutineContext().ensureActive()
                hasEmittedAnyBatch = true
                resolvedPlaylistName = batch.playlistName
                resolvedCoverUrl = batch.coverUrl
                if (batch.totalCount > 0) {
                    totalTracks = batch.totalCount
                }

                // Step 1: Concurrently resolve batch tracks with bounded semaphore, polite pacing and per-track timeout
                val resolvedBatch = coroutineScope {
                    batch.tracks.mapIndexed { index, track ->
                        async {
                            currentCoroutineContext().ensureActive()
                            semaphore.withPermit {
                                currentCoroutineContext().ensureActive()
                                // Add polite inter-request pacing so YouTube Music search does not rate-limit
                                if (index > 0) {
                                    delay(40)
                                }
                                try {
                                    withTimeoutOrNull(12_000L) {
                                        val song = Song(
                                            song = SongEntity(
                                                id = track.id,
                                                title = track.name,
                                                duration = track.durationMs / 1000,
                                                thumbnailUrl = track.album?.images?.firstOrNull()?.url ?: batch.coverUrl,
                                            ),
                                            artists = track.artists.map { artist ->
                                                ArtistEntity(id = artist.id ?: "", name = artist.name)
                                            },
                                        )
                                        resolveTrack(song)
                                    }
                                } catch (e: Exception) {
                                    if (e is CancellationException) throw e
                                    Timber.tag(TAG).w(e, "Resolution failed for track ${track.name}")
                                    reportException(e)
                                    null
                                }
                            }
                        }
                    }.awaitAll()
                }

                var batchImported = 0
                var batchFailed = 0

                val newNextOffset = batch.offset + batch.tracks.size
                val isLastBatch = batch.isLast || (totalTracks > 0 && newNextOffset >= totalTracks)

                val nextStatus = when {
                    isLastBatch && (importedCount + batchImported) == 0 -> SpotifyImportStatus.FAILED
                    isLastBatch && (failedCount + batchFailed) > 0 -> SpotifyImportStatus.COMPLETED_WITH_ERRORS
                    isLastBatch -> SpotifyImportStatus.COMPLETED
                    else -> SpotifyImportStatus.PROCESSING
                }

                // Step 2: Commit tracks AND checkpoint in a single atomic Room transaction
                database.withTransaction {
                    val now = LocalDateTime.now()
                    val existing = playlist(dbPlaylistId).first()

                    // CRITICAL: Ensure PlaylistEntity exists BEFORE inserting any PlaylistSongMap entries
                    // to satisfy SQLite foreign key constraint (code 787).
                    if (existing == null) {
                        val initialEntity = PlaylistEntity(
                            id = dbPlaylistId,
                            name = resolvedPlaylistName,
                            bookmarkedAt = now,
                            lastUpdateTime = now,
                            thumbnailUrl = resolvedCoverUrl,
                            remoteSongCount = totalTracks,
                            description = "Importing from Spotify...",
                            isEditable = true,
                        )
                        insert(initialEntity)
                    }

                    for (matched in resolvedBatch) {
                        if (matched != null) {
                            insert(matched)
                            insert(
                                PlaylistSongMap(
                                    playlistId = dbPlaylistId,
                                    songId = matched.id,
                                    position = currentPosition++,
                                    setVideoId = matched.setVideoId,
                                ),
                            )
                            batchImported++
                        } else {
                            batchFailed++
                        }
                    }

                    val updatedCheckpoint = SpotifyImportCheckpoint(
                        importId = playlistId,
                        playlistUrl = link,
                        playlistName = resolvedPlaylistName,
                        artworkUrl = resolvedCoverUrl,
                        totalTracks = totalTracks,
                        nextOffset = newNextOffset,
                        lastBatchIndex = batchIndex++,
                        importedCount = importedCount + batchImported,
                        skippedCount = skippedCount,
                        failedCount = failedCount + batchFailed,
                        status = nextStatus,
                        isCancelled = false,
                        timestampMs = System.currentTimeMillis(),
                    )

                    val current = playlist(dbPlaylistId).first()?.playlist
                    val entity = current?.copy(
                        name = resolvedPlaylistName,
                        bookmarkedAt = current.bookmarkedAt ?: now,
                        lastUpdateTime = now,
                        thumbnailUrl = resolvedCoverUrl?.ifBlank { current.thumbnailUrl } ?: current.thumbnailUrl,
                        remoteSongCount = totalTracks,
                        description = if (isLastBatch) {
                            "Imported from Spotify ($totalTracks tracks)"
                        } else {
                            SpotifyImportCheckpoint.CHECKPOINT_PREFIX + updatedCheckpoint.toJsonString()
                        },
                        isEditable = true,
                    ) ?: PlaylistEntity(
                        id = dbPlaylistId,
                        name = resolvedPlaylistName,
                        bookmarkedAt = now,
                        lastUpdateTime = now,
                        thumbnailUrl = resolvedCoverUrl,
                        remoteSongCount = totalTracks,
                        description = if (isLastBatch) {
                            "Imported from Spotify ($totalTracks tracks)"
                        } else {
                            SpotifyImportCheckpoint.CHECKPOINT_PREFIX + updatedCheckpoint.toJsonString()
                        },
                        isEditable = true,
                    )

                    update(entity)
                }

                importedCount += batchImported
                failedCount += batchFailed

                val currentCheckpoint = SpotifyImportCheckpoint(
                    importId = playlistId,
                    playlistUrl = link,
                    playlistName = resolvedPlaylistName,
                    artworkUrl = resolvedCoverUrl,
                    totalTracks = totalTracks,
                    nextOffset = newNextOffset,
                    lastBatchIndex = batchIndex,
                    importedCount = importedCount,
                    skippedCount = skippedCount,
                    failedCount = failedCount,
                    status = nextStatus,
                    isCancelled = false,
                    timestampMs = System.currentTimeMillis(),
                )

                if (context != null) {
                    SpotifyImportCheckpointStore.saveCheckpoint(context, currentCheckpoint)
                }

                val processedCount = importedCount + skippedCount + failedCount
                if (totalTracks < processedCount) {
                    totalTracks = processedCount
                }

                currentState = SpotifyImportState(
                    status = nextStatus,
                    playlistId = playlistId,
                    playlistName = resolvedPlaylistName,
                    artworkUrl = resolvedCoverUrl,
                    totalTracks = totalTracks,
                    processedTracks = processedCount,
                    importedCount = importedCount,
                    skippedCount = skippedCount,
                    failedCount = failedCount,
                    currentStage = if (isLastBatch) {
                        if (nextStatus == SpotifyImportStatus.COMPLETED) {
                            "Import complete"
                        } else {
                            "Import completed with $failedCount unmatchable tracks"
                        }
                    } else {
                        "Importing tracks ($processedCount / $totalTracks)"
                    },
                    isIndeterminate = totalTracks <= 0,
                )
                onProgress(currentState)
            }

            if (!hasEmittedAnyBatch || (importedCount == 0 && failedCount == 0)) {
                throw IllegalStateException("No tracks found in the Spotify playlist. Make sure it is public.")
            }

            if (currentState.status != SpotifyImportStatus.COMPLETED &&
                currentState.status != SpotifyImportStatus.COMPLETED_WITH_ERRORS
            ) {
                // If stream ended without reaching total tracks or explicit last, evaluate outcome
                val finalStatus = when {
                    importedCount == 0 -> SpotifyImportStatus.FAILED
                    failedCount > 0 -> SpotifyImportStatus.COMPLETED_WITH_ERRORS
                    else -> SpotifyImportStatus.COMPLETED
                }
                currentState = currentState.copy(status = finalStatus)
                onProgress(currentState)
            }

            if (context != null) {
                val finalCheckpoint = SpotifyImportCheckpoint(
                    importId = playlistId,
                    playlistUrl = link,
                    playlistName = resolvedPlaylistName,
                    artworkUrl = resolvedCoverUrl,
                    totalTracks = totalTracks,
                    nextOffset = totalTracks,
                    lastBatchIndex = batchIndex,
                    importedCount = importedCount,
                    skippedCount = skippedCount,
                    failedCount = failedCount,
                    status = currentState.status,
                    isCancelled = false,
                    timestampMs = System.currentTimeMillis(),
                )
                SpotifyImportCheckpointStore.saveCheckpoint(context, finalCheckpoint)
            }

            return currentState
        } catch (e: CancellationException) {
            Timber.tag(TAG).i("Spotify import cancelled for $playlistId")
            if (context != null) {
                SpotifyImportCheckpointStore.markCancelled(context, playlistId)
            }
            val cancelledState = SpotifyImportState(
                status = SpotifyImportStatus.CANCELLED,
                playlistId = playlistId,
                currentStage = "Import cancelled",
            )
            onProgress(cancelledState)
            throw e
        } catch (e: Exception) {
            reportException(e)
            val failedState = SpotifyImportState(
                status = SpotifyImportStatus.FAILED,
                playlistId = playlistId,
                errorMessage = e.message ?: "Failed to import Spotify playlist",
                currentStage = "Import failed",
            )
            onProgress(failedState)
            throw e
        } finally {
            SpotifyImportLock.release(playlistId)
        }
    }

    /**
     * Backward-compatible overload matching legacy callers.
     */
    suspend fun importPlaylistByLink(
        link: String,
        onProgress: suspend (playlistName: String, currentSongIndex: Int, totalSongs: Int) -> Unit,
    ): String {
        val result = importPlaylist(link) { state ->
            onProgress(state.playlistName, state.processedTracks, state.totalTracks)
        }
        return result.playlistName
    }

    private fun sanitizeTitle(title: String): String {
        return title
            .replace(Regex("""\s*-\s*(?:remaster(?:ed)?(?:\s*\d{4})?|live|radio edit|mono|stereo|bonus track|deluxe|anniversary).*""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*[\(\[](?:feat\.|featuring|with|remastered|live|deluxe|bonus|anniversary|radio edit|official).*?[\)\]]""", RegexOption.IGNORE_CASE), "")
            .trim()
    }

    private suspend fun resolveTrack(song: Song): MediaMetadata? {
        val candidatesById = mutableMapOf<String, SongItem>()
        val engine = DeterministicTrackResolutionEngine(
            searchClient = object : YouTubeSearchClient<YouTubeCandidate> {
                override suspend fun search(query: String): List<YouTubeCandidate> {
                    // Pass 1: standard FILTER_SONG search with provided query
                    var items = YouTube.search(query, YouTube.SearchFilter.FILTER_SONG)
                        .getOrNull()?.items
                        ?.filterIsInstance<SongItem>()
                        ?.distinctBy { it.id }
                        .orEmpty()

                    // Pass 2: if no results and cleaned query differs, try cleaned title
                    val cleanedTitle = sanitizeTitle(song.title)
                    val primaryArtist = song.artists.firstOrNull()?.name.orEmpty()
                    val cleanQuery = "$cleanedTitle $primaryArtist".trim()

                    if (items.isEmpty() && cleanQuery.isNotBlank() && cleanQuery != query) {
                        items = YouTube.search(cleanQuery, YouTube.SearchFilter.FILTER_SONG)
                            .getOrNull()?.items
                            ?.filterIsInstance<SongItem>()
                            ?.distinctBy { it.id }
                            .orEmpty()
                    }

                    // Pass 3: if still no results, fallback to FILTER_VIDEO for music video releases
                    if (items.isEmpty()) {
                        val targetQuery = if (cleanQuery.isNotBlank()) cleanQuery else query
                        items = YouTube.search(targetQuery, YouTube.SearchFilter.FILTER_VIDEO)
                            .getOrNull()?.items
                            ?.filterIsInstance<SongItem>()
                            ?.distinctBy { it.id }
                            .orEmpty()
                    }

                    items.forEach { candidatesById[it.id] = it }
                    return items.map { item ->
                        YouTubeCandidate(
                            videoId = item.id,
                            title = item.title,
                            artist = item.artists.joinToString(" ") { it.name },
                            durationSec = item.duration,
                        )
                    }
                }
            },
        )
        val source = SourceTrackMetadata(
            title = song.title,
            artist = song.artists.joinToString(" ") { it.name },
            durationMs = song.song.duration * 1000,
        )
        return engine.resolve(source)?.let { candidatesById[it.videoId]?.toMediaMetadata() }
    }

    companion object {
        private const val TAG = "SpotifyPlaylistImporter"

        fun extractPlaylistId(link: String): String? {
            val cleanLink = link.trim().removeSurrounding("'", "'").removeSurrounding("\"", "\"").trim()
            val uriPrefix = "spotify:playlist:"
            if (cleanLink.startsWith(uriPrefix)) {
                return cleanLink.removePrefix(uriPrefix).substringBefore("?")
            }

            val webRegex = Regex("""(?:open\.)?spotify\.com/(?:[a-zA-Z-]+/)?playlist/([a-zA-Z0-9]+)""")
            val match = webRegex.find(cleanLink)
            if (match != null) {
                return match.groupValues[1]
            }

            if (cleanLink.matches(Regex("^[a-zA-Z0-9]{22}$"))) {
                return cleanLink
            }
            return null
        }
    }
}
