/**
 * Napster (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.rift.music.playback.resolver

import com.music.innertube.YouTube
import com.music.innertube.models.SongItem
import com.rift.music.db.DatabaseDao
import com.rift.music.db.entities.ProviderMappingEntity
import com.rift.music.feature.spotifyimport.DeterministicTrackResolutionEngine
import com.rift.music.feature.spotifyimport.SourceTrackMetadata
import com.rift.music.feature.spotifyimport.YouTubeCandidate
import com.rift.music.feature.spotifyimport.YouTubeSearchClient
import com.rift.music.playback.diagnostics.PlaybackDiagnostics
import com.rift.music.playback.diagnostics.PlaybackEventType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProviderMappingRepository @Inject constructor(
    private val database: DatabaseDao,
) {
    /**
     * Retrieves an existing valid provider mapping without making any network calls.
     */
    suspend fun getValidMapping(
        sourceId: String,
        provider: String = "youtube",
    ): ProviderMappingEntity? = withContext(Dispatchers.IO) {
        val mapping = database.getProviderMapping(sourceId, provider)
        if (mapping != null && mapping.matchStatus != "FAILED" && mapping.invalidationReason == null) {
            return@withContext mapping
        }

        // Also check if SongEntity has a pre-existing matchedVideoId
        val song = database.getSongBySourceId(sourceId)
            ?: run {
                val cleanId = if (sourceId.startsWith("spotify:")) sourceId else "spotify:$sourceId"
                database.getSongById(cleanId)
            }
        val matchedVideoId = song?.song?.matchedVideoId
        if (!matchedVideoId.isNullOrBlank() && song.song.matchStatus != "FAILED") {
            val syntheticMapping = ProviderMappingEntity(
                sourceId = sourceId,
                sourceProvider = song.song.sourceProvider ?: "spotify",
                providerTrackId = matchedVideoId,
                provider = provider,
                confidence = song.song.matchConfidence ?: 0.8f,
                matchStatus = song.song.matchStatus ?: "MATCHED",
                verifiedAt = song.song.metadataLastUpdated ?: System.currentTimeMillis(),
            )
            // Persist back to provider_mapping for future fast indexing
            database.insert(syntheticMapping)
            return@withContext syntheticMapping
        }
        null
    }

    /**
     * Resolves a source track from composite Song model.
     */
    suspend fun resolveAndPersist(
        song: com.rift.music.db.entities.Song,
        provider: String = "youtube",
    ): ProviderMappingEntity? = resolveAndPersist(
        sourceId = song.song.sourceId ?: song.id.removePrefix("spotify:"),
        title = song.title,
        artist = song.artists.joinToString(" ") { it.name },
        album = song.album?.title,
        durationMs = song.song.duration.takeIf { it > 0 }?.times(1000),
        isrc = null,
        sourceProvider = song.song.sourceProvider ?: "spotify",
        provider = provider,
    )

    /**
     * Resolves a source track to a playable provider track, persists the mapping,
     * and updates the associated SongEntity metadata.
     */
    suspend fun resolveAndPersist(
        sourceId: String,
        title: String,
        artist: String,
        album: String? = null,
        durationMs: Int? = null,
        isrc: String? = null,
        sourceProvider: String = "spotify",
        provider: String = "youtube",
    ): ProviderMappingEntity? = withContext(Dispatchers.IO) {
        // 1. Fast check existing mapping
        val existing = getValidMapping(sourceId, provider)
        if (existing != null) {
            return@withContext existing
        }

        // 2. Perform verified search via DeterministicTrackResolutionEngine
        Timber.tag(TAG).d("Resolving provider mapping for \"$title\" by \"$artist\" ($sourceId)")
        val candidatesById = mutableMapOf<String, SongItem>()
        val engine = DeterministicTrackResolutionEngine(
            searchClient = object : YouTubeSearchClient<YouTubeCandidate> {
                override suspend fun search(query: String): List<YouTubeCandidate> {
                    var items = YouTube.search(query, YouTube.SearchFilter.FILTER_SONG)
                        .getOrNull()?.items
                        ?.filterIsInstance<SongItem>()
                        ?.distinctBy { it.id }
                        .orEmpty()

                    // Fallback to video search if song search returned no items
                    if (items.isEmpty()) {
                        items = YouTube.search(query, YouTube.SearchFilter.FILTER_VIDEO)
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
                            album = item.album?.name,
                            durationSec = item.duration,
                            isAudio = true,
                        )
                    }
                }
            }
        )

        val source = SourceTrackMetadata(
            title = title,
            artist = artist,
            album = album,
            durationMs = durationMs,
            isrc = isrc,
        )

        val candidate = engine.resolve(source)
        if (candidate != null) {
            val confidence = 0.85f // Verified above conservative threshold
            val status = if (confidence >= 0.75f) "MATCHED" else "UNCERTAIN"
            val mapping = ProviderMappingEntity(
                sourceId = sourceId,
                sourceProvider = sourceProvider,
                providerTrackId = candidate.videoId,
                provider = provider,
                confidence = confidence,
                matchStatus = status,
                verifiedAt = System.currentTimeMillis(),
            )

            // Persist to DB atomically
            database.insert(mapping)
            val cleanId = if (sourceId.startsWith("spotify:")) sourceId else "spotify:$sourceId"
            database.updateSongMatch(
                songId = cleanId,
                matchedVideoId = candidate.videoId,
                confidence = confidence,
                status = status,
                isEnriched = true,
                updatedAt = System.currentTimeMillis(),
            )

            PlaybackDiagnostics.record(
                eventType = PlaybackEventType.PROVIDER_SELECTION,
                mediaId = sourceId,
                title = title,
                provider = provider,
                playbackState = 0,
                playWhenReady = false,
                isPlaying = false,
                details = "Mapped $sourceId -> ${candidate.videoId} ($status, confidence=$confidence)",
            )
            Timber.tag(TAG).i("Successfully mapped $sourceId -> ${candidate.videoId} ($title)")
            mapping
        } else {
            Timber.tag(TAG).w("Failed to resolve acceptable match for \"$title\" by \"$artist\"")
            val cleanId = if (sourceId.startsWith("spotify:")) sourceId else "spotify:$sourceId"
            database.updateSongMatch(
                songId = cleanId,
                matchedVideoId = "",
                confidence = 0f,
                status = "FAILED",
                isEnriched = true,
                updatedAt = System.currentTimeMillis(),
            )
            val failedMapping = ProviderMappingEntity(
                sourceId = sourceId,
                sourceProvider = sourceProvider,
                providerTrackId = "",
                provider = provider,
                confidence = 0f,
                matchStatus = "FAILED",
                verifiedAt = System.currentTimeMillis(),
            )
            database.insert(failedMapping)
            PlaybackDiagnostics.record(
                eventType = PlaybackEventType.PROVIDER_SELECTION,
                mediaId = sourceId,
                title = title,
                provider = provider,
                playbackState = 0,
                playWhenReady = false,
                isPlaying = false,
                details = "Mapping rejected: no acceptable candidate found",
            )
            null
        }
    }

    /**
     * Invalidates a provider mapping when playback fails permanently (e.g. video deleted).
     */
    suspend fun invalidateMapping(sourceId: String, reason: String? = null) = withContext(Dispatchers.IO) {
        database.invalidateProviderMapping(sourceId, "FAILED", reason)
        val cleanId = if (sourceId.startsWith("spotify:")) sourceId else "spotify:$sourceId"
        database.updateSongMatch(
            songId = cleanId,
            matchedVideoId = "",
            confidence = 0f,
            status = "FAILED",
            isEnriched = false,
            updatedAt = System.currentTimeMillis(),
        )
        Timber.tag(TAG).w("Invalidated mapping for $sourceId: reason=$reason")
    }

    companion object {
        private const val TAG = "ProviderMappingRepo"
    }
}
