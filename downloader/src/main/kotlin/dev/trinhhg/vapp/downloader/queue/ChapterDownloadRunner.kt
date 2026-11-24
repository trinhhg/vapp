package dev.trinhhg.vapp.downloader.queue

import androidx.room.withTransaction
import dev.trinhhg.vapp.downloader.api.DownloaderConfig
import dev.trinhhg.vapp.downloader.db.ChapterDownloadStatus
import dev.trinhhg.vapp.downloader.db.ChapterEntity
import dev.trinhhg.vapp.downloader.db.DownloadTaskStatus
import dev.trinhhg.vapp.downloader.db.VappDatabase
import dev.trinhhg.vapp.downloader.db.syncBookCounters
import dev.trinhhg.vapp.downloader.storage.ChapterFileStore
import dev.trinhhg.vapp.engine.EngineException
import dev.trinhhg.vapp.engine.api.ExtensionExecutor
import dev.trinhhg.vapp.engine.log.EngineLogger
import dev.trinhhg.vapp.engine.log.LogLevel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

data class TaskRunOutcome(
    val finalStatus: DownloadTaskStatus,
    val completedCount: Int,
    val failedCount: Int,
    val lastError: String?,
)

/**
 * Executes concurrent chapter downloads for a single [dev.trinhhg.vapp.downloader.db.DownloadTaskEntity]
 * with resume-skip, exponential backoff retry, per-chapter DB checkpointing, and consecutive-error circuit breaker.
 */
class ChapterDownloadRunner(
    private val db: VappDatabase,
    private val fileStore: ChapterFileStore,
    private val extensionExecutor: ExtensionExecutor,
    private val log: EngineLogger,
    private val backoffBaseMs: Long = DEFAULT_BACKOFF_BASE_MS,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val bookDao = db.bookDao()
    private val chapterDao = db.chapterDao()
    private val taskDao = db.downloadTaskDao()

    suspend fun runTask(
        taskId: String,
        config: DownloaderConfig,
        onProgress: suspend (completed: Int, failed: Int, total: Int, currentTitle: String?) -> Unit = { _, _, _, _ -> },
    ): TaskRunOutcome {
        val task =
            taskDao.getById(taskId)
                ?: return TaskRunOutcome(DownloadTaskStatus.CANCELLED, 0, 0, "Task not found")
        val book =
            bookDao.getById(task.bookId)
                ?: return TaskRunOutcome(DownloadTaskStatus.FAILED, 0, 0, "Book ${task.bookId} not found")
        val extId =
            book.extensionId
                ?: return failTask(taskId, task.completedCount, task.failedCount, "No extension associated with book")

        if (task.status == DownloadTaskStatus.PAUSED || task.status == DownloadTaskStatus.CANCELLED) {
            return TaskRunOutcome(task.status, task.completedCount, task.failedCount, task.lastError)
        }

        taskDao.updateProgress(taskId, DownloadTaskStatus.RUNNING, task.completedCount, task.failedCount, null, clock())

        val allChapters = chapterDao.listByBook(book.id)
        val targets = selectChaptersForTask(allChapters, task.selectionSpec)
        val total = targets.size
        if (total == 0) {
            taskDao.updateProgress(taskId, DownloadTaskStatus.COMPLETED, 0, 0, null, clock())
            return TaskRunOutcome(DownloadTaskStatus.COMPLETED, 0, 0, null)
        }

        val semaphore = Semaphore(config.maxConcurrency.coerceIn(1, 6))
        val consecutiveErrors = AtomicInteger(0)
        val circuitTripped = AtomicBoolean(false)
        val abortedByPauseOrCancel = AtomicBoolean(false)
        val completedCounter = AtomicInteger(0)
        val failedCounter = AtomicInteger(0)
        val lastErrorMsg = AtomicReference<String?>(null)

        val pendingTargets =
            targets.filter { ch ->
                if (ch.downloadStatus == ChapterDownloadStatus.COMPLETED && fileStore.hasValidChapterFile(ch.contentPath)) {
                    completedCounter.incrementAndGet()
                    false
                } else {
                    true
                }
            }

        onProgress(completedCounter.get(), failedCounter.get(), total, null)

        try {
            coroutineScope {
                pendingTargets
                    .map { chapter ->
                        async {
                            semaphore.withPermit {
                                if (circuitTripped.get() || abortedByPauseOrCancel.get()) return@withPermit
                                val freshTask = taskDao.getById(taskId)
                                if (freshTask == null ||
                                    freshTask.status == DownloadTaskStatus.PAUSED ||
                                    freshTask.status == DownloadTaskStatus.CANCELLED
                                ) {
                                    abortedByPauseOrCancel.set(true)
                                    return@withPermit
                                }

                                if (config.requestDelayMs > 0L) {
                                    delay(config.requestDelayMs)
                                }

                                val result =
                                    downloadSingleChapterWithRetry(
                                        bookId = book.id,
                                        extensionId = extId,
                                        chapter = chapter,
                                        config = config,
                                        shouldAbort = {
                                            circuitTripped.get() || abortedByPauseOrCancel.get() || isTaskInterrupted(taskId)
                                        },
                                    )

                                when (result) {
                                    is SingleResult.Success -> {
                                        consecutiveErrors.set(0)
                                        val done = completedCounter.incrementAndGet()
                                        val fail = failedCounter.get()
                                        taskDao.updateProgress(taskId, DownloadTaskStatus.RUNNING, done, fail, lastErrorMsg.get(), clock())
                                        onProgress(done, fail, total, chapter.title)
                                    }

                                    is SingleResult.Interrupted -> {
                                        abortedByPauseOrCancel.set(true)
                                    }

                                    is SingleResult.Failed -> {
                                        val errCount = consecutiveErrors.incrementAndGet()
                                        val fail = failedCounter.incrementAndGet()
                                        var errMessage = result.reason
                                        if (errCount >= config.circuitBreakerThreshold) {
                                            circuitTripped.set(true)
                                            errMessage =
                                                "Circuit breaker tripped after $errCount consecutive errors: ${result.reason}"
                                            log.warn(errMessage, tag = TAG)
                                        }
                                        lastErrorMsg.set(errMessage)
                                        taskDao.updateProgress(
                                            taskId,
                                            DownloadTaskStatus.RUNNING,
                                            completedCounter.get(),
                                            fail,
                                            errMessage,
                                            clock(),
                                        )
                                        onProgress(completedCounter.get(), fail, total, chapter.title)
                                    }
                                }
                            }
                        }
                    }.awaitAll()
            }
        } catch (ce: CancellationException) {
            abortedByPauseOrCancel.set(true)
            resetInFlightChapters(book.id, targetIds = pendingTargets.map { it.id })
            throw ce
        }

        val latestTask = taskDao.getById(taskId)
        val finalStatus =
            when {
                latestTask?.status == DownloadTaskStatus.PAUSED -> DownloadTaskStatus.PAUSED
                latestTask?.status == DownloadTaskStatus.CANCELLED -> DownloadTaskStatus.CANCELLED
                abortedByPauseOrCancel.get() -> latestTask?.status ?: DownloadTaskStatus.PAUSED
                circuitTripped.get() -> DownloadTaskStatus.FAILED
                failedCounter.get() > 0 && completedCounter.get() == 0 -> DownloadTaskStatus.FAILED
                else -> DownloadTaskStatus.COMPLETED
            }

        if (finalStatus == DownloadTaskStatus.PAUSED || finalStatus == DownloadTaskStatus.FAILED) {
            resetInFlightChapters(book.id, targetIds = pendingTargets.map { it.id })
        }

        db.withTransaction {
            chapterDao.syncBookCounters(book.id, bookDao, clock())
            taskDao.updateProgress(
                taskId = taskId,
                status = finalStatus,
                completedCount = completedCounter.get(),
                failedCount = failedCounter.get(),
                lastError = lastErrorMsg.get(),
                updatedAt = clock(),
            )
        }

        return TaskRunOutcome(finalStatus, completedCounter.get(), failedCounter.get(), lastErrorMsg.get())
    }

    private suspend fun isTaskInterrupted(taskId: String): Boolean {
        val status = taskDao.getById(taskId)?.status ?: return true
        return status == DownloadTaskStatus.PAUSED || status == DownloadTaskStatus.CANCELLED
    }

    private suspend fun resetInFlightChapters(
        bookId: String,
        targetIds: List<String>,
    ) {
        if (targetIds.isEmpty()) return
        val rows = chapterDao.listByBook(bookId)
        val stuck = rows.filter { it.id in targetIds && it.downloadStatus == ChapterDownloadStatus.DOWNLOADING }
        if (stuck.isNotEmpty()) {
            chapterDao.updateStatusBatch(stuck.map { it.id }, ChapterDownloadStatus.QUEUED, clock())
        }
    }

    private suspend fun downloadSingleChapterWithRetry(
        bookId: String,
        extensionId: String,
        chapter: ChapterEntity,
        config: DownloaderConfig,
        shouldAbort: suspend () -> Boolean,
    ): SingleResult {
        chapterDao.updateStatusBatch(listOf(chapter.id), ChapterDownloadStatus.DOWNLOADING, clock())
        val maxAttempts = config.maxRetriesPerChapter.coerceAtLeast(0) + 1
        var lastError = "Unknown download error"

        for (attempt in 0 until maxAttempts) {
            if (shouldAbort()) {
                chapterDao.updateStatusBatch(listOf(chapter.id), ChapterDownloadStatus.QUEUED, clock())
                return SingleResult.Interrupted
            }

            if (attempt > 0) {
                val backoffMs = (backoffBaseMs shl (attempt - 1)).coerceAtMost(MAX_BACKOFF_MS)
                delay(backoffMs)
                if (shouldAbort()) {
                    chapterDao.updateStatusBatch(listOf(chapter.id), ChapterDownloadStatus.QUEUED, clock())
                    return SingleResult.Interrupted
                }
            }

            val fetchResult =
                runCatching {
                    withTimeout(config.requestTimeoutMs) {
                        extensionExecutor.fetchChapterContent(extensionId, chapter.sourceUrl).getOrThrow()
                    }
                }

            if (fetchResult.isSuccess) {
                val content = fetchResult.getOrThrow()
                val rawHtml = content.html.trim()
                if (rawHtml.isEmpty()) {
                    chapterDao.updateDownloadResult(
                        chapterId = chapter.id,
                        status = ChapterDownloadStatus.EMPTY,
                        contentPath = null,
                        charCount = 0,
                        isImageChapter = false,
                        errorMessage = "Chapter returned empty HTML",
                        updatedAt = clock(),
                    )
                    return SingleResult.Failed("Empty chapter content: ${chapter.title}")
                }

                val path = fileStore.saveChapterHtml(bookId, chapter.id, chapter.orderIndex, rawHtml)
                val plainLen = HTML_TAG_REGEX.replace(rawHtml, "").trim().length
                val isImg = plainLen < IMAGE_CHAPTER_TEXT_THRESHOLD && IMG_TAG_REGEX.containsMatchIn(rawHtml)

                chapterDao.updateDownloadResult(
                    chapterId = chapter.id,
                    status = ChapterDownloadStatus.COMPLETED,
                    contentPath = path,
                    charCount = plainLen,
                    isImageChapter = isImg,
                    errorMessage = null,
                    updatedAt = clock(),
                )
                return SingleResult.Success
            } else {
                val err = fetchResult.exceptionOrNull()
                if (err is CancellationException) throw err
                lastError =
                    if (err is EngineException) {
                        "[${err.code}] ${err.message}"
                    } else {
                        err?.message ?: err?.javaClass?.simpleName ?: "Unknown error"
                    }
                log.add(
                    "Attempt ${attempt + 1}/$maxAttempts failed for ${chapter.title}: $lastError",
                    tag = TAG,
                    level = LogLevel.WARN,
                )
            }
        }

        chapterDao.updateDownloadResult(
            chapterId = chapter.id,
            status = ChapterDownloadStatus.FAILED,
            contentPath = null,
            charCount = 0,
            isImageChapter = false,
            errorMessage = lastError,
            updatedAt = clock(),
        )
        return SingleResult.Failed(lastError)
    }

    private suspend fun failTask(
        taskId: String,
        completed: Int,
        failed: Int,
        reason: String,
    ): TaskRunOutcome {
        taskDao.updateProgress(taskId, DownloadTaskStatus.FAILED, completed, failed, reason, clock())
        return TaskRunOutcome(DownloadTaskStatus.FAILED, completed, failed, reason)
    }

    private sealed interface SingleResult {
        data object Success : SingleResult

        data object Interrupted : SingleResult

        data class Failed(
            val reason: String,
        ) : SingleResult
    }

    companion object {
        private const val TAG = "ChapterDownloadRunner"
        private const val DEFAULT_BACKOFF_BASE_MS = 500L
        private const val MAX_BACKOFF_MS = 10_000L
        private const val IMAGE_CHAPTER_TEXT_THRESHOLD = 1000
        private val HTML_TAG_REGEX = Regex("<[^>]+>")
        private val IMG_TAG_REGEX = Regex("<img\\b", RegexOption.IGNORE_CASE)

        fun selectChaptersForTask(
            chapters: List<ChapterEntity>,
            selectionSpec: String,
        ): List<ChapterEntity> {
            val trimmed = selectionSpec.trim()
            return when {
                trimmed.equals("ALL", ignoreCase = true) -> chapters
                trimmed.equals("FAILED_ONLY", ignoreCase = true) ->
                    chapters.filter {
                        it.downloadStatus == ChapterDownloadStatus.FAILED ||
                            it.downloadStatus == ChapterDownloadStatus.EMPTY ||
                            it.downloadStatus == ChapterDownloadStatus.NOT_DOWNLOADED ||
                            it.downloadStatus == ChapterDownloadStatus.QUEUED
                    }
                else -> {
                    val zeroIndices = parseOneBasedRangeSpec(trimmed, chapters.size)
                    chapters.filter { it.orderIndex in zeroIndices }
                }
            }
        }

        fun parseOneBasedRangeSpec(
            spec: String,
            totalCount: Int,
        ): Set<Int> {
            val out = LinkedHashSet<Int>()
            spec.split(",").map { it.trim() }.filter { it.isNotEmpty() }.forEach { part ->
                if (part.contains("-")) {
                    val bounds = part.split("-", limit = 2)
                    val start = bounds[0].trim().toIntOrNull()
                    val end = bounds.getOrNull(1)?.trim()?.toIntOrNull()
                    if (start != null && end != null) {
                        val lo = minOf(start, end).coerceAtLeast(1)
                        val hi = maxOf(start, end).coerceAtMost(totalCount)
                        for (oneBased in lo..hi) {
                            out.add(oneBased - 1)
                        }
                    }
                } else {
                    val idx = part.toIntOrNull()
                    if (idx != null && idx in 1..totalCount) {
                        out.add(idx - 1)
                    }
                }
            }
            return out
        }
    }
}
