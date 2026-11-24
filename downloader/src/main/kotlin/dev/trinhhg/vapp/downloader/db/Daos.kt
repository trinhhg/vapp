package dev.trinhhg.vapp.downloader.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface RepositorySourceDao {
    @Query("SELECT * FROM repositories ORDER BY url ASC")
    fun observeAll(): Flow<List<RepositorySourceEntity>>

    @Query("SELECT * FROM repositories ORDER BY url ASC")
    suspend fun listAll(): List<RepositorySourceEntity>

    @Query("SELECT * FROM repositories WHERE enabled = 1 ORDER BY url ASC")
    suspend fun listEnabled(): List<RepositorySourceEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: RepositorySourceEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<RepositorySourceEntity>)

    @Query("DELETE FROM repositories WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM repositories")
    suspend fun deleteAll()
}

@Dao
interface ExtensionPackageDao {
    @Query("SELECT * FROM extensions ORDER BY name ASC")
    fun observeAll(): Flow<List<ExtensionPackageEntity>>

    @Query("SELECT * FROM extensions ORDER BY name ASC")
    suspend fun listAll(): List<ExtensionPackageEntity>

    @Query("SELECT * FROM extensions WHERE id = :id")
    suspend fun getById(id: String): ExtensionPackageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: ExtensionPackageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<ExtensionPackageEntity>)

    @Query("DELETE FROM extensions WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM extensions")
    suspend fun deleteAll()
}

@Dao
interface BookDao {
    @Query("SELECT * FROM books WHERE inLibrary = 1 ORDER BY COALESCE(lastReadAt, addedAt) DESC")
    fun observeLibrary(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books ORDER BY COALESCE(lastReadAt, addedAt) DESC")
    suspend fun listAll(): List<BookEntity>

    @Query("SELECT * FROM books WHERE id = :bookId")
    suspend fun getById(bookId: String): BookEntity?

    @Query("SELECT * FROM books WHERE sourceUrl = :sourceUrl LIMIT 1")
    suspend fun getBySourceUrl(sourceUrl: String): BookEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(book: BookEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(books: List<BookEntity>)

    @Query("DELETE FROM books")
    suspend fun deleteAll()

    @Query(
        "UPDATE books SET totalChapters = :totalChapters, downloadedChapters = :downloadedChapters, " +
            "updatedAt = :updatedAt WHERE id = :bookId",
    )
    suspend fun updateChapterCounters(
        bookId: String,
        totalChapters: Int,
        downloadedChapters: Int,
        updatedAt: Long,
    )

    @Query(
        "UPDATE books SET lastReadChapterIndex = :chapterIndex, lastReadScrollOffset = :scrollOffset, " +
            "lastReadPercent = :percent, lastReadAt = :lastReadAt, updatedAt = :lastReadAt WHERE id = :bookId",
    )
    suspend fun updateReadingProgress(
        bookId: String,
        chapterIndex: Int,
        scrollOffset: Int,
        percent: Float,
        lastReadAt: Long,
    )

    @Query("UPDATE books SET inLibrary = :inLibrary, updatedAt = :updatedAt WHERE id = :bookId")
    suspend fun setInLibrary(
        bookId: String,
        inLibrary: Boolean,
        updatedAt: Long,
    )

    @Query("DELETE FROM books WHERE id = :bookId")
    suspend fun deleteById(bookId: String)
}

@Dao
interface ChapterDao {
    @Query("SELECT * FROM chapters WHERE bookId = :bookId ORDER BY orderIndex ASC")
    fun observeByBook(bookId: String): Flow<List<ChapterEntity>>

    @Query("SELECT * FROM chapters WHERE bookId = :bookId ORDER BY orderIndex ASC")
    suspend fun listByBook(bookId: String): List<ChapterEntity>

    @Query("SELECT * FROM chapters ORDER BY bookId ASC, orderIndex ASC")
    suspend fun listAll(): List<ChapterEntity>

    @Query("SELECT * FROM chapters WHERE id = :chapterId")
    suspend fun getById(chapterId: String): ChapterEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(chapters: List<ChapterEntity>)

    @Update
    suspend fun update(chapter: ChapterEntity)

    @Query(
        "UPDATE chapters SET downloadStatus = :status, contentPath = :contentPath, charCount = :charCount, " +
            "isImageChapter = :isImageChapter, errorMessage = :errorMessage, updatedAt = :updatedAt " +
            "WHERE id = :chapterId",
    )
    suspend fun updateDownloadResult(
        chapterId: String,
        status: ChapterDownloadStatus,
        contentPath: String?,
        charCount: Int,
        isImageChapter: Boolean,
        errorMessage: String?,
        updatedAt: Long,
    )

    @Query("UPDATE chapters SET downloadStatus = :status, updatedAt = :updatedAt WHERE id IN (:chapterIds)")
    suspend fun updateStatusBatch(
        chapterIds: List<String>,
        status: ChapterDownloadStatus,
        updatedAt: Long,
    )

    @Query("SELECT COUNT(*) FROM chapters WHERE bookId = :bookId")
    suspend fun countTotal(bookId: String): Int

    @Query("SELECT COUNT(*) FROM chapters WHERE bookId = :bookId AND downloadStatus = :completedStatus")
    suspend fun countByStatus(
        bookId: String,
        completedStatus: ChapterDownloadStatus = ChapterDownloadStatus.COMPLETED,
    ): Int
}

suspend fun ChapterDao.syncBookCounters(
    bookId: String,
    bookDao: BookDao,
    nowMs: Long,
) {
    val total = countTotal(bookId)
    val completed = countByStatus(bookId, ChapterDownloadStatus.COMPLETED)
    bookDao.updateChapterCounters(bookId, total, completed, nowMs)
}

@Dao
interface DownloadTaskDao {
    @Query("SELECT * FROM download_tasks ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<DownloadTaskEntity>>

    @Query("SELECT * FROM download_tasks WHERE id = :taskId")
    suspend fun getById(taskId: String): DownloadTaskEntity?

    @Query("SELECT * FROM download_tasks WHERE status IN (:statuses)")
    suspend fun listByStatuses(statuses: List<DownloadTaskStatus>): List<DownloadTaskEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(task: DownloadTaskEntity)

    @Query(
        "UPDATE download_tasks SET status = :status, workRequestId = :workRequestId, updatedAt = :updatedAt " +
            "WHERE id = :taskId",
    )
    suspend fun updateStatusAndWorkId(
        taskId: String,
        status: DownloadTaskStatus,
        workRequestId: String?,
        updatedAt: Long,
    )

    @Query(
        "UPDATE download_tasks SET status = :status, completedCount = :completedCount, " +
            "failedCount = :failedCount, lastError = :lastError, updatedAt = :updatedAt WHERE id = :taskId",
    )
    suspend fun updateProgress(
        taskId: String,
        status: DownloadTaskStatus,
        completedCount: Int,
        failedCount: Int,
        lastError: String?,
        updatedAt: Long,
    )

    @Query("DELETE FROM download_tasks WHERE id = :taskId")
    suspend fun deleteById(taskId: String)
}

@Dao
interface BookmarkDao {
    @Query("SELECT * FROM bookmarks WHERE bookId = :bookId ORDER BY chapterIndex ASC, scrollOffset ASC")
    fun observeByBook(bookId: String): Flow<List<BookmarkEntity>>

    @Query("SELECT * FROM bookmarks ORDER BY bookId ASC, chapterIndex ASC")
    suspend fun listAll(): List<BookmarkEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(bookmark: BookmarkEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(bookmarks: List<BookmarkEntity>)

    @Query("DELETE FROM bookmarks WHERE id = :bookmarkId")
    suspend fun deleteById(bookmarkId: String)
}

@Dao
interface CleanupRuleDao {
    @Query("SELECT * FROM cleanup_rules ORDER BY sortOrder ASC")
    fun observeAll(): Flow<List<CleanupRuleEntity>>

    @Query("SELECT * FROM cleanup_rules ORDER BY sortOrder ASC")
    suspend fun listAll(): List<CleanupRuleEntity>

    @Query("SELECT * FROM cleanup_rules WHERE enabled = 1 ORDER BY sortOrder ASC")
    suspend fun listEnabled(): List<CleanupRuleEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(rule: CleanupRuleEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rules: List<CleanupRuleEntity>)

    @Query("DELETE FROM cleanup_rules WHERE id = :ruleId")
    suspend fun deleteById(ruleId: String)

    @Query("DELETE FROM cleanup_rules")
    suspend fun deleteAll()
}
