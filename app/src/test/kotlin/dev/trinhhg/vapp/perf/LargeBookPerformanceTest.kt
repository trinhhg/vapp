package dev.trinhhg.vapp.perf

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import dev.trinhhg.vapp.downloader.BookLibraryRepository
import dev.trinhhg.vapp.downloader.db.ExtensionPackageEntity
import dev.trinhhg.vapp.downloader.db.VappDatabase
import dev.trinhhg.vapp.downloader.storage.ChapterFileStore
import dev.trinhhg.vapp.engine.api.BookMetadata
import dev.trinhhg.vapp.engine.api.ChapterRef
import dev.trinhhg.vapp.exporter.BookExporterImpl
import dev.trinhhg.vapp.exporter.api.BookExportMetadata
import dev.trinhhg.vapp.exporter.api.ChapterExportInput
import dev.trinhhg.vapp.exporter.api.ExportFormat
import dev.trinhhg.vapp.exporter.api.ExportRequest
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toOkioPath
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import kotlin.system.measureTimeMillis
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class LargeBookPerformanceTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var db: VappDatabase
    private lateinit var fileStore: ChapterFileStore
    private lateinit var repo: BookLibraryRepository

    @Before
    fun setUp() {
        db = VappDatabase.createInMemory(ApplicationProvider.getApplicationContext(), allowMainThread = true)
        fileStore = ChapterFileStore(tempFolder.newFolder("vapp_root").toOkioPath())
        repo = BookLibraryRepository(db, fileStore)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `3000-chapter TOC query completes well under 300ms target`() =
        runTest {
            db.extensionPackageDao().upsert(ExtensionPackageEntity("ext_perf", "Perf Source"))
            val toc =
                (1..3000).map { idx ->
                    ChapterRef(
                        index = idx - 1,
                        name = "Chương $idx: Khởi đầu hành trình mới",
                        url = "https://example.com/truyen/chap-$idx",
                    )
                }
            val saved =
                repo.saveBookAndMergeToc(
                    extensionId = "ext_perf",
                    metadata =
                        BookMetadata(
                            name = "Trường Thiên Đại Tác",
                            author = "Tác giả",
                            url = "https://example.com/truyen",
                        ),
                    toc = toc,
                    inLibrary = true,
                )
            assertEquals(3000, saved.book.totalChapters)

            // Warm query plan once, then measure indexed TOC load
            repo.getChapters(saved.book.id)
            var loadedCount = 0
            val elapsedMs =
                measureTimeMillis {
                    loadedCount = repo.getChapters(saved.book.id).size
                }
            assertEquals(3000, loadedCount)
            assertTrue(elapsedMs < 300, "Expected 3000-chapter TOC load < 300ms, got ${elapsedMs}ms")
        }

    @Test
    fun `streaming EPUB 3 export handles 3000 lazy chapters cleanly`() =
        runTest {
            val inputs =
                (0 until 3000).map { idx ->
                    ChapterExportInput(
                        orderIndex = idx,
                        title = "Chương ${idx + 1}",
                        htmlProvider = {
                            "<p>Đoạn văn mẫu tiếng Việt của chương ${idx + 1} với ký tự ă â đ ê ô ơ ư.</p>"
                        },
                    )
                }
            val outEpub = File(tempFolder.root, "large_3000.epub")
            val exporter = BookExporterImpl()
            val result =
                exporter.export(
                    ExportRequest(
                        book = BookExportMetadata(id = "perf3k", title = "Đại Tác 3000", author = "Vapp"),
                        chapters = inputs,
                        format = ExportFormat.EPUB_3,
                        includeCover = false,
                        includeImages = false,
                        autoSplitVolumes = false,
                        outputFilePath = outEpub.absolutePath,
                    ),
                )
            assertTrue(result.isSuccess, "Export failed: ${result.exceptionOrNull()?.message}")
            assertEquals(3000, result.getOrThrow().totalChaptersExported)

            ZipFile(outEpub).use { zip ->
                val entries = zip.entries().toList()
                assertEquals("mimetype", entries.first().name)
                assertEquals(ZipEntry.STORED, entries.first().method)
                val chapterXhtmlCount = entries.count { it.name.startsWith("OEBPS/text/chap_") && it.name.endsWith(".xhtml") }
                assertEquals(3000, chapterXhtmlCount)
            }
        }
}
