package dev.trinhhg.vapp.ui.reader

import dev.trinhhg.vapp.downloader.BookLibraryRepository
import dev.trinhhg.vapp.downloader.db.BookEntity
import dev.trinhhg.vapp.downloader.db.BookmarkEntity
import dev.trinhhg.vapp.downloader.db.ChapterDownloadStatus
import dev.trinhhg.vapp.downloader.db.ChapterEntity
import dev.trinhhg.vapp.downloader.db.CleanupRuleEntity
import dev.trinhhg.vapp.downloader.db.VappDatabase
import dev.trinhhg.vapp.downloader.storage.ChapterFileStore
import dev.trinhhg.vapp.engine.api.ExtensionExecutor
import dev.trinhhg.vapp.engine.security.RegexSecurityValidator
import dev.trinhhg.vapp.exporter.html.ChapterSanitizer
import dev.trinhhg.vapp.settings.AppSettings
import dev.trinhhg.vapp.settings.AppSettingsRepository
import dev.trinhhg.vapp.tts.text.CleanupPreviewRule
import dev.trinhhg.vapp.tts.text.ReaderTextParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

data class ReaderUiState(
    val loading: Boolean = false,
    val book: BookEntity? = null,
    val chapters: List<ChapterEntity> = emptyList(),
    val currentChapterIndex: Int = 0,
    val initialScrollOffset: Int = 0,
    val chapterTitle: String = "",
    val rawHtml: String = "",
    val paragraphs: List<String> = emptyList(),
    val bookmarks: List<BookmarkEntity> = emptyList(),
    val cleanupRules: List<CleanupRuleEntity> = emptyList(),
    val settings: AppSettings = AppSettings(),
    val isEditMode: Boolean = false,
    val editDraftText: String = "",
    val autoScrollActive: Boolean = false,
    val errorMessage: String? = null,
    val statusBanner: String? = null,
)

/**
 * State holder managing chapter loading, auto-saved reading progress, bookmarks CRUD,
 * ReDoS-safe cleanup rules CRUD, and inline chapter editing (`ReaderEditBody`).
 */
class ReaderViewModel(
    private val db: VappDatabase,
    private val libraryRepo: BookLibraryRepository,
    private val fileStore: ChapterFileStore,
    private val extensionExecutor: ExtensionExecutor,
    private val settingsRepo: AppSettingsRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(ReaderUiState())
    val state: StateFlow<ReaderUiState> = _state.asStateFlow()

    private var bookObserversJob: Job? = null
    private var loadChapterJob: Job? = null

    init {
        scope.launch {
            settingsRepo.settings.collect { settings ->
                _state.update { it.copy(settings = settings) }
            }
        }
        scope.launch {
            db.cleanupRuleDao().observeAll().collect { rules ->
                _state.update { cur ->
                    val reparsed = parseWithRules(cur.rawHtml, rules)
                    cur.copy(cleanupRules = rules, paragraphs = reparsed)
                }
            }
        }
    }

    fun openBook(
        bookId: String,
        chapterIndex: Int? = null,
    ) {
        bookObserversJob?.cancel()
        bookObserversJob =
            scope.launch {
                val book = libraryRepo.getBook(bookId)
                if (book == null) {
                    _state.update { it.copy(errorMessage = "Không tìm thấy sách ($bookId)") }
                    return@launch
                }
                val startIdx = (chapterIndex ?: book.lastReadChapterIndex).coerceAtLeast(0)
                val startOffset = if (chapterIndex == null) book.lastReadScrollOffset else 0

                _state.update {
                    it.copy(
                        book = book,
                        currentChapterIndex = startIdx,
                        initialScrollOffset = startOffset,
                        errorMessage = null,
                    )
                }

                launch {
                    libraryRepo.observeChapters(bookId).collect { list ->
                        _state.update { it.copy(chapters = list) }
                        if (_state.value.rawHtml.isEmpty() && list.isNotEmpty()) {
                            loadChapter(startIdx.coerceIn(0, list.lastIndex), startOffset)
                        }
                    }
                }
                launch {
                    db.bookmarkDao().observeByBook(bookId).collect { marks ->
                        _state.update { it.copy(bookmarks = marks) }
                    }
                }
            }
    }

    fun loadChapter(
        index: Int,
        initialOffset: Int = 0,
    ) {
        val chapters = _state.value.chapters
        if (chapters.isEmpty()) return
        val targetIndex = index.coerceIn(0, chapters.lastIndex)
        val chapter = chapters[targetIndex]
        val book = _state.value.book ?: return

        loadChapterJob?.cancel()
        loadChapterJob =
            scope.launch {
                _state.update {
                    it.copy(
                        loading = true,
                        currentChapterIndex = targetIndex,
                        initialScrollOffset = initialOffset,
                        chapterTitle = chapter.title,
                        isEditMode = false,
                        errorMessage = null,
                    )
                }

                val cachedHtml = fileStore.readChapterHtml(chapter.contentPath)
                val finalHtml =
                    if (!cachedHtml.isNullOrBlank()) {
                        cachedHtml
                    } else {
                        val extId = book.extensionId
                        if (extId.isNullOrBlank()) {
                            _state.update {
                                it.copy(
                                    loading = false,
                                    paragraphs = emptyList(),
                                    errorMessage = "Chương chưa được tải và không có extension nguồn.",
                                )
                            }
                            return@launch
                        }
                        val fetched = extensionExecutor.fetchChapterContent(extId, chapter.sourceUrl)
                        if (fetched.isFailure) {
                            _state.update {
                                it.copy(
                                    loading = false,
                                    paragraphs = emptyList(),
                                    errorMessage = fetched.exceptionOrNull()?.message ?: "Không tải được chương",
                                )
                            }
                            return@launch
                        }
                        val html = fetched.getOrThrow().html
                        if (html.isNotBlank()) {
                            val relPath = fileStore.saveChapterHtml(book.id, chapter.id, chapter.orderIndex, html)
                            val plainLen = ChapterSanitizer.toPlainText(html).length
                            db.chapterDao().updateDownloadResult(
                                chapterId = chapter.id,
                                status = ChapterDownloadStatus.COMPLETED,
                                contentPath = relPath,
                                charCount = plainLen,
                                isImageChapter = false,
                                errorMessage = null,
                                updatedAt = System.currentTimeMillis(),
                            )
                            libraryRepo.recountBook(book.id)
                        }
                        html
                    }

                val parsed = parseWithRules(finalHtml, _state.value.cleanupRules)
                val percent = computeBookPercent(targetIndex, chapters.size)
                libraryRepo.updateReadingProgress(book.id, targetIndex, initialOffset, percent)

                _state.update {
                    it.copy(
                        loading = false,
                        rawHtml = finalHtml,
                        paragraphs = parsed,
                        errorMessage = null,
                    )
                }
            }
    }

    fun onReadingPositionChanged(
        scrollOffset: Int,
        chapterFraction: Float,
    ) {
        val st = _state.value
        val book = st.book ?: return
        val total = st.chapters.size.coerceAtLeast(1)
        val overallPercent = ((st.currentChapterIndex + chapterFraction.coerceIn(0f, 1f)) / total).coerceIn(0f, 1f)
        scope.launch {
            libraryRepo.updateReadingProgress(book.id, st.currentChapterIndex, scrollOffset.coerceAtLeast(0), overallPercent)
        }
    }

    fun toggleAutoScroll() {
        _state.update { it.copy(autoScrollActive = !it.autoScrollActive) }
    }

    fun addBookmark(
        scrollOffset: Int,
        snippet: String,
        note: String? = null,
    ) {
        val st = _state.value
        val book = st.book ?: return
        scope.launch {
            db.bookmarkDao().upsert(
                BookmarkEntity(
                    id = UUID.randomUUID().toString(),
                    bookId = book.id,
                    chapterIndex = st.currentChapterIndex,
                    chapterTitle = st.chapterTitle,
                    scrollOffset = scrollOffset,
                    snippet = snippet.take(80),
                    note = note?.takeIf { it.isNotBlank() },
                    createdAt = System.currentTimeMillis(),
                ),
            )
            _state.update { it.copy(statusBanner = "Đã lưu dấu trang tại ${st.chapterTitle}") }
        }
    }

    fun deleteBookmark(bookmarkId: String) {
        scope.launch {
            db.bookmarkDao().deleteById(bookmarkId)
        }
    }

    fun upsertCleanupRule(
        existingId: String? = null,
        pattern: String,
        replacement: String,
        isRegex: Boolean,
    ): Result<Unit> {
        val trimmed = pattern.trim()
        if (trimmed.isEmpty()) {
            return Result.failure(IllegalArgumentException("Pattern không được để trống"))
        }
        if (isRegex) {
            val check = runCatching { RegexSecurityValidator.validateRegex(trimmed) }
            if (check.isFailure) return check
        }
        val nextOrder = (_state.value.cleanupRules.maxOfOrNull { it.sortOrder } ?: -1) + 1
        scope.launch {
            db.cleanupRuleDao().upsert(
                CleanupRuleEntity(
                    id = existingId ?: UUID.randomUUID().toString(),
                    pattern = trimmed,
                    replacement = replacement,
                    isRegex = isRegex,
                    enabled = true,
                    sortOrder = nextOrder,
                ),
            )
        }
        return Result.success(Unit)
    }

    fun toggleCleanupRule(rule: CleanupRuleEntity) {
        scope.launch {
            db.cleanupRuleDao().upsert(rule.copy(enabled = !rule.enabled))
        }
    }

    fun deleteCleanupRule(ruleId: String) {
        scope.launch {
            db.cleanupRuleDao().deleteById(ruleId)
        }
    }

    fun enterEditMode() {
        val plain = _state.value.paragraphs.joinToString("\n\n")
        _state.update { it.copy(isEditMode = true, editDraftText = plain, autoScrollActive = false) }
    }

    fun cancelEditMode() {
        _state.update { it.copy(isEditMode = false, editDraftText = "") }
    }

    fun updateEditDraft(text: String) {
        _state.update { it.copy(editDraftText = text) }
    }

    fun saveEditedChapter() {
        val st = _state.value
        val book = st.book ?: return
        val chapter = st.chapters.getOrNull(st.currentChapterIndex) ?: return
        val lines =
            st.editDraftText
                .lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        if (lines.isEmpty()) {
            _state.update { it.copy(errorMessage = "Nội dung chương không được để trống") }
            return
        }
        val html =
            lines.joinToString("\n") { line ->
                "<p>${ChapterSanitizer.escapeXml(line)}</p>"
            }
        scope.launch {
            val relPath = fileStore.saveChapterHtml(book.id, chapter.id, chapter.orderIndex, html)
            db.chapterDao().updateDownloadResult(
                chapterId = chapter.id,
                status = ChapterDownloadStatus.COMPLETED,
                contentPath = relPath,
                charCount = lines.sumOf { it.length },
                isImageChapter = false,
                errorMessage = null,
                updatedAt = System.currentTimeMillis(),
            )
            libraryRepo.recountBook(book.id)
            val parsed = parseWithRules(html, _state.value.cleanupRules)
            _state.update {
                it.copy(
                    isEditMode = false,
                    editDraftText = "",
                    rawHtml = html,
                    paragraphs = parsed,
                    statusBanner = "Đã lưu nội dung chỉnh sửa cho ${chapter.title}",
                )
            }
        }
    }

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        scope.launch {
            settingsRepo.update(transform)
        }
    }

    fun resetReaderSettings() {
        scope.launch {
            settingsRepo.resetReaderDefaults()
        }
    }

    fun clearStatusBanner() {
        _state.update { it.copy(statusBanner = null) }
    }

    fun dispose() {
        scope.cancel()
    }

    private fun parseWithRules(
        rawHtml: String,
        rules: List<CleanupRuleEntity>,
    ): List<String> {
        val mapped =
            rules.map {
                CleanupPreviewRule(
                    pattern = it.pattern,
                    replacement = it.replacement,
                    isRegex = it.isRegex,
                    enabled = it.enabled,
                )
            }
        return ReaderTextParser.parseHtmlToParagraphs(rawHtml, mapped)
    }

    private fun computeBookPercent(
        chapterIdx: Int,
        totalChapters: Int,
    ): Float = if (totalChapters <= 0) 0f else ((chapterIdx + 1).toFloat() / totalChapters.toFloat()).coerceIn(0f, 1f)
}
