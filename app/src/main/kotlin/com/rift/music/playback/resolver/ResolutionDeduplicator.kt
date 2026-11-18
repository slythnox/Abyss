/**
 * Napster (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.rift.music.playback.resolver

import com.rift.music.playback.diagnostics.PlaybackDiagnostics
import com.rift.music.playback.diagnostics.PlaybackEventType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Concurrency-safe single-flight resolution deduplicator.
 * Ensures that if multiple concurrent callers (e.g. ExoPlayer buffer loader,
 * UI prefetcher, and background preparation worker) attempt to resolve the same track
 * simultaneously, only ONE upstream network request is executed. All other callers
 * await the shared in-flight result.
 *
 * If a caller cancels, the underlying fetch continues safely under [NonCancellable]
 * so other callers or subsequent cache reads do not suffer from abandoned work.
 */
@Singleton
class ResolutionDeduplicator @Inject constructor() {

    private val sharedScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<Any?>>()

    /**
     * Executes [block] for [key] with single-flight request coalescing.
     */
    @Suppress("UNCHECKED_CAST")
    suspend fun <T> execute(
        key: String,
        block: suspend () -> T,
    ): T {
        var isLeader = false
        val deferred = inFlight.compute(key) { _, existing ->
            if (existing != null) {
                existing
            } else {
                isLeader = true
                CompletableDeferred()
            }
        }!!

        if (!isLeader) {
            PlaybackDiagnostics.record(
                eventType = PlaybackEventType.RESOLUTION_DEDUPLICATION_HIT,
                mediaId = key,
                title = null,
                provider = null,
                playbackState = 0,
                playWhenReady = false,
                isPlaying = false,
                details = "Coalesced with existing in-flight resolution for $key",
            )
            Timber.tag(TAG).d("Request deduplication: coalesced on in-flight resolution for $key")
            return try {
                deferred.await() as T
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                throw e
            }
        }

        // Leader executes the work in supervisor scope so cancellation of one consumer
        // does not cancel the shared task.
        sharedScope.launch {
            try {
                val result = withContext(NonCancellable) {
                    block()
                }
                deferred.complete(result)
            } catch (e: Throwable) {
                deferred.completeExceptionally(e)
            } finally {
                inFlight.remove(key, deferred)
            }
        }

        return try {
            deferred.await() as T
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            throw e
        }
    }

    /**
     * Number of currently active in-flight resolution operations.
     */
    val activeCount: Int
        get() = inFlight.size

    companion object {
        private const val TAG = "ResolutionDeduplicator"
    }
}
