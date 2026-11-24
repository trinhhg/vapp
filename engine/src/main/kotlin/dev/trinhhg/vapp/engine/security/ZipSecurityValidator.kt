package dev.trinhhg.vapp.engine.security

import net.lingala.zip4j.ZipFile
import okio.FileSystem
import okio.Path

object ZipSecurityValidator {
    private const val MAX_ZIP_SIZE_BYTES = 100L * 1024 * 1024
    private const val MAX_UNCOMPRESSED_SIZE_BYTES = 500L * 1024 * 1024
    private const val MAX_ENTRY_COUNT = 1000

    fun validateZipFile(
        zipPath: Path,
        fileSystem: FileSystem = FileSystem.SYSTEM,
        maxZipSizeBytes: Long = MAX_ZIP_SIZE_BYTES,
        maxUncompressedBytes: Long = MAX_UNCOMPRESSED_SIZE_BYTES,
        maxEntries: Int = MAX_ENTRY_COUNT,
    ) {
        if (!fileSystem.exists(zipPath)) {
            throw SecurityValidationException("ZIP file not found: $zipPath")
        }
        val zipSize = fileSystem.metadataOrNull(zipPath)?.size ?: 0L
        if (zipSize > maxZipSizeBytes) {
            throw SecurityValidationException("ZIP file too large: $zipSize bytes (max: $maxZipSizeBytes bytes)")
        }
        val headers =
            try {
                ZipFile(zipPath.toFile()).fileHeaders
            } catch (e: Exception) {
                throw SecurityValidationException("Failed to read ZIP entries", e)
            }
        if (headers.size > maxEntries) {
            throw SecurityValidationException("ZIP file contains too many entries: ${headers.size}")
        }
        var totalUncompressed = 0L
        for (header in headers) {
            if (header.uncompressedSize > 0) {
                totalUncompressed += header.uncompressedSize
                if (totalUncompressed > maxUncompressedBytes) {
                    throw SecurityValidationException("Total uncompressed size exceeds $maxUncompressedBytes bytes")
                }
            }
            validateEntryName(header.fileName)
        }
    }

    fun validateEntryName(entryName: String?) {
        if (entryName.isNullOrEmpty()) {
            throw SecurityValidationException("ZIP entry name is empty")
        }
        val normalized = entryName.replace("\\", "/")
        var depth = 0
        for (seg in normalized.split("/")) {
            when (seg) {
                "", "." -> Unit
                ".." -> {
                    depth--
                    if (depth < 0) {
                        throw SecurityValidationException(
                            "ZIP entry escapes directory (path traversal): $entryName",
                        )
                    }
                }
                else -> depth++
            }
        }
        if (normalized.startsWith("/")) {
            throw SecurityValidationException("ZIP entry contains absolute path: $entryName")
        }
        if (entryName.matches(Regex("^[A-Za-z]:.*"))) {
            throw SecurityValidationException("ZIP entry contains Windows absolute path: $entryName")
        }
        if (entryName.startsWith("\\\\") || entryName.startsWith("//")) {
            throw SecurityValidationException("ZIP entry contains UNC path: $entryName")
        }
    }
}
