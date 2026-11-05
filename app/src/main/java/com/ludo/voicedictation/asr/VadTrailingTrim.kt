package com.ludo.voicedictation.asr

/** Per-chunk raw Silero observations, in original recording coordinates. */
internal class VadTrailingTrim(private val frameSamples: Int = 512) {
    private var processed = 0
    private var lastPositiveEnd: Int? = null

    init { require(frameSamples > 0) }

    fun observe(end: Int, probability: Float) {
        require(end == processed + frameSamples && probability.isFinite())
        processed = end
        if (probability >= 0.5f) lastPositiveEnd = end
    }

    data class Plan(val originalEnd: Int, val prefixEnd: Int, val partialStart: Int, val reason: String) {
        val droppedSamples: Int get() = partialStart - prefixEnd
        val partialSamples: Int get() = originalEnd - partialStart
    }

    /** Incomplete observations or no positives fail open. A final partial frame is appended. */
    fun finishChunk(start: Int, end: Int, observationsAvailable: Boolean): Plan {
        require(start >= 0 && end > start)
        val positive = lastPositiveEnd
        val valid = observationsAvailable && processed in start..end && end - processed < frameSamples
        val plan = when {
            !valid -> Plan(end, end, end, "VAD observations incomplete; unchanged")
            positive == null || positive <= start || positive > processed ->
                Plan(end, end, end, "no positive frame; unchanged")
            else -> Plan(end, positive, processed,
                if (processed < end) "unclassified final partial frame retained" else "last p >= 0.5 frame")
        }
        lastPositiveEnd = null
        return plan
    }

    companion object {
        /** Last retained original coordinate, including any optional inner removal. */
        fun decoderEnd(start: Int, plan: Plan, runs: List<HistoryNegativeRun>?, sampleRate: Int): Int {
            if (plan.partialSamples > 0) return plan.originalEnd
            var lastEnd = plan.prefixEnd
            if (runs != null) {
                val guard = sampleRate / 5 + if (sampleRate % 5 == 0) 0 else 1
                for (run in runs.asReversed()) {
                    if (run.endExclusive - run.start < guard) continue
                    val from = maxOf(start + minOf(plan.prefixEnd - start, guard), run.start)
                    val until = minOf(plan.prefixEnd, run.endExclusive - guard)
                    if (from < until && lastEnd > from && lastEnd <= until) lastEnd = from
                }
            }
            return lastEnd
        }

        /** Apply optional inner-removal to the prefix only, then append the unclassified tail. */
        fun input(samples: ShortArray, start: Int, plan: Plan,
                  runs: List<HistoryNegativeRun>?, sampleRate: Int): FloatArray {
            require(start >= 0 && start < plan.prefixEnd && plan.prefixEnd <= plan.partialStart &&
                plan.partialStart <= plan.originalEnd && plan.originalEnd <= samples.size)
            val prefix = if (runs != null) HistoryVadTransform.removeSilence(samples, start, plan.prefixEnd, runs, sampleRate)
                else FloatArray(plan.prefixEnd - start) { samples[start + it] / 32768f }
            if (plan.partialSamples == 0) return prefix
            return prefix + FloatArray(plan.partialSamples) { samples[plan.partialStart + it] / 32768f }
        }
    }
}
