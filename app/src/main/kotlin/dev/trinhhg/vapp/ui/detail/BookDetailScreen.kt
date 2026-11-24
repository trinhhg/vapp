package dev.trinhhg.vapp.ui.detail

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.BookmarkRemove
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
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
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.trinhhg.vapp.downloader.BookLibraryRepository
import dev.trinhhg.vapp.downloader.api.ChapterSelection
import dev.trinhhg.vapp.downloader.api.DownloadQueueManager
import dev.trinhhg.vapp.downloader.db.BookEntity
import dev.trinhhg.vapp.downloader.db.ChapterDownloadStatus
import dev.trinhhg.vapp.downloader.db.ChapterEntity
import dev.trinhhg.vapp.downloader.db.VappDatabase
import dev.trinhhg.vapp.downloader.storage.ChapterFileStore
import dev.trinhhg.vapp.downloader.storage.CoverStore
import dev.trinhhg.vapp.engine.api.ExtensionExecutor
import dev.trinhhg.vapp.export.SafExportSaver
import dev.trinhhg.vapp.exporter.api.BookExportMetadata
import dev.trinhhg.vapp.exporter.api.BookExporter
import dev.trinhhg.vapp.exporter.api.ChapterExportInput
import dev.trinhhg.vapp.exporter.api.CleanupRuleSpec
import dev.trinhhg.vapp.exporter.api.ExportFormat
import dev.trinhhg.vapp.exporter.api.ExportRequest
import dev.trinhhg.vapp.settings.AppSettings
import dev.trinhhg.vapp.settings.AppSettingsRepository
import dev.trinhhg.vapp.settings.DefaultExportFormat
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookDetailScreen(
    bookId: String,
    libraryRepo: BookLibraryRepository = koinInject(),
    coverStore: CoverStore = koinInject(),
    executor: ExtensionExecutor = koinInject(),
    queueManager: DownloadQueueManager = koinInject(),
    onBack: () -> Unit,
    onReadChapter: (bookId: String, chapterIndex: Int) -> Unit,
    onListenAudiobook: (bookId: String, chapterIndex: Int) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val chapters by libraryRepo.observeChapters(bookId).collectAsState(initial = emptyList())
    var book by remember { mutableStateOf<BookEntity?>(null) }
    var busy by remember { mutableStateOf(false) }
    var statusMsg by remember { mutableStateOf<String?>(null) }
    var showPickerSheet by remember { mutableStateOf(false) }
    var showExportSheet by remember { mutableStateOf(false) }

    LaunchedEffect(bookId, chapters.size) {
        book = libraryRepo.getBook(bookId)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(book?.title ?: "Chi tiết truyện", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Quay lại")
                    }
                },
                actions = {
                    book?.let { b ->
                        IconButton(
                            onClick = {
                                scope.launch {
                                    libraryRepo.setInLibrary(b.id, !b.inLibrary)
                                    book = libraryRepo.getBook(b.id)
                                }
                            },
                        ) {
                            Icon(
                                if (b.inLibrary) Icons.Default.BookmarkRemove else Icons.Default.BookmarkAdd,
                                contentDescription = if (b.inLibrary) "Bỏ khỏi Tủ sách" else "Lưu vào Tủ sách",
                            )
                        }
                    }
                    IconButton(
                        onClick = {
                            val b = book ?: return@IconButton
                            val extId = b.extensionId ?: return@IconButton
                            scope.launch {
                                busy = true
                                val tocRes = executor.fetchTableOfContents(extId, b.sourceUrl)
                                tocRes
                                    .onSuccess { toc ->
                                        val added = libraryRepo.mergeFetchedChapters(b.id, toc)
                                        book = libraryRepo.getBook(b.id)
                                        statusMsg = "Đã cập nhật mục lục (+$added chương mới)."
                                    }.onFailure { err ->
                                        statusMsg = err.message
                                    }
                                busy = false
                            }
                        },
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Cập nhật chương mới")
                    }
                },
            )
        },
    ) { padding ->
        val currentBook = book
        if (currentBook == null) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Đang tải thông tin truyện...")
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                BookDetailHeader(currentBook, coverStore)
            }
            item {
                if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                statusMsg?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
                BookDetailActionButtons(
                    book = currentBook,
                    onRead = { onReadChapter(currentBook.id, currentBook.lastReadChapterIndex) },
                    onListen = { onListenAudiobook(currentBook.id, currentBook.lastReadChapterIndex) },
                    onDownloadAll = {
                        scope.launch {
                            queueManager.enqueueBook(currentBook.id, ChapterSelection.All)
                            statusMsg = "Đã thêm tác vụ tải tất cả vào hàng đợi."
                        }
                    },
                    onPickChapters = { showPickerSheet = true },
                    onExport = { showExportSheet = true },
                )
            }
            if (currentBook.synopsis.isNotBlank()) {
                item {
                    Text("Giới thiệu", style = MaterialTheme.typography.titleMedium)
                    Text(currentBook.synopsis, style = MaterialTheme.typography.bodySmall, maxLines = 6, overflow = TextOverflow.Ellipsis)
                }
            }
            item {
                Text("Danh sách chương (${chapters.size})", style = MaterialTheme.typography.titleMedium)
            }
            items(chapters, key = { it.id }) { ch ->
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable { onReadChapter(currentBook.id, ch.orderIndex) }
                            .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(ch.title, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val badge =
                        when (ch.downloadStatus) {
                            ChapterDownloadStatus.COMPLETED -> "Đã tải"
                            ChapterDownloadStatus.FAILED -> "Lỗi"
                            ChapterDownloadStatus.QUEUED -> "Đợi"
                            ChapterDownloadStatus.DOWNLOADING -> "Đang tải"
                            else -> ""
                        }
                    if (badge.isNotEmpty()) {
                        Text(badge, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }

    if (showPickerSheet) {
        ChapterPickerSheet(
            chapters = chapters,
            onEnqueue = { selection ->
                showPickerSheet = false
                scope.launch {
                    queueManager.enqueueBook(bookId, selection)
                    statusMsg = "Đã đưa các chương đã chọn vào hàng đợi tải."
                }
            },
            onDismiss = { showPickerSheet = false },
        )
    }

    val activeBook = book
    if (showExportSheet && activeBook != null) {
        ExportSheet(
            book = activeBook,
            chapters = chapters,
            onDismiss = { showExportSheet = false },
        )
    }
}

@Composable
private fun BookDetailHeader(
    book: BookEntity,
    coverStore: CoverStore,
) {
    val coverModel = coverStore.resolveCoverPath(book.coverLocalPath)?.toFile() ?: book.coverUrl
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        if (coverModel != null) {
            AsyncImage(
                model = coverModel,
                contentDescription = book.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(width = 104.dp, height = 148.dp).clip(RoundedCornerShape(10.dp)),
            )
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(book.title, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
            if (book.author.isNotBlank()) Text("Tác giả: ${book.author}", style = MaterialTheme.typography.bodyMedium)
            Text(
                text =
                    "Đã tải ${book.downloadedChapters}/${book.totalChapters} chương • " +
                        if (book.ongoing) "Đang ra" else "Hoàn thành",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun BookDetailActionButtons(
    book: BookEntity,
    onRead: () -> Unit,
    onListen: () -> Unit,
    onDownloadAll: () -> Unit,
    onPickChapters: () -> Unit,
    onExport: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onRead, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.MenuBook, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(if (book.lastReadChapterIndex > 0) " Đọc tiếp" else " Đọc ngay")
            }
            OutlinedButton(onClick = onListen, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.Headphones, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(" Nghe TTS")
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onDownloadAll, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(" Tải hết")
            }
            OutlinedButton(onClick = onPickChapters, modifier = Modifier.weight(1f)) {
                Text("Chọn chương")
            }
            OutlinedButton(onClick = onExport, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.IosShare, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(" Xuất")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChapterPickerSheet(
    chapters: List<ChapterEntity>,
    onEnqueue: (ChapterSelection) -> Unit,
    onDismiss: () -> Unit,
) {
    var rangeSpec by remember { mutableStateOf("1-${chapters.size.coerceAtLeast(1)}") }
    var search by remember { mutableStateOf("") }
    val selectedMap = remember { androidx.compose.runtime.mutableStateMapOf<String, Boolean>() }

    val filtered =
        remember(chapters, search) {
            if (search.isBlank()) chapters else chapters.filter { it.title.contains(search.trim(), ignoreCase = true) }
        }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Chọn chương cần tải", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = rangeSpec,
                onValueChange = { rangeSpec = it },
                label = { Text("Dải chương (vd: 1-100, 105)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = { onEnqueue(ChapterSelection.Spec(rangeSpec)) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Tải theo dải '$rangeSpec'") }

            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                label = { Text("Lọc tên chương") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            LazyColumn(modifier = Modifier.height(220.dp)) {
                items(filtered, key = { it.id }) { ch ->
                    val checked = selectedMap[ch.id] == true
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (checked) selectedMap.remove(ch.id) else selectedMap[ch.id] = true
                                },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = checked, onCheckedChange = {
                            if (it) selectedMap[ch.id] = true else selectedMap.remove(ch.id)
                        })
                        Text(ch.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            OutlinedButton(
                onClick = {
                    val indices = chapters.filter { selectedMap[it.id] == true }.map { it.orderIndex }
                    if (indices.isNotEmpty()) onEnqueue(ChapterSelection.Indices(indices))
                },
                enabled = selectedMap.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Tải ${selectedMap.size} chương đã tick") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportSheet(
    book: BookEntity,
    chapters: List<ChapterEntity>,
    fileStore: ChapterFileStore = koinInject(),
    coverStore: CoverStore = koinInject(),
    db: VappDatabase = koinInject(),
    exporter: BookExporter = koinInject(),
    safSaver: SafExportSaver = koinInject(),
    settingsRepo: AppSettingsRepository = koinInject(),
    onDismiss: () -> Unit,
) {
    val settings by settingsRepo.settings.collectAsState(initial = AppSettings())
    val scope = rememberCoroutineScope()
    val downloaded =
        remember(chapters) {
            chapters.filter {
                it.downloadStatus == ChapterDownloadStatus.COMPLETED && !it.contentPath.isNullOrBlank()
            }
        }

    var format by remember(settings.defaultExportFormat) {
        mutableStateOf(
            when (settings.defaultExportFormat) {
                DefaultExportFormat.EPUB2 -> ExportFormat.EPUB_2
                DefaultExportFormat.TXT -> ExportFormat.TXT
                DefaultExportFormat.EPUB3 -> ExportFormat.EPUB_3
            },
        )
    }
    var fromChap by remember { mutableStateOf("1") }
    var toChap by remember { mutableStateOf(downloaded.size.coerceAtLeast(1).toString()) }
    var includeCover by remember(settings.includeCover) { mutableStateOf(settings.includeCover) }
    var includeImages by remember(settings.includeImages) { mutableStateOf(settings.includeImages) }
    var autoSplit by remember(settings.autoSplitVolumes) { mutableStateOf(settings.autoSplitVolumes) }
    var compressionLevel by remember(settings.zipCompressionLevel) { mutableFloatStateOf(settings.zipCompressionLevel.toFloat()) }
    var busy by remember { mutableStateOf(false) }
    var progressMsg by remember { mutableStateOf<String?>(null) }
    var pendingSafSource by remember { mutableStateOf<File?>(null) }
    val context = LocalContext.current

    val safLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            val src = pendingSafSource
            val uri = res.data?.data
            if (res.resultCode == Activity.RESULT_OK && uri != null && src != null) {
                safSaver.copyToDocumentUri(src, uri)
                progressMsg = "Đã lưu tệp ra vị trí SAF đã chọn."
            }
        }

    fun runExport(afterExport: (File) -> Unit) {
        if (downloaded.isEmpty()) {
            progressMsg = "Chưa có chương nào được tải offline để xuất."
            return
        }
        scope.launch {
            busy = true
            progressMsg = "Đang chuẩn bị xuất..."
            val ext = if (format == ExportFormat.TXT) "txt" else "epub"
            val safeTitle =
                book.title
                    .replace(Regex("[^a-zA-Z0-9._\\- ]"), "_")
                    .trim()
                    .ifBlank { "book" }
            val staged = File(safSaver.stagingDir(), "$safeTitle.$ext")
            val coverBytes =
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    coverStore
                        .resolveCoverPath(book.coverLocalPath)
                        ?.toFile()
                        ?.takeIf { it.exists() }
                        ?.readBytes()
                }
            val rules =
                db.cleanupRuleDao().listEnabled().map {
                    CleanupRuleSpec(it.pattern, it.replacement, it.isRegex, it.enabled)
                }
            val inputs =
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    downloaded.mapNotNull { ch ->
                        if (!fileStore.hasValidChapterFile(ch.contentPath)) return@mapNotNull null
                        ChapterExportInput(
                            orderIndex = ch.orderIndex,
                            title = ch.title,
                            partName = ch.partName,
                            htmlProvider = { fileStore.readChapterHtml(ch.contentPath).orEmpty() },
                        )
                    }
                }
            val rangeSpec = "${fromChap.trim()}-${toChap.trim()}"
            val result =
                exporter.export(
                    ExportRequest(
                        book = BookExportMetadata(book.id, book.title, book.author, book.synopsis, coverBytes = coverBytes),
                        chapters = inputs,
                        format = format,
                        includeCover = includeCover,
                        includeImages = includeImages,
                        autoSplitVolumes = autoSplit,
                        compressionLevel = compressionLevel.toInt().coerceIn(1, 9),
                        chapterRangeSpec = rangeSpec,
                        cleanupRules = rules,
                        outputFilePath = staged.absolutePath,
                    ),
                ) { pct, msg ->
                    progressMsg = "$pct% — $msg"
                }
            busy = false
            result
                .onSuccess { out ->
                    val primary = out.outputFiles.firstOrNull()?.let { File(it) }
                    if (primary != null) afterExport(primary)
                }.onFailure { err ->
                    progressMsg = "Lỗi xuất: ${err.message}"
                }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Xuất sách (${downloaded.size} chương đã tải)", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = format == ExportFormat.EPUB_3,
                    onClick = { format = ExportFormat.EPUB_3 },
                    label = { Text("EPUB 3.0") },
                )
                FilterChip(
                    selected = format == ExportFormat.EPUB_2,
                    onClick = { format = ExportFormat.EPUB_2 },
                    label = { Text("EPUB 2.0") },
                )
                FilterChip(selected = format == ExportFormat.TXT, onClick = { format = ExportFormat.TXT }, label = { Text("TXT") })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = fromChap,
                    onValueChange = { fromChap = it },
                    label = { Text("Từ chương") },
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = toChap,
                    onValueChange = { toChap = it },
                    label = { Text("Đến chương") },
                    modifier = Modifier.weight(1f),
                )
            }
            ExportToggleRow("Kèm ảnh bìa", includeCover) { includeCover = it }
            ExportToggleRow("Kèm ảnh trong chương", includeImages) { includeImages = it }
            ExportToggleRow("Tự tách tập (>500 chương)", autoSplit) { autoSplit = it }
            if (format != ExportFormat.TXT) {
                Text("Mức nén ZIP: ${compressionLevel.toInt()}", style = MaterialTheme.typography.bodySmall)
                Slider(value = compressionLevel, onValueChange = { compressionLevel = it }, valueRange = 1f..9f)
            }
            if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            progressMsg?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        runExport { file ->
                            safSaver.saveToDownloads(file)
                            progressMsg = "Đã lưu vào Downloads/vapp/${file.name}"
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                ) { Text("Downloads") }
                OutlinedButton(
                    onClick = {
                        runExport { file ->
                            pendingSafSource = file
                            val mime = SafExportSaver.guessMimeType(file.name)
                            safLauncher.launch(safSaver.createDocumentIntent(file.name, mime))
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                ) { Text("Chọn nơi lưu") }
                OutlinedButton(
                    onClick = {
                        runExport { file ->
                            context.startActivity(safSaver.createShareIntent(file))
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                ) { Text("Chia sẻ") }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun ExportToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
