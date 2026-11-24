# Contract: Module Boundaries & Interfaces (`vapp`)

**Purpose**: Enforces clean separation between `:engine`, `:downloader`, `:epub-exporter`, `:tts-player`, and `:app`.

## Dependency Graph (Strictly Acyclic)

```text
:app (Compose UI + Android WebView Bridge + SAF Backup/Restore)
  ├── :downloader (Room + WorkManager + ForegroundService) ──▶ :engine
  ├── :epub-exporter (Pure JVM EPUB 2/3 & TXT Builder)    ──▶ :engine
  ├── :tts-player (Android TextToSpeech + Media3 Service)
  └── :engine (Pure JVM: Rhino Sandbox + VBook API + Security + Fetch)
```

## 1. `:engine` Public Contracts

```kotlin
interface BrowserEngineFactory {
    fun createSession(): BrowserSession
}

interface ChallengeSolver {
    suspend fun solveChallenge(url: String, timeoutMs: Long = 120_000L): String?
}

interface ExtensionExecutor {
    suspend fun fetchBookDetail(extensionId: String, bookUrl: String): Result<BookMetadata>
    suspend fun fetchTableOfContents(extensionId: String, bookUrl: String): Result<List<ChapterRef>>
    suspend fun fetchChapterContent(extensionId: String, chapterUrl: String): Result<ChapterContent>
    suspend fun search(extensionId: String, query: String, page: String?): Result<PagedList<BookSearchItem>>
    suspend fun browse(extensionId: String, script: String, input: String?, page: String?): Result<PagedList<BookSearchItem>>
}
```

- All failures return `Result.failure(EngineException)` with typed error codes (`ENCRYPTED_UNSUPPORTED`, `NETWORK_ERROR`, `CHALLENGE_REQUIRED`, `SCRIPT_ERROR`, `VALIDATION_ERROR`) and append structured traces to `EngineLogger`.

## 2. `:downloader` Public Contracts

```kotlin
interface DownloadQueueManager {
    fun observeTasks(): Flow<List<DownloadTaskSummary>>
    suspend fun enqueueBook(bookId: String, selection: ChapterSelection): String
    suspend fun pauseTask(taskId: String)
    suspend fun resumeTask(taskId: String)
    suspend fun cancelTask(taskId: String)
    suspend fun retryFailedChapters(bookId: String)
    suspend fun recoverInterruptedTasksOnStartup()
}
```

- Uses `WorkManager` + `ForegroundService` (`dataSync`); guarantees idempotent chapter writes (`.tmp` -> atomic rename) and Room state checkpointing per chapter.

## 3. `:epub-exporter` Public Contracts

```kotlin
enum class ExportFormat { EPUB_2, EPUB_3, TXT }

data class ExportRequest(
    val book: BookExportMetadata,
    val chapters: List<ChapterExportInput>, // caller supplies selected range / downloaded chapters
    val format: ExportFormat,
    val includeCover: Boolean = true,
    val includeImages: Boolean = true,
    val autoSplitVolumes: Boolean = false,
    val compressionLevel: Int = 6,
    val cleanupRules: List<CleanupRuleSpec> = emptyList(),
    val outputFilePath: String,
)

interface BookExporter {
    suspend fun export(request: ExportRequest, onProgress: (Int, String) -> Unit): Result<ExportOutput>
}
```

- Guarantees atomic `.tmp` output creation; deletes temp files on failure so 0-byte EPUBs are never emitted.

## 4. `:tts-player` Public Contracts

```kotlin
interface TtsEngine {
    suspend fun listVoices(): List<TtsVoiceInfo>
    suspend fun speakParagraph(text: String, voiceId: String?, rate: Float, pitch: Float): TtsUtteranceResult
    fun stop()
}

interface AudiobookController {
    val playbackState: StateFlow<AudiobookState>
    fun playChapter(bookId: String, chapterIndex: Int, startParagraph: Int = 0)
    fun pause()
    fun resume()
    fun seekToParagraph(index: Int)
    fun skipChapter(delta: Int)
    fun setSpeed(rate: Float)
    fun setSleepTimer(minutes: Int?)
    fun setAmbience(trackId: String?, volume: Float)
}
```
