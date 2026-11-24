package dev.trinhhg.vapp.ui.extensions

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.trinhhg.vapp.downloader.db.ExtensionPackageEntity
import dev.trinhhg.vapp.downloader.db.RepositorySourceEntity
import dev.trinhhg.vapp.downloader.db.VappDatabase
import dev.trinhhg.vapp.engine.EngineErrorCode
import dev.trinhhg.vapp.engine.EngineException
import dev.trinhhg.vapp.engine.log.EngineLogger
import dev.trinhhg.vapp.engine.util.sha256Hex
import dev.trinhhg.vapp.engine.vbook.ExtensionManager
import dev.trinhhg.vapp.engine.vbook.InstalledExtension
import dev.trinhhg.vapp.engine.vbook.VBookExtensionItem
import dev.trinhhg.vapp.engine.vbook.VBookRepositoryClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okio.Path.Companion.toOkioPath
import org.koin.compose.koinInject
import java.io.File

internal fun formatEngineErrorForUser(err: Throwable): String {
    val eng = err as? EngineException
    if (eng?.code == EngineErrorCode.ENCRYPTED_UNSUPPORTED ||
        err.message?.contains("ENCRYPTED_UNSUPPORTED", ignoreCase = true) == true
    ) {
        return "Nguồn này dùng mã hóa độc quyền của VBook nên vapp không hỗ trợ; hãy dùng nguồn mở hoặc kho cộng đồng không mã hóa."
    }
    return eng?.let { "[${it.code}] ${it.message}" } ?: (err.message ?: "Đã xảy ra lỗi không xác định.")
}

internal fun isEncryptedError(err: Throwable): Boolean =
    (err as? EngineException)?.code == EngineErrorCode.ENCRYPTED_UNSUPPORTED ||
        err.message?.contains("ENCRYPTED_UNSUPPORTED", ignoreCase = true) == true

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExtensionManagerScreen(
    extensionManager: ExtensionManager = koinInject(),
    repoClient: VBookRepositoryClient = koinInject(),
    db: VappDatabase = koinInject(),
    onBack: () -> Unit,
    onOpenRepos: () -> Unit,
    onOpenDebugLogs: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repos by db.repositorySourceDao().observeAll().collectAsState(initial = emptyList())
    var installed by remember { mutableStateOf(extensionManager.listExtensions()) }
    var available by remember { mutableStateOf<List<VBookExtensionItem>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf<String?>(null) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var lastErrorIsEncrypted by remember { mutableStateOf(false) }
    var quickRepoInput by remember { mutableStateOf("") }
    var showQuickRepoBar by remember { mutableStateOf(false) }
    var configTarget by remember { mutableStateOf<InstalledExtension?>(null) }

    val zipPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            scope.launch {
                busy = true
                errorText = null
                lastErrorIsEncrypted = false
                runCatching {
                    val tempZip = File(context.cacheDir, resolveDisplayName(context, uri))
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        tempZip.outputStream().use { out -> input.copyTo(out) }
                    } ?: error("Không mở được tệp đã chọn")
                    val ext = withContext(Dispatchers.IO) { extensionManager.installFromLocalZip(tempZip.toOkioPath()) }
                    syncExtensionEntity(db, ext)
                    installed = extensionManager.listExtensions()
                    statusText = "Đã cài '${ext.name}' (v${ext.version}) từ máy."
                }.onFailure { err ->
                    lastErrorIsEncrypted = isEncryptedError(err)
                    errorText = formatEngineErrorForUser(err)
                    if (lastErrorIsEncrypted) showQuickRepoBar = true
                }
                busy = false
            }
        }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Quản lý Extension") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Quay lại") }
                },
                actions = {
                    IconButton(onClick = { zipPicker.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) }) {
                        Icon(Icons.Default.FolderZip, contentDescription = "Nhập .zip/.plugin")
                    }
                    IconButton(onClick = onOpenDebugLogs) {
                        Icon(Icons.Default.BugReport, contentDescription = "Debug Logs")
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        scope.launch {
                            busy = true
                            errorText = null
                            val urls =
                                repos
                                    .filter { it.enabled }
                                    .map { it.url }
                                    .ifEmpty { listOf(VBookRepositoryClient.DEFAULT_PLUGIN_INDEX_URL) }
                            runCatching {
                                val loaded =
                                    withContext(Dispatchers.IO) {
                                        urls.flatMap { url -> repoClient.fetchExtensions(url, forceRefresh = true) }
                                    }
                                available = loaded
                                statusText = "Đã tải ${loaded.size} nguồn từ ${urls.size} repository."
                            }.onFailure { err ->
                                lastErrorIsEncrypted = isEncryptedError(err)
                                errorText = formatEngineErrorForUser(err)
                            }
                            busy = false
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Text(" Tải kho nguồn")
                }
                OutlinedButton(onClick = onOpenRepos, modifier = Modifier.weight(1f)) {
                    Text("Repositories (${repos.size})")
                }
            }

            if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            statusText?.let { Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall) }
            errorText?.let { msg ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = msg,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = onOpenRepos) {
                                Text("Mở quản lý repo")
                            }
                            if (lastErrorIsEncrypted) {
                                OutlinedButton(
                                    onClick = {
                                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        val pasted =
                                            cm.primaryClip
                                                ?.getItemAt(0)
                                                ?.text
                                                ?.toString()
                                                ?.trim()
                                                .orEmpty()
                                        if (pasted.isNotEmpty()) quickRepoInput = pasted
                                        showQuickRepoBar = true
                                    },
                                ) {
                                    Icon(Icons.Default.ContentPaste, contentDescription = null)
                                    Text(" Dán link repo khác")
                                }
                            }
                        }
                    }
                }
            }

            if (showQuickRepoBar) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = quickRepoInput,
                        onValueChange = { quickRepoInput = it },
                        label = { Text("Dán URL repo không mã hóa (repository.json)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Button(
                        onClick = {
                            val clean = quickRepoInput.trim()
                            if (!clean.startsWith("http")) {
                                errorText = "URL repository cần bắt đầu bằng http:// hoặc https://"
                                return@Button
                            }
                            scope.launch {
                                db.repositorySourceDao().upsert(
                                    RepositorySourceEntity(id = clean.sha256Hex().take(20), url = clean, enabled = true),
                                )
                                quickRepoInput = ""
                                showQuickRepoBar = false
                                errorText = null
                                lastErrorIsEncrypted = false
                                busy = true
                                runCatching {
                                    val loaded = withContext(Dispatchers.IO) { repoClient.fetchExtensions(clean, forceRefresh = true) }
                                    available = loaded
                                    statusText = "Đã thêm repo mới và tải ${loaded.size} nguồn."
                                }.onFailure { err ->
                                    lastErrorIsEncrypted = isEncryptedError(err)
                                    errorText = formatEngineErrorForUser(err)
                                }
                                busy = false
                            }
                        },
                    ) { Text("Thêm") }
                }
            }

            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item { Text("Đã cài (${installed.size})", style = MaterialTheme.typography.titleMedium) }
                items(installed, key = { "inst_${it.id}" }) { ext ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("${ext.name} (v${ext.version})", fontWeight = FontWeight.SemiBold)
                                Text(ext.sourceUrl, style = MaterialTheme.typography.bodySmall)
                            }
                            Switch(
                                checked = ext.enabled,
                                onCheckedChange = { enabled ->
                                    extensionManager.setEnabled(ext.id, enabled)
                                    installed = extensionManager.listExtensions()
                                },
                            )
                            IconButton(onClick = { configTarget = ext }) {
                                Icon(Icons.Default.Settings, contentDescription = "Cấu hình")
                            }
                            IconButton(
                                onClick = {
                                    extensionManager.uninstall(ext.id)
                                    scope.launch { db.extensionPackageDao().deleteById(ext.id) }
                                    installed = extensionManager.listExtensions()
                                },
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = "Gỡ cài đặt")
                            }
                        }
                    }
                }

                if (available.isNotEmpty()) {
                    item {
                        Spacer(Modifier.height(6.dp))
                        Text("Từ kho VBook (${available.size})", style = MaterialTheme.typography.titleMedium)
                    }
                    items(available) { item ->
                        val isEncrypted = item.tag?.contains("encrypt", ignoreCase = true) == true
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("${item.name ?: "?"} (v${item.version ?: 1})", fontWeight = FontWeight.Medium)
                                    Text(item.source.orEmpty(), style = MaterialTheme.typography.bodySmall)
                                    if (isEncrypted) {
                                        Text(
                                            "Nguồn này dùng mã hóa độc quyền của VBook nên vapp không hỗ trợ; hãy dùng nguồn mở hoặc kho cộng đồng không mã hóa.",
                                            color = MaterialTheme.colorScheme.error,
                                            style = MaterialTheme.typography.labelSmall,
                                        )
                                    }
                                }
                                OutlinedButton(
                                    onClick = {
                                        scope.launch {
                                            busy = true
                                            errorText = null
                                            lastErrorIsEncrypted = false
                                            runCatching {
                                                val ext = withContext(Dispatchers.IO) { extensionManager.installFromRegistryItem(item) }
                                                syncExtensionEntity(db, ext)
                                                installed = extensionManager.listExtensions()
                                                statusText = "Đã cài '${ext.name}' (v${ext.version})."
                                            }.onFailure { err ->
                                                lastErrorIsEncrypted = isEncryptedError(err)
                                                errorText = formatEngineErrorForUser(err)
                                                if (lastErrorIsEncrypted) showQuickRepoBar = true
                                            }
                                            busy = false
                                        }
                                    },
                                ) { Text("Cài") }
                            }
                        }
                    }
                }
            }
        }
    }

    configTarget?.let { target ->
        ExtensionConfigSheet(
            extension = target,
            onSave = { updatedMap ->
                extensionManager.updateUserConfig(target.id, updatedMap)
                installed = extensionManager.listExtensions()
                configTarget = null
            },
            onDismiss = { configTarget = null },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RepoManagerScreen(
    db: VappDatabase = koinInject(),
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repos by db.repositorySourceDao().observeAll().collectAsState(initial = emptyList())
    var urlInput by remember { mutableStateOf("") }
    var feedbackMsg by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Quản lý Kho Nguồn (Repos)") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Quay lại") }
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
                    value = urlInput,
                    onValueChange = {
                        urlInput = it
                        feedbackMsg = null
                    },
                    label = { Text("URL repository.json / plugin.json") },
                    trailingIcon = {
                        IconButton(
                            onClick = {
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val pasted =
                                    cm.primaryClip
                                        ?.getItemAt(0)
                                        ?.text
                                        ?.toString()
                                        ?.trim()
                                        .orEmpty()
                                if (pasted.isNotEmpty()) {
                                    urlInput = pasted
                                    feedbackMsg = null
                                } else {
                                    feedbackMsg = "Clipboard đang trống."
                                }
                            },
                        ) {
                            Icon(Icons.Default.ContentPaste, contentDescription = "Dán URL")
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = {
                        val clean = urlInput.trim()
                        if (!clean.startsWith("http://") && !clean.startsWith("https://")) {
                            feedbackMsg = "URL hợp lệ phải bắt đầu bằng http:// hoặc https://"
                            return@Button
                        }
                        scope.launch {
                            db.repositorySourceDao().upsert(
                                RepositorySourceEntity(id = clean.sha256Hex().take(20), url = clean, enabled = true),
                            )
                            urlInput = ""
                            feedbackMsg = "Đã thêm kho nguồn mới."
                        }
                    },
                ) { Icon(Icons.Default.Add, contentDescription = "Thêm") }
            }
            feedbackMsg?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }

            OutlinedButton(
                onClick = {
                    scope.launch {
                        val def = VBookRepositoryClient.DEFAULT_PLUGIN_INDEX_URL
                        db.repositorySourceDao().upsert(
                            RepositorySourceEntity(id = def.sha256Hex().take(20), url = def, author = "Darkrai9x", enabled = true),
                        )
                    }
                },
            ) { Text("Thêm kho mặc định Darkrai9x/vbook-extensions") }

            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(repos, key = { it.id }) { repo ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(repo.url, style = MaterialTheme.typography.bodySmall)
                            }
                            Switch(
                                checked = repo.enabled,
                                onCheckedChange = { enabled ->
                                    scope.launch { db.repositorySourceDao().upsert(repo.copy(enabled = enabled)) }
                                },
                            )
                            IconButton(onClick = { scope.launch { db.repositorySourceDao().deleteById(repo.id) } }) {
                                Icon(Icons.Default.Delete, contentDescription = "Xóa repo")
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExtensionConfigSheet(
    extension: InstalledExtension,
    onSave: (Map<String, String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val editableMap =
        remember(extension.id) {
            mutableStateMapOf<String, String>().apply {
                putAll(extension.userConfig)
            }
        }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Cấu hình: ${extension.name}", style = MaterialTheme.typography.titleMedium)
            editableMap.keys.sorted().forEach { key ->
                OutlinedTextField(
                    value = editableMap[key].orEmpty(),
                    onValueChange = { editableMap[key] = it },
                    label = { Text(key) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Button(onClick = { onSave(editableMap.toMap()) }, modifier = Modifier.fillMaxWidth()) {
                Text("Lưu cấu hình")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugLogScreen(
    logger: EngineLogger = koinInject(),
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var text by remember { mutableStateOf(logger.formattedText()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Engine Debug Logs") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Quay lại") }
                },
                actions = {
                    IconButton(onClick = { text = logger.formattedText() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Làm mới")
                    }
                    IconButton(
                        onClick = {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("vapp_engine_logs", text))
                        },
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Sao chép")
                    }
                    IconButton(
                        onClick = {
                            logger.clear()
                            text = ""
                        },
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = "Xóa logs")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding).padding(12.dp)) {
            item {
                Text(
                    text = text.ifBlank { "(Chưa ghi nhận log nào)" },
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

internal suspend fun syncExtensionEntity(
    db: VappDatabase,
    ext: InstalledExtension,
) {
    db.extensionPackageDao().upsert(
        ExtensionPackageEntity(
            id = ext.id,
            name = ext.name,
            author = ext.author,
            version = ext.version,
            sourceUrl = ext.sourceUrl,
            urlRegex = ext.urlRegex,
            type = ext.type,
            locale = ext.locale,
            description = ext.description,
            scriptsDir = ext.scriptsDir,
            scriptMapJson = Json.encodeToString(ext.scriptMap),
            configSpecJson = ext.configSpecsJson,
            userConfigJson = Json.encodeToString(ext.userConfig),
            enabled = ext.enabled,
            updatedAt = System.currentTimeMillis(),
        ),
    )
}

private fun resolveDisplayName(
    context: Context,
    uri: android.net.Uri,
): String {
    var name = "plugin_${System.currentTimeMillis()}.zip"
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) {
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0) {
                val raw = c.getString(idx)
                if (!raw.isNullOrBlank()) name = raw
            }
        }
    }
    return name.replace(Regex("[^a-zA-Z0-9._-]"), "_")
}
