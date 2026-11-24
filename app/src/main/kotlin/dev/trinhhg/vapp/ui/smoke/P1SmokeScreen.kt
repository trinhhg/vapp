package dev.trinhhg.vapp.ui.smoke

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.trinhhg.vapp.engine.EngineException
import dev.trinhhg.vapp.engine.api.BookMetadata
import dev.trinhhg.vapp.engine.api.BookSearchItem
import dev.trinhhg.vapp.engine.api.ChapterContent
import dev.trinhhg.vapp.engine.api.ChapterRef
import dev.trinhhg.vapp.engine.api.ExtensionExecutor
import dev.trinhhg.vapp.engine.log.EngineLogger
import dev.trinhhg.vapp.engine.vbook.ExtensionManager
import dev.trinhhg.vapp.engine.vbook.InstalledExtension
import dev.trinhhg.vapp.engine.vbook.VBookExtensionItem
import dev.trinhhg.vapp.engine.vbook.VBookRepositoryClient
import dev.trinhhg.vapp.webview.WebViewChallengeSolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

private data class P1SmokeState(
    val busy: Boolean = false,
    val statusText: String = "Sẵn sàng kiểm thử Gate P1.",
    val errorText: String? = null,
    val registryUrl: String = VBookRepositoryClient.DEFAULT_PLUGIN_INDEX_URL,
    val registryItems: List<VBookExtensionItem> = emptyList(),
    val installed: List<InstalledExtension> = emptyList(),
    val selectedExtId: String? = null,
    val searchQuery: String = "tiên",
    val searchResults: List<BookSearchItem> = emptyList(),
    val bookUrl: String = "",
    val detail: BookMetadata? = null,
    val chapters: List<ChapterRef> = emptyList(),
    val chapterPreview: ChapterContent? = null,
    val logsText: String = "",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun P1SmokeScreen(
    repoClient: VBookRepositoryClient = koinInject(),
    extensionManager: ExtensionManager = koinInject(),
    executor: ExtensionExecutor = koinInject(),
    logger: EngineLogger = koinInject(),
    challengeSolver: WebViewChallengeSolver = koinInject(),
) {
    val scope = rememberCoroutineScope()
    var state by remember {
        val current = extensionManager.listExtensions()
        mutableStateOf(
            P1SmokeState(
                installed = current,
                selectedExtId = current.firstOrNull()?.id,
                logsText = logger.formattedText(),
            ),
        )
    }

    fun runTask(
        label: String,
        block: suspend () -> Unit,
    ) {
        scope.launch {
            state = state.copy(busy = true, statusText = label, errorText = null)
            try {
                block()
            } catch (e: EngineException) {
                state = state.copy(errorText = "[${e.code}] ${e.message}")
            } catch (t: Throwable) {
                state = state.copy(errorText = t.message ?: t::class.simpleName)
            } finally {
                state = state.copy(busy = false, logsText = logger.formattedText())
            }
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("vapp — Gate P1 Engine Smoke Test") }) },
    ) { innerPadding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text(state.statusText, style = MaterialTheme.typography.bodyMedium)
            state.errorText?.let { err -> ErrorBanner(err) }

            RegistrySection(
                registryUrl = state.registryUrl,
                items = state.registryItems,
                onUrlChange = { state = state.copy(registryUrl = it) },
                onFetchRegistry = {
                    runTask("Đang tải plugin.json...") {
                        val items =
                            withContext(Dispatchers.IO) {
                                repoClient.fetchExtensions(state.registryUrl.trim(), forceRefresh = true)
                            }
                        state =
                            state.copy(
                                registryItems = items,
                                statusText = "Đã tải ${items.size} nguồn từ registry.",
                            )
                    }
                },
                onInstallItem = { item ->
                    runTask("Đang cài đặt ${item.name}...") {
                        val installed =
                            withContext(Dispatchers.IO) {
                                extensionManager.installFromRegistryItem(item)
                            }
                        val all = extensionManager.listExtensions()
                        state =
                            state.copy(
                                installed = all,
                                selectedExtId = installed.id,
                                statusText = "Đã cài ${installed.name} (v${installed.version}).",
                            )
                    }
                },
            )

            InstalledExtensionsSection(
                installed = state.installed,
                selectedExtId = state.selectedExtId,
                onSelect = { ext ->
                    state = state.copy(selectedExtId = ext.id, statusText = "Đang chọn nguồn: ${ext.name}")
                },
                onManualWebView = { ext ->
                    runTask("Mở WebView đăng nhập cho ${ext.name}...") {
                        challengeSolver.openManualLogin(ext.sourceUrl)
                        state = state.copy(statusText = "Đã đồng bộ cookie từ WebView cho ${ext.name}.")
                    }
                },
            )

            SmokePipelineSection(
                selectedExtId = state.selectedExtId,
                searchQuery = state.searchQuery,
                bookUrl = state.bookUrl,
                searchResults = state.searchResults,
                detail = state.detail,
                chapters = state.chapters,
                chapterPreview = state.chapterPreview,
                onQueryChange = { state = state.copy(searchQuery = it) },
                onBookUrlChange = { state = state.copy(bookUrl = it) },
                onSearch = {
                    val extId = state.selectedExtId ?: return@SmokePipelineSection
                    runTask("Đang tìm '${state.searchQuery}'...") {
                        val paged = executor.search(extId, state.searchQuery.trim()).getOrThrow()
                        state =
                            state.copy(
                                searchResults = paged.items,
                                bookUrl = paged.items.firstOrNull()?.link ?: state.bookUrl,
                                statusText = "Tìm thấy ${paged.items.size} truyện.",
                            )
                    }
                },
                onSelectBook = { book -> state = state.copy(bookUrl = book.link) },
                onRunDetailTocChap = {
                    val url = state.bookUrl.trim()
                    runTask("Đang chạy detail -> toc -> chap...") {
                        val ext =
                            extensionManager.matchByUrl(url)
                                ?: state.selectedExtId?.let { extensionManager.getExtension(it) }
                                ?: withContext(Dispatchers.IO) { extensionManager.findAndInstallByUrl(url) }
                                ?: error("Không tìm thấy extension phù hợp cho URL: $url")

                        val meta = executor.fetchBookDetail(ext.id, url).getOrThrow()
                        val toc = executor.fetchTableOfContents(ext.id, url).getOrThrow()
                        val firstChap = toc.firstOrNull()?.let { executor.fetchChapterContent(ext.id, it.url).getOrThrow() }
                        state =
                            state.copy(
                                installed = extensionManager.listExtensions(),
                                selectedExtId = ext.id,
                                detail = meta,
                                chapters = toc,
                                chapterPreview = firstChap,
                                statusText = "Hoàn tất E2E: ${meta.name} (${toc.size} chương).",
                            )
                    }
                },
            )

            EngineLogsSection(
                logsText = state.logsText,
                onRefresh = { state = state.copy(logsText = logger.formattedText()) },
                onClear = {
                    logger.clear()
                    state = state.copy(logsText = "")
                },
            )
        }
    }
}

@Composable
private fun ErrorBanner(message: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = message,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(12.dp),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun RegistrySection(
    registryUrl: String,
    items: List<VBookExtensionItem>,
    onUrlChange: (String) -> Unit,
    onFetchRegistry: () -> Unit,
    onInstallItem: (VBookExtensionItem) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("1. VBook Registry", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = registryUrl,
                onValueChange = onUrlChange,
                label = { Text("URL repository.json / plugin.json") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(onClick = onFetchRegistry) { Text("Tải danh sách nguồn") }
            items.take(8).forEach { item ->
                val isEncryptedHint = item.tag?.contains("encrypt", ignoreCase = true) == true
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("${item.name ?: "?"} (v${item.version ?: 1})")
                        Text(item.source ?: "", style = MaterialTheme.typography.bodySmall)
                        if (isEncryptedHint) {
                            Text("Mã hóa — sẽ từ chối", color = MaterialTheme.colorScheme.error)
                        }
                    }
                    OutlinedButton(onClick = { onInstallItem(item) }) { Text("Cài") }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InstalledExtensionsSection(
    installed: List<InstalledExtension>,
    selectedExtId: String?,
    onSelect: (InstalledExtension) -> Unit,
    onManualWebView: (InstalledExtension) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("2. Nguồn đã cài (${installed.size})", style = MaterialTheme.typography.titleMedium)
            if (installed.isEmpty()) {
                Text("Chưa cài extension nào.", style = MaterialTheme.typography.bodySmall)
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    installed.forEach { ext ->
                        FilterChip(
                            selected = ext.id == selectedExtId,
                            onClick = { onSelect(ext) },
                            label = { Text("${ext.name} (v${ext.version})") },
                        )
                    }
                }
                installed.firstOrNull { it.id == selectedExtId }?.let { active ->
                    OutlinedButton(onClick = { onManualWebView(active) }) {
                        Text("Mở WebView đăng nhập (${active.name})")
                    }
                }
            }
        }
    }
}

@Composable
private fun SmokePipelineSection(
    selectedExtId: String?,
    searchQuery: String,
    bookUrl: String,
    searchResults: List<BookSearchItem>,
    detail: BookMetadata?,
    chapters: List<ChapterRef>,
    chapterPreview: ChapterContent?,
    onQueryChange: (String) -> Unit,
    onBookUrlChange: (String) -> Unit,
    onSearch: () -> Unit,
    onSelectBook: (BookSearchItem) -> Unit,
    onRunDetailTocChap: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("3. Kiểm thử Search -> Detail -> TOC -> Chap", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onQueryChange,
                label = { Text("Từ khóa tìm kiếm") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(onClick = onSearch, enabled = selectedExtId != null) { Text("Tìm truyện") }
            searchResults.take(5).forEach { book ->
                OutlinedButton(onClick = { onSelectBook(book) }, modifier = Modifier.fillMaxWidth()) {
                    Text("${book.name} — ${book.link}", maxLines = 1)
                }
            }
            OutlinedTextField(
                value = bookUrl,
                onValueChange = onBookUrlChange,
                label = { Text("URL truyện (dán trực tiếp để tự khớp extension)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(onClick = onRunDetailTocChap, enabled = bookUrl.isNotBlank()) {
                Text("Chạy Detail -> TOC -> Chap 1")
            }
            detail?.let { meta ->
                Text("Truyện: ${meta.name} | Tác giả: ${meta.author} | Chương: ${chapters.size}")
            }
            chapterPreview?.let { chap ->
                Spacer(Modifier.height(4.dp))
                Text(chap.title ?: chapters.firstOrNull()?.name.orEmpty(), style = MaterialTheme.typography.titleSmall)
                Text(chap.html.take(600), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun EngineLogsSection(
    logsText: String,
    onRefresh: () -> Unit,
    onClear: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("4. Engine Logs", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onRefresh) { Text("Làm mới") }
                    OutlinedButton(onClick = onClear) { Text("Xóa") }
                }
            }
            Text(
                text = logsText.ifBlank { "(Chưa có log)" },
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
