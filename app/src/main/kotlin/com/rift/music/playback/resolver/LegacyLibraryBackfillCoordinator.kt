/**
 * Napster (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.rift.music.playback.resolver

import android.content.Context
import com.rift.music.db.DatabaseDao
import com.rift.music.db.entities.ProviderMappingEntity
import com.rift.music.feature.spotifyimport.PlaylistEnrichmentWorker
import com.rift.music.playback.diagnostics.PlaybackDiagnostics
import com.rift.music.playback.diagnostics.PlaybackEventType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Coordinates backward-compatible metadata normalization, identity backfill,
 * and background enrichment for libraries and playlists imported prior to
 * the Persistent Playlist Intelligence architecture.
 *
 * Guaranteed Properties:
 * 1. Idempotent: Can safely run multiple times without duplicating tracks, entries, or mappings.
 * 2. Non-Destructive: Never wipes or overwrites richer existing user metadata, history, or playlists.
 * 3. Bounded & Polite: Normalizes identities in local SQLite transactions and schedules network
 *    enrichment through WorkManager rather than making thousands of indiscriminate network requests.
 * 4. Playable: Existing playlists remain immediately playable during backfill via just-in-time resolution.
 */
@Singleton
class LegacyLibraryBackfillCoordinator @Inject constructor(
    private val database: DatabaseDao,
    private val providerMappingRepository: ProviderMappingRepository,
) {
    /**
     * Executes the idempotent backfill and normalization process.
     */
    suspend fun runBackfill(context: Context? = null): BackfillResult = withContext(Dispatchers.IO) {
        Timber.tag(TAG).i("Starting legacy library identity scan and backfill")
        var normalizedCount = 0
        var batch: List<com.rift.music.db.entities.SongEntity>

        // Phase 1: Fast local metadata normalization for pre-feature songs
        do {
            batch = database.getSongsNeedingIdentityBackfill(limit = 100)
            for (song in batch) {
                val id = song.id
                when {
                    id.startsWith("spotify:") -> {
                        val sourceId = id.removePrefix("spotify:")
                        val existingMapping = database.getProviderMapping(sourceId, "youtube")
                        val hasMatch = existingMapping != null && existingMapping.matchStatus != "FAILED"
                        database.updateSongIdentity(
                            songId = id,
                            sourceId = sourceId,
                            sourceProvider = "spotify",
                            matchedVideoId = existingMapping?.providerTrackId ?: song.matchedVideoId,
                            confidence = existingMapping?.confidence ?: song.matchConfidence,
                            status = if (hasMatch) "MATCHED" else (song.matchStatus ?: "UNRESOLVED"),
                            isEnriched = hasMatch,
                        )
                    }
                    song.isLocal || id.startsWith("content://") || id.startsWith("file://") || id.startsWith("/storage/") -> {
                        database.updateSongIdentity(
                            songId = id,
                            sourceId = id,
                            sourceProvider = "local",
                            matchedVideoId = id,
                            confidence = 1.0f,
                            status = "MATCHED",
                            isEnriched = true,
                        )
                    }
                    else -> {
                        // Standard YouTube / InnerTube song
                        database.updateSongIdentity(
                            songId = id,
                            sourceId = id,
                            sourceProvider = "youtube",
                            matchedVideoId = id,
                            confidence = 1.0f,
                            status = "MATCHED",
                            isEnriched = true,
                        )
                        // Insert synthetic provider mapping for instant O(1) index hit
                        database.insert(
                            ProviderMappingEntity(
                                sourceId = id,
                                sourceProvider = "youtube",
                                providerTrackId = id,
                                provider = "youtube",
                                confidence = 1.0f,
                                matchStatus = "MATCHED",
                                verifiedAt = System.currentTimeMillis(),
                            )
                        )
                    }
                }
                normalizedCount++
            }
        } while (batch.isNotEmpty())

        // Phase 2: Prioritized Resolution for Recently Played Tracks
        val recentSongIds = database.getRecentEventSongIds(limit = 20)
        for (songId in recentSongIds) {
            if (songId.startsWith("spotify:")) {
                val sourceId = songId.removePrefix("spotify:")
                if (database.getProviderMapping(sourceId, "youtube") == null) {
                    val song = database.getSongById(songId)
                    if (song != null && !song.song.isEnriched) {
                        try {
                            providerMappingRepository.resolveAndPersist(song, "youtube")
                        } catch (e: Exception) {
                            Timber.tag(TAG).w(e, "Failed prioritized resolution for recent song $songId")
                        }
                    }
                }
            }
        }

        // Phase 3: Schedule background enrichment for playlists with unresolved songs
        val playlistIds = database.getPlaylistIdsWithUnresolvedSongs()
        if (context != null) {
            for (playlistId in playlistIds) {
                try {
                    PlaylistEnrichmentWorker.enqueue(context, playlistId)
                } catch (e: Exception) {
                    Timber.tag(TAG).w(e, "Could not enqueue WorkManager for playlist $playlistId")
                }
            }
        }

        val remainingUnresolved = database.getUnresolvedSongsCount()
        Timber.tag(TAG).i("Legacy backfill completed: normalized=$normalizedCount, scheduledPlaylists=${playlistIds.size}, remainingUnresolved=$remainingUnresolved")

        PlaybackDiagnostics.record(
            eventType = PlaybackEventType.PROVIDER_SELECTION,
            mediaId = "library_backfill",
            title = "Legacy Library Backfill",
            provider = "system",
            playbackState = 0,
            playWhenReady = false,
            isPlaying = false,
            details = "Normalized $normalizedCount songs, scheduled ${playlistIds.size} playlists, $remainingUnresolved pending",
        )

        BackfillResult(
            normalizedCount = normalizedCount,
            scheduledPlaylists = playlistIds.size,
            remainingUnresolved = remainingUnresolved,
        )
    }

    data class BackfillResult(
        val normalizedCount: Int,
        val scheduledPlaylists: Int,
        val remainingUnresolved: Int,
    )

    companion object {
        private const val TAG = "LegacyBackfillCoord"
    }
}
