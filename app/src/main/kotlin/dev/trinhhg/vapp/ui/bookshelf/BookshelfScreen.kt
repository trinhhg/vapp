package dev.trinhhg.vapp.ui.bookshelf

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.trinhhg.vapp.downloader.BookLibraryRepository
import dev.trinhhg.vapp.downloader.db.BookEntity
import dev.trinhhg.vapp.downloader.storage.CoverStore
import dev.trinhhg.vapp.engine.api.ExtensionExecutor
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import kotlin.math.roundToInt

private enum class BookshelfFilter(
    val label: String,
) {
    ALL("Tất cả"),
    HISTORY("Lịch sử"),
    DOWNLOADED("Đã tải"),
}

private enum class BookshelfSort(
    val label: String,
) {
    RECENT("Đọc gần đây"),
    ADDED("Mới thêm"),
    TITLE("Tên A-Z"),
}

/**
 * Bookshelf home screen modeled on reference screenshot #4: filter pills,
 * `"Xem gần đây"` wide card carousel, and 3-column cover grid with reading `%` & chapter count badges.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookshelfScreen(
    libraryRepo: BookLibraryRepository = koinInject(),
    coverStore: CoverStore = koinInject(),
    executor: ExtensionExecutor = koinInject(),
    onOpenBookDetail: (String) -> Unit,
    onContinueReading: (bookId: String, chapterIndex: Int) -> Unit,
    onOpenExplore: () -> Unit,
) {
    val books by libraryRepo.observeLibrary().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()

    var filter by remember { mutableStateOf(BookshelfFilter.ALL) }
    var sort by remember { mutableStateOf(BookshelfSort.RECENT) }
    var searchVisible by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var sortMenuExpanded by remember { mutableStateOf(false) }
    var checkingUpdates by remember { mutableStateOf(false) }
    var updateStatusText by remember { mutableStateOf<String?>(null) }
    var deleteCandidate by remember { mutableStateOf<BookEntity?>(null) }

    val historyCount = remember(books) { books.count { it.lastReadAt != null } }
    val recentBooks = remember(books) { books.filter { it.lastReadAt != null }.sortedByDescending { it.lastReadAt }.take(8) }

    val displayed =
        remember(books, filter, sort, searchQuery) {
            books
                .filter { b ->
                    val matchesFilter =
                        when (filter) {
                            BookshelfFilter.ALL -> true
                            BookshelfFilter.HISTORY -> b.lastReadAt != null
                            BookshelfFilter.DOWNLOADED -> b.downloadedChapters > 0
                        }
                    val q = searchQuery.trim()
                    val matchesSearch =
                        q.isEmpty() ||
                            b.title.contains(q, ignoreCase = true) ||
                            b.author.contains(q, ignoreCase = true)
                    matchesFilter && matchesSearch
                }.let { list ->
                    when (sort) {
                        BookshelfSort.RECENT -> list.sortedByDescending { it.lastReadAt ?: it.addedAt }
                        BookshelfSort.ADDED -> list.sortedByDescending { it.addedAt }
                        BookshelfSort.TITLE -> list.sortedBy { it.title.lowercase() }
                    }
                }
        }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(Color(0xFFF6F7FA)),
    ) {
        BookshelfTopHeader(
            searchVisible = searchVisible,
            searchQuery = searchQuery,
            onToggleSearch = {
                searchVisible = !searchVisible
                if (!searchVisible) searchQuery = ""
            },
            onSearchChange = { searchQuery = it },
            onCheckUpdates = {
                if (checkingUpdates) return@BookshelfTopHeader
                scope.launch {
                    checkingUpdates = true
                    updateStatusText = "Đang kiểm tra chương mới..."
                    var totalNew = 0
                    var checked = 0
                    for (book in books) {
                        val extId = book.extensionId ?: continue
                        checked++
                        val toc = executor.fetchTableOfContents(extId, book.sourceUrl).getOrNull() ?: continue
                        totalNew += libraryRepo.mergeFetchedChapters(book.id, toc)
                    }
                    checkingUpdates = false
                    updateStatusText = "Đã kiểm tra $checked truyện: phát hiện $totalNew chương mới."
                }
            },
            sortMenuExpanded = sortMenuExpanded,
            onSortMenuToggle = { sortMenuExpanded = it },
            onSelectSort = {
                sort = it
                sortMenuExpanded = false
            },
        )

        if (checkingUpdates) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        updateStatusText?.let { msg ->
            Text(
                text = msg,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        FilterPillsRow(
            totalCount = books.size,
            historyCount = historyCount,
            selected = filter,
            onSelect = { filter = it },
        )

        if (displayed.isEmpty()) {
            EmptyBookshelfState(onOpenExplore = onOpenExplore)
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 104.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (recentBooks.isNotEmpty() && searchQuery.isBlank() && filter == BookshelfFilter.ALL) {
                    item(span = { GridItemSpan(3) }) {
                        RecentCarouselSection(
                            recentBooks = recentBooks,
                            coverStore = coverStore,
                            onResume = { b -> onContinueReading(b.id, b.lastReadChapterIndex) },
                        )
                    }
                }
                items(displayed, key = { it.id }) { book ->
                    BookshelfCoverTile(
                        book = book,
                        coverStore = coverStore,
                        onClick = { onOpenBookDetail(book.id) },
                        onLongClick = { deleteCandidate = book },
                    )
                }
            }
        }
    }

    deleteCandidate?.let { victim ->
        AlertDialog(
            onDismissRequest = { deleteCandidate = null },
            title = { Text("Xóa khỏi Tủ sách?") },
            text = { Text("Xóa '${victim.title}' và toàn bộ chương đã tải trên máy?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteCandidate = null
                        scope.launch { libraryRepo.deleteBook(victim.id) }
                    },
                ) { Text("Xóa", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteCandidate = null }) { Text("Hủy") }
            },
        )
    }
}

@Composable
private fun BookshelfTopHeader(
    searchVisible: Boolean,
    searchQuery: String,
    onToggleSearch: () -> Unit,
    onSearchChange: (String) -> Unit,
    onCheckUpdates: () -> Unit,
    sortMenuExpanded: Boolean,
    onSortMenuToggle: (Boolean) -> Unit,
    onSelectSort: (BookshelfSort) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "Tủ sách",
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                color = Color(0xFF1A1D24),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onToggleSearch) {
                    Icon(if (searchVisible) Icons.Default.Close else Icons.Default.Search, contentDescription = "Tìm trong tủ")
                }
                IconButton(onClick = onCheckUpdates) {
                    Icon(Icons.Default.Refresh, contentDescription = "Cập nhật chương mới")
                }
                Box {
                    IconButton(onClick = { onSortMenuToggle(true) }) {
                        Icon(Icons.Default.Sort, contentDescription = "Sắp xếp")
                    }
                    DropdownMenu(expanded = sortMenuExpanded, onDismissRequest = { onSortMenuToggle(false) }) {
                        BookshelfSort.entries.forEach { s ->
                            DropdownMenuItem(text = { Text(s.label) }, onClick = { onSelectSort(s) })
                        }
                    }
                }
            }
        }
        if (searchVisible) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchChange,
                placeholder = { Text("Lọc theo tên truyện hoặc tác giả...") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(end = 8.dp, bottom = 6.dp),
            )
        }
    }
}

@Composable
private fun FilterPillsRow(
    totalCount: Int,
    historyCount: Int,
    selected: BookshelfFilter,
    onSelect: (BookshelfFilter) -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(BookshelfFilter.entries) { f ->
            val label =
                when (f) {
                    BookshelfFilter.ALL -> "${f.label} $totalCount"
                    BookshelfFilter.HISTORY -> "${f.label} $historyCount"
                    BookshelfFilter.DOWNLOADED -> f.label
                }
            FilterChip(
                selected = f == selected,
                onClick = { onSelect(f) },
                label = { Text(label) },
            )
        }
    }
}

@Composable
private fun RecentCarouselSection(
    recentBooks: List<BookEntity>,
    coverStore: CoverStore,
    onResume: (BookEntity) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "Xem gần đây",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            color = Color(0xFF1F242D),
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(recentBooks, key = { it.id }) { book ->
                val coverModel = coverStore.resolveCoverPath(book.coverLocalPath)?.toFile() ?: book.coverUrl
                Box(
                    modifier =
                        Modifier
                            .width(236.dp)
                            .height(132.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color(0xFF283446))
                            .clickable { onResume(book) },
                ) {
                    if (coverModel != null) {
                        AsyncImage(
                            model = coverModel,
                            contentDescription = book.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    Box(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.verticalGradient(
                                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.78f)),
                                    ),
                                ).padding(12.dp),
                    ) {
                        Row(
                            modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth(),
                            verticalAlignment = Alignment.Bottom,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = book.title,
                                    color = Color.White,
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = "Chương ${book.lastReadChapterIndex + 1} / ${book.totalChapters}",
                                    color = Color.White.copy(alpha = 0.85f),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            Surface(
                                shape = CircleShape,
                                color = Color.White.copy(alpha = 0.25f),
                                modifier = Modifier.size(30.dp),
                            ) {
                                Icon(
                                    Icons.Default.PlayArrow,
                                    contentDescription = "Đọc tiếp",
                                    tint = Color.White,
                                    modifier = Modifier.padding(5.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookshelfCoverTile(
    book: BookEntity,
    coverStore: CoverStore,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val coverModel = coverStore.resolveCoverPath(book.coverLocalPath)?.toFile() ?: book.coverUrl
    val percent = (book.lastReadPercent * 100f).roundToInt().coerceIn(0, 100)

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.68f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF334155)),
        ) {
            if (coverModel != null) {
                AsyncImage(
                    model = coverModel,
                    contentDescription = book.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(
                    modifier = Modifier.fillMaxSize().padding(8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = book.title,
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Surface(
                color = Color.Black.copy(alpha = 0.72f),
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.align(Alignment.TopEnd).padding(5.dp),
            ) {
                Text(
                    text = "$percent%",
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                )
            }

            Surface(
                color = Color.Black.copy(alpha = 0.72f),
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.align(Alignment.BottomEnd).padding(5.dp),
            ) {
                Text(
                    text = "${book.totalChapters}",
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                )
            }
        }

        Text(
            text = book.title,
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            color = Color(0xFF1F242D),
        )
        Text(
            text = "Ch. ${book.lastReadChapterIndex + 1} (${book.downloadedChapters}/${book.totalChapters})",
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = Color(0xFF6B7280),
        )
    }
}

@Composable
private fun EmptyBookshelfState(onOpenExplore: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Default.Book,
            contentDescription = null,
            tint = Color(0xFF9CA3AF),
            modifier = Modifier.size(56.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text("Tủ sách chưa có truyện", style = MaterialTheme.typography.titleMedium)
        Text(
            text = "Nhấn Khám phá hoặc nút + để thêm truyện từ nguồn VBook.",
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF6B7280),
        )
        Spacer(Modifier.height(12.dp))
        TextButton(onClick = onOpenExplore) { Text("Đi tới Khám phá") }
    }
}
