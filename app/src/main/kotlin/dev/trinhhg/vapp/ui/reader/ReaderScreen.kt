package dev.trinhhg.vapp.ui.reader

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.trinhhg.vapp.downloader.db.BookmarkEntity
import dev.trinhhg.vapp.downloader.db.ChapterEntity
import dev.trinhhg.vapp.downloader.db.CleanupRuleEntity
import dev.trinhhg.vapp.settings.AppSettings
import dev.trinhhg.vapp.settings.ReadMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import kotlin.math.absoluteValue

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    bookId: String,
    initialChapterIndex: Int? = null,
    viewModel: ReaderViewModel = koinInject(),
    onBack: () -> Unit = {},
    onOpenAudiobook: (bookId: String, chapterIndex: Int, paragraphIndex: Int) -> Unit = { _, _, _ -> },
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    var controlsVisible by remember { mutableStateOf(false) }
    var showAppearanceSheet by remember { mutableStateOf(false) }
    var showTocSheet by remember { mutableStateOf(false) }
    var showBookmarksSheet by remember { mutableStateOf(false) }
    var showCleanupSheet by remember { mutableStateOf(false) }
    var availableFonts by remember { mutableStateOf(ReaderFontRegistry.listAvailableFonts(context)) }

    val fontPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                ReaderFontRegistry
                    .importFontFromUri(context, uri)
                    .onSuccess { imported ->
                        availableFonts = ReaderFontRegistry.listAvailableFonts(context)
                        viewModel.updateSettings { it.copy(fontKey = imported.key) }
                    }
            }
        }

    LaunchedEffect(bookId, initialChapterIndex) {
        viewModel.openBook(bookId, initialChapterIndex)
    }

    LaunchedEffect(state.statusBanner) {
        val msg = state.statusBanner
        if (!msg.isNullOrBlank()) {
            snackbarHostState.showSnackbar(msg)
            viewModel.clearStatusBanner()
        }
    }

    ApplyReaderWindowBrightness(state.settings.brightness)

    val theme = ReaderThemePresets.resolve(state.settings)
    val readerFont = remember(state.settings.fontKey) { ReaderFontRegistry.resolveFontFamily(context, state.settings.fontKey) }
    val listState = rememberLazyListState()

    val bookProgressPct =
        remember(state.currentChapterIndex, state.chapters.size) {
            if (state.chapters.isEmpty()) 0 else (((state.currentChapterIndex + 1) * 100f) / state.chapters.size).toInt()
        }

    Scaffold(
        containerColor = theme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            AnimatedVisibility(visible = controlsVisible && !state.isEditMode) {
                TopAppBar(
                    title = { Text(state.chapterTitle.ifBlank { state.book?.title.orEmpty() }, maxLines = 1) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Quay lại")
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = {
                                val firstVisible = listState.firstVisibleItemIndex
                                val snippet = state.paragraphs.getOrNull(firstVisible).orEmpty()
                                viewModel.addBookmark(firstVisible, snippet)
                            },
                        ) {
                            Icon(Icons.Default.BookmarkAdd, contentDescription = "Lưu dấu trang")
                        }
                        IconButton(onClick = { viewModel.enterEditMode() }) {
                            Icon(Icons.Default.Edit, contentDescription = "Sửa chương")
                        }
                        IconButton(
                            onClick = {
                                onOpenAudiobook(bookId, state.currentChapterIndex, listState.firstVisibleItemIndex)
                            },
                        ) {
                            Icon(Icons.Default.Headphones, contentDescription = "Nghe TTS")
                        }
                    },
                )
            }
        },
        bottomBar = {
            AnimatedVisibility(visible = controlsVisible && !state.isEditMode) {
                Surface(tonalElevation = 4.dp) {
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        IconButton(onClick = { showTocSheet = true }) {
                            Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Mục lục")
                        }
                        IconButton(onClick = { showBookmarksSheet = true }) {
                            Icon(Icons.Default.Bookmarks, contentDescription = "Dấu trang")
                        }
                        IconButton(onClick = { showCleanupSheet = true }) {
                            Icon(Icons.Default.CleaningServices, contentDescription = "Lọc rác")
                        }
                        IconButton(onClick = { showAppearanceSheet = true }) {
                            Icon(Icons.Default.Tune, contentDescription = "Tùy chỉnh đọc")
                        }
                    }
                }
            }
        },
    ) { padding ->
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(theme.background)
                    .padding(padding),
        ) {
            when {
                state.loading -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
                state.errorMessage != null -> {
                    Column(
                        modifier =
                            Modifier
                                .align(Alignment.Center)
                                .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(state.errorMessage.orEmpty(), color = theme.foreground)
                        Button(onClick = { viewModel.loadChapter(state.currentChapterIndex) }) {
                            Text("Thử lại")
                        }
                    }
                }
                state.isEditMode -> {
                    ReaderEditBody(
                        chapterTitle = state.chapterTitle,
                        draftText = state.editDraftText,
                        onDraftChange = viewModel::updateEditDraft,
                        onSave = viewModel::saveEditedChapter,
                        onCancel = viewModel::cancelEditMode,
                    )
                }
                else -> {
                    ReaderReadingCanvas(
                        state = state,
                        theme = theme,
                        fontFamily = readerFont,
                        listState = listState,
                        onToggleControls = { controlsVisible = !controlsVisible },
                        onPrevChapter = {
                            if (state.currentChapterIndex > 0) viewModel.loadChapter(state.currentChapterIndex - 1)
                        },
                        onNextChapter = {
                            if (state.currentChapterIndex + 1 < state.chapters.size) {
                                viewModel.loadChapter(state.currentChapterIndex + 1)
                            }
                        },
                        onPositionChanged = viewModel::onReadingPositionChanged,
                        onScrollStep = { deltaPx ->
                            coroutineScope.launch { listState.scrollBy(deltaPx) }
                        },
                    )
                }
            }
        }
    }

    if (showAppearanceSheet) {
        ReaderBottomSheet(
            settings = state.settings,
            availableFonts = availableFonts,
            chapterProgressPercent = bookProgressPct,
            autoScrollActive = state.autoScrollActive,
            onDismiss = { showAppearanceSheet = false },
            onUpdateSettings = viewModel::updateSettings,
            onToggleAutoScroll = viewModel::toggleAutoScroll,
            onImportFontClick = { fontPicker.launch(arrayOf("font/ttf", "font/otf", "application/octet-stream", "*/*")) },
            onResetDefaults = viewModel::resetReaderSettings,
        )
    }

    if (showTocSheet) {
        TocBottomSheet(
            chapters = state.chapters,
            currentIndex = state.currentChapterIndex,
            onSelect = { idx ->
                showTocSheet = false
                controlsVisible = false
                viewModel.loadChapter(idx)
            },
            onDismiss = { showTocSheet = false },
        )
    }

    if (showBookmarksSheet) {
        BookmarksBottomSheet(
            bookmarks = state.bookmarks,
            onJump = { mark ->
                showBookmarksSheet = false
                controlsVisible = false
                viewModel.loadChapter(mark.chapterIndex, mark.scrollOffset)
            },
            onDelete = viewModel::deleteBookmark,
            onDismiss = { showBookmarksSheet = false },
        )
    }

    if (showCleanupSheet) {
        CleanupRulesBottomSheet(
            rules = state.cleanupRules,
            onAddRule = { pat, rep, isRegex -> viewModel.upsertCleanupRule(null, pat, rep, isRegex) },
            onToggle = viewModel::toggleCleanupRule,
            onDelete = viewModel::deleteCleanupRule,
            onDismiss = { showCleanupSheet = false },
        )
    }
}

@Composable
private fun ReaderReadingCanvas(
    state: ReaderUiState,
    theme: ReaderThemePreset,
    fontFamily: FontFamily,
    listState: LazyListState,
    onToggleControls: () -> Unit,
    onPrevChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onPositionChanged: (offset: Int, chapterFraction: Float) -> Unit,
    onScrollStep: (Float) -> Unit,
) {
    val settings = state.settings
    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = state.chapterTitle,
            color = theme.foreground.copy(alpha = 0.6f),
            style = MaterialTheme.typography.labelMedium,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, bottom = 4.dp),
            textAlign = TextAlign.Center,
            maxLines = 1,
        )

        if (settings.readMode == ReadMode.VERTICAL_SCROLL) {
            LaunchedEffect(state.currentChapterIndex, state.initialScrollOffset) {
                if (state.initialScrollOffset in 0..state.paragraphs.lastIndex) {
                    listState.scrollToItem(state.initialScrollOffset)
                }
            }
            LaunchedEffect(listState, state.paragraphs.size) {
                snapshotFlow { listState.firstVisibleItemIndex }
                    .distinctUntilChanged()
                    .collect { idx ->
                        val frac =
                            if (state.paragraphs.isEmpty()) 0f else idx.toFloat() / state.paragraphs.size.toFloat()
                        onPositionChanged(idx, frac)
                    }
            }
            LaunchedEffect(state.autoScrollActive, settings.autoScrollSpeed) {
                while (state.autoScrollActive) {
                    onScrollStep(settings.autoScrollSpeed * 2.2f)
                    delay(16L)
                }
            }

            Box(
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .pointerInput(Unit) {
                            detectTapGestures { offset ->
                                val w = size.width
                                when {
                                    offset.x < w * 0.25f -> onPrevChapter()
                                    offset.x > w * 0.75f -> onNextChapter()
                                    else -> onToggleControls()
                                }
                            }
                        },
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = settings.marginHorizontalDp.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    itemsIndexed(state.paragraphs) { idx, paragraph ->
                        if (idx == 0) {
                            Text(
                                text = state.chapterTitle,
                                color = theme.foreground,
                                fontFamily = fontFamily,
                                fontSize = (settings.fontSizeSp + 4).sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(bottom = 12.dp),
                            )
                        }
                        ParagraphText(paragraph = paragraph, settings = settings, foreground = theme.foreground, fontFamily = fontFamily)
                    }
                    item {
                        ChapterNavFooter(
                            canPrev = state.currentChapterIndex > 0,
                            canNext = state.currentChapterIndex + 1 < state.chapters.size,
                            foreground = theme.foreground,
                            onPrev = onPrevChapter,
                            onNext = onNextChapter,
                        )
                    }
                }
            }
        } else {
            PagedReaderCanvas(
                chapterTitle = state.chapterTitle,
                paragraphs = state.paragraphs,
                settings = settings,
                theme = theme,
                fontFamily = fontFamily,
                isCurlMode = settings.readMode == ReadMode.PAGED_CURL,
                initialParagraphIndex = state.initialScrollOffset,
                onToggleControls = onToggleControls,
                onPrevChapter = onPrevChapter,
                onNextChapter = onNextChapter,
                onPositionChanged = onPositionChanged,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun PagedReaderCanvas(
    chapterTitle: String,
    paragraphs: List<String>,
    settings: AppSettings,
    theme: ReaderThemePreset,
    fontFamily: FontFamily,
    isCurlMode: Boolean,
    initialParagraphIndex: Int,
    onToggleControls: () -> Unit,
    onPrevChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onPositionChanged: (Int, Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val approxCharsPerPage =
            remember(maxHeight, maxWidth, settings.fontSizeSp, settings.lineHeightMultiplier) {
                val lines = (maxHeight.value / (settings.fontSizeSp * settings.lineHeightMultiplier)).toInt().coerceAtLeast(8)
                val charsPerLine =
                    ((maxWidth.value - settings.marginHorizontalDp * 2) / (settings.fontSizeSp * 0.55f))
                        .toInt()
                        .coerceAtLeast(
                            20,
                        )
                (lines * charsPerLine).coerceIn(300, 1800)
            }

        val pages =
            remember(paragraphs, approxCharsPerPage) {
                paginateParagraphs(paragraphs, approxCharsPerPage)
            }
        val initialPage =
            remember(pages, initialParagraphIndex) {
                pages.indexOfFirst { it.firstParagraphIdx >= initialParagraphIndex }.coerceAtLeast(0)
            }
        val pagerState = rememberPagerState(initialPage = initialPage, pageCount = { pages.size.coerceAtLeast(1) })

        LaunchedEffect(pagerState.currentPage, pages.size) {
            val pageSlice = pages.getOrNull(pagerState.currentPage)
            if (pageSlice != null) {
                val frac = (pagerState.currentPage + 1).toFloat() / pages.size.coerceAtLeast(1).toFloat()
                onPositionChanged(pageSlice.firstParagraphIdx, frac)
            }
        }

        HorizontalPager(
            state = pagerState,
            modifier =
                Modifier
                    .fillMaxSize()
                    .pointerInput(pagerState.currentPage, pages.size) {
                        detectTapGestures { offset ->
                            val w = size.width
                            when {
                                offset.x < w * 0.28f -> {
                                    if (pagerState.currentPage > 0) {
                                        scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) }
                                    } else {
                                        onPrevChapter()
                                    }
                                }
                                offset.x > w * 0.72f -> {
                                    if (pagerState.currentPage + 1 < pages.size) {
                                        scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                                    } else {
                                        onNextChapter()
                                    }
                                }
                                else -> onToggleControls()
                            }
                        }
                    },
        ) { pageIdx ->
            val pageOffset = ((pagerState.currentPage - pageIdx) + pagerState.currentPageOffsetFraction)
            val slice = pages.getOrNull(pageIdx)
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .then(
                            if (isCurlMode) {
                                Modifier.graphicsLayer {
                                    cameraDistance = 18f * density
                                    transformOrigin = TransformOrigin(0f, 0.5f)
                                    rotationY = (-pageOffset * 38f).coerceIn(-45f, 45f)
                                    alpha = (1f - pageOffset.absoluteValue * 0.35f).coerceIn(0.4f, 1f)
                                }
                            } else {
                                Modifier
                            },
                        ).background(theme.background)
                        .padding(horizontal = settings.marginHorizontalDp.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (pageIdx == 0) {
                    Text(
                        text = chapterTitle,
                        color = theme.foreground,
                        fontFamily = fontFamily,
                        fontSize = (settings.fontSizeSp + 4).sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
                slice?.items?.forEach { p ->
                    ParagraphText(p, settings, theme.foreground, fontFamily)
                }
                Spacer(Modifier.weight(1f))
                Text(
                    text = "Trang ${pageIdx + 1}/${pages.size.coerceAtLeast(1)}",
                    color = theme.foreground.copy(alpha = 0.5f),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.End,
                )
            }
        }
    }
}

@Composable
private fun ParagraphText(
    paragraph: String,
    settings: AppSettings,
    foreground: Color,
    fontFamily: FontFamily,
) {
    val spaces = " ".repeat((settings.paragraphIndentEm * 2f).toInt().coerceAtLeast(0))
    val annotated =
        remember(paragraph, spaces) {
            buildAnnotatedString {
                if (spaces.isNotEmpty()) {
                    withStyle(SpanStyle(color = Color.Transparent)) { append(spaces) }
                }
                append(paragraph)
            }
        }
    Text(
        text = annotated,
        color = foreground,
        fontFamily = fontFamily,
        fontSize = settings.fontSizeSp.sp,
        lineHeight = (settings.fontSizeSp * settings.lineHeightMultiplier).sp,
        textAlign = settings.textAlign.toComposeTextAlign(),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ChapterNavFooter(
    canPrev: Boolean,
    canNext: Boolean,
    foreground: Color,
    onPrev: () -> Unit,
    onNext: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 24.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        OutlinedButton(onClick = onPrev, enabled = canPrev) { Text("Chương trước", color = foreground) }
        Button(onClick = onNext, enabled = canNext) { Text("Chương tiếp") }
    }
}

/**
 * Inline chapter editor allowing users to fix typos or remove watermarks directly inside Reader.
 */
@Composable
fun ReaderEditBody(
    chapterTitle: String,
    draftText: String,
    onDraftChange: (String) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Chỉnh sửa: $chapterTitle", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = draftText,
            onValueChange = onDraftChange,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .weight(1f),
            label = { Text("Nội dung chương") },
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text("Hủy") }
            Button(onClick = onSave, modifier = Modifier.weight(1f)) { Text("Lưu thay đổi") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TocBottomSheet(
    chapters: List<ChapterEntity>,
    currentIndex: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var filterQuery by remember { mutableStateOf("") }
    val filtered =
        remember(chapters, filterQuery) {
            if (filterQuery.isBlank()) {
                chapters
            } else {
                val q = filterQuery.trim()
                chapters.filter { it.title.contains(q, ignoreCase = true) || "${it.orderIndex + 1}".contains(q) }
            }
        }
    val listState =
        rememberLazyListState(
            initialFirstVisibleItemIndex =
                if (filterQuery.isBlank()) {
                    currentIndex.coerceIn(0, (chapters.size - 1).coerceAtLeast(0))
                } else {
                    0
                },
        )

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxHeight(0.75f).padding(horizontal = 16.dp)) {
            Text("Mục lục (${chapters.size} chương)", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = filterQuery,
                onValueChange = { filterQuery = it },
                label = { Text("Tìm chương hoặc nhập số chương...") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            LazyColumn(state = listState) {
                itemsIndexed(filtered, key = { _, ch -> ch.id }) { _, ch ->
                    val active = ch.orderIndex == currentIndex
                    Text(
                        text = ch.title,
                        color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(ch.orderIndex) }
                                .padding(vertical = 10.dp),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BookmarksBottomSheet(
    bookmarks: List<BookmarkEntity>,
    onJump: (BookmarkEntity) -> Unit,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Dấu trang đã lưu (${bookmarks.size})", style = MaterialTheme.typography.titleMedium)
            if (bookmarks.isEmpty()) {
                Text("Chưa có dấu trang nào.", style = MaterialTheme.typography.bodySmall)
            } else {
                LazyColumn(modifier = Modifier.height(260.dp)) {
                    itemsIndexed(bookmarks) { _, mark ->
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { onJump(mark) }
                                    .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(mark.chapterTitle, fontWeight = FontWeight.SemiBold)
                                Text(mark.snippet, maxLines = 2, style = MaterialTheme.typography.bodySmall)
                            }
                            IconButton(onClick = { onDelete(mark.id) }) {
                                Icon(Icons.Default.Delete, contentDescription = "Xóa dấu trang")
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
private fun CleanupRulesBottomSheet(
    rules: List<CleanupRuleEntity>,
    onAddRule: (String, String, Boolean) -> Result<Unit>,
    onToggle: (CleanupRuleEntity) -> Unit,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var showAddDialog by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Quy tắc lọc chữ rác (${rules.size})", style = MaterialTheme.typography.titleMedium)
                Button(onClick = { showAddDialog = true }) { Text("Thêm rule") }
            }
            LazyColumn(modifier = Modifier.height(260.dp)) {
                itemsIndexed(rules) { _, rule ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(rule.pattern, fontWeight = FontWeight.Medium)
                            Text("Thay bằng: '${rule.replacement}' (regex=${rule.isRegex})", style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(checked = rule.enabled, onCheckedChange = { onToggle(rule) })
                        IconButton(onClick = { onDelete(rule.id) }) {
                            Icon(Icons.Default.Delete, contentDescription = "Xóa rule")
                        }
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        var pat by remember { mutableStateOf("") }
        var rep by remember { mutableStateOf("") }
        var isRegex by remember { mutableStateOf(true) }
        var errMsg by remember { mutableStateOf<String?>(null) }

        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("Thêm quy tắc lọc rác") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = pat, onValueChange = { pat = it }, label = { Text("Mẫu cần xóa/thay") })
                    OutlinedTextField(value = rep, onValueChange = { rep = it }, label = { Text("Chuỗi thay thế") })
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = isRegex, onCheckedChange = { isRegex = it })
                        Text("Dùng Regex", modifier = Modifier.padding(start = 8.dp))
                    }
                    errMsg?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onAddRule(pat, rep, isRegex)
                            .onSuccess { showAddDialog = false }
                            .onFailure { errMsg = it.message }
                    },
                ) { Text("Lưu") }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) { Text("Hủy") }
            },
        )
    }
}

@Composable
private fun ApplyReaderWindowBrightness(brightness: Float) {
    val context = LocalContext.current
    DisposableEffect(brightness) {
        val window = (context as? Activity)?.window
        val original = window?.attributes?.screenBrightness
        if (window != null) {
            val lp = window.attributes
            lp.screenBrightness = brightness
            window.attributes = lp
        }
        onDispose {
            if (window != null && original != null) {
                val lp = window.attributes
                lp.screenBrightness = original
                window.attributes = lp
            }
        }
    }
}

private data class PageSlice(
    val firstParagraphIdx: Int,
    val items: List<String>,
)

private fun paginateParagraphs(
    paragraphs: List<String>,
    maxCharsPerPage: Int,
): List<PageSlice> {
    if (paragraphs.isEmpty()) return listOf(PageSlice(0, emptyList()))
    val out = ArrayList<PageSlice>()
    var currentItems = ArrayList<String>()
    var currentStartIdx = 0
    var charCount = 0

    paragraphs.forEachIndexed { idx, p ->
        val pLen = p.length + 40
        if (currentItems.isNotEmpty() && charCount + pLen > maxCharsPerPage) {
            out.add(PageSlice(currentStartIdx, currentItems))
            currentItems = ArrayList()
            currentStartIdx = idx
            charCount = 0
        }
        currentItems.add(p)
        charCount += pLen
    }
    if (currentItems.isNotEmpty()) {
        out.add(PageSlice(currentStartIdx, currentItems))
    }
    return out
}
