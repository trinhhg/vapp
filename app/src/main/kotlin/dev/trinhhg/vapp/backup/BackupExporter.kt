package dev.trinhhg.vapp.backup

import dev.trinhhg.vapp.downloader.db.VappDatabase
import dev.trinhhg.vapp.engine.net.CookiePersistence
import dev.trinhhg.vapp.settings.AppSettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okio.Path
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Exports all library metadata, bookmarks, cleanup rules, settings, cookies, installed extensions,
 * and optional chapter HTML/cover files into a portable local `vapp-backup.zip` atomically via `.tmp`.
 */
class BackupExporter(
    private val db: VappDatabase,
    private val settingsRepo: AppSettingsRepository,
    private val cookieStore: CookiePersistence,
    private val engineRootDir: Path,
    private val dataRootDir: Path,
    private val appVersion: String = "0.1.0",
) {
    private val json =
        Json {
            prettyPrint = true
            ignoreUnknownKeys = true
        }

    suspend fun exportToZip(
        outputZipFile: File,
        includeChapterContent: Boolean = true,
    ): Result<BackupManifest> =
        withContext(Dispatchers.IO) {
            outputZipFile.parentFile?.let { if (!it.exists()) it.mkdirs() }
            val tmpFile = File(outputZipFile.parentFile, "${outputZipFile.name}.tmp")
            if (tmpFile.exists()) tmpFile.delete()

            runCatching {
                val repos = db.repositorySourceDao().listAll().map { it.toDto() }
                val exts = db.extensionPackageDao().listAll().map { it.toDto() }
                val books = db.bookDao().listAll().map { it.toDto() }
                val chapters = db.chapterDao().listAll().map { it.toDto() }
                val bookmarks = db.bookmarkDao().listAll().map { it.toDto() }
                val cleanupRules = db.cleanupRuleDao().listAll().map { it.toDto() }
                val settings = settingsRepo.current().toBackupDto(cookieStore.allCookies())

                val manifest =
                    BackupManifest(
                        schemaVersion = BackupManifest.SUPPORTED_SCHEMA_VERSION,
                        appVersion = appVersion,
                        createdAtEpochMs = System.currentTimeMillis(),
                        bookCount = books.size,
                        chapterCount = chapters.size,
                        extensionCount = exts.size,
                        includesChapterContent = includeChapterContent,
                    )
                val dbSnapshot =
                    BackupDbSnapshot(
                        repositories = repos,
                        extensions = exts,
                        books = books,
                        chapters = chapters,
                        bookmarks = bookmarks,
                        cleanupRules = cleanupRules,
                    )

                FileOutputStream(tmpFile).use { fos ->
                    ZipOutputStream(fos, Charsets.UTF_8).use { zos ->
                        writeJsonEntry(zos, "backup-manifest.json", json.encodeToString(manifest))
                        writeJsonEntry(zos, "db-snapshot.json", json.encodeToString(dbSnapshot))
                        writeJsonEntry(zos, "settings.json", json.encodeToString(settings))

                        val extDir = engineRootDir.toFile().resolve("extensions")
                        if (extDir.exists() && extDir.isDirectory) {
                            addDirectoryToZip(zos, extDir, "extensions")
                        }

                        if (includeChapterContent) {
                            val booksDir = dataRootDir.toFile().resolve("books")
                            if (booksDir.exists() && booksDir.isDirectory) {
                                addDirectoryToZip(zos, booksDir, "books")
                            }
                        }
                        zos.finish()
                    }
                }

                Files.move(tmpFile.toPath(), outputZipFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
                manifest
            }.onFailure {
                runCatching { tmpFile.delete() }
            }
        }

    private fun writeJsonEntry(
        zos: ZipOutputStream,
        entryName: String,
        content: String,
    ) {
        zos.putNextEntry(ZipEntry(entryName))
        zos.write(content.toByteArray(Charsets.UTF_8))
        zos.closeEntry()
    }

    private fun addDirectoryToZip(
        zos: ZipOutputStream,
        baseDir: File,
        zipPrefix: String,
    ) {
        baseDir.walkTopDown().filter { it.isFile && !it.name.endsWith(".tmp") }.forEach { file ->
            val rel =
                baseDir
                    .toPath()
                    .relativize(file.toPath())
                    .toString()
                    .replace("\\", "/")
            zos.putNextEntry(ZipEntry("$zipPrefix/$rel"))
            file.inputStream().use { it.copyTo(zos) }
            zos.closeEntry()
        }
    }
}
