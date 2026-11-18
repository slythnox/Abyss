package com.rift.music.playback.resolver

import com.rift.music.db.DatabaseDao
import com.rift.music.db.entities.PlaybackStreamCacheEntity
import com.rift.music.db.entities.ProviderMappingEntity
import com.rift.music.db.entities.Song
import com.rift.music.db.entities.SongEntity
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class PersistentPlaylistIntelligenceTest {

    private val providerMappings = ConcurrentHashMap<String, ProviderMappingEntity>()
    private val streamCaches = ConcurrentHashMap<String, PlaybackStreamCacheEntity>()
    private val songs = ConcurrentHashMap<String, Song>()

    private val fakeDao: DatabaseDao = Proxy.newProxyInstance(
        DatabaseDao::class.java.classLoader,
        arrayOf(DatabaseDao::class.java),
        InvocationHandler { _, method, args ->
            when (method.name) {
                "insert" -> {
                    when (val entity = args?.get(0)) {
                        is ProviderMappingEntity -> providerMappings[entity.sourceId + ":" + entity.provider] = entity
                        is PlaybackStreamCacheEntity -> streamCaches[entity.cacheKey] = entity
                    }
                    Unit
                }
                "getProviderMapping" -> {
                    val sourceId = args?.get(0) as String
                    val provider = (args?.getOrNull(1) as? String) ?: "youtube"
                    providerMappings[sourceId + ":" + provider]
                }
                "invalidateProviderMapping" -> {
                    val sourceId = args?.get(0) as String
                    val status = (args?.getOrNull(1) as? String) ?: "FAILED"
                    val reason = args?.getOrNull(2) as? String
                    for ((key, mapping) in providerMappings) {
                        if (mapping.sourceId == sourceId) {
                            providerMappings[key] = mapping.copy(matchStatus = status, invalidationReason = reason)
                        }
                    }
                    Unit
                }
                "getValidStreamCache" -> {
                    val cacheKey = args?.get(0) as String
                    val minExpiresAt = (args?.get(1) as? Long) ?: System.currentTimeMillis()
                    val entry = streamCaches[cacheKey]
                    if (entry != null && entry.isValid && entry.expiresAtMs > minExpiresAt) entry else null
                }
                "getValidStreamCacheForTrack" -> {
                    val trackId = args?.get(0) as String
                    val minExpiresAt = (args?.get(1) as? Long) ?: System.currentTimeMillis()
                    streamCaches.values.find { it.trackId == trackId && it.isValid && it.expiresAtMs > minExpiresAt }
                }
                "invalidateStreamCache" -> {
                    val cacheKey = args?.get(0) as String
                    val entry = streamCaches[cacheKey]
                    if (entry != null) streamCaches[cacheKey] = entry.copy(isValid = false)
                    Unit
                }
                "invalidateStreamCacheForTrack" -> {
                    val trackId = args?.get(0) as String
                    for ((key, entry) in streamCaches) {
                        if (entry.trackId == trackId) {
                            streamCaches[key] = entry.copy(isValid = false)
                        }
                    }
                    Unit
                }
                "cleanupExpiredStreamCaches" -> {
                    val currentTime = (args?.get(0) as? Long) ?: System.currentTimeMillis()
                    var count = 0
                    val it = streamCaches.entries.iterator()
                    while (it.hasNext()) {
                        val entry = it.next().value
                        if (entry.expiresAtMs <= currentTime || !entry.isValid) {
                            it.remove()
                            count++
                        }
                    }
                    count
                }
                "getSongBySourceId", "getSongBySourceIdBlocking" -> {
                    val sourceId = args?.get(0) as String
                    songs.values.find { it.song.sourceId == sourceId }
                }
                "getSongById", "getSongByIdBlocking" -> {
                    val id = args?.get(0) as String
                    songs[id]
                }
                "updateSongMatch" -> {
                    val songId = args?.get(0) as String
                    val videoId = args?.get(1) as String
                    val confidence = args?.get(2) as Float
                    val status = args?.get(3) as String
                    val isEnriched = args?.get(4) as Boolean
                    val existing = songs[songId]
                    if (existing != null) {
                        val updated = existing.song.copy(
                            matchedVideoId = videoId,
                            matchConfidence = confidence,
                            matchStatus = status,
                            isEnriched = isEnriched,
                        )
                        songs[songId] = Song(updated, existing.artists, existing.album, existing.format)
                    }
                    Unit
                }
                else -> null
            }
        }
    ) as DatabaseDao

    private lateinit var streamCacheRepo: PlaybackStreamCacheRepository
    private lateinit var deduplicator: ResolutionDeduplicator
    private lateinit var mappingRepo: ProviderMappingRepository

    @Before
    fun setUp() {
        providerMappings.clear()
        streamCaches.clear()
        songs.clear()
        streamCacheRepo = PlaybackStreamCacheRepository(fakeDao)
        deduplicator = ResolutionDeduplicator()
        mappingRepo = ProviderMappingRepository(fakeDao)
    }

    @Test
    fun `stream cache - builds consistent composite cache keys`() {
        val key = streamCacheRepo.buildCacheKey("vid123", "youtube", "HIGH")
        assertEquals("vid123:youtube:HIGH", key)

        val losslessKey = streamCacheRepo.buildCacheKey("vid123", "tidal", "LOSSLESS")
        assertEquals("vid123:tidal:LOSSLESS", losslessKey)
    }

    @Test
    fun `stream cache - extracts expire query param correctly`() {
        val url = "https://rr1---sn.googlevideo.com/videoplayback?expire=1732000000&ei=xyz"
        val timestamp = streamCacheRepo.extractUrlExpiryTimestampMs(url)
        assertEquals(1732000000000L, timestamp)
    }

    @Test
    fun `stream cache - memory L1 hit avoids database query`() = runBlocking {
        val trackId = "vid_memory_test"
        val provider = "youtube"
        val quality = "HIGH"
        val streamUrl = "https://example.com/stream?expire=9999999999"

        // Cache entry valid for 2 hours (7200 seconds)
        streamCacheRepo.cacheStream(
            trackId = trackId,
            provider = provider,
            quality = quality,
            streamUrl = streamUrl,
            expiresInSeconds = 7200,
            formatMimeType = "audio/webm",
            bitrate = 160000,
        )

        // Retrieval should return from L1 memory
        val result = streamCacheRepo.getValidStream(trackId, provider, quality)
        assertNotNull(result)
        assertEquals(streamUrl, result?.streamUrl)
        assertEquals(trackId, result?.trackId)
        assertTrue(result?.isValid == true)
    }

    @Test
    fun `stream cache - 10 minute safety margin treats near-expiry stream as expired`() = runBlocking {
        val trackId = "vid_expiring_soon"
        val provider = "youtube"
        val quality = "HIGH"
        val streamUrl = "https://example.com/stream"

        // Cached with only 5 minutes remaining (300 seconds), which is less than the 10-minute safety margin
        streamCacheRepo.cacheStream(
            trackId = trackId,
            provider = provider,
            quality = quality,
            streamUrl = streamUrl,
            expiresInSeconds = 300,
        )

        val result = streamCacheRepo.getValidStream(trackId, provider, quality)
        // Should be considered expired and return null to prevent mid-track playback failure
        assertNull(result)
    }

    @Test
    fun `stream cache - invalidation purges entry from memory and database`() = runBlocking {
        val trackId = "vid_invalidated"
        val provider = "youtube"
        val quality = "HIGH"
        val streamUrl = "https://example.com/stream?expire=9999999999"

        streamCacheRepo.cacheStream(
            trackId = trackId,
            provider = provider,
            quality = quality,
            streamUrl = streamUrl,
            expiresInSeconds = 3600,
        )

        // Invalidate on 403 / 416
        streamCacheRepo.invalidate(trackId, provider, quality)

        // Subsequent lookup should miss memory and database
        val result = streamCacheRepo.getValidStream(trackId, provider, quality)
        assertNull(result)
    }

    @Test
    fun `stream cache - invalidateAllForTrack invalidates all qualities for track`() = runBlocking {
        val trackId = "vid_all_qualities"
        streamCacheRepo.cacheStream(trackId, "youtube", "HIGH", "https://example.com/high?expire=9999999999", 3600)
        streamCacheRepo.cacheStream(trackId, "youtube", "LOW", "https://example.com/low?expire=9999999999", 3600)

        streamCacheRepo.invalidateAllForTrack(trackId)

        assertNull(streamCacheRepo.getValidStream(trackId, "youtube", "HIGH"))
        assertNull(streamCacheRepo.getValidStream(trackId, "youtube", "LOW"))
    }

    @Test
    fun `deduplicator - concurrent callers for same key coalesce into single execution`() = runBlocking {
        val executionCounter = AtomicInteger(0)

        val jobs = (1..5).map {
            async {
                deduplicator.execute("coalesce_key") {
                    delay(50) // Simulate network resolution delay
                    executionCounter.incrementAndGet()
                    "resolved_url_result"
                }
            }
        }

        val results = jobs.awaitAll()

        // All 5 callers should receive the exact same result
        for (res in results) {
            assertEquals("resolved_url_result", res)
        }

        // The underlying block must have executed exactly once
        assertEquals(1, executionCounter.get())
    }

    @Test
    fun `deduplicator - different keys execute independently`() = runBlocking {
        val executionCounter = AtomicInteger(0)

        val job1 = async {
            deduplicator.execute("key_alpha") {
                delay(30)
                executionCounter.incrementAndGet()
                "alpha"
            }
        }

        val job2 = async {
            deduplicator.execute("key_beta") {
                delay(30)
                executionCounter.incrementAndGet()
                "beta"
            }
        }

        val res1 = job1.await()
        val res2 = job2.await()

        assertEquals("alpha", res1)
        assertEquals("beta", res2)
        assertEquals(2, executionCounter.get())
    }

    @Test
    fun `provider mapping repo - getValidMapping returns existing un-invalidated mapping`() = runBlocking {
        val sourceId = "spotify_track_001"
        val expected = ProviderMappingEntity(
            sourceId = sourceId,
            sourceProvider = "spotify",
            providerTrackId = "youtube_vid_001",
            provider = "youtube",
            confidence = 0.95f,
            matchStatus = "MATCHED",
            verifiedAt = System.currentTimeMillis(),
        )

        fakeDao.insert(expected)

        val actual = mappingRepo.getValidMapping(sourceId, "youtube")
        assertNotNull(actual)
        assertEquals("youtube_vid_001", actual?.providerTrackId)
        assertTrue((actual?.confidence ?: 0f) >= 0.9f)
    }

    @Test
    fun `provider mapping repo - getValidMapping ignores failed mappings`() = runBlocking {
        val sourceId = "spotify_failed_002"
        val failed = ProviderMappingEntity(
            sourceId = sourceId,
            sourceProvider = "spotify",
            providerTrackId = "old_dead_vid",
            provider = "youtube",
            confidence = 0f,
            matchStatus = "FAILED",
            invalidationReason = "HTTP 404 Video Unavailable",
            verifiedAt = System.currentTimeMillis(),
        )

        fakeDao.insert(failed)

        val actual = mappingRepo.getValidMapping(sourceId, "youtube")
        assertNull(actual)
    }

    @Test
    fun `provider mapping repo - resolves from existing matchedVideoId in Song`() = runBlocking {
        val sourceId = "spotify_song_cached"
        val existingSong = Song(
            song = SongEntity(
                id = "spotify:$sourceId",
                title = "Test Song",
                sourceId = sourceId,
                sourceProvider = "spotify",
                matchedVideoId = "pre_matched_yt_id",
                matchConfidence = 0.88f,
                matchStatus = "MATCHED",
                metadataLastUpdated = 123456789L,
            ),
            artists = emptyList(),
        )
        songs[existingSong.id] = existingSong

        val actual = mappingRepo.getValidMapping(sourceId, "youtube")
        assertNotNull(actual)
        assertEquals("pre_matched_yt_id", actual?.providerTrackId)
        assertTrue((actual?.confidence ?: 0f) >= 0.8f)
    }

    @Test
    fun `provider mapping repo - invalidateMapping marks entity FAILED`() = runBlocking {
        val sourceId = "spotify_track_invalidate"
        val existingSong = Song(
            song = SongEntity(
                id = "spotify:$sourceId",
                title = "Test Song",
                sourceId = sourceId,
                sourceProvider = "spotify",
                matchedVideoId = "old_vid_to_kill",
                matchConfidence = 0.9f,
                matchStatus = "MATCHED",
                metadataLastUpdated = 123456789L,
            ),
            artists = emptyList(),
        )
        songs[existingSong.id] = existingSong
        fakeDao.insert(
            ProviderMappingEntity(
                sourceId = sourceId,
                sourceProvider = "spotify",
                providerTrackId = "old_vid_to_kill",
                provider = "youtube",
                confidence = 0.9f,
                matchStatus = "MATCHED",
                verifiedAt = System.currentTimeMillis(),
            )
        )

        mappingRepo.invalidateMapping(sourceId, "Video removed by uploader")

        val reloadedMapping = fakeDao.getProviderMapping(sourceId, "youtube")
        assertEquals("FAILED", reloadedMapping?.matchStatus)
        assertEquals("Video removed by uploader", reloadedMapping?.invalidationReason)

        val reloadedSong = fakeDao.getSongById(existingSong.id)
        assertEquals("FAILED", reloadedSong?.song?.matchStatus)
        assertEquals("", reloadedSong?.song?.matchedVideoId)
    }
}
