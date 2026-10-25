package com.ludo.voicedictation.asr

import java.util.Locale

/** Converts clear English number-word sequences to digits without involving the ASR decoder. */
internal class StaticNumericCorrector {
    companion object {
        private val numberWordValues = mapOf(
            "zero" to 0L, "oh" to 0L,
            "one" to 1L, "two" to 2L, "three" to 3L, "four" to 4L, "five" to 5L,
            "six" to 6L, "seven" to 7L, "eight" to 8L, "nine" to 9L,
            "ten" to 10L, "eleven" to 11L, "twelve" to 12L, "thirteen" to 13L,
            "fourteen" to 14L, "fifteen" to 15L, "sixteen" to 16L, "seventeen" to 17L,
            "eighteen" to 18L, "nineteen" to 19L, "twenty" to 20L, "thirty" to 30L,
            "forty" to 40L, "fifty" to 50L, "sixty" to 60L, "seventy" to 70L,
            "eighty" to 80L, "ninety" to 90L
        )
        private val scales = mapOf(
            "hundred" to 100L, "thousand" to 1_000L, "million" to 1_000_000L,
            "billion" to 1_000_000_000L, "trillion" to 1_000_000_000_000L
        )
        private val numberWords = (numberWordValues.keys + scales.keys + "and")
            .sortedByDescending { it.length }
        private val wordAlternatives = numberWords.joinToString("|") { Regex.escape(it) }
        private val sequencePattern = Regex(
            "(?<![\\p{L}\\p{M}])(?:$wordAlternatives)(?:[\\s-]+(?:$wordAlternatives))*(?![\\p{L}\\p{M}])",
            RegexOption.IGNORE_CASE
        )
        private val tokenPattern = Regex("[\\p{L}\\p{M}]+|\\d+(?:[.,]\\d+)?")
        private val clockSuffixPattern = Regex("""(?is)^\s*(?:a\.?m\.?|p\.?m\.?)(?![\p{L}\p{M}]).*""")
        private val clockPrefixPattern = Regex("""(?i)\b(?:at|around|by|until|before|after|for)\s+$""")
        private val oneAnotherSuffixPattern = Regex("""(?i)^\s+another\b""")
        private val standaloneOnePattern = Regex("""(?<![\p{L}\p{M}])one(?![\p{L}\p{M}])""", RegexOption.IGNORE_CASE)
        private val numberPhraseSeparator = Regex("[\\s-]+")
        private val numericWords = numberWordValues.keys + scales.keys

        /** Detect two numeric values separated by no more than two word tokens. */
        fun containsNearbyNumbers(text: String): Boolean {
            var previousNumber = false
            var wordsBetween = 0
            for (match in tokenPattern.findAll(text)) {
                val token = match.value.lowercase(Locale.ROOT)
                val isNumber = token.firstOrNull()?.isDigit() == true || token in numericWords
                if (isNumber) {
                    if (previousNumber && wordsBetween <= 2) return true
                    previousNumber = true
                    wordsBetween = 0
                } else if (previousNumber) {
                    wordsBetween++
                    if (wordsBetween > 2) previousNumber = false
                }
            }
            return false
        }

        /** Detect context-sensitive “one” so AI can distinguish a value from ordinary wording. */
        fun containsAmbiguousOne(text: String): Boolean =
            standaloneOnePattern.findAll(text).any { match ->
                !oneAnotherSuffixPattern.containsMatchIn(text.substring(match.range.last + 1))
            }
    }

    fun apply(text: String): String = sequencePattern.replace(text) { match ->
        val phrase = match.value
        val prefix = text.substring(0, match.range.first)
        val suffix = text.substring(match.range.last + 1)
        val hasClockSuffix = clockSuffixPattern.matches(suffix)
        val clockContext = hasClockSuffix || clockPrefixPattern.containsMatchIn(prefix)
        val timeParts = phrase.lowercase(Locale.ROOT).split(numberPhraseSeparator)
        when {
            phrase.equals("one", ignoreCase = true) -> phrase
            clockContext -> formatClock(timeParts, hasClockSuffix) ?: parse(phrase) ?: phrase
            else -> parse(phrase) ?: phrase
        }
    }

    private fun formatClock(parts: List<String>, hasAmPm: Boolean): String? {
        val hourWord: String
        val minuteWords: List<String>
        when {
            parts.size == 2 -> {
                hourWord = parts[0]
                minuteWords = listOf(parts[1])
            }
            parts.size == 3 && parts[1] == "oh" -> {
                hourWord = parts[0]
                minuteWords = listOf(parts[1], parts[2])
            }
            else -> return null
        }
        val hour = numberWordValues[hourWord] ?: return null
        val minuteDigits = StringBuilder()
        for (word in minuteWords) {
            if (word != "oh") minuteDigits.append(numberWordValues[word] ?: return null)
        }
        val minute = minuteDigits.toString().toIntOrNull() ?: return null
        if (hour !in (if (hasAmPm) 1L..12L else 0L..23L) || minute !in 0..59) return null
        return "$hour:${minute.toString().padStart(2, '0')}"
    }

    private fun parse(phrase: String): String? {
        val words = phrase.lowercase(Locale.ROOT).split(numberPhraseSeparator).filter { it.isNotEmpty() }
        if (words.isEmpty() || words.all { it == "and" } || words == listOf("oh")) return null
        // “Sixteen oh five” is digit-by-digit/time speech, not arithmetic addition.
        if ("oh" in words) {
            if (words.any { it != "oh" && it !in numberWordValues }) return null
            return words.filter { it != "oh" }
                .joinToString(" ") { numberWordValues.getValue(it).toString() }
        }

        // Adjacent standalone values are not additive (“twelve fifteen” is not 27).
        // Only a tens word followed by a unit is valid without a hundred/scale word.
        if (words.none { it in scales } && words.size > 1) {
            val values = words.map { numberWordValues[it] ?: return null }
            val validTensAndUnit = values.size == 2 && values[0] in 20L..90L &&
                values[0] % 10L == 0L && values[1] in 1L..9L
            if (!validTensAndUnit) return values.joinToString(" ")
        }

        var total = 0L
        var group = 0L
        var hasValue = false
        for ((index, word) in words.withIndex()) {
            when {
                word == "and" -> if (index == 0 || index == words.lastIndex) return null
                word in numberWordValues -> {
                    group += numberWordValues.getValue(word)
                    hasValue = true
                }
                word == "hundred" -> {
                    if (!hasValue) group = 1
                    group *= 100
                    hasValue = true
                }
                word in scales -> {
                    if (!hasValue) group = 1
                    total += group * scales.getValue(word)
                    group = 0
                    hasValue = false
                }
                else -> return null
            }
        }
        if (!hasValue) return null
        return (total + group).takeIf { it >= 0 }?.toString()
    }
}
