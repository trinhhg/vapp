# Quickstart & Milestone Validation Guide: `vapp`

**Date**: 2026-11-24 | **Feature**: `001-vapp-core-reader`

This guide defines the concrete verification gates for each milestone (`P1` -> `P6`). No milestone may proceed past its gate without passing these checks.

## P1 Gate — VBook Extension Engine E2E (JVM + Android WebView)

1. **Automated Engine Tests (JVM)**:
   ```bash
   ./gradlew :engine:test
   ```
   - Verifies Rhino sandbox security (blocks Java reflection/IO), Zip-Slip & ReDoS validators, `"encrypt": true` rejection (`EncryptedExtensionException`), `JsHttpResponse` (`.ok`, `.status`, `.text(charset)`, `.html(charset)`), GBK/UTF-8 charset decoding, and fixture parsing for real VBook extensions (`truyenfull`, `bachngocsach`, `tangthuvien`, `truyenyy`, `isach`, `twkan`, `169shu`).
2. **Real Live Extension E2E Smoke (CLI/Test harness)**:
   - Install 3–5 unencrypted extensions from `Darkrai9x/vbook-extensions` / `dat-bi/ext-vbook` and run live `search` -> `detail` -> `toc` -> `chap` on real URLs, confirming non-empty Vietnamese/Chinese UTF-8 output and structured logs.
   - Run on Android device/emulator for at least 1 `Engine.newBrowser()` source (`truyendocviet` or `169shu`) and 1 Cloudflare-protected source to confirm `WebViewBrowserBridge` + cookie sync works end-to-end.

## P2 Gate — Resilient Download Queue & Storage

1. **Automated Downloader Tests**:
   ```bash
   ./gradlew :downloader:testDebugUnitTest
   ```
   - Verifies `DownloadTask` persistence, resume skipping completed chapters, retry backoff, circuit breaker trip at 8 consecutive errors, and `mergeFetchedChapters` on ongoing books.
2. **Android Stress & Interruption Verification**:
   - Queue a 1,000-chapter book on Android; toggle Airplane Mode mid-download and force-stop the app; relaunch and verify queued task recovers automatically, fetches only missing chapters (`0` duplicate fetches), and completes with `ForegroundService` progress notification.

## P3 Gate — Standalone EPUB 2/3 & TXT Export

1. **Automated Exporter Tests**:
   ```bash
   ./gradlew :epub-exporter:test
   ```
   - Verifies valid ZIP structure (`mimetype` stored uncompressed first), OPF 2.0 + `toc.ncx` (EPUB 2), OPF 3.0 + `nav.xhtml` (EPUB 3), custom chapter range (`10-50`), UTF-8 Vietnamese preservation, HTML cleanup rules, and cover normalization.
2. **External Reader Validation**:
   - Export real downloaded books in both **EPUB 2** and **EPUB 3** formats via Android SAF/Downloads and open them in at least 2 independent readers (e.g., ReadEra / Moon+ Reader / Calibre E-book Viewer) to verify cover, TOC jump, and clean formatting.

## P4 Gate — Reader & Offline Android TTS Audiobook

1. **Reader Persistence & Controls**:
   - Verify theme presets, custom colors, font switching (`Literata` + custom `.ttf`), indent/alignment, paged slide/curl vs vertical scroll, bookmarking, and exact position restoration across app restart.
2. **TTS Lock-Screen Playback**:
   - Start Audiobook mode on Android; lock screen; verify media notification controls (`Play/Pause/Next/Prev`), speed adjustment, sleep timer, and seamless transition across 3 consecutive chapters.

## P5 Gate — UI Polish, Backup/Restore & Settings

1. **UI Parity Check against Reference Screenshots**:
   - Verify Bookshelf ("Xem gần đây" carousel + 3-column grid with `%` and chapter count badges + floating capsule nav + `+` FAB), Reader bottom sheet, and TTS player match approved layouts with zero Premium/Community/Server clutter.
2. **Backup & Restore Round-Trip**:
   - Export `vapp-backup.zip`, clear app storage, restore archive, and verify 100% restoration of books, chapters, reading progress, bookmarks, repositories, unencrypted extensions, cookies, and settings.

## P6 Gate — CI/CD & Final Release Verification

1. **CI Pipeline Verification**:
   - Verify `.github/workflows/android-ci.yml` executes `spotlessCheck`, `detekt`, unit tests across all modules, and `:app:assembleDebug` cleanly.
2. **Push Gate (Constitution Principle V)**:
   - Obtain explicit Admin approval before pushing code/tags to `https://github.com/trinhhg/vapp`.
