package com.ludo.voicedictation.asr

/** Literal final-text removals only. Never changes audio or acoustic decoding. */
internal class TranscriptFilter(entries: Collection<String>) {
    companion object {
        const val MAX_ENTRIES = 100
        const val MAX_ENTRY_LENGTH = 80
        private val spaces = Regex("[\\s\\p{Z}]+")
        // Treat combining marks, connectors, apostrophes and hyphens as part of a
        // word: removing 'uh' must not trim 'uhm', 'uh-oh', or an apostrophe word.
        private const val WORD = "[\\p{L}\\p{M}\\p{N}\\p{Pc}'’\\-]"
        private val allowed = Regex("[\\p{L}\\p{M}\\p{N}\\p{Pc}'’\\- ]+")

        fun normalizedEntries(lines: Collection<String>): List<String> = lines.asSequence()
            .map { it.trim().replace(spaces, " ") }
            .filter { it.isNotEmpty() && it.length <= MAX_ENTRY_LENGTH && allowed.matches(it) && it.codePoints().anyMatch { cp -> Character.isLetterOrDigit(cp) } }
            .distinctBy { it.lowercase(java.util.Locale.ROOT) }
            .take(MAX_ENTRIES).toList()
    }

    private val pattern = normalizedEntries(entries).sortedByDescending { it.length }
        .takeIf { it.isNotEmpty() }?.joinToString("|") { entry ->
            entry.split(' ').joinToString("[\\s\\p{Z}]+") { Regex.escape(it) }
        }?.let { Regex("(?<!$WORD)(?:$it)(?!$WORD)", RegexOption.IGNORE_CASE) }

    fun apply(text: String): String {
        val matcher = pattern ?: return text
        if (!matcher.containsMatchIn(text)) return text // Preserve untouched transcripts exactly.
        // Consume a filler-attached comma/semicolon, not sentence punctuation or
        // punctuation inside other words. Preserve punctuation before the filler.
        val stripped = Regex("${matcher.pattern}[\\p{Zs}\\t]*[,;]?", RegexOption.IGNORE_CASE)
            .replace(text, "")
        if (!stripped.codePoints().anyMatch { Character.isLetterOrDigit(it) }) return ""
        return stripped
            .replace(Regex("[\\p{Zs}\\t]{2,}"), " ")
            .replace(Regex("[\\p{Zs}\\t]+([,;:.!?…])"), "$1")
            .replace(Regex("([,;:])[\\p{Zs}\\t]*([,;:.!?…])"), "$2")
            .replace(Regex("^[\\s\\p{Z},;:.!?…]+"), "")
            .replace(Regex("[\\s\\p{Z},;:]+$"), "")
            .replace(Regex("""([\(\[\{])[\p{Zs}\t]+"""), "$1")
            .replace(Regex("""[\p{Zs}\t]+([\]\)\}])"""), "$1")
            .replace(Regex("\\(\\s*\\)|\\[\\s*\\]|\\{\\s*\\}"), "")
            .replace(Regex("[\\p{Zs}\\t]{2,}"), " ")
            .trim()
    }
}
