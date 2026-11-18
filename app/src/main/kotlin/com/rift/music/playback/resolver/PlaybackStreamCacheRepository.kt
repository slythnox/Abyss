/**
 * Napster (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.rift.music.playback.resolver

import com.rift.music.db.DatabaseDao
import com.rift.music.db.entities.PlaybackStreamCacheEntity
import com.rift.music.playback.diagnostics.PlaybackDiagnostics
import com.rift.music.playback.diagnostics.PlaybackEventType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlaybackStreamCacheRepository @Inject constructor(
    private val database: DatabaseDao,
) {
    /**
     * Fast in-memory L1 cache to avoid Room DB dispatch overhead during high-frequency checks.
     */
    private val memoryCache = ConcurrentHashMap<String, PlaybackStreamCacheEntity>()

    /**
     * Generates a stable composite cache key.
     */
    fun buildCacheKey(trackId: String, provider: String, quality: String): String =
        "$trackId:$provider:$quality"

    /**
     * Retrieves a valid cached stream entry, checking memory L1 first, then Room L2.
     * Enforces the 10-minute safety margin before expiry.
     */
    suspend fun getValidStream(
        trackId: String,
        provider: String = "youtube",
        quality: String = "HIGH",
    ): PlaybackStreamCacheEntity? = withContext(Dispatchers.IO) {
        val key = buildCacheKey(trackId, provider, quality)
        val now = System.currentTimeMillis()
        val effectiveExpiry = now + SAFETY_MARGIN_MS

        // Check L1 memory
        val inMemory = memoryCache[key]
        if (inMemory != null && inMemory.isValid && inMemory.expiresAtMs > effectiveExpiry) {
            PlaybackDiagnostics.record(
                eventType = PlaybackEventType.STREAM_CACHE_HIT,
                mediaId = trackId,
                title = null,
                provider = provider,
                playbackState = 0,
                playWhenReady = false,
                isPlaying = false,
                details = "L1 memory hit for $key (expires in ${(inMemory.expiresAtMs - now) / 1000}s)",
            )
            return@withContext inMemory
        }

        // Check L2 persistent database
        val inDb = database.getValidStreamCache(key, effectiveExpiry)
        if (inDb != null) {
            memoryCache[key] = inDb
            PlaybackDiagnostics.record(
                eventType = PlaybackEventType.STREAM_CACHE_HIT,
                mediaId = trackId,
                title = null,
                provider = provider,
                playbackState = 0,
                playWhenReady = false,
                isPlaying = false,
                details = "L2 DB hit for $key (expires in ${(inDb.expiresAtMs - now) / 1000}s)",
            )
            return@withContext inDb
        }

        PlaybackDiagnostics.record(
            eventType = PlaybackEventType.STREAM_CACHE_MISS,
            mediaId = trackId,
            title = null,
            provider = provider,
            playbackState = 0,
            playWhenReady = false,
            isPlaying = false,
            details = "Cache miss for $key",
        )
        null
    }

    /**
     * Saves a newly resolved stream URL and its expiry information into L1 memory and L2 database.
     */
    suspend fun cacheStream(
        trackId: String,
        provider: String,
        quality: String,
        streamUrl: String,
        expiresInSeconds: Int,
        formatMimeType: String? = null,
        bitrate: Int = 0,
        sampleRate: Int? = null,
        contentLength: Long = 0L,
    ) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        // Extract URL 'expire' query param if present for ground-truth YouTube timestamp
        val urlExpiryMs = extractUrlExpiryTimestampMs(streamUrl)
        val expiresAtMs = urlExpiryMs ?: (now + expiresInSeconds * 1000L)

        val key = buildCacheKey(trackId, provider, quality)
        val entry = PlaybackStreamCacheEntity(
            cacheKey = key,
            trackId = trackId,
            provider = provider,
            quality = quality,
            streamUrl = streamUrl,
            expiresAtMs = expiresAtMs,
            resolvedAtMs = now,
            formatMimeType = formatMimeType,
            bitrate = bitrate,
            sampleRate = sampleRate,
            contentLength = contentLength,
            isValid = true,
        )

        memoryCache[key] = entry
        database.insert(entry)
        Timber.tag(TAG).d("Cached stream for $key (valid for ${(expiresAtMs - now) / 1000}s)")
    }

    /**
     * Invalidates a stream cache entry immediately on playback errors (HTTP 403, 404, 416).
     */
    suspend fun invalidate(
        trackId: String,
        provider: String = "youtube",
        quality: String = "HIGH",
    ) = withContext(Dispatchers.IO) {
        val key = buildCacheKey(trackId, provider, quality)
        memoryCache.remove(key)
        database.invalidateStreamCache(key)
        PlaybackDiagnostics.record(
            eventType = PlaybackEventType.CACHE_INVALIDATED,
            mediaId = trackId,
            title = null,
            provider = provider,
            playbackState = 0,
            playWhenReady = false,
            isPlaying = false,
            details = "Invalidated stream cache for $key",
        )
        Timber.tag(TAG).w("Invalidated stream cache for $key")
    }

    /**
     * Invalidate all qualities/providers for a given track ID (e.g. on generic IO error).
     */
    suspend fun invalidateAllForTrack(trackId: String) = withContext(Dispatchers.IO) {
        memoryCache.entries.removeIf { it.value.trackId == trackId }
        database.invalidateStreamCacheForTrack(trackId)
        PlaybackDiagnostics.record(
            eventType = PlaybackEventType.CACHE_INVALIDATED,
            mediaId = trackId,
            title = null,
            provider = null,
            playbackState = 0,
            playWhenReady = false,
            isPlaying = false,
            details = "Invalidated all stream cache entries for track $trackId",
        )
        Timber.tag(TAG).w("Invalidated all stream cache entries for track $trackId")
    }

    /**
     * Cleans up expired entries from L1 and L2 caches.
     */
    suspend fun cleanupExpired(): Int = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        memoryCache.entries.removeIf { it.value.expiresAtMs <= now || !it.value.isValid }
        val count = database.cleanupExpiredStreamCaches(now)
        Timber.tag(TAG).d("Cleaned up $count expired stream cache entries")
        count
    }

    internal fun extractUrlExpiryTimestampMs(url: String): Long? {
        return try {
            val match = Regex("""[?&]expire=(\d+)""").find(url)
            match?.groupValues?.get(1)?.toLongOrNull()?.let { it * 1000L }
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private const val TAG = "StreamCacheRepo"
        /**
         * 10-minute safety margin: if a URL expires in less than 10 minutes,
         * consider it expired and fetch fresh to prevent playback dropouts mid-track.
         */
        const val SAFETY_MARGIN_MS = 10 * 60 * 1000L
    }
}
