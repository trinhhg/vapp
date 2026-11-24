package dev.trinhhg.vapp.tts.text

import com.fleeksoft.ksoup.Ksoup

data class CleanupPreviewRule(
    val pattern: String,
    val replacement: String = "",
    val isRegex: Boolean = true,
    val enabled: Boolean = true,
)

/**
 * Converts scraped chapter HTML into clean readable/speakable paragraphs, optionally applying
 * active [CleanupPreviewRule] transformations.
 */
object ReaderTextParser {
    private val UNSAFE_TAGS = listOf("script", "style", "iframe", "noscript", "object", "embed")

    fun parseHtmlToParagraphs(
        rawHtml: String,
        rules: List<CleanupPreviewRule> = emptyList(),
    ): List<String> {
        if (rawHtml.isBlank()) return emptyList()
        val preApplied = applyRules(rawHtml, rules)
        val doc = Ksoup.parseBodyFragment(preApplied)
        val body = doc.body()
        UNSAFE_TAGS.forEach { body.select(it).remove() }

        body.select("br").append("\\n")
        body.select("p, div, h1, h2, h3, h4, h5, li, blockquote").prepend("\\n")

        val rawLines =
            body
                .text()
                .replace("\\n", "\n")
                .lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() }

        return rawLines
            .map { line -> applyRules(line, rules).trim() }
            .filter { it.isNotEmpty() }
    }

    fun applyRules(
        input: String,
        rules: List<CleanupPreviewRule>,
    ): String {
        var current = input
        for (rule in rules) {
            if (!rule.enabled || rule.pattern.isEmpty()) continue
            current =
                if (rule.isRegex) {
                    runCatching { Regex(rule.pattern).replace(current, rule.replacement) }.getOrDefault(current)
                } else {
                    current.replace(rule.pattern, rule.replacement)
                }
        }
        return current
    }

    fun previewCleanup(
        sampleText: String,
        rules: List<CleanupPreviewRule>,
    ): String = parseHtmlToParagraphs(sampleText, rules).joinToString("\n\n")
}
