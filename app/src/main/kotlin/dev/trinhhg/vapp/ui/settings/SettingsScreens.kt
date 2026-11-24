package dev.trinhhg.vapp.ui.settings

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Cookie
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.trinhhg.vapp.backup.BackupExporter
import dev.trinhhg.vapp.backup.ValidatedBackupImporter
import dev.trinhhg.vapp.engine.net.CookiePersistence
import dev.trinhhg.vapp.settings.AppSettings
import dev.trinhhg.vapp.settings.AppSettingsRepository
import dev.trinhhg.vapp.ui.audiobook.VoiceModelManagerPlaceholder
import dev.trinhhg.vapp.webview.WebViewChallengeSolver
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SettingsScreen(
    settingsRepo: AppSettingsRepository = koinInject(),
    onOpenExtensions: () -> Unit,
    onOpenCookies: () -> Unit,
    onOpenBackupRestore: () -> Unit,
    onOpenDebugLogs: () -> Unit,
) {
    val settings by settingsRepo.settings.collectAsState(initial = AppSettings())
    val scope = rememberCoroutineScope()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 104.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("Cài đặt", style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold))
        }

        item {
            SettingsNavCard(
                "Quản lý Extension & Kho nguồn",
                "Cài từ GitHub hoặc file .zip/.plugin",
                Icons.Default.Extension,
                onOpenExtensions,
            )
        }
        item {
            SettingsNavCard(
                "Quản lý Cookie & Đăng nhập WebView",
                "Xem/xóa cookie hoặc vượt xác minh thủ công",
                Icons.Default.Cookie,
                onOpenCookies,
            )
        }
        item {
            SettingsNavCard(
                "Sao lưu & Khôi phục cục bộ",
                "Xuất / nhập vapp-backup.zip không cần server",
                Icons.Default.Backup,
                onOpenBackupRestore,
            )
        }
        item {
            SettingsNavCard("Engine Debug Logs", "Kiểm tra nhật ký Rhino JS / HTTP / WebView", Icons.Default.BugReport, onOpenDebugLogs)
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Thông số tải & mạng", style = MaterialTheme.typography.titleMedium)
                    Text("Số luồng tải đồng thời: ${settings.maxConcurrency}")
                    Slider(
                        value = settings.maxConcurrency.toFloat(),
                        onValueChange = { v -> scope.launch { settingsRepo.update { it.copy(maxConcurrency = v.toInt()) } } },
                        valueRange = 1f..6f,
                        steps = 4,
                    )
                    Text("Số lần tự thử lại mỗi chương: ${settings.maxRetriesPerChapter}")
                    Slider(
                        value = settings.maxRetriesPerChapter.toFloat(),
                        onValueChange = { v -> scope.launch { settingsRepo.update { it.copy(maxRetriesPerChapter = v.toInt()) } } },
                        valueRange = 0f..5f,
                        steps = 4,
                    )
                    Text("Ngôn ngữ giao diện")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = settings.uiLanguage == "vi",
                            onClick = { scope.launch { settingsRepo.update { it.copy(uiLanguage = "vi") } } },
                            label = { Text("Tiếng Việt") },
                        )
                        FilterChip(
                            selected = settings.uiLanguage == "en",
                            onClick = { scope.launch { settingsRepo.update { it.copy(uiLanguage = "en") } } },
                            label = { Text("English") },
                        )
                    }
                }
            }
        }

        item {
            // P5+ slot remains hidden/disabled per constitution & spec
            VoiceModelManagerPlaceholder(visible = false)
        }
    }
}

@Composable
private fun SettingsNavCard(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(icon, contentDescription = null)
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CookieManagerScreen(
    cookieStore: CookiePersistence = koinInject(),
    challengeSolver: WebViewChallengeSolver = koinInject(),
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var cookies by remember { mutableStateOf(cookieStore.allCookies()) }
    var loginUrl by remember { mutableStateOf("https://") }
    var status by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Quản lý Cookie") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Quay lại") }
                },
                actions = {
                    IconButton(
                        onClick = {
                            cookieStore.clearAll()
                            cookies = cookieStore.allCookies()
                        },
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = "Xóa tất cả cookie")
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = loginUrl,
                    onValueChange = { loginUrl = it },
                    label = { Text("URL nguồn cần đăng nhập") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = {
                        val url = loginUrl.trim()
                        if (!url.startsWith("http")) return@Button
                        scope.launch {
                            challengeSolver.openManualLogin(url)
                            cookies = cookieStore.allCookies()
                            status = "Đã đồng bộ cookie từ WebView cho $url"
                        }
                    },
                ) {
                    Icon(Icons.Default.OpenInBrowser, contentDescription = null)
                    Text(" Login")
                }
            }
            status?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }

            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(cookies.entries.toList(), key = { it.key }) { (host, raw) ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(host, fontWeight = FontWeight.SemiBold)
                                Text(raw, maxLines = 2, style = MaterialTheme.typography.bodySmall)
                            }
                            IconButton(
                                onClick = {
                                    cookieStore.clearHost(host)
                                    cookies = cookieStore.allCookies()
                                },
                            ) { Icon(Icons.Default.Delete, contentDescription = "Xóa host") }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupRestoreScreen(
    backupExporter: BackupExporter = koinInject(),
    backupImporter: ValidatedBackupImporter = koinInject(),
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var includeChapters by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    val createBackupLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            val destUri = res.data?.data
            if (res.resultCode == Activity.RESULT_OK && destUri != null) {
                scope.launch {
                    busy = true
                    errorMsg = null
                    val staged = File(context.cacheDir, "vapp_backup_tmp.zip")
                    backupExporter
                        .exportToZip(staged, includeChapters)
                        .onSuccess { manifest ->
                            runCatching {
                                context.contentResolver.openOutputStream(destUri)?.use { out ->
                                    staged.inputStream().use { it.copyTo(out) }
                                } ?: error("Không ghi được tệp sao lưu")
                                message =
                                    "Đã sao lưu ${manifest.bookCount} truyện, " +
                                    "${manifest.chapterCount} chương, ${manifest.extensionCount} extension."
                            }.onFailure { errorMsg = it.message }
                            staged.delete()
                        }.onFailure { errorMsg = it.message }
                    busy = false
                }
            }
        }

    val restoreBackupLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            scope.launch {
                busy = true
                errorMsg = null
                val tempIn = File(context.cacheDir, "vapp_restore_in.zip")
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        tempIn.outputStream().use { out -> input.copyTo(out) }
                    } ?: error("Không đọc được file sao lưu")
                    backupImporter.restoreFromZip(tempIn).getOrThrow()
                }.onSuccess { manifest ->
                    message =
                        "Đã khôi phục thành công ${manifest.bookCount} truyện, " +
                        "${manifest.chapterCount} chương, ${manifest.extensionCount} extension."
                }.onFailure { err ->
                    errorMsg = "Tệp sao lưu không hợp lệ (dữ liệu hiện tại được giữ nguyên): ${err.message}"
                }
                tempIn.delete()
                busy = false
            }
        }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sao lưu & Khôi phục") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Quay lại") }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Bao gồm nội dung chương đã tải")
                Switch(checked = includeChapters, onCheckedChange = { includeChapters = it })
            }
            if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            errorMsg?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            Button(
                onClick = {
                    val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                    val intent =
                        Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = "application/zip"
                            putExtra(Intent.EXTRA_TITLE, "vapp-backup-$ts.zip")
                        }
                    createBackupLauncher.launch(intent)
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Tạo bản sao lưu (.zip)") }

            OutlinedButton(
                onClick = { restoreBackupLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Khôi phục từ file sao lưu (.zip)") }
        }
    }
}
