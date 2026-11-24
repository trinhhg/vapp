package dev.trinhhg.vapp.ui.downloads

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.trinhhg.vapp.downloader.api.DownloadQueueManager
import dev.trinhhg.vapp.downloader.api.DownloadTaskSummary
import dev.trinhhg.vapp.downloader.db.DownloadTaskStatus
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@Composable
fun DownloadQueueScreen(
    queueManager: DownloadQueueManager = koinInject(),
    onOpenBookDetail: (String) -> Unit,
) {
    val tasks by queueManager.observeTasks().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()

    val failedTasks = tasks.filter { it.failedCount > 0 || it.status == DownloadTaskStatus.FAILED }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Hàng đợi tải", style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold))
            if (failedTasks.isNotEmpty()) {
                Button(
                    onClick = {
                        scope.launch {
                            failedTasks.map { it.bookId }.distinct().forEach { bId ->
                                queueManager.retryFailedChapters(bId)
                            }
                        }
                    },
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Text(" Thử lại lỗi")
                }
            }
        }

        if (tasks.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(Icons.Default.DownloadDone, contentDescription = null, tint = MaterialTheme.colorScheme.outline)
                Spacer(Modifier.height(8.dp))
                Text("Chưa có tác vụ tải nào.", style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(tasks, key = { it.taskId }) { task ->
                    DownloadTaskCard(
                        task = task,
                        onOpenBook = { onOpenBookDetail(task.bookId) },
                        onPause = { scope.launch { queueManager.pauseTask(task.taskId) } },
                        onResume = { scope.launch { queueManager.resumeTask(task.taskId) } },
                        onCancel = { scope.launch { queueManager.cancelTask(task.taskId) } },
                        onRetryBook = { scope.launch { queueManager.retryFailedChapters(task.bookId) } },
                    )
                }
            }
        }
    }
}

@Composable
private fun DownloadTaskCard(
    task: DownloadTaskSummary,
    onOpenBook: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onRetryBook: () -> Unit,
) {
    val progress =
        if (task.totalCount > 0) (task.completedCount.toFloat() / task.totalCount.toFloat()).coerceIn(0f, 1f) else 0f

    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenBook)) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(task.bookTitle, fontWeight = FontWeight.SemiBold)
                    Text(
                        text =
                            "${task.status.name} • ${task.completedCount}/${task.totalCount} chương" +
                                if (task.failedCount > 0) " (${task.failedCount} lỗi)" else "",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Row {
                    if (task.status == DownloadTaskStatus.RUNNING || task.status == DownloadTaskStatus.PENDING) {
                        IconButton(onClick = onPause) {
                            Icon(Icons.Default.Pause, contentDescription = "Tạm dừng")
                        }
                    } else if (task.status == DownloadTaskStatus.PAUSED || task.status == DownloadTaskStatus.FAILED) {
                        IconButton(onClick = onResume) {
                            Icon(Icons.Default.PlayArrow, contentDescription = "Tiếp tục")
                        }
                    }
                    if (task.status != DownloadTaskStatus.COMPLETED && task.status != DownloadTaskStatus.CANCELLED) {
                        IconButton(onClick = onCancel) {
                            Icon(Icons.Default.Cancel, contentDescription = "Hủy")
                        }
                    }
                }
            }

            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())

            task.lastError?.let { err ->
                Text(err, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = onRetryBook) { Text("Tải lại chương lỗi") }
            }
        }
    }
}
