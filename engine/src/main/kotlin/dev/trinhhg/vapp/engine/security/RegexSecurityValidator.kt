package dev.trinhhg.vapp.engine.security

object RegexSecurityValidator {
    private const val REDOS_TEST_STRING = "aaaaaaaaaaaaaaaaaaaabbbbbbbbbbbbbbbbcccccccccccc"

    private val dangerousPatterns =
        listOf(
            "(.+)+",
            "(.+)*",
            "(.+){",
            "([a-zA-Z]+)+",
            "([a-z]+)*",
            "([a-z]+)+",
            "(\\w+)+",
            "(\\d+)+",
            "(.+)*(.+)*",
            "(.*)+(.*+)",
            "\\(([^)]+\\)+\\)",
            "(([^()]+)+)+",
            "(a+)+",
            "([a-z]+)+[a-z]+",
        )

    fun validateRegex(regex: String?) {
        if (regex.isNullOrEmpty()) {
            throw SecurityValidationException("Regex pattern cannot be empty")
        }
        if (regex.length > 1000) {
            throw SecurityValidationException("Regex pattern too long: ${regex.length} chars (max: 1000)")
        }
        checkForDangerousPatterns(regex)
        try {
            Regex(regex)
        } catch (e: Exception) {
            throw SecurityValidationException("Invalid regex pattern: $regex", e)
        }
        try {
            Regex(regex).matches(REDOS_TEST_STRING)
        } catch (e: Exception) {
            throw SecurityValidationException("Regex test failed: $regex", e)
        }
    }

    private fun checkForDangerousPatterns(regex: String) {
        val lower = regex.lowercase()
        for (dangerous in dangerousPatterns) {
            if (lower.contains(dangerous.lowercase())) {
                throw SecurityValidationException(
                    "Regex pattern contains potentially dangerous ReDoS pattern: $regex",
                )
            }
        }
        val quantifiers = regex.count { it == '+' || it == '*' || it == '?' || it == '{' }
        if (quantifiers > 24) {
            // Note: real VBook regexes like truyenfull/wikidich often have ~12-16 optional groups
            throw SecurityValidationException("Regex pattern contains excessive quantifiers: $regex")
        }
        if (regex.count { it == '|' } > 8) {
            checkOverlappingAlternations(regex)
        }
    }

    private fun checkOverlappingAlternations(regex: String) {
        val alternatives = regex.split("|")
        for (i in 0 until alternatives.size - 1) {
            for (j in i + 1 until alternatives.size) {
                if (alternatives[i].length > 10 &&
                    alternatives[j].length > 10 &&
                    hasCommonPrefix(alternatives[i], alternatives[j])
                ) {
                    throw SecurityValidationException(
                        "Regex pattern has overlapping alternations (potential ReDoS): $regex",
                    )
                }
            }
        }
    }

    private fun hasCommonPrefix(
        s1: String,
        s2: String,
    ): Boolean {
        val minLen = minOf(s1.length, s2.length)
        var common = 0
        for (i in 0 until minLen) {
            if (s1[i] == s2[i]) common++ else break
        }
        return common > 5
    }
}
