package dev.trinhhg.vapp.backup

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.trinhhg.vapp.downloader.db.BookEntity
import dev.trinhhg.vapp.downloader.db.BookmarkEntity
import dev.trinhhg.vapp.downloader.db.ChapterDownloadStatus
import dev.trinhhg.vapp.downloader.db.ChapterEntity
import dev.trinhhg.vapp.downloader.db.CleanupRuleEntity
import dev.trinhhg.vapp.downloader.db.ExtensionPackageEntity
import dev.trinhhg.vapp.downloader.db.RepositorySourceEntity
import dev.trinhhg.vapp.downloader.db.VappDatabase
import dev.trinhhg.vapp.engine.net.OkioJsonCookieStore
import dev.trinhhg.vapp.settings.AppSettingsRepository
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
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class BackupRoundTripTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var db: VappDatabase
    private lateinit var settingsRepo: AppSettingsRepository
    private lateinit var cookieStore: OkioJsonCookieStore
    private lateinit var engineRoot: File
    private lateinit var dataRoot: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = VappDatabase.createInMemory(context, allowMainThread = true)
        settingsRepo = AppSettingsRepository(context)
        engineRoot = tempFolder.newFolder("engine_root")
        dataRoot = tempFolder.newFolder("data_root")
        cookieStore = OkioJsonCookieStore(engineRoot.resolve("cookies/cookies.json").toOkioPath())
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `BackupExporter and ValidatedBackupImporter round-trip DB, settings, cookies, and files`() =
        runTest {
            db.repositorySourceDao().upsert(RepositorySourceEntity("r1", "https://example.com/repo.json"))
            db.extensionPackageDao().upsert(ExtensionPackageEntity("ext1", "Truyện Test", sourceUrl = "https://example.com"))
            db.bookDao().upsert(
                BookEntity(
                    id = "b1",
                    sourceUrl = "https://example.com/book1",
                    extensionId = "ext1",
                    title = "Phàm Nhân Tu Tiên",
                    author = "Vong Ngữ",
                    inLibrary = true,
                    totalChapters = 1,
                    downloadedChapters = 1,
                    lastReadChapterIndex = 0,
                    lastReadScrollOffset = 4,
                    lastReadPercent = 0.5f,
                ),
            )
            val chapFile = dataRoot.resolve("books/b1/chapters/0_abc.html").apply { parentFile?.mkdirs() }
            chapFile.writeText("<p>Nội dung bảo toàn</p>", Charsets.UTF_8)
            db.chapterDao().upsertAll(
                listOf(
                    ChapterEntity(
                        id = "c1",
                        bookId = "b1",
                        orderIndex = 0,
                        title = "Chương 1",
                        sourceUrl = "https://example.com/book1/c1",
                        downloadStatus = ChapterDownloadStatus.COMPLETED,
                        contentPath = "books/b1/chapters/0_abc.html",
                    ),
                ),
            )
            db.bookmarkDao().upsert(BookmarkEntity("bm1", "b1", 0, "Chương 1", 4, "Nội dung bảo toàn"))
            db.cleanupRuleDao().upsert(CleanupRuleEntity("cr1", "\\[QC\\]", "", true, true, 0))
            settingsRepo.update { it.copy(fontSizeSp = 24, themePresetId = "sepia") }
            cookieStore.setRawCookie("https://example.com", "sid=abc123")

            val backupZip = tempFolder.newFile("vapp-backup.zip")
            val exporter =
                BackupExporter(
                    db = db,
                    settingsRepo = settingsRepo,
                    cookieStore = cookieStore,
                    engineRootDir = engineRoot.toOkioPath(),
                    dataRootDir = dataRoot.toOkioPath(),
                )
            val manifest = exporter.exportToZip(backupZip, includeChapterContent = true).getOrThrow()
            assertEquals(1, manifest.bookCount)
            assertEquals(1, manifest.chapterCount)

            // Wipe live state cleanly before restoring
            db.cleanupRuleDao().deleteAll()
            db.bookDao().deleteAll()
            db.extensionPackageDao().deleteAll()
            db.repositorySourceDao().deleteAll()
            cookieStore.clearAll()
            settingsRepo.update { it.copy(fontSizeSp = 14, themePresetId = "dark") }
            dataRoot.resolve("books").deleteRecursively()

            val importer =
                ValidatedBackupImporter(
                    db = db,
                    settingsRepo = settingsRepo,
                    cookieStore = cookieStore,
                    extensionManager = null,
                    engineRootDir = engineRoot.toOkioPath(),
                    dataRootDir = dataRoot.toOkioPath(),
                    stagingParentDir = tempFolder.newFolder("staging"),
                )
            val restoreRes = importer.restoreFromZip(backupZip)
            assertTrue(restoreRes.isSuccess)

            assertEquals("Phàm Nhân Tu Tiên", db.bookDao().getById("b1")?.title)
            assertEquals(1, db.chapterDao().listByBook("b1").size)
            assertEquals(1, db.bookmarkDao().listAll().size)
            assertEquals(1, db.cleanupRuleDao().listAll().size)
            assertEquals(24, settingsRepo.current().fontSizeSp)
            assertEquals("sepia", settingsRepo.current().themePresetId)
            assertEquals("sid=abc123", cookieStore.getCookieHeader("https://example.com"))
            assertEquals("<p>Nội dung bảo toàn</p>", chapFile.readText(Charsets.UTF_8))
        }

    @Test
    fun `ValidatedBackupImporter rejects Zip-Slip and future schema without mutating live DB`() =
        runTest {
            db.bookDao().upsert(
                BookEntity(
                    id = "keep",
                    sourceUrl = "https://example.com/keep",
                    title = "Sách Hiện Có",
                    inLibrary = true,
                ),
            )

            val importer =
                ValidatedBackupImporter(
                    db = db,
                    settingsRepo = settingsRepo,
                    cookieStore = cookieStore,
                    extensionManager = null,
                    engineRootDir = engineRoot.toOkioPath(),
                    dataRootDir = dataRoot.toOkioPath(),
                    stagingParentDir = tempFolder.newFolder("staging_bad"),
                )

            // 1. Zip-Slip attack archive
            val slipZip = tempFolder.newFile("zipslip.zip")
            ZipOutputStream(FileOutputStream(slipZip)).use { zos ->
                zos.putNextEntry(ZipEntry("../evil.txt"))
                zos.write("pwned".toByteArray())
                zos.closeEntry()
            }
            assertTrue(importer.restoreFromZip(slipZip).isFailure)
            assertEquals("Sách Hiện Có", db.bookDao().getById("keep")?.title)

            // 2. Unsupported future schemaVersion
            val futureZip = tempFolder.newFile("future.zip")
            ZipOutputStream(FileOutputStream(futureZip)).use { zos ->
                zos.putNextEntry(ZipEntry("backup-manifest.json"))
                zos.write(
                    """{"schemaVersion":99,"createdAtEpochMs":1,"bookCount":0,"chapterCount":0,"extensionCount":0,"includesChapterContent":true}"""
                        .toByteArray(),
                )
                zos.closeEntry()
                zos.putNextEntry(ZipEntry("db-snapshot.json"))
                zos.write("{}".toByteArray())
                zos.closeEntry()
            }
            assertTrue(importer.restoreFromZip(futureZip).isFailure)
            assertEquals("Sách Hiện Có", db.bookDao().getById("keep")?.title)
        }
}
