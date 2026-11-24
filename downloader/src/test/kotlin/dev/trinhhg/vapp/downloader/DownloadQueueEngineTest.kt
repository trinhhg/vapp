package dev.trinhhg.vapp.downloader

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import dev.trinhhg.vapp.downloader.api.ChapterSelection
import dev.trinhhg.vapp.downloader.api.DownloaderConfig
import dev.trinhhg.vapp.downloader.db.ChapterDownloadStatus
import dev.trinhhg.vapp.downloader.db.DownloadTaskEntity
import dev.trinhhg.vapp.downloader.db.DownloadTaskStatus
import dev.trinhhg.vapp.downloader.db.ExtensionPackageEntity
import dev.trinhhg.vapp.downloader.db.VappDatabase
import dev.trinhhg.vapp.downloader.queue.ChapterDownloadRunner
import dev.trinhhg.vapp.downloader.queue.DownloadQueueManagerImpl
import dev.trinhhg.vapp.downloader.storage.ChapterFileStore
import dev.trinhhg.vapp.engine.EngineErrorCode
import dev.trinhhg.vapp.engine.EngineException
import dev.trinhhg.vapp.engine.api.BookMetadata
import dev.trinhhg.vapp.engine.api.BookSearchItem
import dev.trinhhg.vapp.engine.api.ChapterContent
import dev.trinhhg.vapp.engine.api.ChapterRef
import dev.trinhhg.vapp.engine.api.ExtensionExecutor
import dev.trinhhg.vapp.engine.api.ExtensionNavTab
import dev.trinhhg.vapp.engine.api.PagedList
import dev.trinhhg.vapp.engine.log.EngineLogger
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toOkioPath
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class DownloadQueueEngineTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var db: VappDatabase
    private lateinit var fileStore: ChapterFileStore
    private lateinit var logger: EngineLogger
    private lateinit var workManager: WorkManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val wmConfig =
            Configuration
                .Builder()
                .setMinimumLoggingLevel(Log.DEBUG)
                .setExecutor(SynchronousExecutor())
                .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, wmConfig)
        workManager = WorkManager.getInstance(context)

        db = VappDatabase.createInMemory(context, allowMainThread = true)
        val rootDir = tempFolder.newFolder("vapp_data").toOkioPath()
        fileStore = ChapterFileStore(rootDir)
        logger = EngineLogger()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `ChapterFileStore writes atomically without lingering tmp files and rejects blank HTML`() {
        val path = fileStore.saveChapterHtml("book1", "chap1", 0, "<p>Chương 1: Khởi đầu</p>")
        assertTrue(fileStore.hasValidChapterFile(path))
        assertEquals("<p>Chương 1: Khởi đầu</p>", fileStore.readChapterHtml(path))

        val absParent = fileStore.resolveAbsolutePath(path).parent!!.toFile()
        val tmpFiles = absParent.listFiles()?.filter { it.name.endsWith(".tmp") }.orEmpty()
        assertTrue(tmpFiles.isEmpty(), "Expected zero .tmp files after atomic write")

        val blankErr =
            runCatching {
                fileStore.saveChapterHtml("book1", "chap2", 1, "   \n  ")
            }
        assertTrue(blankErr.isFailure)
    }

    @Test
    fun `BookLibraryRepository mergeFetchedChapters preserves downloaded chapters and updates counters`() =
        runTest {
            seedExtension("ext_truyenfull")
            val repo = BookLibraryRepository(db, fileStore)
            val meta =
                BookMetadata(
                    name = "Phàm Nhân Tu Tiên",
                    author = "Vong Ngữ",
                    ongoing = true,
                    url = "https://example.com/truyen/pntt",
                )
            val initialToc =
                listOf(
                    ChapterRef(0, "Chương 1", "https://example.com/truyen/pntt/c1"),
                    ChapterRef(1, "Chương 2", "https://example.com/truyen/pntt/c2"),
                )
            val first = repo.saveBookAndMergeToc("ext_truyenfull", meta, initialToc)
            assertEquals(2, first.newlyAddedChapters)
            assertEquals(2, first.book.totalChapters)

            val chapters = repo.getChapters(first.book.id)
            val savedPath = fileStore.saveChapterHtml(first.book.id, chapters[0].id, 0, "<p>OK</p>")
            db.chapterDao().updateDownloadResult(
                chapterId = chapters[0].id,
                status = ChapterDownloadStatus.COMPLETED,
                contentPath = savedPath,
                charCount = 2,
                isImageChapter = false,
                errorMessage = null,
                updatedAt = 100L,
            )

            val updatedToc =
                listOf(
                    ChapterRef(0, "Chương 1 (Đã sửa)", "https://example.com/truyen/pntt/c1"),
                    ChapterRef(1, "Chương 2", "https://example.com/truyen/pntt/c2"),
                    ChapterRef(2, "Chương 3 (Mới)", "https://example.com/truyen/pntt/c3"),
                )
            val added = repo.mergeFetchedChapters(first.book.id, updatedToc)
            assertEquals(1, added)

            val merged = repo.getChapters(first.book.id)
            assertEquals(3, merged.size)
            assertEquals(ChapterDownloadStatus.COMPLETED, merged[0].downloadStatus)
            assertEquals(savedPath, merged[0].contentPath)
            assertEquals("Chương 1 (Đã sửa)", merged[0].title)
            assertEquals(ChapterDownloadStatus.NOT_DOWNLOADED, merged[2].downloadStatus)

            val refreshedBook = repo.getBook(first.book.id)!!
            assertEquals(3, refreshedBook.totalChapters)
            assertEquals(1, refreshedBook.downloadedChapters)
        }

    @Test
    fun `ChapterDownloadRunner skips completed valid chapters and retries transient failures with backoff`() =
        runTest {
            seedExtension("ext_test")
            val repo = BookLibraryRepository(db, fileStore)
            val meta = BookMetadata(name = "Test Book", url = "https://example.com/book")
            val toc =
                listOf(
                    ChapterRef(0, "Ch 1", "https://example.com/book/1"),
                    ChapterRef(1, "Ch 2 (missing file)", "https://example.com/book/2"),
                    ChapterRef(2, "Ch 3 (flaky)", "https://example.com/book/3"),
                )
            val book = repo.saveBookAndMergeToc("ext_test", meta, toc).book
            val chapters = repo.getChapters(book.id)

            // ch0 already COMPLETED + valid file; ch1 marked COMPLETED but file missing on disk!
            val ch0Path = fileStore.saveChapterHtml(book.id, chapters[0].id, 0, "<p>Cached 1</p>")
            db.chapterDao().updateDownloadResult(chapters[0].id, ChapterDownloadStatus.COMPLETED, ch0Path, 8, false, null, 1L)
            db.chapterDao().updateDownloadResult(chapters[1].id, ChapterDownloadStatus.COMPLETED, "books/missing.html", 8, false, null, 1L)

            val attempts = ConcurrentHashMap<String, AtomicInteger>()
            val fakeExecutor =
                FakeChapterExecutor { url ->
                    val count = attempts.getOrPut(url) { AtomicInteger(0) }.incrementAndGet()
                    if (url.endsWith("/3") && count <= 2) {
                        Result.failure(EngineException(EngineErrorCode.NETWORK_ERROR, "Simulated timeout #$count"))
                    } else {
                        Result.success(ChapterContent(title = url, html = "<p>Downloaded $url</p>"))
                    }
                }

            val taskId = "task_retry"
            db.downloadTaskDao().upsert(
                DownloadTaskEntity(
                    id = taskId,
                    bookId = book.id,
                    selectionSpec = "ALL",
                    status = DownloadTaskStatus.PENDING,
                    totalCount = 3,
                ),
            )

            val runner = ChapterDownloadRunner(db, fileStore, fakeExecutor, logger, backoffBaseMs = 5L)
            val outcome =
                runner.runTask(
                    taskId,
                    DownloaderConfig(maxConcurrency = 2, maxRetriesPerChapter = 2, circuitBreakerThreshold = 8),
                )

            assertEquals(DownloadTaskStatus.COMPLETED, outcome.finalStatus)
            assertEquals(3, outcome.completedCount)
            assertEquals(0, outcome.failedCount)
            assertEquals(null, attempts["https://example.com/book/1"])
            assertEquals(1, attempts["https://example.com/book/2"]?.get())
            assertEquals(3, attempts["https://example.com/book/3"]?.get())
            assertEquals(3, repo.getBook(book.id)?.downloadedChapters)
        }

    @Test
    fun `ChapterDownloadRunner trips circuit breaker after 8 consecutive errors`() =
        runTest {
            seedExtension("ext_fail")
            val repo = BookLibraryRepository(db, fileStore)
            val toc = (1..15).map { idx -> ChapterRef(idx - 1, "Ch $idx", "https://example.com/fail/$idx") }
            val book =
                repo
                    .saveBookAndMergeToc(
                        "ext_fail",
                        BookMetadata(name = "Fail Book", url = "https://example.com/fail"),
                        toc,
                    ).book

            val callCount = AtomicInteger(0)
            val alwaysFailExecutor =
                FakeChapterExecutor {
                    callCount.incrementAndGet()
                    Result.failure(EngineException(EngineErrorCode.HTTP_ERROR, "HTTP 503"))
                }

            val taskId = "task_breaker"
            db.downloadTaskDao().upsert(
                DownloadTaskEntity(
                    id = taskId,
                    bookId = book.id,
                    selectionSpec = "ALL",
                    status = DownloadTaskStatus.PENDING,
                    totalCount = 15,
                ),
            )

            val runner = ChapterDownloadRunner(db, fileStore, alwaysFailExecutor, logger, backoffBaseMs = 1L)
            val outcome =
                runner.runTask(
                    taskId,
                    DownloaderConfig(maxConcurrency = 1, maxRetriesPerChapter = 0, circuitBreakerThreshold = 8),
                )

            assertEquals(DownloadTaskStatus.FAILED, outcome.finalStatus)
            assertEquals(8, outcome.failedCount)
            assertEquals(8, callCount.get())
            assertTrue(outcome.lastError?.contains("Circuit breaker") == true)
        }

    @Test
    fun `DownloadQueueManager parses ranges, pauses, resumes, cancels, and recovers interrupted tasks`() =
        runTest {
            seedExtension("ext_queue")
            val repo = BookLibraryRepository(db, fileStore)
            val toc = (1..6).map { idx -> ChapterRef(idx - 1, "Ch $idx", "https://example.com/q/$idx") }
            val book =
                repo
                    .saveBookAndMergeToc(
                        "ext_queue",
                        BookMetadata(name = "Queue Book", url = "https://example.com/q"),
                        toc,
                    ).book

            val parsed = ChapterDownloadRunner.parseOneBasedRangeSpec("1-3, 5", 6)
            assertEquals(setOf(0, 1, 2, 4), parsed)

            val queue =
                DownloadQueueManagerImpl(
                    appContext = context,
                    db = db,
                    fileStore = fileStore,
                    log = logger,
                    workManager = workManager,
                    startService = false,
                )

            val taskId = queue.enqueueBook(book.id, ChapterSelection.Spec("1-4"))
            val enqueuedTask = db.downloadTaskDao().getById(taskId)!!
            assertEquals(DownloadTaskStatus.PENDING, enqueuedTask.status)
            assertEquals(4, enqueuedTask.totalCount)
            assertNotNull(enqueuedTask.workRequestId)

            val chaptersAfterEnqueue = repo.getChapters(book.id)
            assertEquals(ChapterDownloadStatus.QUEUED, chaptersAfterEnqueue[0].downloadStatus)
            assertEquals(ChapterDownloadStatus.NOT_DOWNLOADED, chaptersAfterEnqueue[4].downloadStatus)

            // Pause cancels WorkManager request and sets PAUSED
            queue.pauseTask(taskId)
            assertEquals(DownloadTaskStatus.PAUSED, db.downloadTaskDao().getById(taskId)?.status)
            val pausedWork = workManager.getWorkInfoById(UUID.fromString(enqueuedTask.workRequestId)).get()
            assertEquals(WorkInfo.State.CANCELLED, pausedWork?.state)

            // Resume enqueues fresh work and sets PENDING
            queue.resumeTask(taskId)
            val resumedTask = db.downloadTaskDao().getById(taskId)!!
            assertEquals(DownloadTaskStatus.PENDING, resumedTask.status)
            assertNotNull(resumedTask.workRequestId)

            // Simulate process kill mid-flight (RUNNING task + DOWNLOADING chapter)
            db.downloadTaskDao().updateStatusAndWorkId(taskId, DownloadTaskStatus.RUNNING, null, 50L)
            db.chapterDao().updateStatusBatch(listOf(chaptersAfterEnqueue[1].id), ChapterDownloadStatus.DOWNLOADING, 50L)

            queue.recoverInterruptedTasksOnStartup()
            val recoveredTask = db.downloadTaskDao().getById(taskId)!!
            assertEquals(DownloadTaskStatus.PENDING, recoveredTask.status)
            assertNotNull(recoveredTask.workRequestId)
            assertEquals(ChapterDownloadStatus.QUEUED, db.chapterDao().getById(chaptersAfterEnqueue[1].id)?.downloadStatus)

            // Cancel preserves completed chapters while resetting QUEUED -> NOT_DOWNLOADED
            val ch0Path = fileStore.saveChapterHtml(book.id, chaptersAfterEnqueue[0].id, 0, "<p>Keep me</p>")
            db.chapterDao().updateDownloadResult(
                chaptersAfterEnqueue[0].id,
                ChapterDownloadStatus.COMPLETED,
                ch0Path,
                7,
                false,
                null,
                60L,
            )
            queue.cancelTask(taskId)
            assertEquals(DownloadTaskStatus.CANCELLED, db.downloadTaskDao().getById(taskId)?.status)
            assertEquals(ChapterDownloadStatus.COMPLETED, db.chapterDao().getById(chaptersAfterEnqueue[0].id)?.downloadStatus)
            assertEquals(ChapterDownloadStatus.NOT_DOWNLOADED, db.chapterDao().getById(chaptersAfterEnqueue[1].id)?.downloadStatus)
            assertFalse(db.chapterDao().listByBook(book.id).any { it.downloadStatus == ChapterDownloadStatus.QUEUED })
        }

    private suspend fun seedExtension(id: String) {
        db.extensionPackageDao().upsert(
            ExtensionPackageEntity(
                id = id,
                name = id,
                sourceUrl = "https://example.com",
            ),
        )
    }

    private class FakeChapterExecutor(
        private val handler: suspend (String) -> Result<ChapterContent>,
    ) : ExtensionExecutor {
        override suspend fun fetchBookDetail(
            extensionId: String,
            bookUrl: String,
        ): Result<BookMetadata> = Result.failure(UnsupportedOperationException())

        override suspend fun fetchTableOfContents(
            extensionId: String,
            bookUrl: String,
        ): Result<List<ChapterRef>> = Result.failure(UnsupportedOperationException())

        override suspend fun fetchChapterContent(
            extensionId: String,
            chapterUrl: String,
        ): Result<ChapterContent> = handler(chapterUrl)

        override suspend fun search(
            extensionId: String,
            query: String,
            page: String?,
        ): Result<PagedList<BookSearchItem>> = Result.failure(UnsupportedOperationException())

        override suspend fun browse(
            extensionId: String,
            script: String,
            input: String?,
            page: String?,
        ): Result<PagedList<BookSearchItem>> = Result.failure(UnsupportedOperationException())

        override suspend fun fetchHomeTabs(extensionId: String): Result<List<ExtensionNavTab>> =
            Result.failure(UnsupportedOperationException())

        override suspend fun fetchGenres(extensionId: String): Result<List<ExtensionNavTab>> =
            Result.failure(UnsupportedOperationException())
    }
}
