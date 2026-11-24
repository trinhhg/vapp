package dev.trinhhg.vapp.exporter

import dev.trinhhg.vapp.exporter.api.BookExportMetadata
import dev.trinhhg.vapp.exporter.html.ChapterSanitizer
import dev.trinhhg.vapp.exporter.html.CleanupRuleApplier
import dev.trinhhg.vapp.exporter.html.VolumeChunk
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Exports a book volume to a clean UTF-8 `.txt` file atomically via `.tmp`.
 */
object PlainTextExporter {
    fun writeAtomic(
        outputFile: File,
        book: BookExportMetadata,
        volume: VolumeChunk,
        cleanupApplier: CleanupRuleApplier?,
        onChapterProcessed: (Int, String) -> Unit = { _, _ -> },
    ): Long {
        outputFile.parentFile?.let { parent ->
            if (!parent.exists() && !parent.mkdirs()) {
                throw IOException("Unable to create export directory: ${parent.absolutePath}")
            }
        }
        val tmpFile = File(outputFile.parentFile, "${outputFile.name}.tmp")
        if (tmpFile.exists()) tmpFile.delete()

        try {
            FileOutputStream(tmpFile).bufferedWriter(Charsets.UTF_8).use { writer ->
                writer.appendLine(volume.volumeTitle)
                if (book.author.isNotBlank()) {
                    writer.appendLine("Tác giả: ${book.author}")
                }
                if (book.synopsis.isNotBlank()) {
                    writer.appendLine()
                    writer.appendLine(ChapterSanitizer.toPlainText(book.synopsis, cleanupApplier))
                }
                writer.appendLine()
                writer.appendLine("========================================")
                writer.appendLine()

                volume.chapters.forEachIndexed { idx, ch ->
                    writer.appendLine(ch.title)
                    writer.appendLine()
                    writer.appendLine(ChapterSanitizer.toPlainText(ch.resolveHtml(), cleanupApplier))
                    writer.appendLine()
                    writer.appendLine("----------------------------------------")
                    writer.appendLine()
                    onChapterProcessed(idx + 1, ch.title)
                }
                writer.flush()
            }

            if (!tmpFile.exists() || tmpFile.length() <= 0L) {
                throw IOException("TXT export produced an empty file: ${tmpFile.absolutePath}")
            }

            Files.move(
                tmpFile.toPath(),
                outputFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
            return outputFile.length()
        } catch (t: Throwable) {
            runCatching { tmpFile.delete() }
            throw t
        }
    }
}
