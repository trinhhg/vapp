# Phase 0 Technical Research & Architectural Decisions: `vapp`

**Date**: 2026-11-24 | **Feature**: `001-vapp-core-reader`

## 1. Core Engine Reuse Strategy (`haipham22/LeechText` MIT)

- **Decision**: Adopt and refactor the MIT-licensed Kotlin engine core from `haipham22/LeechText` (`RhinoContextPool`, `JsSandbox`, `SecurityConstants`, `NetworkSecurityValidator`, `RegexSecurityValidator`, `ZipSecurityValidator`, `VBookRepositoryClient`, `PluginZipExtractor`, `VBookToLeechTextConverter`, `BookFetch.mergeFetchedChapters`, `ZipIo`, `HtmlSanitizer`) into pure JVM/Kotlin modules (`:engine`, `:epub-exporter`), while replacing/upgrading incompatible or desktop-skewed layers.
- **Rationale**:
  - Proven Rhino `1.7.15` sandbox + security validators + VBook registry/zip extraction already pass extensive unit tests in LeechText.
  - Pure JVM/Kotlin modules enable fast host-side E2E verification against real VBook extensions (`./gradlew :engine:test`) before touching Android UI (Constitution Principle III).
- **Alternatives Considered**:
  - *QuickJS (`quickjs-kt` / JNI)*: Used by official vBook (`libkitejs_quickjs.so`), but requires native `.so` per ABI, increases JNI debugging complexity on Windows/CI, and lacks LeechText's existing Rhino sandbox/context-pool tests. Rhino `1.7.15` (MPL-2.0) runs pure JVM/Android ART with zero native ABI overhead.
  - *Forking `app-shared` UI directly from LeechText*: Rejected because LeechText's UI is desktop-first (1280×800), lacks persistent Room/WorkManager queue, lacks TTS/audiobook player, lacks EPUB 3, and does not match the 4 Android reference screenshots.

## 2. Closing VBook Extension Runtime Gaps on Android

- **Decision**: Upgrade LeechText's JS API layer in `:engine` to implement VBook 1.2.8's `_HttpResponse` wrapper, charset-aware decoding, `UserAgent` presets, `load()` pre-substitution, DOM helpers, and an Android `WebViewBrowserBridge` implementing `Engine.newBrowser()`.
- **Rationale** (verified directly in Phase 0 against `core.js`, `extension-api.md`, `tangthuvien`, `truyenyy`, `isach`, `169shu`, `truyendocviet`):
  1. **`fetch(url, options)` → `JsHttpResponse`**: Expose JS properties (`ok`, `status`, `statusText`, `url`, `headers`, `request`) and methods (`text(charset)`, `html(charset)`, `json()`, `base64()`, `blob()`, `header(k)`). Fixes the Rhino truthiness bug where `if (response.ok)` evaluated a Java method reference as always `true`.
  2. **Charset Decoding Pipeline**: Decode bytes in strict order: (a) explicit `charset` param passed to `.text(charset)` / `.html(charset)` / `Http.get().string(charset)`, (b) HTTP `Content-Type` header `charset=...`, (c) HTML `<meta charset>` / `<meta http-equiv="Content-Type">` sniff in first 4 KB, (d) fallback `UTF-8`.
  3. **`UserAgent` Presets**: Add `UserAgent.chrome()` and `UserAgent.system()` alongside `android()`, `iOS()`, `desktop()`, `mobile()`.
  4. **`Engine.newBrowser()` & Challenge Solver on Android**: Define a clean `BrowserEngine` interface in `:engine`, backed in `:app` by an Android `WebView` controller (headless offscreen `WebView` on main looper for normal JS-rendered pages with `launch`/`launchAsync`/`callJs`/`html`/`block`/`waitUrl`/`urls`/`setUserAgent`/`getVariable`/`close`, escalating automatically to a visible modal `WebView` activity if interactive Cloudflare Turnstile/CAPTCHA input is required). Shares cookies bidirectionally between `android.webkit.CookieManager` and OkHttp `CookieJar` / `CookiesUtils`.
  5. **`load()` & Missing JS Helpers**: Support `load('file.js')` pre-execution substitution/eval from `src/` (including `crypto.js` MIT CryptoJS shim if needed by unencrypted extensions), `cacheStorage`, `localCookie`, `JSElement.tagName(newTag)` setter, and `Response.error(msg)`.
  6. **Encrypted Extensions (`"encrypt": true`)**: Reject cleanly at install/load with explicit diagnostic error code `"encrypted"`, preserving Constitution Principles II & IV.
- **Alternatives Considered**:
  - *Playwright on Android*: Impossible (desktop browser binaries only).
  - *Regex-only scraping without WebView*: Fails on 26+ JS-rendered extensions in `ext-vbook` and Cloudflare-protected sites.

## 3. Persistent Storage & Resilient Background Download Queue

- **Decision**: Replace LeechText's in-memory `DownloadQueueState` + `START_NOT_STICKY` `DownloadService` + thousands-of-small-files `raw/<id>.txt` layout with:
  - **Room (SQLite)** for `RepositorySource`, `ExtensionPackage`, `Book`, `Chapter` metadata/index, `DownloadTask`, `Bookmark`, and `CleanupRule`.
  - **Structured Per-Book Chapter Store**: Store downloaded chapter bodies in an indexed per-book local directory or compressed SQLite/blob-backed chapter files keyed by `chapterId` with atomic temp-file writes (`writeTmpAndRename`), avoiding full directory scans of 2,000+ files for progress/resume checks (chapter status tracked in Room + verified non-empty on disk).
  - **WorkManager + ForegroundService (`FOREGROUND_SERVICE_TYPE_DATA_SYNC`)**: `DownloadTask` rows in Room survive app force-stop and device reboot (`WorkManager` enqueues/resumes pending tasks automatically on network availability). Within an active worker, chapters execute via Kotlin Coroutines + `Semaphore(maxConn)`, exponential backoff retry (e.g., 500ms, 1.5s, 4s) on transient HTTP 429/503/IO errors, and a per-book consecutive-error circuit breaker (8 errors).
- **Rationale**: Solves `SC-003` (1,000-chapter download survives app kill + network drop with zero duplicate fetches) and directly addresses the Android file-count performance risk flagged in LeechText's `TODOS.md`.
- **Alternatives Considered**:
  - *Keep LeechText `properties.json` + `raw/*.txt`*: Fragile on concurrent updates, slow directory enumeration on Android Scoped Storage/internal files for large novels, and loses queue state on process death.

## 4. Standalone EPUB 2 & EPUB 3 Export Engine

- **Decision**: Build `:epub-exporter` as a pure Kotlin/JVM module supporting **EPUB 2.0**, **EPUB 3.0**, and **TXT** independently (no Calibre, no KindleGen, no server).
- **Rationale**:
  - **EPUB 2.0**: `mimetype` (STORED/uncompressed first ZIP entry — critical EPUB container requirement missing or fragile in naive ZIP tools!), `META-INF/container.xml`, `OEBPS/content.opf` (`version="2.0"`), `OEBPS/toc.ncx`, XHTML 1.1 chapter files, CSS, normalized JPEG/PNG cover.
  - **EPUB 3.0**: `mimetype` (STORED), `META-INF/container.xml`, `OEBPS/content.opf` (`version="3.0"`, `dcterms:modified`), `OEBPS/nav.xhtml` (`epub:type="toc"` & `landmarks`), optional `toc.ncx` for hybrid backward compatibility, HTML5 (`<!DOCTYPE html>`, UTF-8) chapter files, CSS, normalized cover (`properties="cover-image"`).
  - **Scope & Cleanup**: Select `ALL_DOWNLOADED`, `CUSTOM_RANGE` (`from..to`), or `AUTO_SPLIT_VOLUMES`; run active `CleanupRule`s + Ksoup HTML-to-valid-XHTML/HTML5 sanitization before packaging; write via `.tmp` + atomic move; deliver to user-chosen Android folder via `SAF` (`ACTION_CREATE_DOCUMENT` / `MediaStore.Downloads`) or `FileProvider` share sheet.
- **Alternatives Considered**:
  - *Third-party Java EPUB library (e.g., epublib)*: `epublib` is unmaintained, has legacy XML/Java issues on Android, and lacks clean EPUB 3.0 `nav.xhtml` + custom volume splitting. Writing a focused, deterministic ZIP/OPF/NCX/NAV builder (~500 LOC) using `Zip4j`/`java.util.zip` + `Ksoup` is cleaner, 100% MIT/Apache-2.0, and fully testable.

## 5. Reader & Offline Android TTS / Audiobook Architecture

- **Decision**:
  - **Reader UI**: Jetpack Compose canvas supporting vertical continuous scroll (`LazyColumn`) and horizontal paged mode (`HorizontalPager` with slide and optional page-curl/flip transition effect), bottom control sheet matching reference screenshot #3 (theme toggle, brightness, circular color presets + custom color picker, `A-`/`A+`/font picker with bundled Vietnamese-complete `Literata`, `Noticia Text`, `Hanken Grotesk`, `Roboto`, `JetBrains Mono` + user-imported `.ttf`/`.otf`, paragraph indent/line height, 4-way alignment, reset), exact per-book position restoration, bookmarks, and inline trash-rule/chapter editors.
  - **TTS / Audiobook (P4)**: Define a `TtsEngine` abstraction in `:tts-player` backed in P4 by Android's built-in `android.speech.tts.TextToSpeech` + `Media3` (`androidx.media3:media3-session`) foreground `MediaSessionService` (`mediaPlayback` permission) for lock-screen controls, sleep timer, speed/pitch (`0.5x–2.5x`), auto-advance across chapters, paragraph highlighting, and optional local background ambience audio track mixing. Post-core P5+ can plug an open-source neural voice engine (e.g., Sherpa-ONNX / Piper / VieNeu open models if permissively licensed) into `TtsEngine` without touching UI or playback service.
- **Rationale**: Satisfies `FR-013`–`FR-017`, `SC-005`, and user's clarified choice (System TTS in P4, neural voice cloning in P5+) while keeping APK size lean (~15–25 MB instead of 100+ MB).

## 6. Dependency License Audit (Constitution Principle I Compliance)

All planned dependencies are open-source and free under permissive or approved licenses:
- **Kotlin, Coroutines, Serialization, Compose BOM, AndroidX (Room, WorkManager, WebKit, Media3, DocumentFile, Core-Splashscreen, Navigation)**: Apache 2.0
- **OkHttp 5, Okio, Koin**: Apache 2.0
- **Ksoup**: Apache 2.0
- **Zip4j**: Apache 2.0
- **Mozilla Rhino `1.7.15`**: MPL-2.0 (weak copyleft; library-level compatibility with Apache-2.0/MIT app code, already used in LeechText)
- **Coil 3 (image loading for covers/icons)**: Apache 2.0
- **JUnit 5 / MockK / Turbine / Robolectric (test-only)**: EPL-2.0 / Apache 2.0 / MIT
- **Forbidden**: Google Play Billing (`openiap`), Firebase Analytics/Messaging, proprietary `libvbcrypto.so`, closed SDKs.

## 7. CI/CD Pipeline (`https://github.com/trinhhg/vapp`)

- **Decision**: Configure `.github/workflows/android-ci.yml`:
  - Trigger on `push` (branches `main`, `master`, `001-*`), `pull_request`, and `workflow_dispatch`.
  - Gate job (`test`): JDK 17, Gradle cache, runs `./gradlew spotlessCheck detekt :engine:test :epub-exporter:test :downloader:testDebugUnitTest :app:testDebugUnitTest`.
  - Artifact job (`build-debug-apk`, `needs: test`): runs `./gradlew :app:assembleDebug`, uploads `vapp-debug-<sha>.apk` to GitHub Actions Artifacts (retention 7 days).
  - Tag Release job (`build-release-apk`, `if: startsWith(github.ref, 'refs/tags/v')`, `needs: test`): runs `./gradlew :app:assembleRelease` (signing via GitHub Secrets `KEYSTORE_BASE64`/`KEY_ALIAS` if configured, or unsigned/debug-test fallback clearly labeled), creates GitHub Release with attached APK.
- **Note**: Per Constitution Principle V, the workflow file will be created locally and verified, and nothing is pushed to `https://github.com/trinhhg/vapp` until Admin explicitly instructs.
