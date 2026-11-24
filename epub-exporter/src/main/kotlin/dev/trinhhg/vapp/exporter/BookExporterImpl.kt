package dev.trinhhg.vapp.exporter

import dev.trinhhg.vapp.exporter.api.BookExporter
import dev.trinhhg.vapp.exporter.api.CoverNormalizer
import dev.trinhhg.vapp.exporter.api.ExportFormat
import dev.trinhhg.vapp.exporter.api.ExportOutput
import dev.trinhhg.vapp.exporter.api.ExportRequest
import dev.trinhhg.vapp.exporter.api.PassThroughCoverNormalizer
import dev.trinhhg.vapp.exporter.epub.EpubAssetEntry
import dev.trinhhg.vapp.exporter.epub.EpubChapterEntry
import dev.trinhhg.vapp.exporter.epub.EpubSharedTemplates
import dev.trinhhg.vapp.exporter.epub.NavXhtmlWriter
import dev.trinhhg.vapp.exporter.epub.NcxWriter
import dev.trinhhg.vapp.exporter.epub.Opf2Writer
import dev.trinhhg.vapp.exporter.epub.Opf3Writer
import dev.trinhhg.vapp.exporter.html.ChapterSanitizer
import dev.trinhhg.vapp.exporter.html.CleanupRuleApplier
import dev.trinhhg.vapp.exporter.html.VolumeChunk
import dev.trinhhg.vapp.exporter.html.VolumeSplitter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.coroutines.coroutineContext

/**
 * Orchestrates filtering, cleanup rules, HTML sanitization, cover normalization,
 * volume splitting, and atomic EPUB 2/3 or TXT emission.
 */
class BookExporterImpl(
    private val coverNormalizer: CoverNormalizer = PassThroughCoverNormalizer,
) : BookExporter {
    override suspend fun export(
        request: ExportRequest,
        onProgress: (Int, String) -> Unit,
    ): Result<ExportOutput> =
        withContext(Dispatchers.IO) {
            val createdFiles = ArrayList<File>()
            try {
                require(request.outputFilePath.isNotBlank()) { "outputFilePath must not be blank" }
                val cleanupApplier =
                    if (request.cleanupRules.isNotEmpty()) {
                        CleanupRuleApplier(request.cleanupRules)
                    } else {
                        null
                    }

                val volumes =
                    VolumeSplitter.filterAndSplit(
                        bookTitle = request.book.title.ifBlank { "Untitled" },
                        chapters = request.chapters,
                        chapterRangeSpec = request.chapterRangeSpec,
                        autoSplitVolumes = request.autoSplitVolumes,
                        maxChaptersPerVolume = request.maxChaptersPerVolume,
                    )
                require(volumes.isNotEmpty()) { "No chapters matched selection for export" }

                val totalExported = volumes.sumOf { it.chapters.size }
                val normalizedCover =
                    if (request.includeCover && request.book.coverBytes != null) {
                        coverNormalizer.normalizeToJpeg(request.book.coverBytes, request.book.coverMimeType)
                    } else {
                        null
                    }

                var processedSoFar = 0
                var totalBytes = 0L

                for (volume in volumes) {
                    coroutineContext.ensureActive()
                    val targetFile = resolveVolumeOutputFile(request.outputFilePath, volume)

                    if (request.format == ExportFormat.TXT) {
                        val written =
                            PlainTextExporter.writeAtomic(
                                outputFile = targetFile,
                                book = request.book,
                                volume = volume,
                                cleanupApplier = cleanupApplier,
                            ) { _, chapTitle ->
                                processedSoFar++
                                val pct = ((processedSoFar * 100f) / totalExported).toInt().coerceIn(0, 100)
                                onProgress(pct, chapTitle)
                            }
                        createdFiles.add(targetFile)
                        totalBytes += written
                    } else {
                        val written =
                            streamEpubVolume(
                                targetFile = targetFile,
                                request = request,
                                volume = volume,
                                cleanupApplier = cleanupApplier,
                                coverExt = normalizedCover?.extension,
                                coverMime = normalizedCover?.mimeType,
                                coverBytes = normalizedCover?.bytes,
                            ) { chapTitle ->
                                processedSoFar++
                                val pct = ((processedSoFar * 99f) / totalExported).toInt().coerceIn(0, 99)
                                onProgress(pct, chapTitle)
                            }
                        createdFiles.add(targetFile)
                        totalBytes += written
                    }
                }

                onProgress(100, "Hoàn tất xuất ${volumes.size} tệp")
                Result.success(
                    ExportOutput(
                        outputFiles = createdFiles.map { it.absolutePath },
                        totalChaptersExported = totalExported,
                        totalVolumes = volumes.size,
                        totalBytes = totalBytes,
                    ),
                )
            } catch (t: Throwable) {
                createdFiles.forEach { file -> runCatching { file.delete() } }
                Result.failure(t)
            }
        }

    private suspend fun streamEpubVolume(
        targetFile: File,
        request: ExportRequest,
        volume: VolumeChunk,
        cleanupApplier: CleanupRuleApplier?,
        coverExt: String?,
        coverMime: String?,
        coverBytes: ByteArray?,
        onChapterBuilt: (String) -> Unit,
    ): Long {
        val job = coroutineContext[Job]
        val bookUuid =
            "urn:uuid:" +
                UUID
                    .nameUUIDFromBytes(
                        "${request.book.id}:${volume.volumeNumber}".encodeToByteArray(),
                    ).toString()

        val hasCover = coverBytes != null && coverExt != null && coverMime != null
        val assets = ArrayList<EpubAssetEntry>()
        val epubChapters = ArrayList<EpubChapterEntry>(volume.chapters.size)
        val addedAssetHrefs = HashSet<String>()

        return EpubPackageWriter.streamAtomic(
            outputFile = targetFile,
            compressionLevel = request.compressionLevel,
        ) { sink ->
            sink.writeEntry("META-INF/container.xml", EpubSharedTemplates.CONTAINER_XML.encodeToByteArray())
            sink.writeEntry("OEBPS/styles/style.css", EpubSharedTemplates.DEFAULT_CSS.encodeToByteArray())

            if (hasCover) {
                val coverHref = "images/cover.$coverExt"
                assets.add(EpubAssetEntry("cover-image", coverHref, coverMime!!, ByteArray(0), isCoverImage = true))
                sink.writeEntry("OEBPS/$coverHref", coverBytes!!)
                val coverXhtml =
                    EpubSharedTemplates.buildCoverXhtml(
                        title = volume.volumeTitle,
                        coverImageRelativeToTextDir = "../$coverHref",
                        format = request.format,
                        language = request.book.language,
                    )
                sink.writeEntry("OEBPS/text/cover.xhtml", coverXhtml.encodeToByteArray())
            }

            val introXhtml =
                EpubSharedTemplates.buildIntroXhtml(
                    title = volume.volumeTitle,
                    author = request.book.author,
                    synopsis = request.book.synopsis,
                    format = request.format,
                    language = request.book.language,
                )
            sink.writeEntry("OEBPS/text/intro.xhtml", introXhtml.encodeToByteArray())

            volume.chapters.forEachIndexed { idx, ch ->
                job?.ensureActive()
                val rawHtml = ch.resolveHtml()
                val cleanBody =
                    ChapterSanitizer.sanitizeBodyFragment(
                        rawHtml = rawHtml,
                        includeImages = request.includeImages,
                        cleanupApplier = cleanupApplier,
                    )
                val fullXhtml =
                    ChapterSanitizer.wrapChapterXhtml(
                        title = ch.title,
                        bodyFragmentHtml = cleanBody,
                        format = request.format,
                        language = request.book.language,
                    )
                val chapNum = idx + 1
                val chapId = "chap_%04d".format(chapNum)
                val chapHref = "text/$chapId.xhtml"
                sink.writeEntry("OEBPS/$chapHref", fullXhtml.encodeToByteArray())
                // Keep only TOC/OPF metadata in memory so 3,000+ chapter exports maintain bounded heap usage
                epubChapters.add(EpubChapterEntry(chapId, chapHref, ch.title, ch.partName))

                if (request.includeImages && ch.images.isNotEmpty()) {
                    ch.images.forEach { (name, imgBytes) ->
                        if (imgBytes.isNotEmpty()) {
                            val safeName = name.replace(Regex("[^a-zA-Z0-9._-]"), "_")
                            val assetHref = "images/${chapId}_$safeName"
                            if (addedAssetHrefs.add(assetHref)) {
                                val mediaType = guessImageMimeType(safeName)
                                assets.add(EpubAssetEntry("img_${assets.size}", assetHref, mediaType, ByteArray(0)))
                                sink.writeEntry("OEBPS/$assetHref", imgBytes)
                            }
                        }
                    }
                }
                onChapterBuilt(ch.title)
            }

            val ncxXml = NcxWriter.build(bookUuid, volume.volumeTitle, epubChapters)
            sink.writeEntry("OEBPS/toc.ncx", ncxXml.encodeToByteArray())

            if (request.format == ExportFormat.EPUB_3) {
                val navXml = NavXhtmlWriter.build(volume.volumeTitle, request.book.language, hasCover, epubChapters)
                sink.writeEntry("OEBPS/nav.xhtml", navXml.encodeToByteArray())
                val opf3 =
                    Opf3Writer.build(
                        bookUuid = bookUuid,
                        title = volume.volumeTitle,
                        author = request.book.author,
                        language = request.book.language,
                        synopsis = request.book.synopsis,
                        hasCoverPage = hasCover,
                        chapters = epubChapters,
                        assets = assets,
                    )
                sink.writeEntry("OEBPS/content.opf", opf3.encodeToByteArray())
            } else {
                val opf2 =
                    Opf2Writer.build(
                        bookUuid = bookUuid,
                        title = volume.volumeTitle,
                        author = request.book.author,
                        language = request.book.language,
                        synopsis = request.book.synopsis,
                        hasCoverPage = hasCover,
                        chapters = epubChapters,
                        assets = assets,
                    )
                sink.writeEntry("OEBPS/content.opf", opf2.encodeToByteArray())
            }
        }
    }

    private fun resolveVolumeOutputFile(
        basePath: String,
        volume: VolumeChunk,
    ): File {
        val baseFile = File(basePath)
        if (volume.totalVolumes <= 1) return baseFile
        val name = baseFile.name
        val dot = name.lastIndexOf('.')
        val volumeName =
            if (dot > 0) {
                "${name.substring(0, dot)}_vol${volume.volumeNumber}${name.substring(dot)}"
            } else {
                "${name}_vol${volume.volumeNumber}"
            }
        return File(baseFile.parentFile, volumeName)
    }

    private fun guessImageMimeType(fileName: String): String =
        when (fileName.substringAfterLast('.', "").lowercase()) {
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            else -> "image/jpeg"
        }
}
