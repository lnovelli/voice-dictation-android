package com.ludo.voicedictation.asr

import com.ludo.voicedictation.data.StringReplacementRule
import com.ludo.voicedictation.data.StringReplacementRules
import java.util.Locale

/** Literal, case-insensitive final-text replacements; never changes acoustic decoding. */
internal class CustomStringReplacer(rules: Collection<StringReplacementRule>) {
    private data class Entry(val input: String, val output: String)

    private val entries = StringReplacementRules.normalize(rules)
        .flatMap { rule -> rule.inputs.map { Entry(it, rule.output) } }

    private val outputByInput = entries.associate { canonical(it.input) to it.output }

    // Longest aliases win when two configured inputs could begin at the same position.
    private val pattern: Regex? = entries
        .sortedByDescending { it.input.length }
        .takeIf { it.isNotEmpty() }
        ?.joinToString("|") { entry ->
            val left = if (isWordCharacterAtStart(entry.input)) "(?<!$WORD)" else ""
            val right = if (isWordCharacterAtEnd(entry.input)) "(?!$WORD)" else ""
            left + entry.input.split(' ').joinToString("[\\s\\p{Z}]+") { Regex.escape(it) } + right
        }
        ?.let { Regex(it, RegexOption.IGNORE_CASE) }

    fun apply(text: String): String {
        val matcher = pattern ?: return text
        if (!matcher.containsMatchIn(text)) return text
        // Single pass over the original transcript prevents replacement outputs from
        // recursively triggering other user-defined inputs.
        return matcher.replace(text) { match ->
            outputByInput[canonical(match.value)] ?: match.value
        }
    }

    private fun isWordCharacterAtStart(value: String): Boolean =
        value.isNotEmpty() && isWordCodePoint(Character.codePointAt(value, 0))

    private fun isWordCharacterAtEnd(value: String): Boolean =
        value.isNotEmpty() && isWordCodePoint(Character.codePointBefore(value, value.length))

    private fun isWordCodePoint(codePoint: Int): Boolean {
        val type = Character.getType(codePoint)
        return Character.isLetterOrDigit(codePoint) ||
            type == Character.NON_SPACING_MARK.toInt() ||
            type == Character.COMBINING_SPACING_MARK.toInt() ||
            type == Character.ENCLOSING_MARK.toInt() ||
            type == Character.CONNECTOR_PUNCTUATION.toInt() ||
            codePoint == '\''.code || codePoint == '’'.code || codePoint == '-'.code
    }

    private companion object {
        const val WORD = "[\\p{L}\\p{M}\\p{N}\\p{Pc}'’\\-]"
        val whitespace = Regex("[\\s\\p{Z}]+")

        fun canonical(value: String): String =
            value.trim().replace(whitespace, " ").lowercase(Locale.ROOT)
    }
}
