package dev.trinhhg.vapp.exporter.html

import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Document
import com.fleeksoft.ksoup.nodes.Entities
import dev.trinhhg.vapp.exporter.api.ExportFormat

/**
 * Sanitizes scraped chapter HTML via Ksoup into clean, EPUB 2.0 (XHTML 1.1) and EPUB 3.0 (XHTML5)
 * compliant UTF-8 markup, stripping scripts, iframes, event handlers, and unsafe URLs.
 */
object ChapterSanitizer {
    private val UNSAFE_TAGS =
        listOf("script", "style", "iframe", "frame", "frameset", "object", "embed", "noscript", "form", "input", "button")

    fun sanitizeBodyFragment(
        rawHtml: String,
        includeImages: Boolean,
        cleanupApplier: CleanupRuleApplier? = null,
    ): String {
        val preCleaned = cleanupApplier?.apply(rawHtml) ?: rawHtml
        val doc = Ksoup.parseBodyFragment(preCleaned)
        doc
            .outputSettings()
            .syntax(Document.OutputSettings.Syntax.xml)
            .escapeMode(Entities.EscapeMode.xhtml)
            .charset("UTF-8")
            .prettyPrint(true)

        val body = doc.body()
        UNSAFE_TAGS.forEach { tag -> body.select(tag).remove() }

        if (!includeImages) {
            body.select("img").remove()
        }

        val allElements = body.select("*").toList()
        for (el in allElements) {
            val attrs = el.attributes().map { it.key }
            for (attr in attrs) {
                val lower = attr.lowercase()
                if (lower.startsWith("on") || lower == "style") {
                    el.removeAttr(attr)
                } else if (lower == "href" || lower == "src") {
                    val value = el.attr(attr).trim()
                    if (value.startsWith("javascript:", ignoreCase = true) ||
                        value.startsWith("vbscript:", ignoreCase = true)
                    ) {
                        el.removeAttr(attr)
                    }
                }
            }
            if (el.tagName().equals("img", ignoreCase = true)) {
                val alt = el.attr("alt")
                if (alt.isEmpty()) el.attr("alt", "illustration")
            }
        }

        val postHtml = body.html()
        return cleanupApplier?.apply(postHtml) ?: postHtml
    }

    fun wrapChapterXhtml(
        title: String,
        bodyFragmentHtml: String,
        format: ExportFormat,
        language: String = "vi",
        cssRelativePath: String = "../styles/style.css",
    ): String {
        val safeTitle = escapeXml(title.ifBlank { "Chương" })
        val safeLang = escapeXml(language.ifBlank { "vi" })
        return if (format == ExportFormat.EPUB_2) {
            buildString {
                appendLine("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
                appendLine(
                    "<!DOCTYPE html PUBLIC \"-//W3C//DTD XHTML 1.1//EN\" " +
                        "\"http://www.w3.org/TR/xhtml11/DTD/xhtml11.dtd\">",
                )
                appendLine("<html xmlns=\"http://www.w3.org/1999/xhtml\" xml:lang=\"$safeLang\">")
                appendLine("<head>")
                appendLine("  <meta http-equiv=\"Content-Type\" content=\"application/xhtml+xml; charset=utf-8\"/>")
                appendLine("  <title>$safeTitle</title>")
                appendLine("  <link rel=\"stylesheet\" type=\"text/css\" href=\"$cssRelativePath\"/>")
                appendLine("</head>")
                appendLine("<body>")
                appendLine("  <h1 class=\"chapter-title\">$safeTitle</h1>")
                appendLine("  <div class=\"chapter-body\">")
                appendLine(bodyFragmentHtml)
                appendLine("  </div>")
                appendLine("</body>")
                append("</html>")
            }
        } else {
            buildString {
                appendLine("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
                appendLine("<!DOCTYPE html>")
                appendLine("<html xmlns=\"http://www.w3.org/1999/xhtml\" xml:lang=\"$safeLang\" lang=\"$safeLang\">")
                appendLine("<head>")
                appendLine("  <meta charset=\"utf-8\"/>")
                appendLine("  <title>$safeTitle</title>")
                appendLine("  <link rel=\"stylesheet\" type=\"text/css\" href=\"$cssRelativePath\"/>")
                appendLine("</head>")
                appendLine("<body>")
                appendLine("  <section class=\"chapter\">")
                appendLine("    <h1 class=\"chapter-title\">$safeTitle</h1>")
                appendLine("    <div class=\"chapter-body\">")
                appendLine(bodyFragmentHtml)
                appendLine("    </div>")
                appendLine("  </section>")
                appendLine("</body>")
                append("</html>")
            }
        }
    }

    fun toPlainText(
        rawHtml: String,
        cleanupApplier: CleanupRuleApplier? = null,
    ): String {
        val preCleaned = cleanupApplier?.apply(rawHtml) ?: rawHtml
        val doc = Ksoup.parseBodyFragment(preCleaned)
        val body = doc.body()
        UNSAFE_TAGS.forEach { body.select(it).remove() }
        body.select("br").append("\\n")
        body.select("p, div, li, h1, h2, h3, h4, blockquote").prepend("\\n\\n")
        val text = body.text().replace("\\n", "\n")
        val collapsed =
            text
                .lines()
                .map { it.trim() }
                .fold(StringBuilder()) { sb, line ->
                    if (line.isEmpty()) {
                        if (sb.isNotEmpty() && !sb.endsWith("\n\n")) sb.append("\n")
                    } else {
                        if (sb.isNotEmpty() && !sb.endsWith("\n")) sb.append("\n")
                        sb.append(line).append("\n")
                    }
                    sb
                }.toString()
                .trim()
        return cleanupApplier?.apply(collapsed) ?: collapsed
    }

    fun escapeXml(raw: String): String =
        buildString(raw.length) {
            for (ch in raw) {
                when (ch) {
                    '&' -> append("&amp;")
                    '<' -> append("&lt;")
                    '>' -> append("&gt;")
                    '"' -> append("&quot;")
                    '\'' -> append("&#39;")
                    else -> append(ch)
                }
            }
        }
}
