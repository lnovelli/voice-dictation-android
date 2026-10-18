package com.ludo.voicedictation.asr

import java.util.Locale

/** Audio partitioning and optional orthographic hints; never repairs/deduplicates ASR output. */
internal object RecognitionPolicy {
    private const val SAMPLE_RATE = 16000
    const val MAX_DECODE_SAMPLES = SAMPLE_RATE * 20
    private const val SEARCH_SAMPLES = SAMPLE_RATE * 5
    private const val ENERGY_SAMPLES = SAMPLE_RATE / 5
    private const val STEP_SAMPLES = SAMPLE_RATE / 50

    /** Contiguous, non-overlapping slices cover every original sample exactly once.
     * Prefer a quiet boundary in seconds 15..20; even continuous speech has a hard cap.
     * This is not VAD trimming: silence, quiet words and final short tails are retained.
     */
    fun nextEnd(samples: FloatArray, start: Int): Int {
        if (samples.size - start <= MAX_DECODE_SAMPLES) return samples.size
        // Do not create a tiny final native call when duration is just over 20 s.
        val limit = minOf(start + MAX_DECODE_SAMPLES, samples.size - SAMPLE_RATE * 2)
        var best = limit
        var minimum = Double.POSITIVE_INFINITY
        var end = limit
        while (end >= maxOf(start + SAMPLE_RATE * 15, limit - SEARCH_SAMPLES) + ENERGY_SAMPLES / 2) {
            var energy = 0.0
            for (i in end - ENERGY_SAMPLES until end) energy += samples[i].toDouble() * samples[i]
            if (energy < minimum) {
                minimum = energy
                best = end - ENERGY_SAMPLES / 2
            }
            end -= STEP_SAMPLES
        }
        return best
    }

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
