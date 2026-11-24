---

description: "Actionable, dependency-ordered task list for vapp P1 -> P6"
---

# Tasks: vapp — Standalone Android Novel Reader, Downloader & EPUB 2/3 Exporter

**Input**: Design documents from `/specs/001-vapp-core-reader/`  
**Prerequisites**: `plan.md` (required), `spec.md` (required), `research.md`, `data-model.md`, `contracts/`, `quickstart.md`  
**Constitution Gate**: Strict Milestone-Gated Delivery (`P0` -> `P6`): complete and verify **US1 (P1 Engine E2E)** before building major UI polish (**US5**). Never push to `https://github.com/trinhhg/vapp` without passing checks and Admin approval.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies on incomplete tasks)
- **[Story]**: Maps to User Story from `spec.md` (`US1`..`US5`) — omitted in Setup, Foundational, and Final Polish phases
- Exact file paths and `data-model.md` (`Entity.field`) citations included where constrained fields are implemented

---

## Phase 1: Setup (Shared Infrastructure & CI Skeleton)

**Purpose**: Initialize 5-module Android Gradle project (`:engine`, `:downloader`, `:epub-exporter`, `:tts-player`, `:app`), code quality tooling, and automated GitHub Actions APK pipeline (`FR-029`).

- [X] T001 Create root Gradle configuration and 5-module layout in `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, and `gradle/libs.versions.toml`
- [X] T002 [P] Initialize Gradle module build scripts for `:engine` and `:epub-exporter` (pure JVM/Kotlin) in `engine/build.gradle.kts` and `epub-exporter/build.gradle.kts`
- [X] T003 [P] Initialize Android library/app build scripts (`compileSdk=35`, `minSdk=26`, `targetSdk=35`, Compose BOM, ProGuard) in `downloader/build.gradle.kts`, `tts-player/build.gradle.kts`, `app/build.gradle.kts`, and `app/proguard-rules.pro`
- [X] T004 [P] Configure Spotless/ktlint and Detekt rules in `build.gradle.kts` and `config/detekt/detekt.yml`
- [X] T005 [P] Create GitHub Actions CI workflow (run tests + build Debug APK on push/dispatch; Release APK on `v*` tags) in `.github/workflows/android-ci.yml`

---

## Phase 2: Foundational (Blocking Prerequisites Across Modules)

**Purpose**: Core shared models, structured logging, security validators, Room database schema, DataStore settings, and DI wiring required before any user story runs end-to-end.

**⚠️ CRITICAL**: No user story verification can complete until this foundation is ready.

- [X] T006 Implement `EngineLogger` ring-buffer + rotating file logger (`logs/engine-debug.log`) and typed `EngineException` codes (`ENCRYPTED_UNSUPPORTED`, `NETWORK_ERROR`, `CHALLENGE_REQUIRED`, `SCRIPT_ERROR`, `VALIDATION_ERROR`) in `engine/src/main/kotlin/dev/trinhhg/vapp/engine/log/EngineLogger.kt` and `engine/src/main/kotlin/dev/trinhhg/vapp/engine/EngineException.kt`
- [X] T007 [P] Port and harden MIT security validators (`NetworkSecurityValidator`, `RegexSecurityValidator`, `ZipSecurityValidator`, `SecurityConstants`) from LeechText in `engine/src/main/kotlin/dev/trinhhg/vapp/engine/security/`
- [X] T008 [P] Define shared interface contracts (`BrowserEngineFactory`, `ChallengeSolver`, `ExtensionExecutor`, `DownloadQueueManager`, `BookExporter`, `TtsEngine`, `AudiobookController`) per `contracts/module-interfaces.md` in `engine/src/main/kotlin/dev/trinhhg/vapp/engine/api/` and `tts-player/src/main/kotlin/dev/trinhhg/vapp/tts/api/`
- [X] T009 [P] Define Room entities (`RepositorySourceEntity`, `ExtensionPackageEntity`, `BookEntity`, `ChapterEntity`, `DownloadTaskEntity`, `BookmarkEntity`, `CleanupRuleEntity`) enforcing all constrained fields (`data-model.md`: `RepositorySource.id`, `RepositorySource.url`, `RepositorySource.enabled`, `ExtensionPackage.id`, `ExtensionPackage.name`, `ExtensionPackage.version`, `ExtensionPackage.urlRegex`, `ExtensionPackage.type`, `ExtensionPackage.enabled`, `Book.id`, `Book.sourceUrl`, `Book.extensionId`, `Book.title`, `Book.inLibrary`, `Book.downloadedChapters`, `Book.lastReadChapterIndex`, `Book.lastReadScrollOffset`, `Book.lastReadPercent`, `Chapter.id`, `Chapter.bookId`, `Chapter.orderIndex`, `Chapter.sourceUrl`, `Chapter.downloadStatus`, `Chapter.charCount`, `Chapter.isImageChapter`, `DownloadTask.id`, `DownloadTask.bookId`, `DownloadTask.selectionSpec`, `DownloadTask.status`, `DownloadTask.completedCount`, `DownloadTask.totalCount`, `DownloadTask.failedCount`, `Bookmark.id`, `Bookmark.bookId`, `Bookmark.chapterIndex`, `Bookmark.scrollOffset`, `Bookmark.snippet`, `CleanupRule.id`, `CleanupRule.pattern`, `CleanupRule.isRegex`, `CleanupRule.enabled`, `CleanupRule.sortOrder`) in `downloader/src/main/kotlin/dev/trinhhg/vapp/downloader/db/Entities.kt`
- [X] T010 Create Room `VappDatabase` (WAL mode) and DAOs in `downloader/src/main/kotlin/dev/trinhhg/vapp/downloader/db/VappDatabase.kt` and `downloader/src/main/kotlin/dev/trinhhg/vapp/downloader/db/Daos.kt` (depends on T009)
- [X] T011 [P] Implement `AppSettings` DataStore repository enforcing constrained ranges (`data-model.md`: `AppSettings.fontKey`, `AppSettings.fontSizeSp`, `AppSettings.lineHeightMultiplier`, `AppSettings.paragraphIndentEm`, `AppSettings.textAlign`, `AppSettings.marginHorizontalDp`, `AppSettings.themePresetId`, `AppSettings.brightness`, `AppSettings.readMode`, `AppSettings.autoScrollSpeed`, `AppSettings.ttsSpeechRate`, `AppSettings.ttsPitch`, `AppSettings.ttsSleepTimerMinutes`, `AppSettings.ttsAmbienceEnabled`, `AppSettings.ttsAmbienceTrack`, `AppSettings.ttsAmbienceVolume`, `AppSettings.maxConcurrency`, `AppSettings.requestDelayMs`, `AppSettings.requestTimeoutMs`, `AppSettings.maxRetriesPerChapter`, `AppSettings.circuitBreakerThreshold`, `AppSettings.defaultExportFormat`, `AppSettings.includeCover`, `AppSettings.includeImages`, `AppSettings.autoSplitVolumes`, `AppSettings.zipCompressionLevel`, `AppSettings.uiLanguage`) in `app/src/main/kotlin/dev/trinhhg/vapp/settings/AppSettingsRepository.kt`
- [X] T012 [P] Bundle MIT/OFL Vietnamese-complete reader fonts (`literata_400.ttf`, `noticia_text_400.ttf`, `hanken_grotesk_400.ttf`, `roboto_400.ttf`, `jetbrains_mono_400.ttf`) and localize string resources (`vi`/`en`) in `app/src/main/res/` and `app/src/main/assets/fonts/`
- [X] T013 Wire Koin DI modules and `VappApplication` entry point across `:engine`, `:downloader`, `:epub-exporter`, `:tts-player`, and `:app` in `app/src/main/kotlin/dev/trinhhg/vapp/VappApplication.kt` and `app/src/main/kotlin/dev/trinhhg/vapp/di/AppModule.kt` (depends on T008, T010, T011)

**Checkpoint**: Foundation compiles across all 5 modules; Room schema, security validators, logger, and settings store are ready.

---

## Phase 3: User Story 1 — VBook Extension Engine & E2E Discovery (Priority: P1) 🎯 MVP Gate

**Goal**: Execute real non-encrypted VBook extensions (`search`, `home`, `explore`, `genre`, `detail`, `page`, `toc`, `chap`) end-to-end on JVM & Android (including GBK/non-UTF-8 sites and Android WebView-rendered / Cloudflare sites), while rejecting `"encrypt": true` extensions cleanly.

**Independent Test**: Run `./gradlew :engine:test` plus live Android P1 smoke harness against 3–5 real unencrypted extensions (`truyenfull`, `tangthuvien`, `truyenyy`/`bachngocsach`, `twkan`/`169shu`, `truyendocviet`) and 1 encrypted extension (`metruyenchu`).

### Tests for User Story 1

- [X] T014 [P] [US1] Write Rhino sandbox, `JsHttpResponse` (`.ok`, `.status`, `.text(charset)`, `.html(charset)`), and GBK/UTF-8 charset unit tests in `engine/src/test/kotlin/dev/trinhhg/vapp/engine/js/VBookJsRuntimeContractTest.kt`
- [X] T015 [P] [US1] Write fixture-based contract tests for `PluginZipExtractor` (valid zip, Zip-Slip, `"encrypt": true` rejection) and real VBook extension script suites (`truyenfull`, `tangthuvien`, `truyenyy`, `isach`, `169shu`) in `engine/src/test/kotlin/dev/trinhhg/vapp/engine/vbook/VBookExtensionCompatTest.kt`

### Implementation for User Story 1

- [X] T016 [P] [US1] Port `RhinoContextPool` (`MAX_PARALLELISM = 5`), `JsSandbox`, and `JsValueBridge` in `engine/src/main/kotlin/dev/trinhhg/vapp/engine/js/RhinoSandbox.kt`
- [X] T017 [P] [US1] Implement `CharsetDecoder` (explicit arg -> HTTP `Content-Type` -> HTML `<meta charset>` -> UTF-8) and `CookiePersistence` interface + atomic Okio JSON `CookiesStore` (`cookies/cookies.json`, zero Room dependency in `:engine`) in `engine/src/main/kotlin/dev/trinhhg/vapp/engine/net/CharsetDecoder.kt` and `engine/src/main/kotlin/dev/trinhhg/vapp/engine/net/CookiesStore.kt`
- [X] T018 [US1] Implement `JsHttpResponse`, upgraded `Http` / `fetch(url, options)` binding, `UserAgent` (`system`, `chrome`, `android`, `ios`), `Response.success/error`, `Blob`, `cacheStorage`, `localCookie`, `localStorage`, and `localConfig` per `contracts/vbook-js-runtime.md` in `engine/src/main/kotlin/dev/trinhhg/vapp/engine/js/api/` (depends on T016, T017)
- [X] T019 [P] [US1] Upgrade `Html`, `JSElement` (including `.tagName(newTag)` setter), and `JSElements` over Ksoup in `engine/src/main/kotlin/dev/trinhhg/vapp/engine/js/api/HtmlApi.kt`
- [X] T020 [US1] Implement `JsApiSetup` (`load()` pre-substitution/in-memory `src/*.js` + built-in MIT `crypto.js` shim, `BASE_URL` skip when `config.js` exists) and `Engine.newBrowser()` JS-to-`BrowserSession` bridge in `engine/src/main/kotlin/dev/trinhhg/vapp/engine/js/loader/JsApiSetup.kt` (depends on T018, T019)
- [X] T021 [P] [US1] Port and adapt `VBookRepositoryClient` (`repository.json` + `plugin.json` parsing & cache) and `PluginZipExtractor` (reject `"encrypt": true` per `data-model.md` `ExtensionPackage.scriptsDir`, `ExtensionPackage.scriptMapJson`, `ExtensionPackage.configSpecJson`, `ExtensionPackage.userConfigJson`) in `engine/src/main/kotlin/dev/trinhhg/vapp/engine/vbook/` (depends on T007)
- [X] T022 [US1] Implement `ExtensionManager` (install from URL/local ZIP, update, enable/disable, uninstall, `findAndInstallByUrl` regex match) and `ExtensionExecutorImpl` (`BrowseLoader`, `DetailLoader`, `PageLoader`, `ListLoader`, `TextLoader`, `BookFetch.mergeFetchedChapters`) in `engine/src/main/kotlin/dev/trinhhg/vapp/engine/fetch/ExtensionExecutorImpl.kt` (depends on T020, T021)
- [X] T023 [US1] Implement Android `AndroidWebViewBrowserFactory` (offscreen/headless `WebView` implementing `launch`, `launchAsync`, `loadHtml`, `waitUrl`, `block`, `urls`, `callJs` + `_callNativeFunction`/`putVariable`, `setUserAgent`, `html`, `close`) and `ChallengeSolverActivity` (supporting both auto-challenge resolution and `EXTRA_MANUAL_LOGIN` mode for site login) with bidirectional `CookieManager` <-> `CookiePersistence` sync in `app/src/main/kotlin/dev/trinhhg/vapp/webview/` (depends on T008, T017)
- [X] T024 [US1] Build minimal P1 smoke-test activity/screen (paste URL / pick repo / run `detail` + `toc` + `chap` + view `EngineLogger` diagnostics) in `app/src/main/kotlin/dev/trinhhg/vapp/ui/smoke/P1SmokeScreen.kt` (depends on T022, T023)

**Checkpoint (STRICT GATE P1)**: Verify `./gradlew :engine:test` + live Android E2E on real VBook extensions before proceeding to heavy UI work.

---

## Phase 4: User Story 2 — Resilient Batch & Background Downloading (Priority: P2)

**Goal**: Download selected chapters, ranges, full books, or multiple URLs in the background via Android ForegroundService + WorkManager, surviving network drops, app force-stop, and reboots with zero lost progress.

**Independent Test**: Run `./gradlew :downloader:testDebugUnitTest` and verify 1,000-chapter Android download survives Airplane Mode + force-stop and resumes only missing chapters.

### Tests for User Story 2

- [X] T025 [P] [US2] Write DAO, `BookFetch` ongoing-merge, `ChapterFileStore` atomic write, resume skip, backoff retry, and 8-error circuit breaker tests in `downloader/src/test/kotlin/dev/trinhhg/vapp/downloader/DownloadQueueEngineTest.kt`

### Implementation for User Story 2

- [X] T026 [P] [US2] Implement `ChapterFileStore` and `CoverStore` (atomic `.tmp` write/rename, non-empty verification, `data-model.md` `Chapter.contentPath`) in `downloader/src/main/kotlin/dev/trinhhg/vapp/downloader/storage/ChapterFileStore.kt`
- [X] T027 [US2] Implement `BookLibraryRepository` (save/update `Book` and `Chapter` rows, `mergeFetchedChapters`, `Book.downloadedChapters` transactional counter updates) in `downloader/src/main/kotlin/dev/trinhhg/vapp/downloader/BookLibraryRepository.kt` (depends on T010, T022)
- [X] T028 [US2] Implement `ChapterDownloadRunner` (coroutines + `Semaphore(maxConcurrency)`, `requestDelayMs`, `maxRetriesPerChapter` exponential backoff, `circuitBreakerThreshold` 8 consecutive errors, updating `Chapter.downloadStatus` and `Chapter.errorMessage` per `data-model.md`) in `downloader/src/main/kotlin/dev/trinhhg/vapp/downloader/queue/ChapterDownloadRunner.kt` (depends on T026, T027)
- [X] T029 [US2] Implement `DownloadWorker` (WorkManager) and `DownloadForegroundService` (`FOREGROUND_SERVICE_TYPE_DATA_SYNC`, live progress notification with Pause/Cancel actions) in `downloader/src/main/kotlin/dev/trinhhg/vapp/downloader/worker/` and declare service/permissions in `downloader/src/main/AndroidManifest.xml` (depends on T028)
- [X] T030 [US2] Implement `DownloadQueueManagerImpl` (`enqueueBook` with `ALL`/`FAILED_ONLY`/range parsing, `pauseTask`, `resumeTask`, `cancelTask`, `retryFailedChapters`, `recoverInterruptedTasksOnStartup` resetting stale `RUNNING` -> `PENDING`, tracking `data-model.md` `DownloadTask.completedCount`, `DownloadTask.totalCount`, `DownloadTask.failedCount`, `DownloadTask.workRequestId`) in `downloader/src/main/kotlin/dev/trinhhg/vapp/downloader/queue/DownloadQueueManagerImpl.kt` (depends on T029)

**Checkpoint**: User Story 2 is independently verifiable via `:downloader:testDebugUnitTest` and Android background kill/resume test.

---

## Phase 5: User Story 3 — Standalone EPUB 2/3 & TXT Export (Priority: P3)

**Goal**: Generate valid, standalone **EPUB 2.0**, **EPUB 3.0**, and **TXT** files directly on Android (with custom chapter range, cover normalization, HTML cleanup, volume splitting) and save/share them cleanly.

**Independent Test**: Run `./gradlew :epub-exporter:test` and open exported Vietnamese EPUB 2 & EPUB 3 files in 2 external e-readers.

### Tests for User Story 3

- [X] T031 [P] [US3] Write EPUB 2.0 (`OPF 2.0` + `toc.ncx`), EPUB 3.0 (`OPF 3.0` + `nav.xhtml`), uncompressed `mimetype` ZIP order, UTF-8 Vietnamese, custom chapter range, HTML sanitization, and failure cleanup tests in `epub-exporter/src/test/kotlin/dev/trinhhg/vapp/exporter/EpubExporterTest.kt`

### Implementation for User Story 3

- [X] T032 [P] [US3] Implement `CleanupRuleApplier`, `ChapterSanitizer` (valid XHTML 1.1 / HTML5 output via Ksoup), and `VolumeSplitter` in `epub-exporter/src/main/kotlin/dev/trinhhg/vapp/exporter/html/`
- [X] T033 [P] [US3] Implement `Opf2Writer` + `NcxWriter` (EPUB 2.0) and `Opf3Writer` + `NavXhtmlWriter` (EPUB 3.0 with `dcterms:modified`, `properties="cover-image"`, landmarks) in `epub-exporter/src/main/kotlin/dev/trinhhg/vapp/exporter/epub/`
- [X] T034 [P] [US3] Implement `PlainTextExporter` and `EpubPackageWriter` (`mimetype` STORED as first ZIP entry, configurable compression `AppSettings.zipCompressionLevel`, atomic `.tmp` write + cleanup on failure) in `epub-exporter/src/main/kotlin/dev/trinhhg/vapp/exporter/`
- [X] T035 [US3] Define `CoverNormalizer` interface and implement `BookExporterImpl` orchestrating chapter range filtering, cover normalization hook, and `ExportResult` in `epub-exporter/src/main/kotlin/dev/trinhhg/vapp/exporter/BookExporterImpl.kt` (depends on T032, T033, T034)
- [X] T036 [US3] Implement `AndroidCoverNormalizer` (`BitmapFactory` WebP/PNG -> JPEG, wired via Koin) and `SafExportSaver` (`ACTION_CREATE_DOCUMENT` / `MediaStore.Downloads` + `FileProvider` share sheet) in `app/src/main/kotlin/dev/trinhhg/vapp/export/AndroidCoverNormalizer.kt`, `app/src/main/kotlin/dev/trinhhg/vapp/export/SafExportSaver.kt`, and `app/src/main/res/xml/file_paths.xml` (depends on T035)

**Checkpoint**: User Story 3 exports valid EPUB 2/3 & TXT files independently on-device without Premium locks or external tools.

---

## Phase 6: User Story 4 — Distraction-Free Reader & Offline TTS Audiobook (Priority: P4)

**Goal**: Deliver the full reading canvas (scroll/slide/curl, themes, custom fonts, exact position restore, bookmarks, inline trash rules & editing) plus offline Android System TTS Audiobook playback with lock-screen controls, sleep timer, and optional background ambience.

**Independent Test**: Verify reading position & bookmarks survive restart; play TTS continuously across 3 chapters with screen locked.

### Tests for User Story 4

- [X] T037 [P] [US4] Write reader paragraph parser, cleanup-rule preview, sleep timer, and chapter-advancement TTS queue state tests in `tts-player/src/test/kotlin/dev/trinhhg/vapp/tts/AudiobookControllerTest.kt`

### Implementation for User Story 4

- [X] T038 [P] [US4] Implement `AndroidSystemTtsEngine` (`TextToSpeech` voice listing, utterance progress callbacks, speed/pitch) and `SleepTimer` + optional local `AmbienceAudioMixer` in `tts-player/src/main/kotlin/dev/trinhhg/vapp/tts/engine/` and `tts-player/src/main/kotlin/dev/trinhhg/vapp/tts/audio/`
- [X] T039 [US4] Implement `AudiobookMediaService` (Media3 `MediaSessionService` with `FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK`, lock-screen notification controls) and `AudiobookControllerImpl` (auto-next chapter, active paragraph tracking) in `tts-player/src/main/kotlin/dev/trinhhg/vapp/tts/` and `tts-player/src/main/AndroidManifest.xml` (depends on T038)
- [X] T040 [US4] Implement `ReaderViewModel` (chapter loading, `Book.lastReadChapterIndex`/`lastReadScrollOffset`/`lastReadPercent` auto-save, `Bookmark` CRUD, `CleanupRule` CRUD, inline chapter edit persistence) in `app/src/main/kotlin/dev/trinhhg/vapp/ui/reader/ReaderViewModel.kt` (depends on T027, T011)
- [X] T041 [US4] Implement `ReaderScreen` supporting `VERTICAL_SCROLL`, `PAGED_SLIDE`, and `PAGED_CURL` modes, tap zones, auto-scroll, inline chapter edit mode (`ReaderEditBody` saving edited text via `ReaderViewModel`), user `.ttf`/`.otf` font loader, and `ReaderBottomSheet` matching reference screenshot #3 (theme toggle, brightness, circular color presets + custom picker, `A-`/`A+`/font selector, indent, 4-way alignment, reset) in `app/src/main/kotlin/dev/trinhhg/vapp/ui/reader/` (depends on T040, T012)
- [X] T042 [US4] Implement `AudiobookPlayerScreen` matching reference screenshot #1 (cover, scrubber, mode badge, transport controls, sleep timer, `1.30x` speed pill, background ambience sheet, TOC, TTS voice settings sheet) in `app/src/main/kotlin/dev/trinhhg/vapp/ui/audiobook/` (depends on T039, T040)

**Checkpoint**: Reader + offline Android TTS Audiobook work end-to-end with lock-screen media controls and exact progress memory.

---

## Phase 7: User Story 5 — Full Mobile UI, Library, Backup/Restore & Settings (Priority: P5)

**Goal**: Deliver complete VBook + 4-screenshot mobile UI (Bookshelf, Explore/Sources, Book Detail, Download Queue, Extensions/Repos/Debug Logs, Settings, Cookie Manager) and validated portable local Backup/Restore (`vapp-backup.zip`).

**⚠️ GATE CHECK**: Verify P1 real-extension E2E gate passed before final UI integration.

### Tests for User Story 5

- [X] T043 [P] [US5] Write `BackupExporter` & `ValidatedBackupImporter` round-trip and corrupt/Zip-Slip rejection tests per `contracts/backup-format.md` in `app/src/test/kotlin/dev/trinhhg/vapp/backup/BackupRoundTripTest.kt`

### Implementation for User Story 5

- [X] T044 [P] [US5] Implement `BackupExporter` and `ValidatedBackupImporter` (SAF archive read/write, `ZipSecurityValidator` + manifest validation in staging dir before atomic Room/file restore) in `app/src/main/kotlin/dev/trinhhg/vapp/backup/` (depends on T010, T011, T026)
- [X] T045 [P] [US5] Build main navigation shell (`VappNavHost`, floating frosted capsule bottom bar with 4 tabs + standalone `+` FAB matching reference screenshot #4, no Premium/Community/Server entries) in `app/src/main/kotlin/dev/trinhhg/vapp/ui/navigation/`
- [X] T046 [P] [US5] Implement `BookshelfScreen` matching reference screenshot #4 (search/filter chips, `"Xem gần đây"` horizontal carousel, 3-column cover grid with reading `%` and chapter count badges, sort modes, batch update check) in `app/src/main/kotlin/dev/trinhhg/vapp/ui/bookshelf/` (depends on T027)
- [X] T047 [P] [US5] Implement `ExploreSourcesScreen`, `SourceCatalogScreen`, `GlobalSearchScreen`, and `AddUrlSheet` (auto-discovery by pasted URL, multi-URL batch enqueue) in `app/src/main/kotlin/dev/trinhhg/vapp/ui/explore/` (depends on T022)
- [X] T048 [US5] Implement `BookDetailScreen`, `ChapterPickerSheet` (range/checkbox/search), and `ExportSheet` (EPUB 2 / EPUB 3 / TXT selector, range `from-to`, cover/images/split toggles, compression slider) in `app/src/main/kotlin/dev/trinhhg/vapp/ui/detail/` (depends on T027, T030, T036)
- [X] T049 [P] [US5] Implement `DownloadQueueScreen` (active/paused/failed/completed tasks, pause/resume/cancel, one-tap retry failed chapters) in `app/src/main/kotlin/dev/trinhhg/vapp/ui/downloads/` (depends on T030)
- [X] T050 [P] [US5] Implement `ExtensionManagerScreen`, `RepoManagerScreen`, `ExtensionConfigSheet`, and `DebugLogScreen` (view/copy/clear `EngineLogger` output, import local `.zip`/`.plugin`) in `app/src/main/kotlin/dev/trinhhg/vapp/ui/extensions/` (depends on T022, T006)
- [X] T051 [P] [US5] Implement `SettingsScreen`, `CookieManagerScreen` (per-host inspect/clear + launch `ChallengeSolverActivity` in `EXTRA_MANUAL_LOGIN` mode), and `BackupRestoreScreen` in `app/src/main/kotlin/dev/trinhhg/vapp/ui/settings/` (depends on T011, T017, T023, T044)
- [X] T052 [P] [US5] *(Optional P5+ Slot)* Define `VoiceModelManagerScreen` stub/entry point wired only to open-source `TtsEngine` plugin registry when P5+ neural voice cloning is scheduled (keep hidden/disabled until P5+ implementation) in `app/src/main/kotlin/dev/trinhhg/vapp/ui/audiobook/VoiceModelManagerPlaceholder.kt`

**Checkpoint**: Complete mobile app flows match the 4 reference screenshots and VBook ergonomics; local Backup/Restore round-trips 100% of user data.

---

## Phase 8 (P6): Polish, QA, Performance & CI/Release Readiness

**Purpose**: End-to-end verification across all milestones (`quickstart.md`), large-book performance tuning, R8 shrink verification, and CI build validation.

- [X] T053 Verify large-novel performance targets (2,000–3,000+ chapters: TOC load `< 300ms`, smooth 60fps virtualized scroll, Room query indexing, chapter lookup, and bounded EPUB export memory footprint) and optimize any bottlenecks
- [X] T054 [P] Verify R8/ProGuard release rules (`Rhino` reflection/JS bridge classes, `Ksoup`, `Room`, `OkHttp`, `Media3`) via `./gradlew :app:assembleRelease` in `app/proguard-rules.pro`
- [X] T055 [P] Run full static analysis & test suite (`./gradlew spotlessCheck detekt :engine:test :epub-exporter:test :downloader:testDebugUnitTest :tts-player:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug`)
- [ ] T056 Execute end-to-end validation checklist in `specs/001-vapp-core-reader/quickstart.md` (P1 live extensions, P2 kill/resume, P3 external EPUB 2/3 readers, P4 lock-screen TTS, P5 backup/restore) and wait for Admin approval before committing/pushing to `https://github.com/trinhhg/vapp`

---

## Dependencies & Execution Order

### Phase Dependencies
- **Setup (Phase 1)** -> blocks **Foundational (Phase 2)**
- **Foundational (Phase 2)** -> blocks all User Stories (`US1`–`US5`)
- **US1 (P1 Engine E2E)** -> **MANDATORY GATE** before **US2 (P2 Downloader)**, **US5 (Full UI)**, and live integration testing
- **US2 (P2 Downloader)** -> provides persisted books/chapters consumed by **US3 (P3 Exporter)**, **US4 (P4 Reader/TTS)**, and **US5 (Full UI)**
- **US3 (P3 Exporter)** & **US4 (P4 Reader/TTS)** -> can develop in parallel once P1 + Room book/chapter schema (T009–T010, T027) exist
- **US5 (P5 UI & Backup)** -> wires US1–US4 into the complete 4-screenshot UI; strictly gated behind P1 E2E pass
- **Phase 8 (P6 QA & Release)** -> runs after US1–US5 pass their independent gates

### Parallel Opportunities Inside Each Phase
- **Phase 1**: `T002`, `T003`, `T004`, `T005` run in parallel after `T001`.
- **Phase 2**: `T007`, `T008`, `T009`, `T011`, `T012` run in parallel after `T006`.
- **Phase 3 (US1)**: `T014` & `T015` (tests) in parallel; `T016`, `T017`, `T019`, `T021` in parallel before `T020`/`T022`.
- **Phase 5 (US3)**: `T032`, `T033`, `T034` run in parallel before `T035`.
- **Phase 7 (US5)**: `T044`, `T045`, `T046`, `T047`, `T049`, `T050`, `T051` run in parallel after P1 gate & US2–US4 controllers.

---

## Parallel Example: User Story 1 (P1 Engine)

```text
Parallel batch 1 (contracts & foundational engine pieces):
- T014 [US1] VBookJsRuntimeContractTest.kt
- T015 [US1] VBookExtensionCompatTest.kt
- T016 [US1] RhinoSandbox.kt
- T017 [US1] CharsetDecoder.kt & CookiesStore.kt
- T019 [US1] HtmlApi.kt
- T021 [US1] VBookRepositoryClient & PluginZipExtractor
```

---

## Implementation Strategy

1. **MVP Gate First (Setup + Foundational + US1 / P1)**: Stand up the Gradle project, CI workflow, security validators, and `:engine` + minimal Android `WebView` smoke harness. Do **not** build final Compose UI until real VBook extensions execute cleanly end-to-end in P1.
2. **Core Reliability (US2 / P2 + US3 / P3)**: Lock in durable Room + WorkManager downloads and standalone EPUB 2/3 export verified on external readers.
3. **Reading & Listening (US4 / P4)**: Deliver the custom reader + offline Android System TTS Audiobook player.
4. **Visual Parity & Portability (US5 / P5 + P6)**: Build the full 4-screenshot Compose UI, Backup/Restore, and validate CI Debug/Release APK builds prior to Admin-approved GitHub push.
