# Abyss — Persistent Playlist Intelligence, Background Preparation & Playback Reliability

## 1. System Architecture Overview

Abyss delivers an enterprise-grade music playback and playlist management engine designed for large collections (e.g. 4,000+ songs per playlist).

Prior to this architecture, importing a Spotify playlist of 4,000 tracks attempted up to 12,000 synchronous YouTube search requests during the import process. This triggered rate limits, dropped unresolvable tracks, lost Spotify track IDs, and caused SQLite Foreign Key constraint violations (code 787). In addition, stream URLs were only kept in a 100-entry in-memory cache and lost on app restart.

The **Persistent Playlist Intelligence** architecture decouples the lifecycle of tracks into three distinct, non-interchangeable tiers:

```
+-------------------------------------------------------------------------------+
| Tier 1: Persistent Track Metadata (SongEntity, AlbumEntity, ArtistEntity)     |
| - Stored immediately on import with "spotify:<id>" identity                   |
| - Never dropped; playlist immediately 100% visible and browsable offline      |
+-------------------------------------------------------------------------------+
                                      |
                                      v
+-------------------------------------------------------------------------------+
| Tier 2: Persistent Provider Identity Mapping (ProviderMappingEntity)           |
| - sourceId -> providerTrackId (e.g. Spotify ID -> YouTube videoId)            |
| - Deterministic candidate scoring, confidence metrics, and invalidation state |
| - Resolved eagerly for first 5 tracks; remaining tracks enriched in background|
+-------------------------------------------------------------------------------+
                                      |
                                      v
+-------------------------------------------------------------------------------+
| Tier 3: Temporary Playback Stream Information (PlaybackStreamCacheEntity)     |
| - L1 Memory + L2 Room Cache with 10-minute expiry safety margin               |
| - YouTube `expire=` query parameter parsing                                  |
| - Immediate invalidation on HTTP 403, 404, or 416 errors                      |
| - Single-flight resolution deduplication (ResolutionDeduplicator)             |
+-------------------------------------------------------------------------------+
```

---

## 2. Three-Tier Model Details

### Tier 1: Persistent Track Metadata
- **Storage**: `SongEntity`, `AlbumEntity`, `ArtistEntity`, `PlaylistSongMap`
- **Fields**:
  - `sourceId`: Original platform ID (e.g. Spotify track ID `4cOdK2wGLETKBW3PvgPWqT`).
  - `sourceProvider`: Origin identifier (e.g. `"spotify"`).
  - `matchedVideoId`: Currently assigned YouTube video ID.
  - `matchConfidence`: Match confidence score (0.0 to 1.0).
  - `matchStatus`: `"MATCHED"`, `"UNCERTAIN"`, `"FAILED"`.
  - `metadataLastUpdated`: Timestamp of last metadata update.
  - `isEnriched`: Boolean indicating whether background resolution has completed.
- **Guarantee**: When an import finishes, 100% of songs exist in Room. No song is dropped due to network issues or missing YouTube search matches.

### Tier 2: Persistent Provider Mapping
- **Storage**: `ProviderMappingEntity` (`provider_mapping` table)
- **Fields**:
  - `sourceId` (Primary Key): Source identifier.
  - `provider` (Primary Key): Destination provider (`"youtube"`, `"tidal"`, `"spine"`).
  - `sourceProvider`: Origin provider (`"spotify"`).
  - `providerTrackId`: Target provider track ID (e.g. YouTube `videoId`).
  - `confidence`: Candidate match score from `DeterministicTrackResolutionEngine`.
  - `matchStatus`: Status (`"MATCHED"`, `"UNCERTAIN"`, `"FAILED"`).
  - `invalidationReason`: Diagnostic reason if invalidated.
  - `verifiedAt`: Timestamp when match was verified.
- **Lookup Path**: Fast synchronous DB lookup in `ProviderMappingRepository.getValidMapping()` avoids network searches whenever a track is replayed or re-opened.

### Tier 3: Temporary Playback Stream Cache
- **Storage**: `PlaybackStreamCacheEntity` (`playback_stream_cache` table) + In-Memory `ConcurrentHashMap`
- **Fields**:
  - `cacheKey` (Primary Key): Composite key formatted as `trackId:provider:quality`.
  - `trackId`: Provider track ID.
  - `provider`: Active provider (`"youtube"`, `"tidal"`, `"spine"`).
  - `quality`: Stream audio quality (`"HIGH"`, `"LOW"`, etc.).
  - `streamUrl`: Direct media URI.
  - `expiresAtMs`: Absolute expiration timestamp.
  - `resolvedAtMs`: Resolution timestamp.
  - `isValid`: Boolean validity flag.
- **Safety Margin**: 10 minutes (`SAFETY_MARGIN_MS = 600_000L`). If a stream URL expires in less than 10 minutes, it is treated as expired to avoid mid-playback dropouts.

---

## 3. Background Preparation Engine (WorkManager)

The background preparation engine is implemented as `PlaylistEnrichmentWorker` (`CoroutineWorker`):
- **Constraints**: Requires `NetworkType.CONNECTED` and battery not low (`setRequiresBatteryNotLow(true)`).
- **Execution Strategy**:
  - Runs in polite batches of 20 tracks.
  - 250ms rate-limiting delay between tracks to avoid provider throttling.
  - 1-second pause between batches.
  - Uses `DeterministicTrackResolutionEngine` to find the best YouTube candidate matching title, artist, album, and duration.
  - Persists mappings atomically into `provider_mapping` and updates `SongEntity.isEnriched = true`.
- **Cancellation**: Fully cooperative with coroutine cancellation when app exits or constraints change.

---

## 4. Single-Flight Resolution Deduplicator

`ResolutionDeduplicator` prevents concurrent network requests for the same track/stream:
- When a user taps track 1, the player requests stream resolution.
- Simultaneously, `prefetchUpcomingTracks()` may request stream prefetching for the next tracks.
- If multiple callers ask for `effKey` (`mediaId` or `mediaId#flac`), only one `YTPlayerUtils.playerResponseForPlayback` call is executed.
- All concurrent callers await the shared `Deferred<T>` result.

---

## 5. Playback Pipeline Tuning (Media3 / ExoPlayer)

`MusicService.createExoPlayer()` configuration:
- **LoadControl** (`DefaultLoadControl`):
  - Minimum buffer: `15_000ms` (15s)
  - Maximum buffer: `50_000ms` (50s)
  - Buffer for playback: `1_000ms` (1s) — enables sub-second playback start.
  - Buffer for re-buffer: `2_500ms` (2.5s)
  - Back buffer: `15_000ms` (15s, retained) — enables instant rewind within recent window without network re-fetch.
- **TrackSelector** (`DefaultTrackSelector`):
  - `setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)`: Disables video rendering pipeline, reducing CPU and memory overhead during audio playback.
- **Audio Focus & Recovery**:
  - Cancels active `retryJob` and `stallRecoveryJob` during track transitions in `onMediaItemTransition` to prevent stale asynchronous retries from interrupting newly selected songs.
  - In `handleRangeNotSatisfiableError` (HTTP 416), preserves `player.currentPosition` rather than resetting position to 0.

---

## 6. Database Migration (Room v38 -> v39)

- Safe, non-destructive Room migration:
  - Added table `provider_mapping` with composite primary key `(sourceId, provider)`.
  - Added table `playback_stream_cache` with primary key `cacheKey`.
  - Added columns `sourceId`, `sourceProvider`, `matchedVideoId`, `matchConfidence`, `matchStatus`, `metadataLastUpdated`, `isEnriched` to `song` table.
- Both `AutoMigration(from = 38, to = 39)` and explicit `MIGRATION_38_39` registered in `MusicDatabase.kt`.
- No user data, history, playlists, or settings are lost or reset.

---

## 7. Diagnostics & Observability

`PlaybackDiagnostics` now tracks all lifecycle events:
- `PROVIDER_SELECTION`: Records provider resolution (`YouTube`, `Tidal`, `Spine`, `JioSaavn`, `Add-on`).
- `STREAM_CACHE_HIT`: L1 memory or L2 database cache hits with remaining TTL.
- `STREAM_CACHE_MISS`: Cache miss prompting network resolution.
- `CACHE_INVALIDATED`: Invalidation on HTTP 403, 404, or 416.
- `RETRY_ATTEMPTED`: Detailed retry counts and recovery stages.

---

## 8. Backward Compatibility & Existing Library Migration

Abyss guarantees 100% backward compatibility for all libraries, playlists, and tracks imported prior to this architecture:

### 8.1 Zero User Re-Import Requirement
Users are never asked or required to re-import playlists. The app seamlessly upgrades existing records in-place.

### 8.2 Idempotent Library Backfill Coordinator (`LegacyLibraryBackfillCoordinator`)
Launched asynchronously in `App.onCreate` after a 1.5-second UI stabilization pause:
1. **Local Metadata Normalization (Phase 1)**:
   - Scans pre-feature tracks where `sourceId IS NULL` in bounded chunks of 100 tracks.
   - For `spotify:<id>` tracks: assigns `sourceId = <id>` and `sourceProvider = "spotify"`. Preserves pre-existing mappings if present.
   - For local tracks: assigns `sourceId = id`, `sourceProvider = "local"`, `matchedVideoId = id`, `isEnriched = true`.
   - For standard YouTube tracks: assigns `sourceId = id`, `sourceProvider = "youtube"`, `matchedVideoId = id`, `isEnriched = true`, and creates an entry in `provider_mapping`.
   - Execution is purely local SQLite updates running in milliseconds with zero network calls.
2. **Prioritized Resolution for Recently Played Tracks (Phase 2)**:
   - Scans the top 20 recent playback events (`database.getRecentEventSongIds()`).
   - Resolves unmapped tracks first so user's active music is immediately ready.
3. **Bounded Background Enrichment (Phase 3)**:
   - Queries `getPlaylistIdsWithUnresolvedSongs()`.
   - Dispatches `PlaylistEnrichmentWorker.enqueue(context, playlistId)` via WorkManager.
   - Polite rate-limiting and battery/network constraints ensure zero drain or provider throttling.
4. **Resumable Progress & Idempotency**:
   - Updates are committed to Room on every batch. If the app is closed, killed, or the device restarts, backfill picks up from remaining unmigrated records on next boot.
   - Running the backfill multiple times has zero side effects and produces zero duplicate records.

### 8.3 Data Preservation Guarantees
- **No Duplicate Songs**: Track identities (`id`, `sourceId`) are unique.
- **No Duplicate Playlist Entries**: `PlaylistSongMap` records remain untouched during metadata normalization.
- **Order & Duplication Retention**: User-curated playlists with duplicate songs at different positions retain exact positions and sequences.
- **User Metadata & History**: Favorites (`liked`), timestamps (`likedDate`), play count, total listening duration (`totalPlayTime`), custom lyrics offsets, download status, and dislikes are strictly preserved.
- **Immediate Playability**: Playlists remain playable throughout the backfill because `MusicService` resolves tracks just-in-time on press.

---

## 9. Verification & Test Suite Summary

The architecture is verified by automated test suites covering:
1. **`PersistentPlaylistIntelligenceTest`**:
   - Composite cache key construction (`buildCacheKey`).
   - L1 memory cache hit avoiding Room database queries.
   - 10-minute safety margin expiring near-expiry stream URLs.
   - Stream cache invalidation on HTTP 403, 404, 416.
   - Invalidate all stream caches for a track on generic IO failure.
   - URL `expire` query parameter extraction with regex parsing.
   - Single-flight resolution deduplication coalescing concurrent callers.
   - Independent execution for distinct resolution keys.
   - Provider mapping retrieval, candidate scoring, and failure filtering.
   - Invalidation of dead provider mappings (`matchStatus = "FAILED"`).
2. **`LegacyLibraryBackfillCoordinatorTest`**:
   - Simulated pre-feature database migration with zero metadata loss.
   - Linking pre-existing provider mappings into legacy tracks.
   - Strict idempotency across repeated backfill executions.
   - Preservation of duplicate playlist entries and exact track ordering.
   - Detection and scheduling of playlists with unresolved tracks.
3. **`SpotifyLargePlaylistImportTest` & `SpotifyImportPaginationTest`**:
   - 4,000-track pagination, batch durability, and resumption.
4. **End-to-End Build & Release Verification**:
   - `:app:testArm64FossDebugUnitTest`: 100% green across all unit test suites.
   - `:app:assembleArm64FossRelease`: Release APK compiled, obfuscated via R8, and packaged cleanly.
