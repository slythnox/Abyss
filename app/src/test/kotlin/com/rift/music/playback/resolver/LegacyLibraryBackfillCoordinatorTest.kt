package com.rift.music.playback.resolver

import android.content.Context
import com.rift.music.db.DatabaseDao
import com.rift.music.db.entities.PlaylistSongMap
import com.rift.music.db.entities.ProviderMappingEntity
import com.rift.music.db.entities.Song
import com.rift.music.db.entities.SongEntity
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
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap

class LegacyLibraryBackfillCoordinatorTest {

    private val songs = ConcurrentHashMap<String, SongEntity>()
    private val playlistSongMaps = ConcurrentHashMap<String, MutableList<PlaylistSongMap>>()
    private val providerMappings = ConcurrentHashMap<String, ProviderMappingEntity>()
    private val recentEventSongIds = mutableListOf<String>()

    private val fakeDao: DatabaseDao = Proxy.newProxyInstance(
        DatabaseDao::class.java.classLoader,
        arrayOf(DatabaseDao::class.java),
        InvocationHandler { _, method, args ->
            when (method.name) {
                "getSongsNeedingIdentityBackfill" -> {
                    val limit = (args?.get(0) as? Int) ?: 100
                    songs.values.filter { it.sourceId == null || it.sourceProvider == null }.take(limit)
                }
                "updateSongIdentity" -> {
                    val songId = args?.get(0) as String
                    val sourceId = args?.get(1) as String
                    val sourceProvider = args?.get(2) as String
                    val matchedVideoId = args?.getOrNull(3) as? String
                    val confidence = args?.getOrNull(4) as? Float
                    val status = args?.getOrNull(5) as? String
                    val isEnriched = (args?.get(6) as? Boolean) ?: false
                    val updatedAt = (args?.getOrNull(7) as? Long) ?: System.currentTimeMillis()

                    val existing = songs[songId]
                    if (existing != null) {
                        songs[songId] = existing.copy(
                            sourceId = sourceId,
                            sourceProvider = sourceProvider,
                            matchedVideoId = matchedVideoId ?: existing.matchedVideoId,
                            matchConfidence = confidence ?: existing.matchConfidence,
                            matchStatus = status ?: existing.matchStatus,
                            isEnriched = isEnriched,
                            metadataLastUpdated = updatedAt,
                        )
                    }
                    Unit
                }
                "getProviderMapping" -> {
                    val sourceId = args?.get(0) as String
                    val provider = (args?.getOrNull(1) as? String) ?: "youtube"
                    providerMappings[sourceId + ":" + provider]
                }
                "insert" -> {
                    when (val entity = args?.get(0)) {
                        is ProviderMappingEntity -> providerMappings[entity.sourceId + ":" + entity.provider] = entity
                    }
                    Unit
                }
                "getRecentEventSongIds" -> {
                    val limit = (args?.get(0) as? Int) ?: 20
                    recentEventSongIds.take(limit)
                }
                "getPlaylistIdsWithUnresolvedSongs" -> {
                    playlistSongMaps.entries.filter { entry ->
                        entry.value.any { map ->
                            val s = songs[map.songId]
                            s != null && (s.matchedVideoId.isNullOrBlank() || !s.isEnriched)
                        }
                    }.map { it.key }
                }
                "getUnresolvedSongsCount" -> {
                    songs.values.count { (it.matchedVideoId.isNullOrBlank() || !it.isEnriched) && !it.isLocal }
                }
                "getSongById", "getSongByIdBlocking" -> {
                    val id = args?.get(0) as String
                    songs[id]?.let { Song(it, emptyList()) }
                }
                else -> null
            }
        }
    ) as DatabaseDao

    private lateinit var mappingRepo: ProviderMappingRepository
    private lateinit var coordinator: LegacyLibraryBackfillCoordinator

    @Before
    fun setUp() {
        songs.clear()
        playlistSongMaps.clear()
        providerMappings.clear()
        recentEventSongIds.clear()

        mappingRepo = ProviderMappingRepository(fakeDao)
        coordinator = LegacyLibraryBackfillCoordinator(fakeDao, mappingRepo)
    }

    @Test
    fun `backfill - normalizes pre-feature songs with zero metadata loss`() = runBlocking {
        // Pre-feature legacy Spotify song (no sourceId, no sourceProvider)
        val legacySpotify = SongEntity(
            id = "spotify:track_legacy_1",
            title = "Bohemian Rhapsody",
            albumName = "A Night at the Opera",
            duration = 354,
            liked = true,
            likedDate = LocalDateTime.of(2025, 1, 1, 12, 0),
            totalPlayTime = 999999L,
            lyricsOffset = 150,
            sourceId = null,
            sourceProvider = null,
            matchedVideoId = null,
            isEnriched = false,
        )
        songs[legacySpotify.id] = legacySpotify

        // Pre-feature standard YouTube song
        val legacyYouTube = SongEntity(
            id = "dQw4w9WgXcQ",
            title = "Never Gonna Give You Up",
            duration = 213,
            sourceId = null,
            sourceProvider = null,
            matchedVideoId = null,
            isEnriched = false,
        )
        songs[legacyYouTube.id] = legacyYouTube

        // Pre-feature local song
        val legacyLocal = SongEntity(
            id = "content://media/audio/42",
            title = "My Local Track.mp3",
            duration = 180,
            isLocal = true,
            sourceId = null,
            sourceProvider = null,
        )
        songs[legacyLocal.id] = legacyLocal

        val result = coordinator.runBackfill()

        assertEquals(3, result.normalizedCount)

        // Verify Spotify song normalized without losing user data
        val updatedSpotify = songs[legacySpotify.id]!!
        assertEquals("track_legacy_1", updatedSpotify.sourceId)
        assertEquals("spotify", updatedSpotify.sourceProvider)
        assertEquals("Bohemian Rhapsody", updatedSpotify.title)
        assertEquals("A Night at the Opera", updatedSpotify.albumName)
        assertEquals(354, updatedSpotify.duration)
        assertTrue("Favorites must be preserved", updatedSpotify.liked)
        assertEquals(999999L, updatedSpotify.totalPlayTime)
        assertEquals(150, updatedSpotify.lyricsOffset)

        // Verify YouTube song self-mapped
        val updatedYouTube = songs[legacyYouTube.id]!!
        assertEquals("dQw4w9WgXcQ", updatedYouTube.sourceId)
        assertEquals("youtube", updatedYouTube.sourceProvider)
        assertEquals("dQw4w9WgXcQ", updatedYouTube.matchedVideoId)
        assertTrue(updatedYouTube.isEnriched)
        assertNotNull(providerMappings["dQw4w9WgXcQ:youtube"])

        // Verify local song normalized
        val updatedLocal = songs[legacyLocal.id]!!
        assertEquals("local", updatedLocal.sourceProvider)
        assertTrue(updatedLocal.isEnriched)
    }

    @Test
    fun `backfill - preserves existing pre-feature provider mapping if available`() = runBlocking {
        val legacySpotify = SongEntity(
            id = "spotify:pre_mapped_001",
            title = "Already Mapped Song",
            sourceId = null,
            sourceProvider = null,
            matchedVideoId = null,
            isEnriched = false,
        )
        songs[legacySpotify.id] = legacySpotify

        // Existing mapping already present in DB
        providerMappings["pre_mapped_001:youtube"] = ProviderMappingEntity(
            sourceId = "pre_mapped_001",
            sourceProvider = "spotify",
            providerTrackId = "matched_yt_video_id",
            provider = "youtube",
            confidence = 0.92f,
            matchStatus = "MATCHED",
            verifiedAt = 123456789L,
        )

        coordinator.runBackfill()

        val updated = songs[legacySpotify.id]!!
        assertEquals("matched_yt_video_id", updated.matchedVideoId)
        assertEquals(0.92f, updated.matchConfidence ?: 0f, 0.01f)
        assertEquals("MATCHED", updated.matchStatus)
        assertTrue(updated.isEnriched)
    }

    @Test
    fun `backfill - is strictly idempotent when executed repeatedly`() = runBlocking {
        songs["spotify:repeat_test"] = SongEntity(
            id = "spotify:repeat_test",
            title = "Repeat Test",
            sourceId = null,
            sourceProvider = null,
        )

        val run1 = coordinator.runBackfill()
        assertEquals(1, run1.normalizedCount)

        // Second run must find zero unnormalized songs
        val run2 = coordinator.runBackfill()
        assertEquals(0, run2.normalizedCount)
        assertEquals(1, songs.size) // No duplicates
    }

    @Test
    fun `backfill - preserves playlist entries including duplicates and exact positions`() = runBlocking {
        val playlistId = "playlist_with_duplicates"
        val songA = "spotify:song_a"
        val songB = "spotify:song_b"

        songs[songA] = SongEntity(id = songA, title = "Song A", sourceId = null, sourceProvider = null)
        songs[songB] = SongEntity(id = songB, title = "Song B", sourceId = null, sourceProvider = null)

        // User intentionally added Song A twice (positions 0 and 2)
        val playlistEntries = mutableListOf(
            PlaylistSongMap(playlistId = playlistId, songId = songA, position = 0, id = 1),
            PlaylistSongMap(playlistId = playlistId, songId = songB, position = 1, id = 2),
            PlaylistSongMap(playlistId = playlistId, songId = songA, position = 2, id = 3),
        )
        playlistSongMaps[playlistId] = playlistEntries

        coordinator.runBackfill()

        // Verify playlist structure is 100% preserved
        val entriesAfter = playlistSongMaps[playlistId]!!
        assertEquals(3, entriesAfter.size)
        assertEquals(songA, entriesAfter[0].songId)
        assertEquals(0, entriesAfter[0].position)
        assertEquals(songB, entriesAfter[1].songId)
        assertEquals(1, entriesAfter[1].position)
        assertEquals(songA, entriesAfter[2].songId)
        assertEquals(2, entriesAfter[2].position)
    }

    @Test
    fun `backfill - detects playlists with unresolved songs for WorkManager enrichment`() = runBlocking {
        val playlist1 = "pl_unresolved"
        val playlist2 = "pl_all_good"

        val unresolvedTrack = "spotify:needs_work"
        val resolvedTrack = "dQw4w9WgXcQ"

        songs[unresolvedTrack] = SongEntity(id = unresolvedTrack, title = "Unresolved", sourceId = null, sourceProvider = null)
        songs[resolvedTrack] = SongEntity(id = resolvedTrack, title = "Resolved", sourceId = resolvedTrack, sourceProvider = "youtube", matchedVideoId = resolvedTrack, isEnriched = true)

        playlistSongMaps[playlist1] = mutableListOf(PlaylistSongMap(playlistId = playlist1, songId = unresolvedTrack, position = 0, id = 10))
        playlistSongMaps[playlist2] = mutableListOf(PlaylistSongMap(playlistId = playlist2, songId = resolvedTrack, position = 0, id = 20))

        val result = coordinator.runBackfill()

        // Only playlist 1 has unresolved songs
        assertEquals(1, result.scheduledPlaylists)
        assertEquals(1, result.remainingUnresolved)
    }
}
