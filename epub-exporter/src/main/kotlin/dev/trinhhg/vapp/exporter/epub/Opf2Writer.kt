package dev.trinhhg.vapp.exporter.epub

import dev.trinhhg.vapp.exporter.html.ChapterSanitizer

/**
 * Generates EPUB 2.0 `OEBPS/content.opf` (`OPF 2.0` + `toc.ncx` spine + optional cover meta).
 */
object Opf2Writer {
    fun build(
        bookUuid: String,
        title: String,
        author: String,
        language: String,
        synopsis: String,
        hasCoverPage: Boolean,
        chapters: List<EpubChapterEntry>,
        assets: List<EpubAssetEntry>,
    ): String {
        val safeId = ChapterSanitizer.escapeXml(bookUuid)
        val safeTitle = ChapterSanitizer.escapeXml(title)
        val safeAuthor = ChapterSanitizer.escapeXml(author.ifBlank { "Unknown" })
        val safeLang = ChapterSanitizer.escapeXml(language.ifBlank { "vi" })
        val safeDesc = ChapterSanitizer.escapeXml(ChapterSanitizer.toPlainText(synopsis))
        val coverAsset = assets.firstOrNull { it.isCoverImage }

        return buildString {
            appendLine("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
            appendLine("<package xmlns=\"http://www.idpf.org/2007/opf\" unique-identifier=\"BookId\" version=\"2.0\">")
            appendLine("  <metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:opf=\"http://www.idpf.org/2007/opf\">")
            appendLine("    <dc:identifier id=\"BookId\" opf:scheme=\"UUID\">$safeId</dc:identifier>")
            appendLine("    <dc:title>$safeTitle</dc:title>")
            appendLine("    <dc:creator opf:role=\"aut\">$safeAuthor</dc:creator>")
            appendLine("    <dc:language>$safeLang</dc:language>")
            if (safeDesc.isNotBlank()) {
                appendLine("    <dc:description>$safeDesc</dc:description>")
            }
            if (coverAsset != null) {
                appendLine("    <meta name=\"cover\" content=\"${ChapterSanitizer.escapeXml(coverAsset.id)}\"/>")
            }
            appendLine("  </metadata>")
            appendLine("  <manifest>")
            appendLine("    <item id=\"ncx\" href=\"toc.ncx\" media-type=\"application/x-dtbncx+xml\"/>")
            appendLine("    <item id=\"style\" href=\"styles/style.css\" media-type=\"text/css\"/>")
            if (hasCoverPage) {
                appendLine("    <item id=\"cover-page\" href=\"text/cover.xhtml\" media-type=\"application/xhtml+xml\"/>")
            }
            appendLine("    <item id=\"intro-page\" href=\"text/intro.xhtml\" media-type=\"application/xhtml+xml\"/>")
            for (asset in assets) {
                appendLine(
                    "    <item id=\"${ChapterSanitizer.escapeXml(asset.id)}\" " +
                        "href=\"${ChapterSanitizer.escapeXml(asset.href)}\" " +
                        "media-type=\"${ChapterSanitizer.escapeXml(asset.mediaType)}\"/>",
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
                appendLine("    <itemref idref=\"cover-page\" linear=\"no\"/>")
            }
            appendLine("    <itemref idref=\"intro-page\"/>")
            for (ch in chapters) {
                appendLine("    <itemref idref=\"${ChapterSanitizer.escapeXml(ch.id)}\"/>")
            }
            appendLine("  </spine>")
            appendLine("  <guide>")
            if (hasCoverPage) {
                appendLine("    <reference type=\"cover\" title=\"Cover\" href=\"text/cover.xhtml\"/>")
            }
            appendLine("    <reference type=\"text\" title=\"Start\" href=\"text/intro.xhtml\"/>")
            appendLine("  </guide>")
            append("</package>")
        }
    }
}
