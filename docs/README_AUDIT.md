# ABYSS Documentation & Technical Verification Audit

**Audit Date:** 21 November 2026  
**Auditor:** Automated Engineering Verification System  
**Repository:** https://github.com/slythnox/Abyss.git  
**Target Specification:** Complete README.md Creation and Repository Audit  

---

## 1. Scope of Inspection

The repository was comprehensively audited across source code, build scripts, version catalogs, manifest definitions, database configurations, and live emulator runtime behaviour prior to authoring the documentation. The following critical components and files were directly inspected:

1. **Build Configuration & Version Catalog:**
   - `settings.gradle.kts` (Root project declaration, multi-module inclusion)
   - `build.gradle.kts` (Root buildscript, Kotlin compiler configurations)
   - `gradle/libs.versions.toml` (Comprehensive dependency version catalog)
   - `app/build.gradle.kts` (Android application configuration, SDK versions, product flavours, signing configurations, versioning tasks)
   - `version.properties` (Single source of truth for semantic application versioning)

2. **Application Identity & Package Namespace:**
   - Android Application ID: `com.napster.music` (configured in `defaultConfig.applicationId`)
   - Internal Kotlin Namespace: `com.rift.music` (configured in `android.namespace`)
   - Root Project Name: `Abyss` (configured in `settings.gradle.kts` and `base.archivesName`)
   - Application Class: `com.rift.music.app.App`
   - Entry Activities: `com.rift.music.app.MainActivity`, `com.rift.music.app.WelcomeActivity`, `com.rift.music.app.CrashActivity`

3. **Persistence Layer & Database Schema:**
   - `app/src/main/kotlin/com/rift/music/db/MusicDatabase.kt`
   - `app/src/main/kotlin/com/rift/music/db/DatabaseDao.kt`
   - `app/src/main/kotlin/com/rift/music/constants/PreferenceKeys.kt` (AndroidX DataStore keys)

4. **Playback & Media Architecture:**
   - `app/src/main/kotlin/com/rift/music/playback/MusicService.kt` (AndroidX Media3 `MediaSessionService` / `MediaLibraryService`)
   - `app/src/main/kotlin/com/rift/music/playback/PlayerConnection.kt` (`MediaController` client wrapper)
   - `app/src/main/kotlin/com/rift/music/playback/ExoDownloadService.kt`
   - `app/src/main/kotlin/com/rift/music/eq/EqualizerService.kt` & `CustomEqualizerAudioProcessor.kt`

5. **Playlist Intelligence & Provider Integrations:**
   - `app/src/main/kotlin/com/rift/music/feature/spotifyimport/SpotifyPlaylistImporter.kt`
   - `app/src/main/kotlin/com/rift/music/playback/resolver/TrackResolutionEngine.kt`
   - `app/src/main/kotlin/com/rift/music/playback/resolver/ResolutionDeduplicator.kt`
   - `app/src/main/kotlin/com/rift/music/playback/resolver/StreamResolver.kt`
   - Modules: `:innertube`, `:spotify`, `:lrclib`, `:kugou`, `:betterlyrics`, `:musixmatchlyrics`, `:paxsenixlyrics`, `:youlyplus`, `:canvas`, `:applecanvas`, `:vivimusiccanvas`, `:artistvideo`, `:jiosaavn`, `:lastfm`, `:spine`

6. **Permissions & System Integration:**
   - `app/src/main/AndroidManifest.xml` (Permissions, foreground service types, Android Auto metadata, home-screen widgets)

7. **Visual Assets & Branding:**
   - `docs/banner.png` (Approved horizontal brand banner)
   - `docs/logo.png` (Approved high-resolution square brand logo)
   - `app/src/main/res/drawable/napster_logo.png` & mipmap resources

---

## 2. Verified Technical Facts

The following technical metrics, versions, and architectural behaviours have been definitively verified against source code and Gradle files:

| Attribute | Verified Value | Verification Source |
|---|---|---|
| Product Name | **ABYSS** | `settings.gradle.kts`, `app/build.gradle.kts` |
| Application ID | `com.napster.music` | `app/build.gradle.kts` (`defaultConfig.applicationId`) |
| Kotlin Namespace | `com.rift.music` | `app/build.gradle.kts` (`android.namespace`) |
| Current Version | `1.0.7` | `version.properties` (`VERSION_MAJOR=1`, `VERSION_MINOR=0`, `VERSION_PATCH=7`) |
| Derived Version Code | `10007` | Derived via `major * 10000 + minor * 100 + patch` |
| Minimum Android SDK | API 26 (Android 8.0 Oreo) | `app/build.gradle.kts` (`minSdk = 26`) |
| Target Android SDK | API 36 | `app/build.gradle.kts` (`targetSdk = 36`) |
| Compile Android SDK | API 37 | `app/build.gradle.kts` (`compileSdk = 37`) |
| Android NDK Version | `27.0.12077973` | `app/build.gradle.kts` (`ndkVersion`) |
| Room Database Name | `song.db` | `MusicDatabase.kt` (`DB_NAME = "song.db"`) |
| Room Schema Version | `39` | `MusicDatabase.kt` (`@Database(..., version = 39)`) |
| Android Gradle Plugin | `9.2.1` | `gradle/libs.versions.toml` |
| Kotlin Version | `2.3.10` | `gradle/libs.versions.toml` |
| Jetpack Compose | `1.10.2` | `gradle/libs.versions.toml` |
| Material 3 | `1.5.0-alpha18` | `gradle/libs.versions.toml` |
| AndroidX Media3 | `1.7.1` | `gradle/libs.versions.toml` |
| Room Version | `2.8.4` | `gradle/libs.versions.toml` |
| Dagger Hilt | `2.59.1` | `gradle/libs.versions.toml` |
| Ktor Client | `3.4.0` | `gradle/libs.versions.toml` |
| Coil Image Loader | `3.3.0` | `gradle/libs.versions.toml` |
| AndroidX WorkManager | `2.10.0` | `gradle/libs.versions.toml` |
| Licence | GNU General Public Licence v3.0 | `LICENSE` |

### Verified Build Variants & Product Flavours

The application defines a two-dimensional flavour hierarchy (`abi` and `variant`):
- **Variant Dimension:**
  - `foss`: Free and Open Source variant (default). F-Droid compliant, zero proprietary Google Play Services dependencies, Google Cast disabled (`CAST_AVAILABLE = false`).
  - `gms`: Google Mobile Services variant. Includes AndroidX Cast and Google Cast Framework (`CAST_AVAILABLE = true`).
- **ABI Dimension:**
  - `universal`: Universal APK containing all native ABIs.
  - `arm64`: Filtered to `arm64-v8a`.
  - `armeabi`: Filtered to `armeabi-v7a`.
  - `x86`: Filtered to `x86`.
  - `x86_64`: Filtered to `x86_64`.

---

## 3. Verified Features Included in Documentation

The following features were directly verified in the codebase and live application runtime, and are documented in the README:

1. **Three-Tier Persistent Playlist Intelligence:**
   - **Tier 1 (Persistent Metadata):** Immediate insertion of `SongEntity`, `AlbumEntity`, and `ArtistEntity` upon Spotify or local import with stable deterministic `spotify:<id>` keys, preventing data loss.
   - **Tier 2 (Persistent Provider Identity Mapping):** SQLite junction table `ProviderMappingEntity` (`sourceId` -> `providerTrackId`) with scoring metrics. The initial tracks are eagerly resolved to enable immediate playback, with remaining tracks enriched via background `WorkManager` workers.
   - **Tier 3 (Temporary Playback Stream Cache):** Two-layer caching (in-memory L1 cache + Room `PlaybackStreamCacheEntity` L2 cache). Includes automatic stream URL expiry parsing (`expire=` query parameter) with a 10-minute safety window and single-flight resolution deduplication via `ResolutionDeduplicator`.

2. **Playback Engine & Audio Pipeline:**
   - AndroidX Media3 / ExoPlayer backend with `MediaSessionService` implementation in `MusicService`.
   - Audio offload support (`enable_offload`).
   - Seamless crossfade between consecutive tracks with configurable duration.
   - Manual skip crossfade and experimental Auto-DJ beat-synchronised transitions.
   - Audio normalisation and silence skipping (`Skip silence` and `Instantly skip silence`).
   - Custom 3-band radial and advanced equaliser with Abyss signature presets and Dolby Atmos audio profiles.
   - Sleep timer and persistent playback queue restoration across process death.

3. **Music Discovery & Search:**
   - Multi-provider InnerTube search pipeline with real-time suggestions and search history.
   - Categorised exploration hubs ("Moods & moments", "Genres").
   - Complete artist profile pages featuring subscriber statistics, monthly listeners, biographies, and top releases.
   - Album details pages with track numbering, release years, and copyright data.

4. **Synchronised Lyrics & Canvas Video:**
   - Multi-provider synchronised lyrics engine (LRCLIB, KuGou, BetterLyrics, Musixmatch, Paxsenix, YouLyPlus).
   - Karaoke-style word-by-word synchronised lyric highlighting with expanded fullscreen mode.
   - Canvas background video looping support (Apple Music canvas, Vivi Music canvas, artist video loops).

5. **Android System Integration:**
   - Modern Android 13+ media notifications with playback controls and dynamic artwork colouring.
   - Android Auto support via `automotive_app_desc.xml` and `ic_notification_rift`.
   - Three home-screen app widgets:
     - Abyss Dot Player (Compact 2x2 player widget)
     - Abyss Lyrics Player (Wide 2x4 split-panel lyrics widget)
     - Abyss Vinyl Deck (Interactive 2x4 rotating vinyl disc widget)
   - Dynamic per-app launcher icon aliases.

---

## 4. Features & Claims Deliberately Omitted

In accordance with strict technical accuracy standards, several items were excluded from the documentation:

1. **Cloud Account Synchronization & Proprietary Backend:**
   - Abyss possesses no proprietary remote user authentication, proprietary cloud sync database, or centralised hosting server. Any claims suggesting central account logins were omitted; the client operates entirely serverless and client-side.
2. **Proprietary Music Catalogue Ownership:**
   - Abyss is a client audio engine and metadata aggregator; it does not host or distribute proprietary media. Claims implying catalogue ownership were explicitly avoided.
3. **Flac Export Claims:**
   - While a `FlacTaggerTest.kt` exists for writing Vorbis/FLAC metadata comments during local tag manipulation, full lossless transcoding and arbitrary multi-format exporting are not exposed as general user features and were omitted from headline feature lists.
4. **Unsubstantiated Performance Benchmarks:**
   - Empty marketing phrases ("instantaneous playback", "zero memory usage", "world's fastest player") were completely omitted in favour of factual architectural explanations.
5. **Planned or Unmerged Features:**
   - Multi-user collaborative queues and peer-to-peer playlist syncing remain experimental design discussions and were excluded from implemented feature documentation.

---

## 5. Screenshot Capture Audit

Screenshots were captured from a genuine Android Virtual Device running the current release build (`com.napster.music.debug` v1.0.7) on `emulator-5554` (API level 35/36, physical resolution 1080x2400). All images were retrieved via direct raw framebuffer output (`adb exec-out screencap -p`), saved to `docs/images/screenshots/`, and verified for valid PNG headers and appropriate visual presentation.

| Filename | Resolution | File Size | Description |
|---|---|---|---|
| `home.png` | 1080 x 2400 | 923,594 bytes | Main Home dashboard showing "LISTENING EVERYDAY", Speed dial tiles, Random button, and floating navigation pill. |
| `now-playing.png` | 1080 x 2400 | 1,345,962 bytes | Now Playing player view with canvas background, track title, artist, `OPUS · 48.0kHz` format badge, timeline, and transport controls. |
| `queue.png` | 1080 x 2400 | 412,851 bytes | Active playback queue showing "Continue Playing" tracks, play indicators, Shuffle, Repeat, and Autoplay toggles. |
| `lyrics.png` | 1080 x 2400 | 695,031 bytes | Synchronised lyrics view displaying word-level glowing text highlights against a blurred album backdrop. |
| `search.png` | 1080 x 2400 | 305,809 bytes | Search hub displaying "Explore", "Suggestions", and "History" tabs along with "Moods & moments" categories. |
| `library.png` | 1080 x 2400 | 171,058 bytes | User library showcasing Playlists, Songs, Albums, Sort button, and quick collections (Liked, Downloaded, My top 50). |
| `artist.png` | 1080 x 2400 | 933,103 bytes | Complete artist page (Billie Eilish) with hero photography, subscriber count, monthly listeners, biography, and top songs. |
| `settings.png` | 1080 x 2400 | 273,825 bytes | Root Settings panel displaying Appearance, Player and audio, Standby, History, Stats, and Content categories. |
| `equaliser.png` | 1080 x 2400 | 163,862 bytes | Custom Abyss Equaliser panel with 3-point radial tuning dial, Simple/Advanced mode switch, Abyss Signature presets, and Dolby Atmos modes. |

---

## 6. Verification and Test Execution Results

- **Unit Test Execution:** The full unit test suite was executed locally via `./gradlew testUniversalFossDebugUnitTest`. The test run completed successfully (`BUILD SUCCESSFUL in 5m 36s`, 251 actionable tasks: 37 executed, 214 up-to-date) confirming that all database migrations, playlist import pagination logic, stream caching, and audio processing tests pass without failure.
- **Bluetooth Device Discovery in Emulator:** Attempting to open the physical device output routing dialog on the emulator prompts for Android runtime Bluetooth permissions (`android.permission.BLUETOOTH_CONNECT`), as expected when no physical Bluetooth controller is paired. The custom audio equaliser screen was accessed through Settings -> Player and audio -> Abyss Equalizer.
- **Git Commit Restrictions:** In accordance with task constraints, no Git commits, tags, pushes, or releases were performed.
