package dev.trinhhg.vapp.downloader.queue

import android.content.Context
import androidx.room.withTransaction
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import dev.trinhhg.vapp.downloader.api.ChapterSelection
import dev.trinhhg.vapp.downloader.api.DownloadQueueManager
import dev.trinhhg.vapp.downloader.api.DownloadTaskSummary
import dev.trinhhg.vapp.downloader.db.ChapterDownloadStatus
import dev.trinhhg.vapp.downloader.db.DownloadTaskEntity
import dev.trinhhg.vapp.downloader.db.DownloadTaskStatus
import dev.trinhhg.vapp.downloader.db.VappDatabase
import dev.trinhhg.vapp.downloader.storage.ChapterFileStore
import dev.trinhhg.vapp.downloader.worker.DownloadForegroundService
import dev.trinhhg.vapp.downloader.worker.DownloadWorker
import dev.trinhhg.vapp.engine.log.EngineLogger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Persistent Room + WorkManager queue coordinator surviving network drops, pauses, and app restarts.
 */
class DownloadQueueManagerImpl(
    private val appContext: Context,
    private val db: VappDatabase,
    private val fileStore: ChapterFileStore,
    private val log: EngineLogger,
    private val workManager: WorkManager = WorkManager.getInstance(appContext),
    private val startService: Boolean = true,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : DownloadQueueManager {
    private val bookDao = db.bookDao()
    private val chapterDao = db.chapterDao()
    private val taskDao = db.downloadTaskDao()

    override fun observeTasks(): Flow<List<DownloadTaskSummary>> =
        combine(taskDao.observeAll(), bookDao.observeLibrary()) { tasks, books ->
            val bookTitles = books.associate { it.id to it.title }
            tasks.map { task ->
                DownloadTaskSummary(
                    taskId = task.id,
                    bookId = task.bookId,
                    bookTitle = bookTitles[task.bookId] ?: task.bookId,
                    selectionSpec = task.selectionSpec,
                    status = task.status,
                    completedCount = task.completedCount,
                    totalCount = task.totalCount,
                    failedCount = task.failedCount,
                    workRequestId = task.workRequestId,
                    lastError = task.lastError,
                    updatedAt = task.updatedAt,
                )
            }
        }

    override suspend fun enqueueBook(
        bookId: String,
        selection: ChapterSelection,
    ): String {
        val now = clock()
        val taskId = UUID.randomUUID().toString()
        val allChapters = chapterDao.listByBook(bookId)
        val selectionSpec = toSelectionSpec(selection)
        val selected = ChapterDownloadRunner.selectChaptersForTask(allChapters, selectionSpec)

        val toQueueIds =
            selected
                .filter { ch ->
                    !(ch.downloadStatus == ChapterDownloadStatus.COMPLETED && fileStore.hasValidChapterFile(ch.contentPath))
                }.map { it.id }

        val workId = buildAndEnqueueWork(taskId)
        db.withTransaction {
            if (toQueueIds.isNotEmpty()) {
                chapterDao.updateStatusBatch(toQueueIds, ChapterDownloadStatus.QUEUED, now)
            }
            taskDao.upsert(
                DownloadTaskEntity(
                    id = taskId,
                    bookId = bookId,
                    selectionSpec = selectionSpec,
                    status = DownloadTaskStatus.PENDING,
                    completedCount = selected.size - toQueueIds.size,
                    totalCount = selected.size,
                    failedCount = 0,
                    workRequestId = workId,
                    lastError = null,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        }

        if (startService) {
            DownloadForegroundService.start(appContext)
        }
        log.add("Enqueued task $taskId for book $bookId ($selectionSpec, total=${selected.size})", tag = TAG)
        return taskId
    }

    override suspend fun pauseTask(taskId: String) {
        val task = taskDao.getById(taskId) ?: return
        val now = clock()
        cancelWork(task.workRequestId)
        db.withTransaction {
            val downloadingIds =
                chapterDao
                    .listByBook(task.bookId)
                    .filter { it.downloadStatus == ChapterDownloadStatus.DOWNLOADING }
                    .map { it.id }
            if (downloadingIds.isNotEmpty()) {
                chapterDao.updateStatusBatch(downloadingIds, ChapterDownloadStatus.QUEUED, now)
            }
            taskDao.updateStatusAndWorkId(taskId, DownloadTaskStatus.PAUSED, null, now)
        }
        log.add("Paused task $taskId", tag = TAG)
    }

    override suspend fun resumeTask(taskId: String) {
        val task = taskDao.getById(taskId) ?: return
        val now = clock()
        val workId = buildAndEnqueueWork(taskId)
        db.withTransaction {
            val failedOrEmpty =
                chapterDao
                    .listByBook(task.bookId)
                    .filter {
                        it.downloadStatus == ChapterDownloadStatus.FAILED ||
                            it.downloadStatus == ChapterDownloadStatus.EMPTY
                    }.map { it.id }
            if (failedOrEmpty.isNotEmpty()) {
                chapterDao.updateStatusBatch(failedOrEmpty, ChapterDownloadStatus.QUEUED, now)
            }
            taskDao.updateStatusAndWorkId(taskId, DownloadTaskStatus.PENDING, workId, now)
        }
        if (startService) {
            DownloadForegroundService.start(appContext)
        }
        log.add("Resumed task $taskId", tag = TAG)
    }

    override suspend fun cancelTask(taskId: String) {
        val task = taskDao.getById(taskId) ?: return
        val now = clock()
        cancelWork(task.workRequestId)
        db.withTransaction {
            val pendingIds =
                chapterDao
                    .listByBook(task.bookId)
                    .filter {
                        it.downloadStatus == ChapterDownloadStatus.QUEUED ||
                            it.downloadStatus == ChapterDownloadStatus.DOWNLOADING
                    }.map { it.id }
            if (pendingIds.isNotEmpty()) {
                chapterDao.updateStatusBatch(pendingIds, ChapterDownloadStatus.NOT_DOWNLOADED, now)
            }
            taskDao.updateStatusAndWorkId(taskId, DownloadTaskStatus.CANCELLED, null, now)
        }
        log.add("Cancelled task $taskId", tag = TAG)
    }

    override suspend fun retryFailedChapters(bookId: String): String? {
        val failedOrEmpty =
            chapterDao.listByBook(bookId).filter {
                it.downloadStatus == ChapterDownloadStatus.FAILED ||
                    it.downloadStatus == ChapterDownloadStatus.EMPTY ||
                    (it.downloadStatus == ChapterDownloadStatus.COMPLETED && !fileStore.hasValidChapterFile(it.contentPath))
            }
        if (failedOrEmpty.isEmpty()) return null
        return enqueueBook(bookId, ChapterSelection.FailedOnly)
    }

    override suspend fun recoverInterruptedTasksOnStartup() {
        val activeStatuses = listOf(DownloadTaskStatus.RUNNING, DownloadTaskStatus.PENDING)
        val staleTasks = taskDao.listByStatuses(activeStatuses)
        if (staleTasks.isEmpty()) return

        val now = clock()
        for (task in staleTasks) {
            val downloadingIds =
                chapterDao
                    .listByBook(task.bookId)
                    .filter { it.downloadStatus == ChapterDownloadStatus.DOWNLOADING }
                    .map { it.id }
            val workId = buildAndEnqueueWork(task.id)
            db.withTransaction {
                if (downloadingIds.isNotEmpty()) {
                    chapterDao.updateStatusBatch(downloadingIds, ChapterDownloadStatus.QUEUED, now)
                }
                taskDao.updateStatusAndWorkId(task.id, DownloadTaskStatus.PENDING, workId, now)
            }
            log.add("Recovered interrupted task ${task.id} on startup -> PENDING ($workId)", tag = TAG)
        }
        if (startService) {
            DownloadForegroundService.start(appContext)
        }
    }

    private fun buildAndEnqueueWork(taskId: String): String {
        val constraints =
            Constraints
                .Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
        val request =
            OneTimeWorkRequestBuilder<DownloadWorker>()
                .setConstraints(constraints)
                .setInputData(workDataOf(DownloadWorker.KEY_TASK_ID to taskId))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
                .build()
        workManager.enqueueUniqueWork(
            "vapp_dl_$taskId",
            ExistingWorkPolicy.REPLACE,
            request,
        )
        return request.id.toString()
    }

    private fun cancelWork(workRequestId: String?) {
        if (workRequestId.isNullOrBlank()) return
        runCatching {
            workManager.cancelWorkById(UUID.fromString(workRequestId))
        }
    }

    private fun toSelectionSpec(selection: ChapterSelection): String =
        when (selection) {
            ChapterSelection.All -> "ALL"
            ChapterSelection.FailedOnly -> "FAILED_ONLY"
            is ChapterSelection.Indices ->
                selection.zeroBasedIndices
                    .distinct()
                    .sorted()
                    .joinToString(",") { (it + 1).toString() }
            is ChapterSelection.Spec -> selection.selectionSpec.trim().ifBlank { "ALL" }
        }

    companion object {
        private const val TAG = "DownloadQueueManager"
    }
}
