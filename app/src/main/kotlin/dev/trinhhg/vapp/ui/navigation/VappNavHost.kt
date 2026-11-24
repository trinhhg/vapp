package dev.trinhhg.vapp.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.trinhhg.vapp.tts.AudiobookControllerImpl
import dev.trinhhg.vapp.tts.api.AudiobookController
import dev.trinhhg.vapp.ui.audiobook.AudiobookPlayerScreen
import dev.trinhhg.vapp.ui.bookshelf.BookshelfScreen
import dev.trinhhg.vapp.ui.detail.BookDetailScreen
import dev.trinhhg.vapp.ui.downloads.DownloadQueueScreen
import dev.trinhhg.vapp.ui.explore.AddUrlSheet
import dev.trinhhg.vapp.ui.explore.ExploreSourcesScreen
import dev.trinhhg.vapp.ui.explore.GlobalSearchScreen
import dev.trinhhg.vapp.ui.explore.SourceCatalogScreen
import dev.trinhhg.vapp.ui.extensions.DebugLogScreen
import dev.trinhhg.vapp.ui.extensions.ExtensionManagerScreen
import dev.trinhhg.vapp.ui.extensions.RepoManagerScreen
import dev.trinhhg.vapp.ui.reader.ReaderScreen
import dev.trinhhg.vapp.ui.settings.BackupRestoreScreen
import dev.trinhhg.vapp.ui.settings.CookieManagerScreen
import dev.trinhhg.vapp.ui.settings.SettingsScreen
import org.koin.compose.koinInject

sealed interface VappRoute {
    data object Bookshelf : VappRoute

    data object Explore : VappRoute

    data object Downloads : VappRoute

    data object Settings : VappRoute

    data class SourceCatalog(
        val extensionId: String,
    ) : VappRoute

    data class GlobalSearch(
        val initialQuery: String = "",
    ) : VappRoute

    data class BookDetail(
        val bookId: String,
    ) : VappRoute

    data class Reader(
        val bookId: String,
        val initialChapterIndex: Int? = null,
    ) : VappRoute

    data class Audiobook(
        val bookId: String?,
        val initialChapterIndex: Int? = null,
        val initialParagraphIndex: Int = 0,
    ) : VappRoute

    data object ExtensionManager : VappRoute

    data object RepoManager : VappRoute

    data object DebugLogs : VappRoute

    data object CookieManager : VappRoute

    data object BackupRestore : VappRoute
}

private enum class TopTabItem(
    val route: VappRoute,
    val label: String,
    val icon: ImageVector,
) {
    BOOKSHELF(VappRoute.Bookshelf, "Tủ sách", Icons.AutoMirrored.Filled.MenuBook),
    EXPLORE(VappRoute.Explore, "Khám phá", Icons.Default.Explore),
    DOWNLOADS(VappRoute.Downloads, "Tải xuống", Icons.Default.Download),
    SETTINGS(VappRoute.Settings, "Cài đặt", Icons.Default.Settings),
}

@Composable
fun VappNavHost(audiobookController: AudiobookController = koinInject()) {
    val backStack = remember { mutableStateListOf<VappRoute>(VappRoute.Bookshelf) }
    var showAddUrlSheet by remember { mutableStateOf(false) }
    val audioState by audiobookController.playbackState.collectAsState()

    val currentRoute = backStack.lastOrNull() ?: VappRoute.Bookshelf
    val isTopLevelTab =
        currentRoute == VappRoute.Bookshelf ||
            currentRoute == VappRoute.Explore ||
            currentRoute == VappRoute.Downloads ||
            currentRoute == VappRoute.Settings

    fun push(route: VappRoute) {
        if (backStack.lastOrNull() != route) {
            backStack.add(route)
        }
    }

    fun pop() {
        if (backStack.size > 1) {
            backStack.removeAt(backStack.lastIndex)
        }
    }

    fun selectTab(tabRoute: VappRoute) {
        backStack.clear()
        backStack.add(tabRoute)
    }

    BackHandler(enabled = backStack.size > 1 || currentRoute != VappRoute.Bookshelf) {
        if (backStack.size > 1) {
            pop()
        } else {
            selectTab(VappRoute.Bookshelf)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        when (currentRoute) {
            VappRoute.Bookshelf -> {
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .statusBarsPadding()
                            .padding(bottom = 86.dp),
                ) {
                    BookshelfScreen(
                        onOpenBookDetail = { bookId -> push(VappRoute.BookDetail(bookId)) },
                        onContinueReading = { bookId, chIdx -> push(VappRoute.Reader(bookId, chIdx)) },
                        onOpenExplore = { selectTab(VappRoute.Explore) },
                    )
                }
            }

            VappRoute.Explore -> {
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .statusBarsPadding()
                            .padding(bottom = 86.dp),
                ) {
                    ExploreSourcesScreen(
                        onOpenCatalog = { extId -> push(VappRoute.SourceCatalog(extId)) },
                        onOpenGlobalSearch = { push(VappRoute.GlobalSearch()) },
                        onOpenExtensionManager = { push(VappRoute.ExtensionManager) },
                    )
                }
            }

            VappRoute.Downloads -> {
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .statusBarsPadding()
                            .padding(bottom = 86.dp),
                ) {
                    DownloadQueueScreen(
                        onOpenBookDetail = { bookId -> push(VappRoute.BookDetail(bookId)) },
                    )
                }
            }

            VappRoute.Settings -> {
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .statusBarsPadding()
                            .padding(bottom = 86.dp),
                ) {
                    SettingsScreen(
                        onOpenExtensions = { push(VappRoute.ExtensionManager) },
                        onOpenCookies = { push(VappRoute.CookieManager) },
                        onOpenBackupRestore = { push(VappRoute.BackupRestore) },
                        onOpenDebugLogs = { push(VappRoute.DebugLogs) },
                    )
                }
            }

            is VappRoute.SourceCatalog -> {
                SourceCatalogScreen(
                    extensionId = currentRoute.extensionId,
                    onBack = ::pop,
                    onOpenBookDetail = { bookId -> push(VappRoute.BookDetail(bookId)) },
                )
            }

            is VappRoute.GlobalSearch -> {
                GlobalSearchScreen(
                    initialQuery = currentRoute.initialQuery,
                    onBack = ::pop,
                    onOpenBookDetail = { bookId -> push(VappRoute.BookDetail(bookId)) },
                )
            }

            is VappRoute.BookDetail -> {
                BookDetailScreen(
                    bookId = currentRoute.bookId,
                    onBack = ::pop,
                    onReadChapter = { bookId, chIdx -> push(VappRoute.Reader(bookId, chIdx)) },
                    onListenAudiobook = { bookId, chIdx -> push(VappRoute.Audiobook(bookId, chIdx, 0)) },
                )
            }

            is VappRoute.Reader -> {
                ReaderScreen(
                    bookId = currentRoute.bookId,
                    initialChapterIndex = currentRoute.initialChapterIndex,
                    onBack = ::pop,
                    onOpenAudiobook = { bookId, chIdx, paraIdx ->
                        push(VappRoute.Audiobook(bookId, chIdx, paraIdx))
                    },
                )
            }

            is VappRoute.Audiobook -> {
                Box(modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                    AudiobookPlayerScreen(
                        bookId = currentRoute.bookId,
                        initialChapterIndex = currentRoute.initialChapterIndex,
                        initialParagraphIndex = currentRoute.initialParagraphIndex,
                        onCollapse = ::pop,
                        onClose = {
                            (audiobookController as? AudiobookControllerImpl)?.stop() ?: audiobookController.pause()
                            pop()
                        },
                        onDownloadBookClick = { bookId ->
                            pop()
                            push(VappRoute.BookDetail(bookId))
                        },
                    )
                }
            }

            VappRoute.ExtensionManager -> {
                ExtensionManagerScreen(
                    onBack = ::pop,
                    onOpenRepos = { push(VappRoute.RepoManager) },
                    onOpenDebugLogs = { push(VappRoute.DebugLogs) },
                )
            }

            VappRoute.RepoManager -> {
                RepoManagerScreen(onBack = ::pop)
            }

            VappRoute.DebugLogs -> {
                DebugLogScreen(onBack = ::pop)
            }

            VappRoute.CookieManager -> {
                CookieManagerScreen(onBack = ::pop)
            }

            VappRoute.BackupRestore -> {
                BackupRestoreScreen(onBack = ::pop)
            }
        }

        if (isTopLevelTab) {
            Column(
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (audioState.isPlaying && audioState.bookId != null) {
                    Surface(
                        shape = RoundedCornerShape(22.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.94f),
                        shadowElevation = 4.dp,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    push(VappRoute.Audiobook(bookId = null))
                                },
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Icon(
                                Icons.Default.Headphones,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp),
                            )
                            Text(
                                text = "Đang nghe: ${audioState.chapterTitle.ifBlank { "Chương ${audioState.chapterIndex + 1}" }}",
                                style = MaterialTheme.typography.labelLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }

                FloatingCapsuleBottomBar(
                    currentRoute = currentRoute,
                    onSelectTab = ::selectTab,
                    onAddClick = { showAddUrlSheet = true },
                )
            }
        }
    }

    if (showAddUrlSheet) {
        AddUrlSheet(
            onDismiss = { showAddUrlSheet = false },
            onBookAdded = { bookId -> push(VappRoute.BookDetail(bookId)) },
        )
    }
}

/**
 * Floating frosted-capsule 4-tab bar paired with a detached circular `+` button,
 * modeled on reference screenshot #4.
 */
@Composable
private fun FloatingCapsuleBottomBar(
    currentRoute: VappRoute,
    onSelectTab: (VappRoute) -> Unit,
    onAddClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Surface(
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(32.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.90f),
            tonalElevation = 6.dp,
            shadowElevation = 10.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TopTabItem.entries.forEach { tab ->
                    val selected = currentRoute == tab.route
                    val tint =
                        if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    Surface(
                        shape = RoundedCornerShape(24.dp),
                        color =
                            if (selected) {
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                            } else {
                                androidx.compose.ui.graphics.Color.Transparent
                            },
                        modifier =
                            Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(24.dp))
                                .clickable { onSelectTab(tab.route) },
                    ) {
                        Column(
                            modifier = Modifier.padding(vertical = 6.dp, horizontal = 4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Icon(
                                imageVector = tab.icon,
                                contentDescription = tab.label,
                                tint = tint,
                                modifier = Modifier.size(21.dp),
                            )
                            Text(
                                text = tab.label,
                                color = tint,
                                fontSize = 11.sp,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }

        Surface(
            modifier = Modifier.size(56.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
            tonalElevation = 6.dp,
            shadowElevation = 10.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
            onClick = onAddClick,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Thêm URL",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(26.dp),
                )
            }
        }
    }
}
