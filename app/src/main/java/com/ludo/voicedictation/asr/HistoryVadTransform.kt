package com.ludo.voicedictation.asr

/** Original-sample coordinates, end exclusive. Only complete VAD-negative frames are supplied. */
internal data class HistoryNegativeRun(val start: Int, val endExclusive: Int)

/** Observations are contiguous 512-sample Silero frames across the original recording. */
internal class HistoryNegativeRuns {
    private val runs = ArrayList<HistoryNegativeRun>()

    fun observe(start: Int, end: Int, probability: Float) {
        require(start >= 0 && end > start && probability.isFinite())
        if (probability < 0.5f) {
            val last = runs.lastOrNull()
            if (last != null && last.endExclusive == start) {
                runs[runs.lastIndex] = HistoryNegativeRun(last.start, end)
            } else runs.add(HistoryNegativeRun(start, end))
        }
    }

    fun drain(): List<HistoryNegativeRun> = runs.toList().also { runs.clear() }
}

private fun guardSamples(sampleRate: Int): Int = sampleRate / 5 + if (sampleRate % 5 == 0) 0 else 1

/** Incremental equivalent of HistoryNegativeRuns + removeSilence, in original coordinates.
 * Hold a boundary in an open run until it ends or is at least 200 ms behind its current end.
 * This bounds lookahead while retaining the final 200 ms if the run ends at the next frame. */
internal class LiveNegativeRuns(private val sampleRate: Int) {
    private val eligible = java.util.ArrayDeque<HistoryNegativeRun>()
    private val guard = guardSamples(sampleRate)
    private var processed = 0
    private var negativeStart: Int? = null
    private var qualified = false

    init { require(sampleRate > 0) }

    fun observe(start: Int, end: Int, probability: Float) {
        require(start == processed && end > start && probability.isFinite())
        processed = end
        if (probability < 0.5f) {
            val runStart = negativeStart ?: start.also { negativeStart = it }
            qualified = qualified || end - runStart >= guard
            if (qualified) {
                val last = eligible.peekLast()
                if (last != null && last.start == runStart) eligible.removeLast()
                eligible.addLast(HistoryNegativeRun(runStart, end))
            }
        } else {
            negativeStart = null
            qualified = false
        }
    }

    /** No future complete frame can change removal before this original-sample position. */
    fun resolvedThrough(): Int {
        val runStart = negativeStart ?: return processed
        return if (qualified) processed - guard else runStart
    }

    /** End of input resolves the trailing run; any partial frame stays unclassified. */
    fun finish(totalSamples: Int) {
        require(totalSamples >= processed)
        negativeStart = null
        qualified = false
        processed = totalSamples
    }

    fun takeForChunk(start: Int, end: Int): List<HistoryNegativeRun> {
        require(start >= 0 && end > start && end <= resolvedThrough())
        val result = eligible.filter { it.start < end && it.endExclusive > start }
        while (eligible.peekFirst()?.endExclusive?.let { it <= end } == true) eligible.removeFirst()
        return result
    }
}

internal object HistoryVadTransform {
    /** Qualify whole negative runs at 200 ms, then protect each run's final 200 ms and
     * the first 200 ms of this original chunk. Unclassified partial frames are retained. */
    fun removeSilence(samples: ShortArray, start: Int, end: Int, runs: List<HistoryNegativeRun>, sampleRate: Int): FloatArray {
        require(sampleRate > 0 && start >= 0 && start < end && end <= samples.size)
        val guard = guardSamples(sampleRate)
        val protectedStartEnd = start + minOf(end - start, guard)
        var previousEnd = 0
        var removed = 0
        for (run in runs) {
            require(run.start >= previousEnd && run.endExclusive > run.start && run.endExclusive <= samples.size)
            if (run.endExclusive - run.start >= guard) {
                val from = maxOf(protectedStartEnd, run.start)
                val until = minOf(end, run.endExclusive - guard)
                removed += (until - from).coerceAtLeast(0)
            }
            previousEnd = run.endExclusive
        }
        val result = FloatArray(end - start - removed)
        var source = start
        var destination = 0
        fun copy(until: Int) {
            while (source < until) result[destination++] = samples[source++] / 32768f
        }
        for (run in runs) {
            if (run.endExclusive - run.start >= guard) {
                val from = maxOf(protectedStartEnd, run.start)
                val until = minOf(end, run.endExclusive - guard)
                if (until > from) {
                    copy(from)
                    source = until
                }
            }
        }
        copy(end)
        check(destination == result.size)
        return result
    }

}
