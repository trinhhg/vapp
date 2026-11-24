package dev.trinhhg.vapp.exporter.epub

import dev.trinhhg.vapp.exporter.html.ChapterSanitizer
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * Generates EPUB 3.0 `OEBPS/content.opf` (`version="3.0"`, `dcterms:modified`, `properties="nav"`, `properties="cover-image"`).
 */
object Opf3Writer {
    fun build(
        bookUuid: String,
        title: String,
        author: String,
        language: String,
        synopsis: String,
        hasCoverPage: Boolean,
        chapters: List<EpubChapterEntry>,
        assets: List<EpubAssetEntry>,
        modifiedIsoUtc: String = DateTimeFormatter.ISO_INSTANT.format(Instant.now().truncatedTo(ChronoUnit.SECONDS)),
    ): String {
        val safeId = ChapterSanitizer.escapeXml(bookUuid)
        val safeTitle = ChapterSanitizer.escapeXml(title)
        val safeAuthor = ChapterSanitizer.escapeXml(author.ifBlank { "Unknown" })
        val safeLang = ChapterSanitizer.escapeXml(language.ifBlank { "vi" })
        val safeDesc = ChapterSanitizer.escapeXml(ChapterSanitizer.toPlainText(synopsis))

        return buildString {
            appendLine("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
            appendLine("<package xmlns=\"http://www.idpf.org/2007/opf\" unique-identifier=\"BookId\" version=\"3.0\">")
            appendLine("  <metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:dcterms=\"http://purl.org/dc/terms/\">")
            appendLine("    <dc:identifier id=\"BookId\">$safeId</dc:identifier>")
            appendLine("    <dc:title>$safeTitle</dc:title>")
            appendLine("    <dc:creator>$safeAuthor</dc:creator>")
            appendLine("    <dc:language>$safeLang</dc:language>")
            if (safeDesc.isNotBlank()) {
                appendLine("    <dc:description>$safeDesc</dc:description>")
            }
            appendLine("    <meta property=\"dcterms:modified\">${ChapterSanitizer.escapeXml(modifiedIsoUtc)}</meta>")
            appendLine("  </metadata>")
            appendLine("  <manifest>")
            appendLine("    <item id=\"nav\" href=\"nav.xhtml\" media-type=\"application/xhtml+xml\" properties=\"nav\"/>")
            appendLine("    <item id=\"ncx\" href=\"toc.ncx\" media-type=\"application/x-dtbncx+xml\"/>")
            appendLine("    <item id=\"style\" href=\"styles/style.css\" media-type=\"text/css\"/>")
            if (hasCoverPage) {
                appendLine("    <item id=\"cover-page\" href=\"text/cover.xhtml\" media-type=\"application/xhtml+xml\"/>")
            }
            appendLine("    <item id=\"intro-page\" href=\"text/intro.xhtml\" media-type=\"application/xhtml+xml\"/>")
            for (asset in assets) {
                val props = if (asset.isCoverImage) " properties=\"cover-image\"" else ""
                appendLine(
                    "    <item id=\"${ChapterSanitizer.escapeXml(asset.id)}\" " +
                        "href=\"${ChapterSanitizer.escapeXml(asset.href)}\" " +
                        "media-type=\"${ChapterSanitizer.escapeXml(asset.mediaType)}\"$props/>",
                )
            }
            for (ch in chapters) {
                appendLine(
                    "    <item id=\"${ChapterSanitizer.escapeXml(ch.id)}\" " +
                        "href=\"${ChapterSanitizer.escapeXml(ch.href)}\" " +
                        "media-type=\"application/xhtml+xml\"/>",
                )
            }
            appendLine("  </manifest>")
            appendLine("  <spine toc=\"ncx\">")
            if (hasCoverPage) {
                appendLine("    <itemref idref=\"cover-page\"/>")
            }
            appendLine("    <itemref idref=\"intro-page\"/>")
            for (ch in chapters) {
                appendLine("    <itemref idref=\"${ChapterSanitizer.escapeXml(ch.id)}\"/>")
            }
            appendLine("  </spine>")
            append("</package>")
        }
    }
}
