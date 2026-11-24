package dev.trinhhg.vapp.downloader.api

import dev.trinhhg.vapp.downloader.db.DownloadTaskStatus
import kotlinx.coroutines.flow.Flow

sealed interface ChapterSelection {
    data object All : ChapterSelection

    data object FailedOnly : ChapterSelection

    data class Indices(
        val zeroBasedIndices: List<Int>,
    ) : ChapterSelection

    data class Spec(
        val selectionSpec: String,
    ) : ChapterSelection
}

data class DownloadTaskSummary(
    val taskId: String,
    val bookId: String,
    val bookTitle: String,
    val selectionSpec: String,
    val status: DownloadTaskStatus,
    val completedCount: Int,
    val totalCount: Int,
    val failedCount: Int,
    val workRequestId: String?,
    val lastError: String?,
    val updatedAt: Long,
)

data class DownloaderConfig(
    val maxConcurrency: Int = 3,
    val requestDelayMs: Long = 0L,
    val requestTimeoutMs: Long = 30_000L,
    val maxRetriesPerChapter: Int = 2,
    val circuitBreakerThreshold: Int = 8,
)

interface DownloadQueueManager {
    fun observeTasks(): Flow<List<DownloadTaskSummary>>

    suspend fun enqueueBook(
        bookId: String,
        selection: ChapterSelection,
    ): String

    suspend fun pauseTask(taskId: String)

    suspend fun resumeTask(taskId: String)

    suspend fun cancelTask(taskId: String)

    suspend fun retryFailedChapters(bookId: String): String?

    suspend fun recoverInterruptedTasksOnStartup()
}
