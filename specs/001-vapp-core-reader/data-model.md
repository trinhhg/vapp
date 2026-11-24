# Data Model: `vapp`

**Date**: 2026-11-24 | **Feature**: `001-vapp-core-reader`

## 1. Entity Overview & Persistence Mapping

| Entity | Primary Store | Purpose |
|---|---|---|
| `RepositorySource` | Room (`repositories`) | VBook registry URLs (`repository.json` / `plugin.json`) |
| `ExtensionPackage` | Room (`extensions`) + App files (`extensions/<id>/`) | Installed unencrypted VBook extension metadata, icon, scripts, user config |
| `Book` | Room (`books`) + App files (`books/<bookId>/cover.jpg`) | Library & previewed book metadata + reading progress |
| `Chapter` | Room (`chapters`) + App files (`books/<bookId>/chapters/<chapterId>.html`) | Ordered chapter index, status, and stored raw HTML content |
| `DownloadTask` | Room (`download_tasks`) | Persistent queue surviving app kill / reboot |
| `Bookmark` | Room (`bookmarks`) | Saved reader positions per book |
| `CleanupRule` | Room (`cleanup_rules`) | Regex/plain text cleanup rules applied in reader & export |
| `CookieEntry` | Okio JSON store (`cookies/cookies.json`) via `:engine` `CookiePersistence` | Per-host cookies shared across OkHttp and Android WebView without requiring Room inside `:engine` |
| `AppSettings` | DataStore Preferences (`vapp_settings.preferences_pb`) | Global reader, TTS, download, export, UI language settings |

---

## 2. Detailed Entity Schemas & Validation Rules

### 2.1 `RepositorySource`
- `id`: String (PK, SHA-256 of normalized `url`)
- `url`: String (unique, HTTPS/HTTP validated)
- `author`: String
- `description`: String
- `enabled`: Boolean (default `true`)
- `lastSyncedAt`: Long? (epoch ms)
- **Validation**: Reject non-HTTP(S) URLs except `localhost` for local testing.

### 2.2 `ExtensionPackage`
- `id`: String (PK, deterministic slug or SHA-256 of `source + ":" + name`)
- `name`: String (required, non-blank)
- `author`: String
- `version`: Int (`>= 0`)
- `sourceUrl`: String (base URL)
- `urlRegex`: String (validated via `RegexSecurityValidator` against ReDoS)
- `type`: Enum (`NOVEL`, `CHINESE_NOVEL`, `COMIC`, `OTHER`)
- `locale`: String (`vi_VN`, `zh_CN`, `en_US`, `global`, etc.)
- `description`: String
- `iconPath`: String? (local PNG path)
- `scriptsDir`: String (directory holding extracted `src/*.js`)
- `scriptMapJson`: String (JSON map of logical script key -> filename, e.g. `{"detail":"detail.js","toc":"toc.js","chap":"chap.js"}`)
- `configSpecJson`: String (JSON map of declared VBook config specs + built-in `thread_num`, `delay`, `timeout`)
- `userConfigJson`: String (JSON map of user-overridden config values, including domain mirror)
- `enabled`: Boolean (default `true`)
- `installedAt`: Long
- `updatedAt`: Long
- **Validation**: Reject if `"encrypt": true` in `plugin.json`; reject ZIP entries failing `ZipSecurityValidator`; require at least one of `chap`, `toc`, `home`, `explore`, `search`.

### 2.3 `Book`
- `id`: String (PK, UUID or deterministic SHA-256 of canonical `sourceUrl`)
- `sourceUrl`: String (unique index)
- `extensionId`: String (FK -> `ExtensionPackage.id`, nullable if extension uninstalled later)
- `title`: String (non-blank)
- `author`: String
- `coverUrl`: String?
- `coverLocalPath`: String?
- `synopsis`: String
- `detailInfo`: String
- `ongoing`: Boolean
- `inLibrary`: Boolean (distinguishes previewed/temporary search results from saved bookshelf items)
- `totalChapters`: Int
- `downloadedChapters`: Int (denormalized counter updated transactionally for fast 3-column shelf rendering)
- `lastReadChapterIndex`: Int (default `0`)
- `lastReadScrollOffset`: Int (pixel/paragraph offset or paged page index)
- `lastReadPercent`: Float (`0.0f..1.0f`, displayed on shelf badge)
- `addedAt`: Long
- `lastReadAt`: Long?
- `updatedAt`: Long

### 2.4 `Chapter`
- `id`: String (PK, `<bookId>_<stableKey>`)
- `bookId`: String (FK -> `Book.id` ON DELETE CASCADE, indexed)
- `orderIndex`: Int (0-based sequential index, indexed `(bookId, orderIndex)`)
- `partName`: String? (volume/part grouping if provided)
- `title`: String
- `sourceUrl`: String (unique per book `(bookId, sourceUrl)` to support `mergeFetchedChapters` on ongoing novels)
- `downloadStatus`: Enum (`NOT_DOWNLOADED`, `QUEUED`, `DOWNLOADING`, `COMPLETED`, `FAILED`, `EMPTY`)
- `contentPath`: String? (relative path `chapters/<index>_<hash>.html`)
- `charCount`: Int (default `0`)
- `isImageChapter`: Boolean (true if `< 1000` chars with `<img`)
- `errorMessage`: String? (explicit error recorded when `FAILED` / `EMPTY`)
- `updatedAt`: Long
- **State Transitions**:
  - `NOT_DOWNLOADED` -> `QUEUED` -> `DOWNLOADING` -> (`COMPLETED` | `FAILED` | `EMPTY`)
  - On resume: `COMPLETED` with non-empty file on disk is **skipped**; `FAILED` / `EMPTY` / `QUEUED` / `NOT_DOWNLOADED` are eligible for download or retry.

### 2.5 `DownloadTask`
- `id`: String (PK)
- `bookId`: String (FK -> `Book.id` ON DELETE CASCADE)
- `selectionSpec`: String (`"ALL"`, `"FAILED_ONLY"`, or range/list e.g. `"1-100,105"`)
- `status`: Enum (`PENDING`, `RUNNING`, `PAUSED`, `COMPLETED`, `FAILED`, `CANCELLED`)
- `completedCount`: Int
- `totalCount`: Int
- `failedCount`: Int
- `workRequestId`: String? (WorkManager UUID)
- `lastError`: String?
- `createdAt`: Long
- `updatedAt`: Long
- **State Transitions**:
  - `PENDING` -> `RUNNING` -> (`COMPLETED` | `FAILED` | `PAUSED` | `CANCELLED`)
  - `PAUSED` -> `PENDING` -> `RUNNING`
  - On app startup after force-close/reboot: any task left in `RUNNING` is recovered to `PENDING` and re-dispatched by WorkManager.

### 2.6 `Bookmark`
- `id`: String (PK)
- `bookId`: String (FK -> `Book.id` ON DELETE CASCADE, indexed)
- `chapterIndex`: Int
- `chapterTitle`: String
- `scrollOffset`: Int
- `snippet`: String (first ~80 chars at bookmarked position)
- `note`: String?
- `createdAt`: Long

### 2.7 `CleanupRule`
- `id`: String (PK)
- `pattern`: String (validated against ReDoS when `isRegex == true`)
- `replacement`: String
- `isRegex`: Boolean (default `true`)
- `enabled`: Boolean (default `true`)
- `sortOrder`: Int

### 2.8 `AppSettings` (DataStore)
- **Reader**: `fontKey` (`"literata"`, `"noticia"`, `"roboto"`, `"hanken"`, `"mono"`, or `"file:<name>"`), `fontSizeSp` (`12..32`, default `20`), `lineHeightMultiplier` (`1.2f..2.4f`, default `1.6f`), `paragraphIndentEm` (`0f..3f`, default `1.5f`), `textAlign` (`LEFT|CENTER|RIGHT|JUSTIFY`), `marginHorizontalDp` (`8..48`), `themePresetId` (`light`, `sepia`, `dark`, `oled`, `blue`, `pink`, `green`, `custom`), `customBgHex`, `customFgHex`, `brightness` (`-1f` system or `0.05f..1f`), `readMode` (`VERTICAL_SCROLL`, `PAGED_SLIDE`, `PAGED_CURL`), `autoScrollSpeed` (`1..10`).
- **TTS**: `ttsVoiceName` (Android system voice id), `ttsSpeechRate` (`0.5f..2.5f`), `ttsPitch` (`0.5f..2.0f`), `ttsSleepTimerMinutes` (`0` off, `15`, `30`, `45`, `60`, or end-of-chapter), `ttsAmbienceEnabled` (`false`), `ttsAmbienceTrack` (`rain`, `cafe`, `white_noise`, etc.), `ttsAmbienceVolume` (`0f..1f`).
- **Downloader**: `maxConcurrency` (`1..6`, default `3`), `requestDelayMs` (`0..5000`, default `0`), `requestTimeoutMs` (`5000..120000`, default `30000`), `maxRetriesPerChapter` (`0..5`, default `2`), `circuitBreakerThreshold` (`3..20`, default `8`).
- **Export**: `defaultExportFormat` (`EPUB3`, `EPUB2`, `TXT`), `includeCover` (`true`), `includeImages` (`true`), `autoSplitVolumes` (`false`), `zipCompressionLevel` (`1..9`, default `6`).
- **App**: `uiLanguage` (`"vi"`, `"en"`).

---

## 3. On-Device Storage Directory Layout

```text
<appFilesDir>/vapp/
├── db/
│   └── vapp.db                    # Room SQLite database (WAL mode)
├── extensions/
│   └── <extensionId>/
│       ├── plugin.json
│       ├── icon.png
│       └── src/*.js
├── books/
│   └── <bookId>/
│       ├── cover.jpg              # Normalized cover (or cover-custom.jpg)
│       └── chapters/
│           └── <chapterId>.html   # UTF-8 raw chapter HTML
├── cookies/
│   └── cookies.json               # Atomic JSON cookie store via :engine CookiePersistence
├── fonts/                         # User-imported .ttf / .otf fonts
├── exports/                       # Temp staging before SAF / Downloads copy
└── logs/
    └── engine-debug.log           # Rotating extension & downloader debug log
```
