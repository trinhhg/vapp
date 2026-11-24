package dev.trinhhg.vapp.exporter

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Streaming entry sink writing directly into the compressed EPUB ZIP output stream. */
fun interface EpubStreamSink {
    fun writeEntry(
        entryName: String,
        bytes: ByteArray,
    )
}

/**
 * Writes an OCF-compliant EPUB archive atomically via a `.tmp` sibling file.
 * The `mimetype` entry is strictly written first using `ZipEntry.STORED` (uncompressed).
 */
object EpubPackageWriter {
    private val MIMETYPE_BYTES = "application/epub+zip".toByteArray(Charsets.US_ASCII)

    fun writeAtomic(
        outputFile: File,
        entries: List<Pair<String, ByteArray>>,
        compressionLevel: Int = 6,
    ): Long =
        streamAtomic(outputFile, compressionLevel) { sink ->
            for ((entryName, bytes) in entries) {
                sink.writeEntry(entryName, bytes)
            }
        }

    fun streamAtomic(
        outputFile: File,
        compressionLevel: Int = 6,
        block: (EpubStreamSink) -> Unit,
    ): Long {
        outputFile.parentFile?.let { parent ->
            if (!parent.exists() && !parent.mkdirs()) {
                throw IOException("Unable to create export directory: ${parent.absolutePath}")
            }
        }
        val tmpFile = File(outputFile.parentFile, "${outputFile.name}.tmp")
        if (tmpFile.exists()) tmpFile.delete()

        val level = compressionLevel.coerceIn(1, 9)
        try {
            FileOutputStream(tmpFile).buffered(64 * 1024).use { fos ->
                ZipOutputStream(fos, Charsets.UTF_8).use { zos ->
                    zos.setLevel(level)

                    // 1. OCF rule: uncompressed mimetype entry FIRST with exact CRC & size
                    val crc = CRC32().apply { update(MIMETYPE_BYTES) }
                    val mimeEntry =
                        ZipEntry("mimetype").apply {
                            method = ZipEntry.STORED
                            size = MIMETYPE_BYTES.size.toLong()
                            compressedSize = MIMETYPE_BYTES.size.toLong()
                            this.crc = crc.value
                        }
                    zos.putNextEntry(mimeEntry)
                    zos.write(MIMETYPE_BYTES)
                    zos.closeEntry()

                    // 2. Remaining entries streamed one-by-one without holding whole book in RAM
                    val sink =
                        EpubStreamSink { entryName, bytes ->
                            val entry = ZipEntry(entryName).apply { method = ZipEntry.DEFLATED }
                            zos.putNextEntry(entry)
                            zos.write(bytes)
                            zos.closeEntry()
                        }
                    block(sink)
                    zos.finish()
                }
            }

            if (!tmpFile.exists() || tmpFile.length() <= 0L) {
                throw IOException("Export produced an empty archive: ${tmpFile.absolutePath}")
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
