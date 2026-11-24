package dev.trinhhg.vapp.exporter.epub

import dev.trinhhg.vapp.exporter.html.ChapterSanitizer

/**
 * Generates EPUB 3.0 `OEBPS/nav.xhtml` containing `epub:type="toc"` and `epub:type="landmarks"`.
 */
object NavXhtmlWriter {
    fun build(
        title: String,
        language: String,
        hasCoverPage: Boolean,
        chapters: List<EpubChapterEntry>,
    ): String {
        val safeTitle = ChapterSanitizer.escapeXml(title)
        val safeLang = ChapterSanitizer.escapeXml(language.ifBlank { "vi" })
        val firstBodyHref = chapters.firstOrNull()?.href ?: "text/intro.xhtml"

        return buildString {
            appendLine("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
            appendLine("<!DOCTYPE html>")
            appendLine(
                "<html xmlns=\"http://www.w3.org/1999/xhtml\" " +
                    "xmlns:epub=\"http://www.idpf.org/2007/ops\" " +
                    "xml:lang=\"$safeLang\" lang=\"$safeLang\">",
            )
            appendLine("<head>")
            appendLine("  <meta charset=\"utf-8\"/>")
            appendLine("  <title>$safeTitle — Mục lục</title>")
            appendLine("  <link rel=\"stylesheet\" type=\"text/css\" href=\"styles/style.css\"/>")
            appendLine("</head>")
            appendLine("<body>")
            appendLine("  <nav epub:type=\"toc\" id=\"toc\">")
            appendLine("    <h1>Mục lục</h1>")
            appendLine("    <ol>")
            appendLine("      <li><a href=\"text/intro.xhtml\">Giới thiệu</a></li>")
            for (ch in chapters) {
                val safeChapTitle = ChapterSanitizer.escapeXml(ch.title)
                val safeHref = ChapterSanitizer.escapeXml(ch.href)
                appendLine("      <li><a href=\"$safeHref\">$safeChapTitle</a></li>")
            }
            appendLine("    </ol>")
            appendLine("  </nav>")
            appendLine("  <nav epub:type=\"landmarks\" id=\"landmarks\" hidden=\"\">")
            appendLine("    <h2>Landmarks</h2>")
            appendLine("    <ol>")
            if (hasCoverPage) {
                appendLine("      <li><a epub:type=\"cover\" href=\"text/cover.xhtml\">Cover</a></li>")
            }
            appendLine("      <li><a epub:type=\"toc\" href=\"nav.xhtml\">Mục lục</a></li>")
            appendLine(
                "      <li><a epub:type=\"bodymatter\" href=\"${ChapterSanitizer.escapeXml(firstBodyHref)}\">" +
                    "Nội dung chính</a></li>",
            )
            appendLine("    </ol>")
            appendLine("  </nav>")
            appendLine("</body>")
            append("</html>")
        }
    }
}
