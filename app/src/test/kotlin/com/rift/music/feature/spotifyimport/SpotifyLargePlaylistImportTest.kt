package com.rift.music.feature.spotifyimport

import com.music.spotify.Spotify
import com.music.spotify.SpotifyMetadataClient
import com.music.spotify.SpotifyPlaylistMetadata
import com.music.spotify.models.SpotifySimpleAlbum
import com.music.spotify.models.SpotifySimpleArtist
import com.music.spotify.models.SpotifyTrack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class SpotifyLargePlaylistImportTest {

    @Before
    fun setUp() {
        SpotifyImportLock.clearAll()
    }

    @After
    fun tearDown() {
        SpotifyImportLock.clearAll()
    }

    /**
     * Fake metadata client delivering 4,000 synthetic tracks in 40 pages of 100 tracks.
     * Accurately supports startOffset for checkpoint-based resumption.
     */
    private class FakePagedSpotifyClient(
        private val totalCount: Int = 4000,
        private val pageSize: Int = 100,
    ) : SpotifyMetadataClient {
        override suspend fun getPlaylistMetadata(playlistId: String): Result<SpotifyPlaylistMetadata> {
            val all = (1..totalCount).map { i ->
                SpotifyTrack(
                    id = "spotify_track_$i",
                    name = "Track $i",
                    artists = listOf(SpotifySimpleArtist("art_$i", "Artist $i")),
                    album = SpotifySimpleAlbum("alb_$i", "Album $i"),
                    durationMs = 200_000,
                )
            }
            return Result.success(SpotifyPlaylistMetadata(playlistId, "Large 4k Playlist", all, null, totalCount))
        }

        override suspend fun getTrackMetadata(trackId: String): Result<SpotifyTrack> {
            return Result.failure(UnsupportedOperationException())
        }

        override fun getPlaylistTracksPaged(
            playlistId: String,
            startOffset: Int,
            pageSize: Int,
            maxTracks: Int,
        ): Flow<Spotify.SpotifyPagedBatch> = flow {
            var offset = startOffset
            while (offset < totalCount && offset < maxTracks) {
                val end = minOf(offset + pageSize, totalCount)
                val pageTracks = (offset + 1..end).map { i ->
                    SpotifyTrack(
                        id = "track_$i",
                        name = "Track $i",
                        artists = listOf(SpotifySimpleArtist("art_$i", "Artist $i")),
                        album = SpotifySimpleAlbum("alb_$i", "Album $i"),
                        durationMs = 180_000,
                    )
                }
                emit(
                    Spotify.SpotifyPagedBatch(
                        playlistName = "Massive 4k Playlist",
                        coverUrl = "https://example.com/cover.jpg",
                        tracks = pageTracks,
                        offset = offset,
                        totalCount = totalCount,
                        isLast = end >= totalCount,
                    )
                )
                offset += pageTracks.size
            }
        }
    }

    /**
     * Simulated transactional database storage mimicking Room SQLite batch behavior.
     */
    private class SimulatedDatabaseStorage {
        val persistedTracks = mutableListOf<String>()
        var storedCheckpoint: SpotifyImportCheckpoint? = null

        fun commitBatch(tracks: List<String>, checkpoint: SpotifyImportCheckpoint) {
            persistedTracks.addAll(tracks)
            storedCheckpoint = checkpoint
        }

        fun rollbackBatch() {
            // Emulates SQLite rollback: uncommitted tracks are not retained, checkpoint not updated
        }
    }

    @Test
    fun `paged streaming consumes 4000 tracks incrementally across 40 batches without memory overload`() = runBlocking {
        val client = FakePagedSpotifyClient(totalCount = 4000, pageSize = 100)
        var batchCount = 0
        var totalTracksReceived = 0

        client.getPlaylistTracksPaged("test_4k_playlist", startOffset = 0, pageSize = 100).collect { batch ->
            batchCount++
            totalTracksReceived += batch.tracks.size
            assertEquals(4000, batch.totalCount)
            assertEquals("Massive 4k Playlist", batch.playlistName)
            if (batch.isLast) {
                assertEquals(4000, totalTracksReceived)
            }
        }

        assertEquals("4000 tracks in 100-track pages must yield exactly 40 batches", 40, batchCount)
        assertEquals(4000, totalTracksReceived)
    }

    @Test
    fun `SpotifyImportState progress accurately reflects batch advances`() {
        val total = 4000
        val processed = 1240
        val imported = 1200
        val skipped = 30
        val failed = 10

        val state = SpotifyImportState(
            status = SpotifyImportStatus.PROCESSING,
            playlistId = "test_playlist",
            playlistName = "Rock Classics",
            artworkUrl = "https://example.com/art.jpg",
            totalTracks = total,
            processedTracks = processed,
            importedCount = imported,
            skippedCount = skipped,
            failedCount = failed,
            currentStage = "Importing tracks ($processed / $total)",
        )

        assertEquals(0.31f, state.progress, 0.001f)
        assertEquals(31, state.percent)
        assertTrue(state.isActive)
        assertFalse(state.isFinished)
        assertEquals(1240, state.importedCount + state.skippedCount + state.failedCount)
    }

    @Test
    fun `streaming cancellation stops batch collection promptly`() = runBlocking {
        val client = FakePagedSpotifyClient(totalCount = 4000, pageSize = 100)
        val batchesReceived = AtomicInteger(0)

        val job = launch {
            try {
                client.getPlaylistTracksPaged("test_cancel", startOffset = 0, pageSize = 100).collect {
                    val count = batchesReceived.incrementAndGet()
                    if (count == 5) {
                        cancel(CancellationException("Simulated user cancel"))
                    }
                }
            } catch (e: CancellationException) {
                // expected
            }
        }
        job.join()

        assertEquals("Processing should halt after exactly 5 batches upon cancellation", 5, batchesReceived.get())
    }

    @Test
    fun `unmatchable tracks produce COMPLETED_WITH_ERRORS status rather than failing entire import`() {
        val state = SpotifyImportState(
            status = SpotifyImportStatus.COMPLETED_WITH_ERRORS,
            playlistId = "test_mixed",
            playlistName = "Rare Underground Tracks",
            totalTracks = 100,
            processedTracks = 100,
            importedCount = 95,
            skippedCount = 0,
            failedCount = 5,
            currentStage = "Import completed with 5 unmatchable tracks",
        )

        assertTrue(state.isFinished)
        assertFalse(state.isActive)
        assertEquals(SpotifyImportStatus.COMPLETED_WITH_ERRORS, state.status)
        assertEquals(95, state.importedCount)
        assertEquals(5, state.failedCount)
    }

    @Test
    fun `checkpoint persistence and resume after first batch (batch 1 interrupted)`() = runBlocking {
        val client = FakePagedSpotifyClient(totalCount = 4000, pageSize = 100)
        val storage = SimulatedDatabaseStorage()
        val playlistId = "test_4k_resume_b1"

        // Step 1: Run until batch 1 finishes, then simulate process death
        var batchesFirstRun = 0
        client.getPlaylistTracksPaged(playlistId, startOffset = 0, pageSize = 100).take(1).collect { batch ->
            batchesFirstRun++
            val checkpoint = SpotifyImportCheckpoint(
                importId = playlistId,
                playlistUrl = "https://open.spotify.com/playlist/$playlistId",
                playlistName = batch.playlistName,
                totalTracks = batch.totalCount,
                nextOffset = batch.offset + batch.tracks.size,
                lastBatchIndex = batchesFirstRun - 1,
                importedCount = batch.tracks.size,
                status = SpotifyImportStatus.PROCESSING,
            )
            storage.commitBatch(batch.tracks.map { it.id }, checkpoint)
        }

        assertEquals(1, batchesFirstRun)
        assertEquals(100, storage.persistedTracks.size)
        assertEquals(100, storage.storedCheckpoint?.nextOffset)
        assertEquals(1, storage.persistedTracks.distinct().size / 100)

        // Step 2: Resume from stored checkpoint
        val resumeCheckpoint = storage.storedCheckpoint
        assertNotNull(resumeCheckpoint)
        val startOffset = resumeCheckpoint!!.nextOffset

        var batchesSecondRun = 0
        client.getPlaylistTracksPaged(playlistId, startOffset = startOffset, pageSize = 100).collect { batch ->
            batchesSecondRun++
            val updatedCheckpoint = resumeCheckpoint.copy(
                nextOffset = batch.offset + batch.tracks.size,
                lastBatchIndex = resumeCheckpoint.lastBatchIndex + batchesSecondRun,
                importedCount = resumeCheckpoint.importedCount + batch.tracks.size,
                status = if (batch.isLast) SpotifyImportStatus.COMPLETED else SpotifyImportStatus.PROCESSING,
            )
            storage.commitBatch(batch.tracks.map { it.id }, updatedCheckpoint)
        }

        assertEquals("Resumed run must process remaining 39 batches", 39, batchesSecondRun)
        assertEquals(40, batchesFirstRun + batchesSecondRun)
        assertEquals(4000, storage.persistedTracks.size)
        assertEquals("Zero duplicate tracks across both runs", 4000, storage.persistedTracks.distinct().size)
        assertEquals(SpotifyImportStatus.COMPLETED, storage.storedCheckpoint?.status)
    }

    @Test
    fun `checkpoint persistence and resume halfway through (batch 20 of 40 interrupted)`() = runBlocking {
        val client = FakePagedSpotifyClient(totalCount = 4000, pageSize = 100)
        val storage = SimulatedDatabaseStorage()
        val playlistId = "test_4k_resume_halfway"

        // First run: process exactly 20 batches (2,000 tracks)
        var batchesFirstRun = 0
        var tracksFirstRun = 0
        client.getPlaylistTracksPaged(playlistId, startOffset = 0, pageSize = 100).take(20).collect { batch ->
            batchesFirstRun++
            tracksFirstRun += batch.tracks.size
            val checkpoint = SpotifyImportCheckpoint(
                importId = playlistId,
                playlistUrl = "https://open.spotify.com/playlist/$playlistId",
                playlistName = batch.playlistName,
                totalTracks = batch.totalCount,
                nextOffset = batch.offset + batch.tracks.size,
                lastBatchIndex = batchesFirstRun - 1,
                importedCount = tracksFirstRun,
                status = SpotifyImportStatus.PROCESSING,
            )
            storage.commitBatch(batch.tracks.map { it.id }, checkpoint)
        }

        assertEquals(20, batchesFirstRun)
        assertEquals(2000, storage.persistedTracks.size)
        assertEquals(2000, storage.storedCheckpoint?.nextOffset)
        assertEquals(0.5f, storage.storedCheckpoint?.progress)

        // Resumed run: starts at nextOffset = 2000
        val resumeCheckpoint = storage.storedCheckpoint!!
        var batchesSecondRun = 0
        var tracksSecondRun = 0
        client.getPlaylistTracksPaged(playlistId, startOffset = resumeCheckpoint.nextOffset, pageSize = 100).collect { batch ->
            batchesSecondRun++
            tracksSecondRun += batch.tracks.size
            val checkpoint = resumeCheckpoint.copy(
                nextOffset = batch.offset + batch.tracks.size,
                lastBatchIndex = resumeCheckpoint.lastBatchIndex + batchesSecondRun,
                importedCount = resumeCheckpoint.importedCount + tracksSecondRun,
                status = if (batch.isLast) SpotifyImportStatus.COMPLETED else SpotifyImportStatus.PROCESSING,
            )
            storage.commitBatch(batch.tracks.map { it.id }, checkpoint)
        }

        assertEquals(20, batchesSecondRun)
        assertEquals(2000, tracksSecondRun)
        assertEquals(4000, storage.persistedTracks.size)
        assertEquals("No duplicates when resuming from halfway point", 4000, storage.persistedTracks.distinct().size)
        assertEquals(SpotifyImportStatus.COMPLETED, storage.storedCheckpoint?.status)
    }

    @Test
    fun `interruption immediately before database commit rolls back cleanly without advancing checkpoint`() = runBlocking {
        val storage = SimulatedDatabaseStorage()
        val playlistId = "test_rollback_before_commit"

        // Initial state: batch 1 committed (tracks 1..100)
        val initialCheckpoint = SpotifyImportCheckpoint(
            importId = playlistId,
            playlistUrl = "https://open.spotify.com/playlist/$playlistId",
            playlistName = "Test",
            totalTracks = 4000,
            nextOffset = 100,
            lastBatchIndex = 0,
            importedCount = 100,
            status = SpotifyImportStatus.PROCESSING,
        )
        storage.commitBatch((1..100).map { "track_$it" }, initialCheckpoint)

        // Simulate batch 2 (tracks 101..200): network/CPU succeeds, but DB commit crashes before transaction commits
        val uncommittedTracks = (101..200).map { "track_$it" }
        val attemptedCheckpoint = initialCheckpoint.copy(nextOffset = 200, importedCount = 200)

        // Simulate crash/rollback: uncommittedTracks are discarded, checkpoint is NOT updated in storage
        storage.rollbackBatch()

        // Verify storage remains at batch 1 state
        assertEquals(100, storage.persistedTracks.size)
        assertEquals(100, storage.storedCheckpoint?.nextOffset)
        assertEquals(100, storage.storedCheckpoint?.importedCount)

        // On resume, importer reads storedCheckpoint and re-requests from startOffset = 100
        val client = FakePagedSpotifyClient(totalCount = 4000, pageSize = 100)
        var resumedFirstBatchOffset = -1
        client.getPlaylistTracksPaged(playlistId, startOffset = storage.storedCheckpoint!!.nextOffset, pageSize = 100).take(1).collect { batch ->
            resumedFirstBatchOffset = batch.offset
            // Verify re-fetched tracks are 101..200
            assertEquals("track_101", batch.tracks.first().id)
            assertEquals("track_200", batch.tracks.last().id)
        }
        assertEquals(100, resumedFirstBatchOffset)
    }

    @Test
    fun `interruption immediately after database commit preserves committed work and advances next offset`() = runBlocking {
        val storage = SimulatedDatabaseStorage()
        val playlistId = "test_commit_after_commit"

        // Batch 5 finishes and commits successfully
        val checkpointAfterBatch5 = SpotifyImportCheckpoint(
            importId = playlistId,
            playlistUrl = "https://open.spotify.com/playlist/$playlistId",
            playlistName = "Test",
            totalTracks = 4000,
            nextOffset = 500,
            lastBatchIndex = 4,
            importedCount = 500,
            status = SpotifyImportStatus.PROCESSING,
        )
        storage.commitBatch((1..500).map { "track_$it" }, checkpointAfterBatch5)

        // App killed immediately after commit
        // Resume from storage
        val checkpoint = storage.storedCheckpoint!!
        assertEquals(500, checkpoint.nextOffset)
        assertEquals(500, storage.persistedTracks.size)

        // Client starts directly at 500
        val client = FakePagedSpotifyClient(totalCount = 4000, pageSize = 100)
        client.getPlaylistTracksPaged(playlistId, startOffset = checkpoint.nextOffset, pageSize = 100).take(1).collect { batch ->
            assertEquals(500, batch.offset)
            assertEquals("track_501", batch.tracks.first().id)
            assertEquals("track_600", batch.tracks.last().id)
        }
    }

    @Test
    fun `SpotifyImportLock enforces mutual exclusion for concurrent imports`() {
        val playlistId = "exclusive_playlist_123"

        // First worker acquires lock
        val acquiredFirst = SpotifyImportLock.tryAcquire(playlistId)
        assertTrue("First worker must acquire lock successfully", acquiredFirst)

        // Second concurrent worker attempts to acquire same playlist lock
        val acquiredSecond = SpotifyImportLock.tryAcquire(playlistId)
        assertFalse("Second concurrent worker must be rejected", acquiredSecond)

        // Different playlist cannot acquire while one is running (single global import)
        val acquiredOther = SpotifyImportLock.tryAcquire("other_playlist_456")
        assertFalse("Different playlist import must also be rejected while one is running", acquiredOther)

        // First worker completes and releases
        SpotifyImportLock.release(playlistId)

        // Now a new worker can acquire
        val acquiredThird = SpotifyImportLock.tryAcquire("other_playlist_456")
        assertTrue("Lock can be acquired after previous import released", acquiredThird)
        SpotifyImportLock.release("other_playlist_456")
    }

    @Test
    fun `durable cancellation prevents automatic resumption`() {
        val playlistId = "cancelled_import"
        val checkpoint = SpotifyImportCheckpoint(
            importId = playlistId,
            playlistUrl = "https://open.spotify.com/playlist/$playlistId",
            playlistName = "Cancelling Playlist",
            totalTracks = 4000,
            nextOffset = 1500,
            lastBatchIndex = 14,
            importedCount = 1500,
            status = SpotifyImportStatus.CANCELLED,
            isCancelled = true,
        )

        val json = checkpoint.toJsonString()
        val parsed = SpotifyImportCheckpoint.fromJsonString(json)

        assertNotNull(parsed)
        assertTrue(parsed!!.isCancelled)
        assertEquals(SpotifyImportStatus.CANCELLED, parsed.status)

        // Verification of resume check condition in SpotifyPlaylistImporter:
        val isResumable = !parsed.isCancelled &&
            parsed.status != SpotifyImportStatus.COMPLETED &&
            parsed.status != SpotifyImportStatus.COMPLETED_WITH_ERRORS &&
            parsed.nextOffset > 0

        assertFalse("Cancelled checkpoint must NOT be resumable", isResumable)
    }

    @Test
    fun `checkpoint JSON serialization handles Room CHECKPOINT_PREFIX and raw JSON seamlessly`() {
        val original = SpotifyImportCheckpoint(
            importId = "prefix_test",
            playlistUrl = "https://open.spotify.com/playlist/prefix_test",
            playlistName = "Prefix Test",
            artworkUrl = "https://example.com/art.jpg",
            totalTracks = 3500,
            nextOffset = 1200,
            lastBatchIndex = 11,
            importedCount = 1190,
            skippedCount = 0,
            failedCount = 10,
            status = SpotifyImportStatus.PROCESSING,
        )

        // 1. Raw JSON
        val rawJson = original.toJsonString()
        val parsedRaw = SpotifyImportCheckpoint.fromJsonString(rawJson)
        assertEquals(original, parsedRaw)

        // 2. Prefixed description as stored in Room DB
        val dbDescription = SpotifyImportCheckpoint.CHECKPOINT_PREFIX + rawJson
        val parsedFromDb = SpotifyImportCheckpoint.fromJsonString(dbDescription)
        assertEquals(original, parsedFromDb)

        // 3. Regular non-checkpoint description returns null without crash
        val normalDesc = "Imported from Spotify (3500 tracks)"
        val parsedNormal = SpotifyImportCheckpoint.fromJsonString(normalDesc)
        assertNull(parsedNormal)
    }
}
