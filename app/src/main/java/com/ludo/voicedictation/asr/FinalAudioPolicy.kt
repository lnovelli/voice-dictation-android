package com.ludo.voicedictation.asr

/** Final-only, lossless endpoint selection. Never runs during microphone capture. */
internal object FinalAudioPolicy {
    data class EndpointRange(val start: Int, val endExclusive: Int)

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
}
