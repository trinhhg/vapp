package dev.trinhhg.vapp.downloader.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import dev.trinhhg.vapp.engine.vbook.ExtensionType

enum class ChapterDownloadStatus {
    NOT_DOWNLOADED,
    QUEUED,
    DOWNLOADING,
    COMPLETED,
    FAILED,
    EMPTY,
}

enum class DownloadTaskStatus {
    PENDING,
    RUNNING,
    PAUSED,
    COMPLETED,
    FAILED,
    CANCELLED,
}

@Entity(
    tableName = "repositories",
    indices = [Index(value = ["url"], unique = true)],
)
data class RepositorySourceEntity(
    @PrimaryKey val id: String,
    val url: String,
    val author: String = "",
    val description: String = "",
    val enabled: Boolean = true,
    val lastSyncedAt: Long? = null,
)

@Entity(tableName = "extensions")
data class ExtensionPackageEntity(
    @PrimaryKey val id: String,
    val name: String,
    val author: String = "",
    val version: Int = 1,
    val sourceUrl: String = "",
    val urlRegex: String = "",
    val type: ExtensionType = ExtensionType.NOVEL,
    val locale: String = "vi_VN",
    val description: String = "",
    val iconPath: String? = null,
    val scriptsDir: String = "",
    val scriptMapJson: String = "{}",
    val configSpecJson: String = "{}",
    val userConfigJson: String = "{}",
    val enabled: Boolean = true,
    val installedAt: Long = 0L,
    val updatedAt: Long = 0L,
)

@Entity(
    tableName = "books",
    foreignKeys = [
        ForeignKey(
            entity = ExtensionPackageEntity::class,
            parentColumns = ["id"],
            childColumns = ["extensionId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index(value = ["sourceUrl"], unique = true),
        Index(value = ["extensionId"]),
    ],
)
data class BookEntity(
    @PrimaryKey val id: String,
    val sourceUrl: String,
    val extensionId: String? = null,
    val title: String,
    val author: String = "",
    val coverUrl: String? = null,
    val coverLocalPath: String? = null,
    val synopsis: String = "",
    val detailInfo: String = "",
    val ongoing: Boolean = false,
    val inLibrary: Boolean = false,
    val totalChapters: Int = 0,
    val downloadedChapters: Int = 0,
    val lastReadChapterIndex: Int = 0,
    val lastReadScrollOffset: Int = 0,
    val lastReadPercent: Float = 0.0f,
    val addedAt: Long = 0L,
    val lastReadAt: Long? = null,
    val updatedAt: Long = 0L,
)

@Entity(
    tableName = "chapters",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["bookId"]),
        Index(value = ["bookId", "orderIndex"]),
        Index(value = ["bookId", "sourceUrl"], unique = true),
    ],
)
data class ChapterEntity(
    @PrimaryKey val id: String,
    val bookId: String,
    val orderIndex: Int,
    val partName: String? = null,
    val title: String,
    val sourceUrl: String,
    val downloadStatus: ChapterDownloadStatus = ChapterDownloadStatus.NOT_DOWNLOADED,
    val contentPath: String? = null,
    val charCount: Int = 0,
    val isImageChapter: Boolean = false,
    val errorMessage: String? = null,
    val updatedAt: Long = 0L,
)

@Entity(
    tableName = "download_tasks",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["bookId"])],
)
data class DownloadTaskEntity(
    @PrimaryKey val id: String,
    val bookId: String,
    val selectionSpec: String,
    val status: DownloadTaskStatus = DownloadTaskStatus.PENDING,
    val completedCount: Int = 0,
    val totalCount: Int = 0,
    val failedCount: Int = 0,
    val workRequestId: String? = null,
    val lastError: String? = null,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
)

@Entity(
    tableName = "bookmarks",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["bookId"])],
)
data class BookmarkEntity(
    @PrimaryKey val id: String,
    val bookId: String,
    val chapterIndex: Int,
    val chapterTitle: String,
    val scrollOffset: Int,
    val snippet: String = "",
    val note: String? = null,
    val createdAt: Long = 0L,
)

@Entity(tableName = "cleanup_rules")
data class CleanupRuleEntity(
    @PrimaryKey val id: String,
    val pattern: String,
    val replacement: String = "",
    val isRegex: Boolean = true,
    val enabled: Boolean = true,
    val sortOrder: Int = 0,
)
