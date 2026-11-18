/**
 * Napster (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.rift.music.feature.spotifyimport

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.rift.music.db.InternalDatabase
import com.rift.music.db.MusicDatabase
import com.rift.music.playback.diagnostics.PlaybackDiagnostics
import com.rift.music.playback.diagnostics.PlaybackEventType
import com.rift.music.playback.resolver.ProviderMappingRepository
import kotlinx.coroutines.delay
import timber.log.Timber

/**
 * Background WorkManager worker that progressively enriches imported playlist tracks
 * by resolving provider mappings (Spotify track -> YouTube video ID) in polite batches.
 * Survives application backgrounding and process death without holding an endless foreground service.
 */
class PlaylistEnrichmentWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val playlistId = inputData.getString(KEY_PLAYLIST_ID) ?: return Result.failure()
        val batchSize = inputData.getInt(KEY_BATCH_SIZE, DEFAULT_BATCH_SIZE)

        Timber.tag(TAG).i("Starting playlist enrichment work for $playlistId (batchSize=$batchSize)")
        PlaybackDiagnostics.record(
            eventType = PlaybackEventType.PREFETCH_STARTED,
            mediaId = playlistId,
            title = null,
            provider = "youtube",
            playbackState = 0,
            playWhenReady = false,
            isPlaying = false,
            details = "Background enrichment worker started for playlist $playlistId",
        )

        val database = InternalDatabase.newInstance(applicationContext)
        val providerMappingRepo = ProviderMappingRepository(database)

        return try {
            val unresolvedSongs = database.getUnresolvedSongsForPlaylist(playlistId, batchSize)
            if (unresolvedSongs.isEmpty()) {
                Timber.tag(TAG).i("All songs in playlist $playlistId are already resolved and enriched")
                PlaybackDiagnostics.record(
                    eventType = PlaybackEventType.PREFETCH_COMPLETED,
                    mediaId = playlistId,
                    title = null,
                    provider = "youtube",
                    playbackState = 0,
                    playWhenReady = false,
                    isPlaying = false,
                    details = "Enrichment complete for playlist $playlistId (0 pending)",
                )
                return Result.success()
            }

            var resolvedCount = 0
            var failedCount = 0

            for ((index, song) in unresolvedSongs.withIndex()) {
                if (isStopped) {
                    Timber.tag(TAG).w("Enrichment worker stopped for $playlistId at index $index")
                    PlaybackDiagnostics.record(
                        eventType = PlaybackEventType.PREFETCH_CANCELLED,
                        mediaId = playlistId,
                        title = song.title,
                        provider = "youtube",
                        playbackState = 0,
                        playWhenReady = false,
                        isPlaying = false,
                        details = "Enrichment worker interrupted / stopped",
                    )
                    return Result.retry()
                }

                // Inter-request polite spacing to avoid YouTube search rate-limiting
                if (index > 0) {
                    delay(120L)
                }

                val sourceId = song.song.sourceId ?: song.id
                val mapping = providerMappingRepo.resolveAndPersist(
                    sourceId = sourceId,
                    title = song.title,
                    artist = song.artists.firstOrNull()?.name.orEmpty(),
                    album = song.album?.title,
                    durationMs = song.song.duration * 1000,
                    sourceProvider = song.song.sourceProvider ?: "spotify",
                    provider = "youtube",
                )

                if (mapping != null) {
                    resolvedCount++
                } else {
                    failedCount++
                }

                setProgress(
                    workDataOf(
                        KEY_RESOLVED_COUNT to resolvedCount,
                        KEY_FAILED_COUNT to failedCount,
                        KEY_TOTAL_IN_BATCH to unresolvedSongs.size,
                    )
                )
            }

            Timber.tag(TAG).i(
                "Enrichment batch complete for $playlistId: resolved=$resolvedCount, failed=$failedCount"
            )

            // If there may be more tracks remaining, schedule the next batch
            val remaining = database.getUnresolvedSongsForPlaylist(playlistId, 1)
            if (remaining.isNotEmpty()) {
                enqueue(applicationContext, playlistId, batchSize)
            } else {
                PlaybackDiagnostics.record(
                    eventType = PlaybackEventType.PREFETCH_COMPLETED,
                    mediaId = playlistId,
                    title = null,
                    provider = "youtube",
                    playbackState = 0,
                    playWhenReady = false,
                    isPlaying = false,
                    details = "Enrichment finished for playlist $playlistId",
                )
            }

            Result.success()
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Enrichment worker error for $playlistId")
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "PlaylistEnrichment"
        const val KEY_PLAYLIST_ID = "key_playlist_id"
        const val KEY_BATCH_SIZE = "key_batch_size"
        const val KEY_RESOLVED_COUNT = "key_resolved_count"
        const val KEY_FAILED_COUNT = "key_failed_count"
        const val KEY_TOTAL_IN_BATCH = "key_total_in_batch"
        const val DEFAULT_BATCH_SIZE = 25

        fun enqueue(context: Context, playlistId: String, batchSize: Int = DEFAULT_BATCH_SIZE) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = OneTimeWorkRequestBuilder<PlaylistEnrichmentWorker>()
                .setConstraints(constraints)
                .setInputData(
                    workDataOf(
                        KEY_PLAYLIST_ID to playlistId,
                        KEY_BATCH_SIZE to batchSize,
                    )
                )
                .addTag("playlist_enrichment")
                .addTag("playlist_$playlistId")
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                "enrich_playlist_$playlistId",
                ExistingWorkPolicy.KEEP,
                request,
            )
            Timber.tag(TAG).d("Enqueued background enrichment work for playlist $playlistId")
        }
    }
}
