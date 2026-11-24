# Feature Specification: vapp — Standalone Android Novel Reader, Downloader & EPUB 2/3 Exporter

**Feature Branch**: `001-vapp-core-reader`

**Created**: 2026-11-24

**Status**: Draft

**Input**: User description: "vapp — ứng dụng đọc, tải truyện Android và xuất EPUB 2/3 độc lập, tương thích hệ sinh thái VBook Extension, mã nguồn mở, miễn phí, không phụ thuộc server riêng hay tài khoản Premium."

## Clarifications

### Session 2026-11-24

- Q: Which UI design direction should vapp follow for its primary screens? → A: Combine VBook APK functional flows with the visual layouts of the 4 reference screenshots (Bookshelf with recent carousel, 3-column cover grid with progress/chapter badges, floating capsule navigation bar + add FAB; Reader bottom control sheet; Audiobook TTS player layout), excluding community, Premium, and proprietary server features.
- Q: When and how should advanced neural TTS and voice cloning (like VieNeu in reference screenshot #2) be delivered? → A: Deliver offline Android system TTS with the full Audiobook player UI (cover, scrubber, speed/pitch, sleep timer, optional background ambience, lock-screen controls, auto-next chapter) in P4; defer optional open-source neural voice model management and voice cloning to post-core phase P5+.
- Q: How should the automated APK build workflow for `https://github.com/trinhhg/vapp` work? → A: On every push and manual trigger, run automated unit tests and publish a Debug test APK artifact; on version tags (`v*`), build a Release APK (signed via repository secrets if configured, or unsigned otherwise, with no signing keys committed to the repository).

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Install VBook Extensions & Discover Books End-to-End (Priority: P1)

A reader wants to add public VBook extension repositories (or import a local extension package), browse/search stories or paste a story URL, and view accurate story metadata and chapter lists on Android without needing any custom backend server or paid account.

**Why this priority**: Extension compatibility is the foundation of the entire application; without reliable real-world extension execution (including non-UTF-8 sites and interactive browser-rendered pages), downloading, reading, and exporting have no content source.

**Independent Test**: Can be fully tested by adding a public VBook repository, installing 3–5 unencrypted extensions (including standard HTML sites, non-UTF-8 Chinese/legacy sites, and browser-rendered/anti-bot sites), searching or pasting a book URL, and verifying metadata, table of contents, and single-chapter preview load with real data or explicit error diagnostics.

**Acceptance Scenarios**:

1. **Given** a valid VBook repository link or local extension package, **When** the user installs an unencrypted extension, **Then** the extension appears in the installed sources list with its name, version, author, icon, language, and configurable options.
2. **Given** an extension marked as encrypted or relying on unsupported proprietary binaries, **When** the user attempts to install or run it, **Then** the app rejects or halts execution with a clear explanation instead of crashing or returning fabricated content.
3. **Given** a pasted book URL matching an installed (or discoverable repository) extension, **When** the user opens or previews the book, **Then** the app fetches and displays the book title, author, cover, synopsis, and full paginated chapter list with accurate character encoding.
4. **Given** a site that requires JavaScript rendering or presents an anti-bot challenge, **When** an extension requests page rendering or triggers challenge fallback, **Then** the app provides an interactive in-app web session to resolve the page/cookies and continues extraction seamlessly.
5. **Given** an extension script error, network failure, or HTTP error, **When** a request fails, **Then** the user sees a clear failure reason and can open structured execution logs for that extension run.

---

### User Story 2 - Resilient Batch & Background Downloading (Priority: P2)

A reader wants to download specific chapters, ranges, or entire multi-thousand-chapter novels in the background while using other apps or locking the phone, with confidence that network drops or app restarts never lose progress.

**Why this priority**: Offline reading and EPUB export depend on reliably acquiring hundreds or thousands of chapters under mobile network instability and Android background limits.

**Independent Test**: Can be fully tested by queueing 2+ books (full book and custom chapter range), backgrounding/killing the app mid-download, simulating offline interruption, resuming, and verifying only missing/failed chapters are fetched while completed chapters remain intact.

**Acceptance Scenarios**:

1. **Given** a book with a loaded table of contents, **When** the user selects all chapters, a custom range (e.g., `1-100, 150-200`), or individual chapters and starts downloading, **Then** the download is persisted in a durable queue and runs in the background with a live progress notification.
2. **Given** an active download queue, **When** the user pauses, resumes, or cancels a book, or when the device loses network or restarts the app, **Then** completed chapters are preserved on disk, pending/paused queue items survive restarts, and resuming skips already-downloaded chapters.
3. **Given** transient network errors or rate limits on individual chapters, **When** chapters fail, **Then** the downloader retries automatically with backoff, halts the batch safely if consecutive site-wide blocking occurs, and lets the user retry only failed chapters later.
4. **Given** an ongoing novel already in the library, **When** the user checks for updates, **Then** newly added chapters are appended while preserving existing chapter records and reading progress.

---

### User Story 3 - Standalone EPUB 2/3 & TXT Export (Priority: P3)

A reader wants to export downloaded books (full or selected chapter ranges) into standard, valid EPUB 2 or EPUB 3 files (plus TXT fallback) directly on the device—for free, with no Premium gate—and open them cleanly in external e-readers.

**Why this priority**: Independent EPUB 2/3 generation is a core product differentiator (VBook gates export behind Premium, and existing open tools lack EPUB 3 and chapter-range export).

**Independent Test**: Can be fully tested by exporting a downloaded Vietnamese/UTF-8 book with cover image and custom chapter range to both EPUB 2 and EPUB 3, saving via Android storage/share, and opening the resulting files in at least two independent external EPUB readers to verify cover, TOC navigation, and clean chapter formatting.

**Acceptance Scenarios**:

1. **Given** a book with downloaded chapters, **When** the user opens Export, **Then** they can choose format (**EPUB 2**, **EPUB 3**, or **TXT**), select chapter scope (all downloaded, custom range `from–to`, or volume splitting), toggle cover/images, and start export without any account or payment requirement.
2. **Given** downloaded chapters containing raw web HTML, ads, or broken tags, **When** generating an EPUB, **Then** the exporter sanitizes invalid markup, applies active cleanup rules, encodes all text as UTF-8, normalizes the cover image, and builds a valid table of contents (NCX for EPUB 2; HTML5 navigation document + OPF 3.0 for EPUB 3).
3. **Given** an export fails (e.g., zero downloaded chapters or storage write failure), **When** the operation aborts, **Then** no 0-byte or corrupted output file is left behind and a clear error message is shown.

---

### User Story 4 - Distraction-Free Reader & Offline TTS Audiobook (Priority: P4)

A reader wants a comfortable, customizable reading experience (fonts, backgrounds, scroll/paged modes, progress memory, bookmarks, content cleanup) plus built-in offline Text-to-Speech listening with background playback controls.

**Why this priority**: Reading and listening on-device deliver immediate daily value after content is fetched or downloaded, and TTS/audiobook mode solves hands-free reading on Android.

**Independent Test**: Can be fully tested by opening a multi-chapter book, changing typography and theme, switching between scroll and paged modes, saving a bookmark, closing and reopening to verify position restoration, and playing TTS across chapter boundaries with the screen locked.

**Acceptance Scenarios**:

1. **Given** an open chapter in the reader, **When** the user adjusts font family (including Vietnamese-friendly bundled and custom fonts), font size, line spacing, margins, theme preset (light, sepia, dark, OLED, textured), or reading mode (vertical scroll vs horizontal page turn), **Then** changes apply immediately and persist across sessions.
2. **Given** a reader navigates within and across chapters, **When** they close the reader or app and return later, **Then** the app restores the exact chapter and scroll/page position, and lists all saved bookmarks.
3. **Given** noisy web text in a chapter, **When** the user adds or toggles text cleanup rules or edits chapter text inline, **Then** the cleaned text updates immediately in the reader and is honored during future exports.
4. **Given** a chapter with readable text, **When** the user starts TTS playback, **Then** the device speaks paragraphs aloud using on-device/system voices with adjustable speed and pitch, highlights or tracks progress, supports background/lock-screen media controls and sleep timer, and automatically advances to the next chapter.

---

### User Story 5 - Library Management, Backup/Restore & Settings (Priority: P5)

A reader wants an intuitive mobile interface to organize their library, manage cookies/connection settings, and export or restore a full local backup when switching devices or reinstalling.

**Why this priority**: Ensures long-term data safety, polished daily usability, and self-contained portability without cloud accounts.

**Independent Test**: Can be fully tested by configuring settings and cookies, creating a local backup archive containing books, progress, bookmarks, repositories, and unencrypted extensions, clearing app data (or testing on a clean profile), restoring from the archive, and verifying all library items, positions, and preferences are intact.

**Acceptance Scenarios**:

1. **Given** books in the library, **When** the user browses the main tabs (Library, Explore/Sources, Downloads, Settings), **Then** they can search, filter, sort books (recently read, recently added, title), check for new chapters, and view clear download/reading badges.
2. **Given** sites requiring login cookies or custom mirror domains, **When** the user logs in via the built-in browser or edits extension settings/domain overrides, **Then** cookies and settings persist per host/extension and are used by subsequent extension requests.
3. **Given** the user triggers Backup in Settings, **When** the export completes, **Then** a portable archive containing library data, chapters, progress, bookmarks, repositories, extensions, and settings is saved to a user-chosen location and can be restored cleanly without overwriting data silently if invalid.

---

### Edge Cases

- What happens when a site serves content in non-UTF-8 encodings (e.g., GBK, GB2312, Big5, Windows-1252) without explicit UTF-8 headers? The engine MUST honor extension-declared charsets and fall back to HTTP `Content-Type` / HTML `<meta charset>` detection rather than unconditionally decoding as UTF-8.
- What happens when an extension uses VBook property-style response fields (`response.ok`, `response.status`, `response.headers`) vs method-style calls? The runtime MUST support VBook's documented properties and methods uniformly so `if (response.ok)` accurately reflects HTTP 2xx status instead of evaluating to truthy on failures.
- What happens when a book has 3,000+ chapters on Android? Storage and UI rendering MUST avoid blocking the main thread, paginate or virtualize chapter lists, and keep chapter lookup/export fast without filesystem directory-scan bottlenecks.
- What happens when exporting a book whose cover is WebP, PNG, or missing/unreachable? The exporter MUST normalize valid images to reader-compatible JPEG/PNG or cleanly export without a cover if none is available, without failing the EPUB build.
- What happens when Android battery optimization or network loss interrupts a multi-hour download? Background downloads MUST run as a declared foreground service/scheduled work item, checkpoint completed chapters immediately, and resume cleanly without duplicate downloads.
- What happens when a user attempts to restore a corrupted or incompatible backup archive? Validation MUST reject the archive before mutating existing library data and display a clear error.

## Requirements *(mandatory)*

### Functional Requirements

#### VBook Extension Engine
- **FR-001**: System MUST parse and aggregate VBook repository indexes (`repository.json` and `plugin.json`) from default, community, and user-added URLs, caching lists locally with manual refresh.
- **FR-002**: System MUST install, update, enable/disable, and uninstall unencrypted VBook extension packages (`.zip` / `.plugin`) from repositories or local storage, validating archive paths, regex safety, and manifest fields.
- **FR-003**: System MUST detect and reject extensions marked `"encrypt": true` (or containing unreadable encrypted scripts) at install/execution time with an explicit diagnostic message explaining lack of proprietary decryption support.
- **FR-004**: System MUST auto-discover and match installed or repository extensions against pasted book URLs using VBook `regexp` rules.
- **FR-005**: System MUST execute VBook extension lifecycle scripts (`home`, `explore`, `genre`, `search`, `detail`, `page`, `toc`, `chap`, `config`) inside an isolated sandbox enforcing URL/network safety and execution bounds.
- **FR-006**: System MUST provide VBook-compatible runtime bindings covering `fetch(url, options)`, `Http` builder, `Html` DOM selector/mutation, `Response.success/error`, `UserAgent` presets (including `system`, `chrome`, `android`, `ios`), `localStorage`, `cacheStorage`, `localCookie`, `localConfig`, `load()`, `sleep()`, and `Console`/`Log`.
- **FR-007**: System MUST decode HTTP responses according to explicit script charset arguments, HTTP `Content-Type` headers, or HTML `<meta>` charset declarations before falling back to UTF-8.
- **FR-008**: System MUST support browser-rendered sources (`Engine.newBrowser`) and interactive anti-bot/Cloudflare challenge resolution on Android via an in-app web view bridge that shares cookies with the HTTP client.
- **FR-009**: System MUST capture per-extension execution logs (requests, status codes, script errors, console output) viewable in an in-app debug log viewer.

#### User Interface & Navigation
- **FR-010**: System MUST follow a mobile-first navigation and visual layout combining VBook APK workflows with the 4 reference screenshots: a Bookshelf home screen featuring filter chips, a "Recently Read" horizontal carousel, a 3-column cover grid displaying reading percentage and chapter count badges, a floating capsule bottom navigation bar (Library, Explore/Sources, Downloads/Extensions, Settings) with a dedicated add (`+`) button, and NO community/social, Premium paywall, or proprietary server menus.
- **FR-011**: Users MUST be able to view book details (cover, title, author, status, synopsis, source link, chapter count), browse/search the chapter list, preview/read chapters, check for new chapters, and launch download or export actions from the detail view.
- **FR-012**: System MUST surface clear, actionable error states (empty state, network error, challenge required, unsupported encrypted extension, missing chapter) without showing placeholder or fake data.

#### Reader & Offline TTS / Audiobook
- **FR-013**: System MUST render chapter text cleanly with an in-reader bottom control sheet providing theme mode toggle, brightness slider, circular background color presets plus custom colors, font size (`A-`/`A+`) and font family selector (bundled Vietnamese-complete and custom fonts), paragraph indent/line spacing, 4-way text alignment, and one-tap reset.
- **FR-014**: Users MUST be able to switch between vertical continuous scrolling and horizontal paged reading (slide and page-turn simulation modes via tap zones and swipe), plus configurable auto-scroll speed in vertical mode.
- **FR-015**: System MUST persist exact reading position (chapter index and scroll/page offset) per book automatically and allow users to create, navigate, and delete bookmarks.
- **FR-016**: Users MUST be able to define regex/text cleanup rules (trash filters) and edit chapter text locally, with changes reflected immediately in the reader and preserved for exports.
- **FR-017**: System MUST provide an offline Text-to-Speech (TTS) Audiobook player (matching reference screenshot #1 layout) using Android system/installed TTS voices in P4 with play/pause, skip paragraph/chapter, speed/pitch controls, sleep timer, optional local background ambience audio, auto-next-chapter, lock-screen media controls, and active reading-position tracking; optional open-source neural voice model downloads and voice cloning (matching reference screenshot #2) are scoped as a post-core P5+ enhancement.

#### Download Engine
- **FR-018**: Users MUST be able to queue downloads for a single chapter, custom selected chapters/ranges, an entire book, or multiple book URLs simultaneously.
- **FR-019**: System MUST persist download queue state and per-chapter completion status on disk so interrupted downloads survive network loss, app backgrounding, force-close, and device reboot.
- **FR-020**: System MUST execute downloads via an Android background foreground service displaying live progress and pause/cancel notification actions.
- **FR-021**: System MUST support pausing, resuming, and cancelling individual queue items or the entire queue, skipping already-downloaded valid chapters on resume.
- **FR-022**: System MUST retry transient chapter failures with configurable backoff/retry limits, apply a consecutive-error circuit breaker when a source blocks requests, and provide one-tap retry for failed chapters only.

#### EPUB 2/3 & Text Export Engine
- **FR-023**: System MUST generate valid, standalone **EPUB 2** (OPF 2.0 + `toc.ncx`) and **EPUB 3** (OPF 3.0 + HTML5 `nav.xhtml` + backward-compatible NCX option) packages on-device without external servers, tools, or Premium locks, as well as plain **TXT** export.
- **FR-024**: Users MUST be able to choose export chapter scope (all downloaded chapters, custom chapter range `start–end`, or automatic multi-volume splitting), toggle image/cover inclusion, and adjust compression level.
- **FR-025**: System MUST sanitize chapter HTML into well-formed XHTML/HTML5, apply active cleanup rules, normalize cover images to standard JPEG/PNG, preserve full UTF-8 Unicode (including Vietnamese diacritics), and write output atomically before saving to a user-selected Android folder or sharing via system sheet.

#### Storage, Backup/Restore & Settings
- **FR-026**: System MUST store books, chapter content, covers, reading state, bookmarks, extensions, repositories, cookies, and settings locally in a structured, scalable layout that remains responsive for large libraries (2,000–3,000+ chapters per book: TOC opens in under 300ms with smooth scrolling and fast per-chapter lookup).
- **FR-027**: Users MUST be able to inspect, clear, or import/export per-host cookies (including opening an interactive built-in WebView login mode to capture session cookies) and configure connection parameters (max concurrent downloads, request delay, timeout, user-agent, per-extension settings/domain overrides).
- **FR-028**: Users MUST be able to export a complete local backup archive (library metadata, downloaded chapters, progress, bookmarks, unencrypted extensions, repositories, settings) and restore it on the same or another Android device with validation before applying changes.

#### Automated Build & CI Packaging
- **FR-029**: Repository MUST include an automated CI workflow for `https://github.com/trinhhg/vapp` that runs unit tests and produces a downloadable Debug test APK artifact on every push and manual trigger, and builds a Release APK on version tags (`v*`) using optional signing secrets without storing any keystore or credentials in source control.

### Key Entities

- **RepositorySource**: Represents a VBook repository endpoint (`link`, `author`, `description`, `enabled`, `lastSyncedAt`).
- **ExtensionPackage**: Represents an installed VBook extension (`id`, `name`, `author`, `version`, `sourceUrl`, `urlRegex`, `type`, `locale`, `icon`, `scripts`, `configSpec`, `userConfig`, `encryptedFlag`).
- **Book**: Represents a saved or previewed story (`id`, `sourceUrl`, `extensionId`, `title`, `author`, `coverPath`, `synopsis`, `ongoing`, `lastReadChapterIndex`, `lastReadOffset`, `addedAt`, `updatedAt`).
- **Chapter**: Represents an ordered chapter belonging to a Book (`id`, `bookId`, `index`, `partName`, `title`, `sourceUrl`, `downloadStatus`, `contentRef`, `errorMessage`).
- **DownloadTask**: Represents a persistent queue item (`id`, `bookId`, `chapterSelectionSpec`, `status`, `completedCount`, `totalCount`, `failedCount`, `createdAt`, `updatedAt`).
- **Bookmark**: Represents a saved reading position within a Book (`id`, `bookId`, `chapterIndex`, `offset`, `note`, `createdAt`).
- **CleanupRule**: Represents a user-defined text/HTML replacement rule (`id`, `pattern`, `replacement`, `isRegex`, `enabled`).
- **AppSettings**: Represents global user preferences across reader appearance, TTS playback, download concurrency/retries, default export format (EPUB 2 / EPUB 3 / TXT), UI language, and storage paths.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: At least 80% of sampled unencrypted VBook novel extensions from `Darkrai9x/vbook-extensions` and `dat-bi/ext-vbook` (including non-UTF-8 and browser-rendered sources) execute `detail`, `toc`, and `chap` flows end-to-end on Android without script runtime errors.
- **SC-002**: 100% of tested encrypted (`"encrypt": true`) extensions and failed network requests fail cleanly with explicit diagnostic messages and 0 fabricated chapters or silent crashes.
- **SC-003**: A 1,000-chapter novel download survives at least one forced app termination and one simulated network drop, resuming with 0 duplicate chapter fetches and 100% preservation of previously completed chapters.
- **SC-004**: Exported EPUB 2 and EPUB 3 files (including Vietnamese Unicode text, cover image, custom chapter range, and TOC) open and navigate chapters cleanly in at least 2 independent external Android/desktop EPUB readers with 0 encoding corruption.
- **SC-005**: Offline TTS playback runs continuously across at least 3 consecutive chapters with the device screen locked while maintaining accurate chapter/paragraph tracking.
- **SC-006**: Full local backup and restore round-trips 100% of library books, downloaded chapters, reading positions, bookmarks, repositories, unencrypted extensions, and settings with zero data loss.
- **SC-007**: Every CI run on push executes the automated test suite and produces a valid installable Debug APK artifact when tests pass, while tagged `v*` builds produce a Release APK artifact.

## Assumptions

- Target users run modern Android devices (Android 8.0 / API 26 or newer) and expect completely offline-capable local reading, downloading, TTS, and EPUB export with no mandatory login or server.
- Non-encrypted community VBook extensions remain the primary supported ecosystem; proprietary encrypted VBook extensions (`"encrypt": true` decrypted only by VBook's closed binary) are explicitly out of scope for decryption and will report an unsupported diagnostic error.
- The primary Phase 1–5 scope focuses on text-based novels (`novel` / `chinese_novel`), offline system TTS audiobook playback, and EPUB 2/3 and TXT export; optional neural TTS voice cloning (P5+) and comic/video/audio-stream extensions are deferred until core milestones pass verification.
- Visual layout tokens and primary screen ergonomics are aligned with the 4 reference screenshots provided during clarification (Bookshelf home, Reader control sheet, TTS player, and Voice/Model manager), adapted strictly to offline/open-source features.
