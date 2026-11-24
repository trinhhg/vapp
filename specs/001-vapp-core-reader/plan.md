# Implementation Plan: `vapp` — Standalone Android Novel Reader, Downloader & EPUB 2/3 Exporter

**Branch**: `001-vapp-core-reader` | **Date**: 2026-11-24 | **Spec**: [spec.md](file:///c:/Users/Trinh%20Hg/.gemini/antigravity/scratch/vapp/specs/001-vapp-core-reader/spec.md)

**Input**: Feature specification from `/specs/001-vapp-core-reader/spec.md`

## Summary

Build `vapp` as an open-source, standalone Android application compatible with the non-encrypted VBook Extension ecosystem, featuring resilient background downloads, a distraction-free reader with offline Android TTS audiobook playback, independent EPUB 2/3 & TXT export, local backup/restore, and automated GitHub Actions APK builds—with zero proprietary servers, zero paid APIs, and zero Premium gates.

The technical strategy selectively reuses and hardens the MIT-licensed engine core from `haipham22/LeechText` (Rhino `1.7.15` sandbox, security validators, VBook repository/zip pipeline, chapter merge logic) inside a cleanly decoupled 5-module Gradle architecture (`:engine`, `:downloader`, `:epub-exporter`, `:tts-player`, `:app`), while closing LeechText's verified VBook JS API gaps (`_HttpResponse` properties, charset decoding, `UserAgent.chrome()/system()`, Android `WebView` `Engine.newBrowser()` bridge), replacing in-memory download queues with Room + `WorkManager`/`ForegroundService`, and adding true EPUB 3 (`nav.xhtml`) + custom range export.

## Technical Context

**Language/Version**: Kotlin `2.1+` (targeting JVM 17 bytecode), Gradle Kotlin DSL, JDK 17  
**Primary Dependencies**:
- `:engine` (Pure JVM/Kotlin): Mozilla Rhino `1.7.15` (MPL-2.0), OkHttp `5.x`, Okio `3.x`, Ksoup `0.2.x`, Zip4j `2.11.x`, `kotlinx-coroutines`, `kotlinx-serialization-json` (all MIT/Apache-2.0 except Rhino MPL-2.0)
- `:downloader` (Android Library): AndroidX Room (SQLite WAL), WorkManager, ForegroundService (`dataSync`)
- `:epub-exporter` (Pure JVM/Kotlin): Ksoup, Okio, Zip4j / `java.util.zip` (explicit uncompressed `mimetype` entry)
- `:tts-player` (Android Library): Android `TextToSpeech`, AndroidX `Media3` Session/Player (`mediaPlayback` ForegroundService)
- `:app` (Android Application): Jetpack Compose + Material 3, AndroidX Navigation Compose, DataStore Preferences, AndroidX WebKit (`WebView`), Coil 3, Koin DI

**Storage**: Room SQLite (`vapp.db` in WAL mode) for indexed entities + structured app-internal files (`extensions/`, `books/<bookId>/chapters/*.html`, `fonts/`, `logs/`) + DataStore Preferences (`AppSettings`) + SAF / `MediaStore.Downloads` for exported EPUBs and backup archives  
**Testing**: JUnit 5 + `kotlin-test` + MockWebServer / OkHttp fixtures on JVM (`:engine:test`, `:epub-exporter:test`), Robolectric/JUnit + WorkManager Test on `:downloader` / `:tts-player`, Compose UI tests + real-extension smoke harness on Android  
**Target Platform**: Android 8.0+ (`minSdk = 26`, `targetSdk = 35`, `compileSdk = 35`)  
**Project Type**: Multi-module Android mobile application (with pure-JVM core modules `:engine` and `:epub-exporter` for rapid host testing and CI)  
**Performance Goals**:
- Handle books with `3,000+` chapters without UI stutter or directory-scan bottlenecks (Room indexed queries + virtualized lists)
- Parallel chapter downloads (`1..6` configurable concurrency via `Semaphore`) with per-chapter atomic checkpointing
- Sub-5-second EPUB 2/3 export for typical 500-chapter books

**Constraints**:
- 100% open-source libraries (MIT/Apache-2.0/BSD/MPL-2.0); zero Play Billing, Firebase, analytics, or external servers
- No mock/fake fallback content on extension or network failures
- Strict P1 real-extension E2E gate before major UI implementation (P5)
- No commits/pushes to `https://github.com/trinhhg/vapp` without passing checks and Admin approval

**Scale/Scope**: 5 Gradle modules, ~12 core screens/sheets aligned with the 4 reference UI screenshots + VBook workflows, support for 65+ unencrypted `vbook-extensions` and `ext-vbook` sources.

## Constitution Check

*GATE: Evaluated before Phase 0 research and re-checked after Phase 1 design.*

| Principle / Gate | Pre-Design | Post-Design | Verification Notes |
|---|---|---|---|
| **I. Open-Source & Server-Independent Stack** | PASS | PASS | All chosen deps audited in [research.md](file:///c:/Users/Trinh%20Hg/.gemini/antigravity/scratch/vapp/specs/001-vapp-core-reader/research.md) §6 (Apache-2.0 / MIT / MPL-2.0 Rhino). Zero billing, zero server, zero Premium lock. |
| **II. Honest Execution & Explicit Error Reporting** | PASS | PASS | Typed `EngineException` codes, structured `EngineLogger`, atomic `.tmp` export (no 0-byte silent files), explicit UI error states. |
| **III. Milestone-Gated Delivery (P0 -> P6)** | PASS | PASS | Roadmap below enforces P1 E2E extension gate before P2–P5; major UI polish sits at P5 after P1–P4 core validation. |
| **IV. VBook Ecosystem Compatibility & Sandbox Safety** | PASS | PASS | Full `_HttpResponse` + charset + `WebViewBrowserBridge` contract in [vbook-js-runtime.md](file:///c:/Users/Trinh%20Hg/.gemini/antigravity/scratch/vapp/specs/001-vapp-core-reader/contracts/vbook-js-runtime.md); `"encrypt": true` explicitly rejected; Rhino sandbox + Zip-Slip + ReDoS + URL validators retained. |
| **V. Repository & Release Discipline** | PASS | PASS | CI workflow `.github/workflows/android-ci.yml` designed locally; no push to `trinhhg/vapp` until verification & Admin approval. |

No constitution violations exist; Complexity Tracking is not required.

## Project Structure

### Documentation (this feature)

```text
specs/001-vapp-core-reader/
├── spec.md                         # Feature specification (clarified)
├── plan.md                         # This implementation plan
├── research.md                     # Phase 0 technical decisions & LeechText reuse audit
├── data-model.md                   # Room schema, DataStore settings & disk layout
├── quickstart.md                   # Gate verification guide (P1 -> P6)
├── checklists/
│   └── requirements.md             # Spec quality checklist (16/16 passing)
└── contracts/
    ├── vbook-js-runtime.md         # VBook JS API & sandbox contract
    ├── module-interfaces.md        # Acyclic Kotlin interfaces across 5 modules
    └── backup-format.md            # Portable backup ZIP manifest & restore validation
```

### Source Code (repository root)

```text
vapp/
├── .github/
│   └── workflows/
│       └── android-ci.yml          # Automated test + Debug APK (push/dispatch) + Release APK (v* tags)
├── gradle/
│   └── libs.versions.toml          # Centralized versions & license-vetted deps
├── build.gradle.kts                # Root plugins, ktlint/spotless & detekt config
├── settings.gradle.kts             # Includes :engine, :downloader, :epub-exporter, :tts-player, :app
├── engine/                         # Pure JVM/Kotlin — VBook runtime & extension engine (from LeechText MIT)
│   └── src/
│       ├── main/kotlin/dev/trinhhg/vapp/engine/
│       │   ├── js/                 # RhinoContextPool, JsSandbox, JsHttpResponse, Http, Html, UserAgent, BrowserEngine seam, load()
│       │   ├── net/                # CharsetDecoder, CookiePersistence interface + Okio JSON CookiesStore
│       │   ├── security/           # NetworkSecurityValidator, RegexSecurityValidator, ZipSecurityValidator
│       │   ├── vbook/              # VBookRepositoryClient, PluginZipExtractor, VBookToExtensionConverter, ExtensionManager
│       │   ├── fetch/              # BookFetch, mergeFetchedChapters, ExtensionExecutorImpl
│       │   └── log/                # Ring-buffer + file EngineLogger
│       └── test/                   # Fixture + live-opt-in VBook extension tests
├── downloader/                     # Android Library — persistent queue & background download execution
│   └── src/
│       ├── main/kotlin/dev/trinhhg/vapp/downloader/
│       │   ├── db/                 # Room Database, DAOs (Book, Chapter, DownloadTask, Bookmark, CleanupRule, Repo, Extension)
│       │   ├── storage/            # Atomic ChapterFileStore & CoverStore
│       │   ├── worker/             # DownloadWorker (WorkManager) + DownloadForegroundService (dataSync)
│       │   └── queue/              # DownloadQueueManagerImpl (Semaphore concurrency, backoff retry, circuit breaker)
│       └── test/                   # Room migration/DAO + queue resume/breaker tests
├── epub-exporter/                  # Pure JVM/Kotlin — standalone EPUB 2.0, EPUB 3.0 & TXT builder
│   └── src/
│       ├── main/kotlin/dev/trinhhg/vapp/exporter/
│       │   ├── epub/               # Opf2Writer, NcxWriter, Opf3Writer, NavXhtmlWriter, EpubPackageWriter (STORED mimetype)
│       │   ├── html/               # ChapterSanitizer, CleanupRuleApplier, VolumeSplitter, CoverNormalizer interface
│       │   └── txt/                # PlainTextExporter
│       └── test/                   # EPUB 2/3 structural & UTF-8 validation tests
├── tts-player/                     # Android Library — offline TTS & Audiobook playback service
│   └── src/
│       ├── main/kotlin/dev/trinhhg/vapp/tts/
│       │   ├── engine/             # TtsEngine interface + AndroidSystemTtsEngine
│       │   ├── service/            # AudiobookMediaService (Media3 MediaSessionService + mediaPlayback notification)
│       │   ├── audio/              # SleepTimer & optional AmbienceAudioMixer
│       │   └── state/              # AudiobookControllerImpl
│       └── test/                   # Playback progression, sleep timer & queue state tests
└── app/                            # Android Application — Jetpack Compose UI & platform bridges
    └── src/
        ├── main/kotlin/dev/trinhhg/vapp/
        │   ├── di/                 # Koin modules wiring :engine, :downloader, :epub-exporter, :tts-player
        │   ├── export/             # AndroidCoverNormalizer (BitmapFactory) + SafExportSaver
        │   ├── webview/            # AndroidWebViewBrowserFactory + ChallengeSolverActivity (auto-challenge & manual login) + CookieSync
        │   ├── backup/             # BackupExporter & ValidatedBackupImporter (SAF)
        │   └── ui/
        │       ├── theme/          # Light/Dark/Sepia/OLED/Custom reader & app Material 3 tokens
        │       ├── navigation/     # Floating capsule bottom bar + '+' FAB + routes
        │       ├── bookshelf/      # BookshelfScreen ("Xem gần đây" carousel + 3-col grid + %/chapter badges)
        │       ├── explore/        # ExploreSourcesScreen, SourceCatalogScreen, GlobalSearchScreen, AddUrlSheet
        │       ├── detail/         # BookDetailScreen, ChapterPickerSheet, ExportSheet
        │       ├── reader/         # ReaderScreen (scroll/slide/curl) + ReaderBottomSheet (ref screenshot #3)
        │       ├── audiobook/      # AudiobookPlayerScreen (ref screenshot #1) + VoiceSettingsSheet
        │       ├── downloads/      # DownloadQueueScreen (pause/resume/cancel/retry-failed)
        │       ├── extensions/     # ExtensionManagerScreen, RepoManagerScreen, ExtensionConfigSheet, DebugLogScreen
        │       └── settings/       # SettingsScreen, CookieManagerScreen, BackupRestoreScreen
        └── test/                   # ViewModel & navigation state unit tests
```

**Structure Decision**: Multi-module Android Gradle architecture (`:engine` and `:epub-exporter` pure JVM; `:downloader`, `:tts-player`, and `:app` Android) enforces strict acyclic boundaries (see [module-interfaces.md](file:///c:/Users/Trinh%20Hg/.gemini/antigravity/scratch/vapp/specs/001-vapp-core-reader/contracts/module-interfaces.md)), enables sub-10-second JVM test feedback for VBook JS execution and EPUB 2/3 generation, and prepares clean CI gating in `.github/workflows/android-ci.yml`.

## Milestone Roadmap (`P1` -> `P6`)

### Phase 1 (P1): Core VBook Extension Engine & E2E Verification *(Strict Gate Before UI)*
- **Deliverables**:
  - Scaffold Gradle multi-module repo (`settings.gradle.kts`, `libs.versions.toml`, ktlint/detekt, `.github/workflows/android-ci.yml`).
  - Port & refactor LeechText MIT `:engine` core (`RhinoContextPool`, `JsSandbox`, security validators, `VBookRepositoryClient`, `PluginZipExtractor`, `VBookToExtensionConverter`, `BookFetch`).
  - Fix VBook JS API gaps per [vbook-js-runtime.md](file:///c:/Users/Trinh%20Hg/.gemini/antigravity/scratch/vapp/specs/001-vapp-core-reader/contracts/vbook-js-runtime.md): `JsHttpResponse` (`.ok`, `.status`, `.text(charset)`, `.html(charset)`), charset detection cascade, `UserAgent.chrome()`/`system()`, `JSElement.tagName(newTag)`, `cacheStorage`, `localCookie`, `load()` substitution, and explicit `"encrypt": true` rejection.
  - Implement minimal Android `WebView` bridge (`AndroidWebViewBrowserFactory` + `ChallengeSolverActivity` + cookie sync) plus a thin P1 smoke harness screen to validate real VBook extensions end-to-end on Android.
- **Exit Gate**: Pass `:engine:test` + live E2E verification across 3–5 unencrypted extensions (including `truyenfull`, `tangthuvien`, `truyenyy` or `bachngocsach`, a GBK Chinese source like `twkan`/`169shu`, and a `Engine.newBrowser()` source like `truyendocviet`), plus explicit rejection of `metruyenchu` (`"encrypt": true`).

### Phase 2 (P2): Persistent Downloader & Scalable Local Storage
- **Deliverables**:
  - Implement `:downloader` Room database (`vapp.db` with all 7 tables), `ChapterFileStore` (atomic chapter HTML writes), and `BookFetch` ongoing-chapter merge.
  - Implement `DownloadQueueManagerImpl` backed by `WorkManager` + `DownloadForegroundService` (`dataSync`), supporting `ALL` / custom range / selected chapters / multi-URL batching, pause/resume/cancel, exponential backoff retry, 8-error circuit breaker, one-tap retry failed chapters, and startup task recovery.
- **Exit Gate**: `:downloader:testDebugUnitTest` passes + 1,000-chapter Android download survives simulated offline drop and force-stop with 0 duplicate chapter downloads.

### Phase 3 (P3): Standalone EPUB 2 / EPUB 3 & TXT Exporter
- **Deliverables**:
  - Implement `:epub-exporter` with `Opf2Writer` + `NcxWriter` (EPUB 2.0), `Opf3Writer` + `NavXhtmlWriter` + optional NCX (EPUB 3.0), `PlainTextExporter`, `ChapterSanitizer`, `CleanupRuleApplier`, `VolumeSplitter`, and `EpubPackageWriter` (uncompressed first `mimetype` entry + atomic `.tmp` output).
  - Support cover normalization (WebP/PNG/GIF -> JPEG), selectable chapter scope (`all`, `downloaded`, `from–to` range), and Android SAF / Downloads export + share sheet.
- **Exit Gate**: `:epub-exporter:test` passes + exported Vietnamese UTF-8 EPUB 2 & EPUB 3 files open and navigate chapters cleanly in 2 independent external readers.

### Phase 4 (P4): Distraction-Free Reader & Offline System TTS Audiobook
- **Deliverables**:
  - Implement `:tts-player` (`AndroidSystemTtsEngine`, `AudiobookMediaService` via Media3 `MediaSessionService`, sleep timer, speed/pitch, optional local ambience mixer, auto-next-chapter, lock-screen controls).
  - Implement core Reader screen (`VERTICAL_SCROLL`, `PAGED_SLIDE`, `PAGED_CURL`), persistent reading position per book, bookmarks CRUD, inline cleanup rule editor, and inline chapter text editor.
- **Exit Gate**: Reading position & theme settings persist across restart; offline TTS plays 3 consecutive chapters with screen locked and responsive media notification controls.

### Phase 5 (P5): Full Mobile UI Polish, Backup/Restore & Settings
- **Deliverables**:
  - Complete all UI screens matching the 4 reference screenshots (Bookshelf "Xem gần đây" + 3-col grid + floating capsule bottom nav + `+` FAB; Explore/Sources/Search; Book Detail + Chapter Picker + Export Sheet; Reader bottom control sheet; Audiobook player screen; Download queue screen; Extension/Repository/Debug Log screens; Settings + Cookie Manager).
  - Implement `BackupExporter` & `ValidatedBackupImporter` per [backup-format.md](file:///c:/Users/Trinh%20Hg/.gemini/antigravity/scratch/vapp/specs/001-vapp-core-reader/contracts/backup-format.md).
  - *(Optional P5+ extension slot)*: Voice Model Manager UI & open-source neural TTS plugin interface (`TtsEngine`) scoped behind separate P5+ tasks without blocking P5 ship.
- **Exit Gate**: Full visual parity check against the 4 reference screenshots, zero Premium/Community/Server clutter, and 100% Backup/Restore round-trip verification.

### Phase 6 (P6): End-to-End QA, Performance & Release Packaging
- **Deliverables**:
  - Full regression pass across P1–P5 acceptance scenarios, memory/scroll benchmark on 2,000+ chapter books, ProGuard/R8 release shrink rules verification, and GitHub Actions CI verification (`.github/workflows/android-ci.yml`).
- **Exit Gate**: All CI checks (`spotlessCheck`, `detekt`, unit tests, `:app:assembleDebug`, `:app:assembleRelease`) pass cleanly; await explicit Admin instruction before pushing to `https://github.com/trinhhg/vapp`.
