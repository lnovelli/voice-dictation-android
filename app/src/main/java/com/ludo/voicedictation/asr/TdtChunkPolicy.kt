package com.ludo.voicedictation.asr

/** Select live TDT boundaries from VAD observations without changing captured PCM. */
internal class TdtChunkPolicy(private val sampleRate: Int) {
    private var lastSpeechEndSample: Int? = null
    private var speechSamplesAtBoundary = 0
    var lastBoundaryReason: String? = null
        private set

    init {
        require(sampleRate > 0)
    }

    /** Close on a pause after five seconds of speech, or after a three-second silence. */
    fun onFrame(
        processedSamples: Int,
        detected: Boolean,
        speechEnded: Boolean,
        speechSamples: Int,
        possibleSpeech: Boolean
    ): Boolean {
        if (possibleSpeech) lastSpeechEndSample = processedSamples
        val speechEnd = lastSpeechEndSample ?: return false
        val shortPauseAfterMinimum = speechEnded && speechSamples - speechSamplesAtBoundary >= sampleRate * 5
        val longPause = !detected && processedSamples - speechEnd >= sampleRate * 3
        if (shortPauseAfterMinimum || longPause) {
            lastBoundaryReason = if (shortPauseAfterMinimum) "pause after 5s detected speech" else "3s silence"
            lastSpeechEndSample = null
            speechSamplesAtBoundary = speechSamples
            return true
        }
        return false
    }

    /** One second is decoded conservatively; unavailable VAD must not discard audio. */
    fun skipTail(tailSamples: Int, detectorAvailable: Boolean, possibleSpeech: Boolean): Boolean {
        require(tailSamples > 0)
        return tailSamples < sampleRate && detectorAvailable && !possibleSpeech
    }
}
