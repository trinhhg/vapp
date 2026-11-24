package dev.trinhhg.vapp.exporter.html

import dev.trinhhg.vapp.engine.security.RegexSecurityValidator
import dev.trinhhg.vapp.exporter.api.CleanupRuleSpec

/**
 * Applies ReDoS-validated regex and plain-text cleanup rules to chapter text/HTML.
 */
class CleanupRuleApplier(
    rules: List<CleanupRuleSpec>,
) {
    private val compiled: List<CompiledRule> =
        rules.filter { it.enabled && it.pattern.isNotEmpty() }.map { spec ->
            if (spec.isRegex) {
                RegexSecurityValidator.validateRegex(spec.pattern)
                CompiledRule.RegexRule(Regex(spec.pattern), spec.replacement)
            } else {
                CompiledRule.PlainRule(spec.pattern, spec.replacement)
            }
        }

    fun apply(input: String): String {
        var current = input
        for (rule in compiled) {
            current =
                when (rule) {
                    is CompiledRule.RegexRule -> rule.regex.replace(current, rule.replacement)
                    is CompiledRule.PlainRule -> current.replace(rule.literal, rule.replacement)
                }
        }
        return current
    }

    private sealed interface CompiledRule {
        data class RegexRule(
            val regex: Regex,
            val replacement: String,
        ) : CompiledRule

        data class PlainRule(
            val literal: String,
            val replacement: String,
        ) : CompiledRule
    }
}
