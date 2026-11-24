package dev.trinhhg.vapp.downloader

import androidx.room.withTransaction
import dev.trinhhg.vapp.downloader.db.BookEntity
import dev.trinhhg.vapp.downloader.db.ChapterDownloadStatus
import dev.trinhhg.vapp.downloader.db.ChapterEntity
import dev.trinhhg.vapp.downloader.db.VappDatabase
import dev.trinhhg.vapp.downloader.db.syncBookCounters
import dev.trinhhg.vapp.downloader.storage.ChapterFileStore
import dev.trinhhg.vapp.engine.api.BookMetadata
import dev.trinhhg.vapp.engine.api.ChapterRef
import dev.trinhhg.vapp.engine.util.nfc
import dev.trinhhg.vapp.engine.util.normalizeUrl
import dev.trinhhg.vapp.engine.util.sha256Hex
import kotlinx.coroutines.flow.Flow

data class MergeTocResult(
    val book: BookEntity,
    val newlyAddedChapters: Int,
)

/**
 * Manages persisted books, chapters, ongoing TOC merges, and transactional download counters.
 */
class BookLibraryRepository(
    private val db: VappDatabase,
    private val fileStore: ChapterFileStore,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val bookDao = db.bookDao()
    private val chapterDao = db.chapterDao()

    fun observeLibrary(): Flow<List<BookEntity>> = bookDao.observeLibrary()

    fun observeChapters(bookId: String): Flow<List<ChapterEntity>> = chapterDao.observeByBook(bookId)

    suspend fun getBook(bookId: String): BookEntity? = bookDao.getById(bookId)

    suspend fun getChapters(bookId: String): List<ChapterEntity> = chapterDao.listByBook(bookId)

    suspend fun saveBookAndMergeToc(
        extensionId: String?,
        metadata: BookMetadata,
        toc: List<ChapterRef>,
        inLibrary: Boolean = true,
    ): MergeTocResult {
        val canonicalBookUrl = metadata.url.normalizeUrl()
        val bookId = canonicalBookUrl.sha256Hex().take(24)
        val now = clock()

        return db.withTransaction {
            val existingBook = bookDao.getById(bookId)
            val book =
                if (existingBook != null) {
                    existingBook.copy(
                        extensionId = extensionId ?: existingBook.extensionId,
                        title = metadata.name.ifBlank { existingBook.title }.nfc(),
                        author = metadata.author.ifBlank { existingBook.author }.nfc(),
                        coverUrl = metadata.cover ?: existingBook.coverUrl,
                        synopsis = metadata.description.ifBlank { existingBook.synopsis },
                        detailInfo = metadata.detail.ifBlank { existingBook.detailInfo },
                        ongoing = metadata.ongoing,
                        inLibrary = existingBook.inLibrary || inLibrary,
                        updatedAt = now,
                    )
                } else {
                    BookEntity(
                        id = bookId,
                        sourceUrl = canonicalBookUrl,
                        extensionId = extensionId,
                        title = metadata.name.ifBlank { "Untitled" }.nfc(),
                        author = metadata.author.nfc(),
                        coverUrl = metadata.cover,
                        synopsis = metadata.description,
                        detailInfo = metadata.detail,
                        ongoing = metadata.ongoing,
                        inLibrary = inLibrary,
                        addedAt = now,
                        updatedAt = now,
                    )
                }
            bookDao.upsert(book)

            val newCount = mergeFetchedChaptersInternal(bookId, toc, now)
            chapterDao.syncBookCounters(bookId, bookDao, now)
            val refreshed = bookDao.getById(bookId) ?: book
            MergeTocResult(book = refreshed, newlyAddedChapters = newCount)
        }
    }

    /**
     * Merges a freshly fetched TOC into an existing book without clobbering already-downloaded
     * chapters (`sourceUrl` match preserves `id`, `downloadStatus`, and `contentPath`).
     */
    suspend fun mergeFetchedChapters(
        bookId: String,
        fetched: List<ChapterRef>,
    ): Int {
        val now = clock()
        return db.withTransaction {
            val added = mergeFetchedChaptersInternal(bookId, fetched, now)
            chapterDao.syncBookCounters(bookId, bookDao, now)
            added
        }
    }

    private suspend fun mergeFetchedChaptersInternal(
        bookId: String,
        fetched: List<ChapterRef>,
        now: Long,
    ): Int {
        val existingList = chapterDao.listByBook(bookId)
        val byUrl = existingList.associateBy { it.sourceUrl.normalizeUrl() }.toMutableMap()
        val seenUrls = HashSet<String>(fetched.size)
        val toUpsert = ArrayList<ChapterEntity>(fetched.size)
        var newCount = 0
        var nextIdx = 0

        for (ref in fetched) {
            val cleanUrl = ref.url.normalizeUrl()
            if (cleanUrl.isEmpty() || !seenUrls.add(cleanUrl)) continue
            val existing = byUrl.remove(cleanUrl)
            if (existing != null) {
                toUpsert.add(
                    existing.copy(
                        orderIndex = nextIdx++,
                        partName = ref.partName ?: existing.partName,
                        title = ref.name.ifBlank { existing.title }.nfc(),
                        updatedAt = now,
                    ),
                )
            } else {
                val chapterId = "${bookId}_${cleanUrl.sha256Hex().take(12)}"
                toUpsert.add(
                    ChapterEntity(
                        id = chapterId,
                        bookId = bookId,
                        orderIndex = nextIdx++,
                        partName = ref.partName,
                        title = ref.name.nfc(),
                        sourceUrl = cleanUrl,
                        downloadStatus = ChapterDownloadStatus.NOT_DOWNLOADED,
                        updatedAt = now,
                    ),
                )
                newCount++
            }
        }

        // Preserve any trailing old chapters not in partial refresh at the end
        for (leftover in byUrl.values.sortedBy { it.orderIndex }) {
            toUpsert.add(leftover.copy(orderIndex = nextIdx++, updatedAt = now))
        }

        if (toUpsert.isNotEmpty()) {
            chapterDao.upsertAll(toUpsert)
        }
        return newCount
    }

    suspend fun recountBook(bookId: String) {
        val now = clock()
        db.withTransaction {
            chapterDao.syncBookCounters(bookId, bookDao, now)
        }
    }

    suspend fun setInLibrary(
        bookId: String,
        inLibrary: Boolean,
    ) {
        bookDao.setInLibrary(bookId, inLibrary, clock())
    }

    suspend fun updateReadingProgress(
        bookId: String,
        chapterIndex: Int,
        scrollOffset: Int,
        percent: Float,
    ) {
        bookDao.updateReadingProgress(
            bookId = bookId,
            chapterIndex = chapterIndex,
            scrollOffset = scrollOffset,
            percent = percent.coerceIn(0f, 1f),
            lastReadAt = clock(),
        )
    }

    suspend fun deleteBook(bookId: String) {
        bookDao.deleteById(bookId)
        fileStore.deleteBookFiles(bookId)
    }
}
