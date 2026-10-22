package com.ludo.voicedictation.asr

/** Final-only, lossless endpoint selection. Never runs during microphone capture. */
internal object FinalAudioPolicy {
    data class EndpointRange(val start: Int, val endExclusive: Int)
    data class AudioWindow(val start: Int, val endExclusive: Int)

    /**
     * Remove only exact-zero leading/trailing PCM, retaining up to 500 ms of the
     * original recording on either side. Every nonzero sample and every internal
     * pause remains in one contiguous range, with no amplification or new padding.
     *
     * An RMS/relative-energy gate can mistake quiet speech for silence. Do not use
     * one here: noisy/quiet nonzero endpoints deliberately remain untouched. An
     * entirely zero recording also stays unchanged; this is not a speech detector.
     */
    fun endpointRange(samples: FloatArray, sampleRate: Int): EndpointRange {
        require(sampleRate > 0) { "Invalid sample rate." }
        var first = 0
        while (first < samples.size && samples[first] == 0f) first++
        if (first == samples.size) return EndpointRange(0, samples.size)
        var last = samples.lastIndex
        while (last > first && samples[last] == 0f) last--
        val margin = sampleRate / 2
        return EndpointRange(
            maxOf(0, first - margin),
            minOf(samples.size.toLong(), last.toLong() + 1L + margin).toInt()
        )
    }

    /**
     * Alternate retry strategy for long history recordings. Split only above 20 seconds,
     * preferring a low-energy 200 ms point in the final five seconds of each window.
     * Windows are contiguous and non-overlapping, so every sample (including pauses
     * and noisy tails) is decoded exactly once.
     */
    fun reprocessWindows(samples: FloatArray, sampleRate: Int): List<AudioWindow> {
        require(sampleRate > 0) { "Invalid sample rate." }
        val maxSamples = sampleRate.toLong() * 20L
        if (samples.size.toLong() <= maxSamples) return listOf(AudioWindow(0, samples.size))

        val searchSamples = sampleRate * 5
        val energySamples = maxOf(1, sampleRate / 5)
        val stepSamples = maxOf(1, energySamples / 2)
        val minTailSamples = sampleRate * 2
        val windows = mutableListOf<AudioWindow>()
        var start = 0
        while (samples.size.toLong() - start > maxSamples) {
            val maxEnd = minOf(samples.size.toLong(), start.toLong() + maxSamples).toInt()
            val latestCut = minOf(maxEnd.toLong(), samples.size.toLong() - minTailSamples).toInt()
            val searchStart = maxOf(start + 1, maxEnd - searchSamples)
            val lastCandidate = latestCut - energySamples
            var bestStart = searchStart
            if (lastCandidate >= searchStart) {
                var bestEnergy = Double.POSITIVE_INFINITY
                var candidate = searchStart
                while (candidate <= lastCandidate) {
                    var energy = 0.0
                    for (index in candidate until candidate + energySamples) {
                        val sample = samples[index].toDouble()
                        energy += sample * sample
                    }
                    if (energy < bestEnergy) {
                        bestEnergy = energy
                        bestStart = candidate
                    }
                    candidate += stepSamples
                }
            }
            val cut = (bestStart + energySamples / 2).coerceIn(start + 1, maxEnd)
            windows += AudioWindow(start, cut)
            start = cut
        }
        if (start < samples.size) windows += AudioWindow(start, samples.size)
        return windows
    }
}
