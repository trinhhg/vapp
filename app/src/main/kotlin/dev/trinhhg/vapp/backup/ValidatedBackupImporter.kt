package dev.trinhhg.vapp.backup

import androidx.room.withTransaction
import dev.trinhhg.vapp.downloader.db.VappDatabase
import dev.trinhhg.vapp.engine.net.CookiePersistence
import dev.trinhhg.vapp.engine.security.ZipSecurityValidator
import dev.trinhhg.vapp.engine.vbook.ExtensionManager
import dev.trinhhg.vapp.settings.AppSettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okio.Path
import okio.Path.Companion.toOkioPath
import java.io.File
import java.io.FileInputStream
import java.util.UUID
import java.util.zip.ZipInputStream

/**
 * Validates `vapp-backup.zip` thoroughly in an isolated staging directory (Zip-Slip check, manifest schema check,
 * and JSON integrity check) BEFORE mutating any live Room rows or app files.
 */
class ValidatedBackupImporter(
    private val db: VappDatabase,
    private val settingsRepo: AppSettingsRepository,
    private val cookieStore: CookiePersistence,
    private val extensionManager: ExtensionManager?,
    private val engineRootDir: Path,
    private val dataRootDir: Path,
    private val stagingParentDir: File,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun restoreFromZip(backupZipFile: File): Result<BackupManifest> =
        withContext(Dispatchers.IO) {
            val stagingDir = File(stagingParentDir, "vapp_restore_${UUID.randomUUID()}")
            try {
                ZipSecurityValidator.validateZipFile(
                    zipPath = backupZipFile.toOkioPath(),
                    maxZipSizeBytes = MAX_BACKUP_ZIP_BYTES,
                    maxUncompressedBytes = MAX_BACKUP_UNCOMPRESSED_BYTES,
                    maxEntries = MAX_BACKUP_ENTRIES,
                )

                stagingDir.mkdirs()
                extractValidatedToStaging(backupZipFile, stagingDir)

                // Stage 1: Parse & validate all JSON payloads completely BEFORE touching live DB/files
                val manifestFile = File(stagingDir, "backup-manifest.json")
                val dbFile = File(stagingDir, "db-snapshot.json")
                val settingsFile = File(stagingDir, "settings.json")
                require(manifestFile.isFile) { "Missing backup-manifest.json in backup archive" }
                require(dbFile.isFile) { "Missing db-snapshot.json in backup archive" }

                val manifest = json.decodeFromString<BackupManifest>(manifestFile.readText(Charsets.UTF_8))
                require(manifest.schemaVersion in 1..BackupManifest.SUPPORTED_SCHEMA_VERSION) {
                    "Unsupported backup schemaVersion: ${manifest.schemaVersion}"
                }
                val dbSnapshot = json.decodeFromString<BackupDbSnapshot>(dbFile.readText(Charsets.UTF_8))
                val settingsSnapshot =
                    if (settingsFile.isFile) {
                        json.decodeFromString<BackupSettingsSnapshot>(settingsFile.readText(Charsets.UTF_8))
                    } else {
                        null
                    }

                // Stage 2: Atomic Room transaction swap
                db.withTransaction {
                    db.cleanupRuleDao().deleteAll()
                    db.bookDao().deleteAll()
                    db.extensionPackageDao().deleteAll()
                    db.repositorySourceDao().deleteAll()

                    if (dbSnapshot.repositories.isNotEmpty()) {
                        db.repositorySourceDao().upsertAll(dbSnapshot.repositories.map { it.toEntity() })
                    }
                    if (dbSnapshot.extensions.isNotEmpty()) {
                        db.extensionPackageDao().upsertAll(dbSnapshot.extensions.map { it.toEntity() })
                    }
                    if (dbSnapshot.books.isNotEmpty()) {
                        db.bookDao().upsertAll(dbSnapshot.books.map { it.toEntity() })
                    }
                    if (dbSnapshot.chapters.isNotEmpty()) {
                        db.chapterDao().upsertAll(dbSnapshot.chapters.map { it.toEntity() })
                    }
                    if (dbSnapshot.bookmarks.isNotEmpty()) {
                        db.bookmarkDao().upsertAll(dbSnapshot.bookmarks.map { it.toEntity() })
                    }
                    if (dbSnapshot.cleanupRules.isNotEmpty()) {
                        db.cleanupRuleDao().upsertAll(dbSnapshot.cleanupRules.map { it.toEntity() })
                    }
                }

                // Stage 3: Restore settings & cookies
                if (settingsSnapshot != null) {
                    val restoredSettings = settingsSnapshot.toAppSettings()
                    settingsRepo.update { restoredSettings }
                    cookieStore.clearAll()
                    settingsSnapshot.cookies.forEach { (host, rawCookie) ->
                        cookieStore.setRawCookie(host, rawCookie)
                    }
                }

                // Stage 4: Replace extensions & book files from validated staging dir
                val stagedExtensions = File(stagingDir, "extensions")
                val liveExtensions = engineRootDir.toFile().resolve("extensions")
                liveExtensions.deleteRecursively()
                if (stagedExtensions.exists()) {
                    stagedExtensions.copyRecursively(liveExtensions, overwrite = true)
                }
                extensionManager?.reloadFromDisk()

                val stagedBooks = File(stagingDir, "books")
                val liveBooks = dataRootDir.toFile().resolve("books")
                if (manifest.includesChapterContent) {
                    liveBooks.deleteRecursively()
                    if (stagedBooks.exists()) {
                        stagedBooks.copyRecursively(liveBooks, overwrite = true)
                    }
                }

                Result.success(manifest)
            } catch (t: Throwable) {
                Result.failure(t)
            } finally {
                runCatching { stagingDir.deleteRecursively() }
            }
        }

    private fun extractValidatedToStaging(
        zipFile: File,
        stagingDir: File,
    ) {
        val canonicalRoot = stagingDir.canonicalFile
        ZipInputStream(FileInputStream(zipFile)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    ZipSecurityValidator.validateEntryName(entry.name)
                    val dest = File(stagingDir, entry.name).canonicalFile
                    require(dest.path.startsWith(canonicalRoot.path + File.separator)) {
                        "ZIP entry escapes staging directory: ${entry.name}"
                    }
                    dest.parentFile?.mkdirs()
                    dest.outputStream().use { out -> zis.copyTo(out) }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    companion object {
        private const val MAX_BACKUP_ZIP_BYTES = 1024L * 1024 * 1024
        private const val MAX_BACKUP_UNCOMPRESSED_BYTES = 2L * 1024 * 1024 * 1024
        private const val MAX_BACKUP_ENTRIES = 100_000
    }
}
