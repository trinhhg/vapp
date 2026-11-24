package dev.trinhhg.vapp.ui.explore

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.trinhhg.vapp.downloader.BookLibraryRepository
import dev.trinhhg.vapp.downloader.api.ChapterSelection
import dev.trinhhg.vapp.downloader.api.DownloadQueueManager
import dev.trinhhg.vapp.downloader.db.VappDatabase
import dev.trinhhg.vapp.engine.api.BookSearchItem
import dev.trinhhg.vapp.engine.api.ExtensionExecutor
import dev.trinhhg.vapp.engine.api.ExtensionNavTab
import dev.trinhhg.vapp.engine.vbook.ExtensionManager
import dev.trinhhg.vapp.ui.extensions.formatEngineErrorForUser
import dev.trinhhg.vapp.ui.extensions.syncExtensionEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

@Composable
fun ExploreSourcesScreen(
    extensionManager: ExtensionManager = koinInject(),
    onOpenCatalog: (String) -> Unit,
    onOpenGlobalSearch: () -> Unit,
    onOpenExtensionManager: () -> Unit,
) {
    val extensions = remember { extensionManager.listExtensions().filter { it.enabled } }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Khám phá", style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold))
            Row {
                IconButton(onClick = onOpenGlobalSearch) {
                    Icon(Icons.Default.Search, contentDescription = "Tìm kiếm toàn nguồn")
                }
                IconButton(onClick = onOpenExtensionManager) {
                    Icon(Icons.Default.Extension, contentDescription = "Quản lý Extension")
                }
            }
        }

        if (extensions.isEmpty()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Chưa cài đặt nguồn truyện nào", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Mở Quản lý Extension để cài nguồn VBook từ repository hoặc nhập file .zip/.plugin.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Button(onClick = onOpenExtensionManager) { Text("Mở Quản lý Extension") }
                }
            }
        } else {
            OutlinedButton(onClick = onOpenGlobalSearch, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Search, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Tìm kiếm trên tất cả ${extensions.size} nguồn")
            }

            LazyColumn(
                contentPadding = PaddingValues(bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(extensions, key = { it.id }) { ext ->
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable { onOpenCatalog(ext.id) },
                    ) {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("${ext.name} (v${ext.version})", fontWeight = FontWeight.SemiBold)
                            Text("${ext.sourceUrl} • ${ext.locale}", style = MaterialTheme.typography.bodySmall)
                            if (ext.description.isNotBlank()) {
                                Text(ext.description, maxLines = 2, style = MaterialTheme.typography.bodySmall)
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
fun SourceCatalogScreen(
    extensionId: String,
    extensionManager: ExtensionManager = koinInject(),
    executor: ExtensionExecutor = koinInject(),
    libraryRepo: BookLibraryRepository = koinInject(),
    db: VappDatabase = koinInject(),
    onBack: () -> Unit,
    onOpenBookDetail: (String) -> Unit,
) {
    val ext = remember(extensionId) { extensionManager.getExtension(extensionId) }
    val scope = rememberCoroutineScope()
    var tabs by remember { mutableStateOf<List<ExtensionNavTab>>(emptyList()) }
    var activeTab by remember { mutableStateOf<ExtensionNavTab?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var books by remember { mutableStateOf<List<BookSearchItem>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    fun loadTab(tab: ExtensionNavTab) {
        scope.launch {
            busy = true
            errorMsg = null
            activeTab = tab
            val res = executor.browse(extensionId, tab.script, tab.input, "1")
            res
                .onSuccess { books = it.items }
                .onFailure { errorMsg = it.message ?: "Lỗi tải danh mục" }
            busy = false
        }
    }

    LaunchedEffect(extensionId) {
        busy = true
        val homeTabs = executor.fetchHomeTabs(extensionId).getOrDefault(emptyList())
        val genreTabs = executor.fetchGenres(extensionId).getOrDefault(emptyList())
        tabs = (homeTabs + genreTabs)
        val first = tabs.firstOrNull()
        if (first != null) {
            loadTab(first)
        } else {
            busy = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(ext?.name ?: extensionId) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Quay lại")
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    label = { Text("Tìm trong ${ext?.name ?: "nguồn"}") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = {
                        if (searchQuery.isBlank()) return@Button
                        scope.launch {
                            busy = true
                            errorMsg = null
                            executor
                                .search(extensionId, searchQuery.trim(), "1")
                                .onSuccess { books = it.items }
                                .onFailure { errorMsg = it.message }
                            busy = false
                        }
                    },
                ) { Text("Tìm") }
            }

            if (tabs.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(tabs) { tab ->
                        FilterChip(
                            selected = tab == activeTab,
                            onClick = { loadTab(tab) },
                            label = { Text(tab.title) },
                        )
                    }
                }
            }

            if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            errorMsg?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

            BookSearchResultsList(
                items = books,
                onSelectBook = { item ->
                    scope.launch {
                        busy = true
                        errorMsg = null
                        val metaRes = executor.fetchBookDetail(extensionId, item.link)
                        if (metaRes.isFailure) {
                            errorMsg = metaRes.exceptionOrNull()?.message
                            busy = false
                            return@launch
                        }
                        val meta = metaRes.getOrThrow()
                        val toc = executor.fetchTableOfContents(extensionId, meta.url).getOrDefault(emptyList())
                        ext?.let { syncExtensionEntity(db, it) }
                        val saved = libraryRepo.saveBookAndMergeToc(extensionId, meta, toc, inLibrary = false)
                        busy = false
                        onOpenBookDetail(saved.book.id)
                    }
                },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlobalSearchScreen(
    initialQuery: String = "",
    extensionManager: ExtensionManager = koinInject(),
    executor: ExtensionExecutor = koinInject(),
    libraryRepo: BookLibraryRepository = koinInject(),
    db: VappDatabase = koinInject(),
    onBack: () -> Unit,
    onOpenBookDetail: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val enabledExts = remember { extensionManager.listExtensions().filter { it.enabled } }
    var query by remember { mutableStateOf(initialQuery) }
    var busy by remember { mutableStateOf(false) }
    var hits by remember { mutableStateOf<List<Pair<String, BookSearchItem>>>(emptyList()) }
    var errors by remember { mutableStateOf<List<String>>(emptyList()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Tìm kiếm đa nguồn") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Quay lại")
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Tên truyện hoặc tác giả") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = {
                        if (query.isBlank()) return@Button
                        scope.launch {
                            busy = true
                            errors = emptyList()
                            val gathered = ArrayList<Pair<String, BookSearchItem>>()
                            val errs = ArrayList<String>()
                            val jobs =
                                enabledExts.map { ext ->
                                    async {
                                        val res = executor.search(ext.id, query.trim(), "1")
                                        ext to res
                                    }
                                }
                            jobs.awaitAll().forEach { (ext, res) ->
                                res
                                    .onSuccess { paged ->
                                        paged.items.forEach { item -> gathered.add(ext.id to item) }
                                    }.onFailure { err ->
                                        errs.add("${ext.name}: ${err.message}")
                                    }
                            }
                            hits = gathered
                            errors = errs
                            busy = false
                        }
                    },
                ) { Text("Tìm") }
            }
            if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            if (errors.isNotEmpty()) {
                Text(errors.joinToString("\n"), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(hits) { (extId, item) ->
                    val matchedExt = enabledExts.firstOrNull { it.id == extId }
                    val extName = matchedExt?.name ?: extId
                    SearchBookRow(
                        item = item,
                        sourceBadge = extName,
                        onClick = {
                            scope.launch {
                                busy = true
                                val meta = executor.fetchBookDetail(extId, item.link).getOrNull() ?: return@launch.also { busy = false }
                                val toc = executor.fetchTableOfContents(extId, meta.url).getOrDefault(emptyList())
                                matchedExt?.let { syncExtensionEntity(db, it) }
                                val saved = libraryRepo.saveBookAndMergeToc(extId, meta, toc, inLibrary = false)
                                busy = false
                                onOpenBookDetail(saved.book.id)
                            }
                        },
                    )
                }
            }
        }
    }
}

/**
 * Bottom sheet for pasting one or multiple book URLs, auto-matching VBook extensions,
 * and optionally enqueuing batch background downloads right away.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddUrlSheet(
    extensionManager: ExtensionManager = koinInject(),
    executor: ExtensionExecutor = koinInject(),
    libraryRepo: BookLibraryRepository = koinInject(),
    queueManager: DownloadQueueManager = koinInject(),
    db: VappDatabase = koinInject(),
    onDismiss: () -> Unit,
    onBookAdded: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var urlsText by remember { mutableStateOf("") }
    var enqueueDownload by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var statusMsg by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Thêm truyện từ URL", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = urlsText,
                onValueChange = { urlsText = it },
                label = { Text("Dán 1 hoặc nhiều URL (mỗi dòng 1 link)") },
                minLines = 3,
                maxLines = 6,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = enqueueDownload, onCheckedChange = { enqueueDownload = it })
                Text("Tự động tải toàn bộ chương sau khi thêm")
            }
            if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            statusMsg?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
            Button(
                onClick = {
                    val urls = urlsText.lines().map { it.trim() }.filter { it.startsWith("http") }
                    if (urls.isEmpty()) {
                        statusMsg = "Vui lòng nhập URL hợp lệ bắt đầu bằng http/https."
                        return@Button
                    }
                    scope.launch {
                        busy = true
                        var lastBookId: String? = null
                        val logMsgs = ArrayList<String>()
                        for (url in urls) {
                            val ext =
                                extensionManager.matchByUrl(url)
                                    ?: withContext(Dispatchers.IO) { extensionManager.findAndInstallByUrl(url) }
                            if (ext == null) {
                                logMsgs.add("Không tìm thấy extension cho $url")
                                continue
                            }
                            val meta =
                                executor.fetchBookDetail(ext.id, url).getOrElse { err ->
                                    logMsgs.add("$url: ${formatEngineErrorForUser(err)}")
                                    null
                                } ?: continue
                            val toc = executor.fetchTableOfContents(ext.id, meta.url).getOrDefault(emptyList())
                            syncExtensionEntity(db, ext)
                            val saved = libraryRepo.saveBookAndMergeToc(ext.id, meta, toc, inLibrary = true)
                            if (enqueueDownload && toc.isNotEmpty()) {
                                queueManager.enqueueBook(saved.book.id, ChapterSelection.All)
                            }
                            lastBookId = saved.book.id
                            logMsgs.add("Đã thêm '${saved.book.title}' (${toc.size} chương)")
                        }
                        busy = false
                        statusMsg = logMsgs.joinToString("\n")
                        if (urls.size == 1 && lastBookId != null && !enqueueDownload) {
                            onDismiss()
                            onBookAdded(lastBookId)
                        }
                    }
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Thêm vào Tủ sách") }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun BookSearchResultsList(
    items: List<BookSearchItem>,
    onSelectBook: (BookSearchItem) -> Unit,
) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(items) { item ->
            SearchBookRow(item = item, sourceBadge = null, onClick = { onSelectBook(item) })
        }
    }
}

@Composable
private fun SearchBookRow(
    item: BookSearchItem,
    sourceBadge: String?,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(),
    ) {
        Row(modifier = Modifier.padding(10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (!item.cover.isNullOrBlank()) {
                AsyncImage(
                    model = item.cover,
                    contentDescription = item.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(width = 56.dp, height = 78.dp).clip(RoundedCornerShape(6.dp)),
                )
            } else {
                Box(modifier = Modifier.size(width = 56.dp, height = 78.dp))
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(item.name, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                sourceBadge?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
                if (item.description.isNotBlank()) {
                    Text(item.description, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
