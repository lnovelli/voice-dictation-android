package com.ludo.voicedictation.asr

import java.util.Locale

/** Optional orthographic hints only; never biases acoustics or deduplicates ASR output. */
internal object RecognitionPolicy {
    /** No phonetic substitution. Only identical Unicode letters/digits may be recased
     * or regrouped across spaces (e.g. "next cloud" -> "Nextcloud"). Punctuation,
     * word boundaries and all other words remain intact. Ambiguous hints are ignored.
     */
    class SpellingHints(words: Set<String>) {
        private val word = Regex("[\\p{L}\\p{M}\\p{N}]+")
        private fun key(text: String) = text.filterNot { it.isWhitespace() }.lowercase(Locale.ROOT)
        private val hints = words.asSequence().filter { it.length in 2..80 }
            .filter { it.all { c -> c.isLetterOrDigit() || c.isWhitespace() || Character.getType(c) == Character.NON_SPACING_MARK.toInt() } }
            .map { it.trim().replace(Regex("\\s+"), " ") }
            .filter { word.findAll(it).count() in 1..6 }
            .groupBy { key(it) }.filterValues { it.distinct().size == 1 }.mapValues { it.value.first() }

        fun apply(text: String): String {
            if (hints.isEmpty()) return text
            val matches = word.findAll(text).toList()
            val result = StringBuilder()
            var cursor = 0
            var index = 0
            while (index < matches.size) {
                val first = matches[index]
                var replacement: String? = null
                var last = index
                for (end in index until minOf(index + 6, matches.size)) {
                    if (end > index && text.substring(matches[end - 1].range.last + 1, matches[end].range.first)
                            .any { !it.isWhitespace() }) break
                    val candidate = hints[key(text.substring(first.range.first, matches[end].range.last + 1))]
                    if (candidate != null) {
                        replacement = candidate
                        last = end
                    }
                }
                result.append(text, cursor, first.range.first)
                if (replacement == null) {
                    result.append(first.value)
                    cursor = first.range.last + 1
                    index++
                } else {
                    result.append(replacement)
                    cursor = matches[last].range.last + 1
                    index = last + 1
                }
            }
            return result.append(text, cursor, text.length).toString()
        }
    }
}
