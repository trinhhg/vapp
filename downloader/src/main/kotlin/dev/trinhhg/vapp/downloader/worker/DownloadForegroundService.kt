package dev.trinhhg.vapp.downloader.worker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import dev.trinhhg.vapp.downloader.api.DownloadQueueManager
import dev.trinhhg.vapp.downloader.api.DownloadTaskSummary
import dev.trinhhg.vapp.downloader.db.DownloadTaskStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext

/**
 * Foreground service (`dataSync`) displaying live batch download progress with Pause/Resume/Cancel controls.
 */
class DownloadForegroundService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observerJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        val manager = resolveQueueManager()
        val action = intent?.action
        val taskId = intent?.getStringExtra(EXTRA_TASK_ID)

        if (manager != null && taskId != null) {
            serviceScope.launch(Dispatchers.IO) {
                when (action) {
                    ACTION_PAUSE -> manager.pauseTask(taskId)
                    ACTION_RESUME -> manager.resumeTask(taskId)
                    ACTION_CANCEL -> manager.cancelTask(taskId)
                }
            }
        }

        val initialNotification = buildNotification(this, null)
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            initialNotification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )

        if (manager == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        if (observerJob == null) {
            observerJob =
                serviceScope.launch {
                    manager.observeTasks().collectLatest { tasks ->
                        val active =
                            tasks.firstOrNull {
                                it.status == DownloadTaskStatus.RUNNING ||
                                    it.status == DownloadTaskStatus.PENDING
                            }
                        if (active != null) {
                            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                            nm.notify(NOTIFICATION_ID, buildNotification(this@DownloadForegroundService, active))
                        } else {
                            ServiceCompat.stopForeground(this@DownloadForegroundService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                            stopSelf()
                        }
                    }
                }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        observerJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun resolveQueueManager(): DownloadQueueManager? =
        GlobalContext.getOrNull()?.let { koin ->
            runCatching { koin.get<DownloadQueueManager>() }.getOrNull()
        }

    companion object {
        const val CHANNEL_ID = "vapp_downloads"
        const val NOTIFICATION_ID = 2041
        const val ACTION_START = "dev.trinhhg.vapp.downloader.ACTION_START"
        const val ACTION_PAUSE = "dev.trinhhg.vapp.downloader.ACTION_PAUSE"
        const val ACTION_RESUME = "dev.trinhhg.vapp.downloader.ACTION_RESUME"
        const val ACTION_CANCEL = "dev.trinhhg.vapp.downloader.ACTION_CANCEL"
        const val EXTRA_TASK_ID = "task_id"

        fun start(context: Context) {
            runCatching {
                val intent = Intent(context, DownloadForegroundService::class.java).setAction(ACTION_START)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }

        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val nm = context.getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                    val channel =
                        NotificationChannel(
                            CHANNEL_ID,
                            "Tiến trình tải truyện",
                            NotificationManager.IMPORTANCE_LOW,
                        ).apply {
                            description = "Thông báo trạng thái tải chương truyện chạy nền"
                        }
                    nm.createNotificationChannel(channel)
                }
            }
        }

        fun buildNotification(
            context: Context,
            task: DownloadTaskSummary?,
        ): Notification {
            ensureChannel(context)
            val title = task?.bookTitle ?: "Đang tải truyện..."
            val progressText =
                if (task != null && task.totalCount > 0) {
                    "${task.completedCount}/${task.totalCount} chương" +
                        if (task.failedCount > 0) " (${task.failedCount} lỗi)" else ""
                } else {
                    "Đang chuẩn bị hàng đợi..."
                }

            val builder =
                NotificationCompat
                    .Builder(context, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.stat_sys_download)
                    .setContentTitle(title)
                    .setContentText(progressText)
                    .setOnlyAlertOnce(true)
                    .setOngoing(true)

            if (task != null && task.totalCount > 0) {
                builder.setProgress(task.totalCount, task.completedCount, false)
            } else {
                builder.setProgress(0, 0, true)
            }

            if (task != null) {
                val toggleAction =
                    if (task.status == DownloadTaskStatus.PAUSED) {
                        serviceActionIntent(context, ACTION_RESUME, task.taskId, 101)
                    } else {
                        serviceActionIntent(context, ACTION_PAUSE, task.taskId, 102)
                    }
                val toggleLabel = if (task.status == DownloadTaskStatus.PAUSED) "Tiếp tục" else "Tạm dừng"
                val cancelAction = serviceActionIntent(context, ACTION_CANCEL, task.taskId, 103)
                builder.addAction(0, toggleLabel, toggleAction)
                builder.addAction(0, "Hủy", cancelAction)
            }
            return builder.build()
        }

        private fun serviceActionIntent(
            context: Context,
            action: String,
            taskId: String,
            requestCode: Int,
        ): PendingIntent {
            val intent =
                Intent(context, DownloadForegroundService::class.java).apply {
                    this.action = action
                    putExtra(EXTRA_TASK_ID, taskId)
                }
            return PendingIntent.getService(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
    }
}
