package dev.trinhhg.vapp.downloader.worker

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import dev.trinhhg.vapp.downloader.api.DownloaderConfig
import dev.trinhhg.vapp.downloader.db.DownloadTaskStatus
import dev.trinhhg.vapp.downloader.queue.ChapterDownloadRunner
import dev.trinhhg.vapp.engine.log.EngineLogger
import org.koin.core.context.GlobalContext

/**
 * WorkManager worker executing a queued [ChapterDownloadRunner] task with background-safe foreground info.
 */
class DownloadWorker(
    appContext: Context,
    params: WorkerParameters,
    private val runnerOverride: ChapterDownloadRunner? = null,
    private val configProviderOverride: (suspend () -> DownloaderConfig)? = null,
    private val loggerOverride: EngineLogger? = null,
) : CoroutineWorker(appContext, params) {
    constructor(appContext: Context, params: WorkerParameters) : this(
        appContext = appContext,
        params = params,
        runnerOverride = null,
        configProviderOverride = null,
        loggerOverride = null,
    )

    override suspend fun doWork(): Result {
        val taskId = inputData.getString(KEY_TASK_ID) ?: return Result.failure()
        val koin = GlobalContext.getOrNull()
        val runner = runnerOverride ?: koin?.getOrNull<ChapterDownloadRunner>() ?: return Result.retry()
        val log = loggerOverride ?: koin?.getOrNull<EngineLogger>()
        val configProvider =
            configProviderOverride
                ?: koin?.getOrNull<DownloaderConfigProvider>()?.let { p -> suspend { p.currentConfig() } }
                ?: suspend { DownloaderConfig() }

        runCatching { setForeground(createForegroundInfo(null)) }

        val config = configProvider()
        return try {
            val outcome =
                runner.runTask(taskId, config) { completed, failed, total, _ ->
                    val statusText = "$completed/$total chương" + if (failed > 0) " ($failed lỗi)" else ""
                    runCatching { setForeground(createForegroundInfo(statusText)) }
                }
            when (outcome.finalStatus) {
                DownloadTaskStatus.COMPLETED,
                DownloadTaskStatus.PAUSED,
                DownloadTaskStatus.CANCELLED,
                -> Result.success()
                DownloadTaskStatus.FAILED -> Result.failure()
                DownloadTaskStatus.PENDING,
                DownloadTaskStatus.RUNNING,
                -> Result.retry()
            }
        } catch (t: Throwable) {
            log?.error("DownloadWorker failed for task $taskId", t, tag = "DownloadWorker")
            Result.retry()
        }
    }

    private fun createForegroundInfo(text: String?): ForegroundInfo {
        val notification =
            androidx.core.app.NotificationCompat
                .Builder(applicationContext, DownloadForegroundService.CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("vapp — Tải truyện")
                .setContentText(text ?: "Đang tải chương truyện...")
                .setOngoing(true)
                .build()
        DownloadForegroundService.ensureChannel(applicationContext)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                DownloadForegroundService.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(DownloadForegroundService.NOTIFICATION_ID, notification)
        }
    }

    fun interface DownloaderConfigProvider {
        suspend fun currentConfig(): DownloaderConfig
    }

    companion object {
        const val KEY_TASK_ID = "task_id"
    }
}

/**
 * Explicit WorkManager [WorkerFactory] wiring [DownloadWorker] cleanly from Koin or test fakes.
 */
class VappWorkerFactory(
    private val runner: ChapterDownloadRunner,
    private val configProvider: DownloadWorker.DownloaderConfigProvider,
    private val log: EngineLogger,
) : WorkerFactory() {
    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters,
    ): ListenableWorker? =
        if (workerClassName == DownloadWorker::class.java.name) {
            DownloadWorker(
                appContext = appContext,
                params = workerParameters,
                runnerOverride = runner,
                configProviderOverride = { configProvider.currentConfig() },
                loggerOverride = log,
            )
        } else {
            null
        }
}
