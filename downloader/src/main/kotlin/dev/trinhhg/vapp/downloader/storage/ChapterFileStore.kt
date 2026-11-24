package dev.trinhhg.vapp.downloader.storage

import dev.trinhhg.vapp.engine.util.sha256Hex
import okio.FileSystem
import okio.Path
import java.io.IOException

/**
 * Atomic on-disk storage for downloaded chapter HTML files and normalized book covers.
 * Writes always target a `.tmp` sibling file first, verify non-empty payload, then perform atomic rename.
 */
class ChapterFileStore(
    private val vappRootDir: Path,
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
) {
    fun saveChapterHtml(
        bookId: String,
        chapterId: String,
        orderIndex: Int,
        html: String,
    ): String {
        val trimmed = html.trim()
        require(trimmed.isNotEmpty()) { "Refusing to write blank chapter HTML for $chapterId" }

        val shortHash = chapterId.sha256Hex().take(8)
        val relPath = "books/$bookId/chapters/${orderIndex}_$shortHash.html"
        val target = vappRootDir / relPath
        writeAtomically(target, html.encodeToByteArray())
        if (!hasValidChapterFile(relPath)) {
            throw IOException("Chapter file verification failed after atomic write: $relPath")
        }
        return relPath
    }

    fun readChapterHtml(contentPath: String?): String? {
        if (contentPath.isNullOrBlank()) return null
        val target = vappRootDir / contentPath
        if (!fileSystem.exists(target)) return null
        return runCatching {
            fileSystem.read(target) { readUtf8() }
        }.getOrNull()
    }

    fun hasValidChapterFile(contentPath: String?): Boolean {
        if (contentPath.isNullOrBlank()) return false
        val target = vappRootDir / contentPath
        val meta = fileSystem.metadataOrNull(target) ?: return false
        return meta.isRegularFile && (meta.size ?: 0L) > 0L
    }

    fun deleteBookFiles(bookId: String) {
        val dir = vappRootDir / "books" / bookId
        runCatching { fileSystem.deleteRecursively(dir) }
    }

    private fun writeAtomically(
        target: Path,
        bytes: ByteArray,
    ) {
        require(bytes.isNotEmpty()) { "Refusing to write empty bytes to $target" }
        val parent = target.parent ?: error("Missing parent directory for $target")
        fileSystem.createDirectories(parent)
        val tmp = parent / "${target.name}.tmp"
        try {
            fileSystem.write(tmp) {
                write(bytes)
            }
            val tmpSize = fileSystem.metadataOrNull(tmp)?.size ?: 0L
            if (tmpSize <= 0L) {
                throw IOException("Temp file is 0 bytes: $tmp")
            }
            if (fileSystem.exists(target)) {
                fileSystem.delete(target)
            }
            fileSystem.atomicMove(tmp, target)
        } catch (t: Throwable) {
            runCatching { fileSystem.delete(tmp) }
            throw t
        }
    }

    fun resolveAbsolutePath(relativePath: String): Path = vappRootDir / relativePath
}

class CoverStore(
    private val vappRootDir: Path,
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
) {
    fun saveCoverBytes(
        bookId: String,
        bytes: ByteArray,
    ): String {
        require(bytes.isNotEmpty()) { "Refusing to write empty cover bytes for $bookId" }
        val relPath = "books/$bookId/cover.jpg"
        val target = vappRootDir / relPath
        val parent = target.parent ?: error("Missing parent directory for $target")
        fileSystem.createDirectories(parent)
        val tmp = parent / "${target.name}.tmp"
        try {
            fileSystem.write(tmp) { write(bytes) }
            if (fileSystem.exists(target)) fileSystem.delete(target)
            fileSystem.atomicMove(tmp, target)
            return relPath
        } catch (t: Throwable) {
            runCatching { fileSystem.delete(tmp) }
            throw t
        }
    }

    fun resolveCoverPath(relativePath: String?): Path? {
        if (relativePath.isNullOrBlank()) return null
        val path = vappRootDir / relativePath
        return if (fileSystem.exists(path)) path else null
    }
}
