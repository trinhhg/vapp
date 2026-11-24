package dev.trinhhg.vapp.exporter.epub

import dev.trinhhg.vapp.exporter.html.ChapterSanitizer

/**
 * Generates EPUB 2.0 `OEBPS/toc.ncx` navigation map.
 */
object NcxWriter {
    fun build(
        bookUuid: String,
        title: String,
        chapters: List<EpubChapterEntry>,
    ): String {
        val safeId = ChapterSanitizer.escapeXml(bookUuid)
        val safeTitle = ChapterSanitizer.escapeXml(title)
        var playOrder = 1

        return buildString {
            appendLine("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
            appendLine(
                "<!DOCTYPE ncx PUBLIC \"-//NISO//DTD ncx 2005-1//EN\" " +
                    "\"http://www.daisy.org/z3986/2005/ncx-2005-1.dtd\">",
            )
            appendLine("<ncx xmlns=\"http://www.daisy.org/z3986/2005/ncx/\" version=\"2005-1\">")
            appendLine("  <head>")
            appendLine("    <meta name=\"dtb:uid\" content=\"$safeId\"/>")
            appendLine("    <meta name=\"dtb:depth\" content=\"1\"/>")
            appendLine("    <meta name=\"dtb:totalPageCount\" content=\"0\"/>")
            appendLine("    <meta name=\"dtb:maxPageNumber\" content=\"0\"/>")
            appendLine("  </head>")
            appendLine("  <docTitle><text>$safeTitle</text></docTitle>")
            appendLine("  <navMap>")
            appendLine("    <navPoint id=\"nav-intro\" playOrder=\"${playOrder++}\">")
            appendLine("      <navLabel><text>Giới thiệu</text></navLabel>")
            appendLine("      <content src=\"text/intro.xhtml\"/>")
            appendLine("    </navPoint>")
            for (ch in chapters) {
                val safeChapTitle = ChapterSanitizer.escapeXml(ch.title)
                val safeHref = ChapterSanitizer.escapeXml(ch.href)
                val safeNavId = ChapterSanitizer.escapeXml("nav-${ch.id}")
                appendLine("    <navPoint id=\"$safeNavId\" playOrder=\"${playOrder++}\">")
                appendLine("      <navLabel><text>$safeChapTitle</text></navLabel>")
                appendLine("      <content src=\"$safeHref\"/>")
                appendLine("    </navPoint>")
            }
            appendLine("  </navMap>")
            append("</ncx>")
        }
    }
}
