package com.ludo.voicedictation.data

/** Shared live-capture and saved-history bounds for 16 kHz mono PCM. */
internal object RecordingLimits {
    const val SAMPLE_RATE = 16_000
    const val MAX_LIVE_SECONDS = 600
    const val MAX_LIVE_SAMPLES = SAMPLE_RATE * MAX_LIVE_SECONDS
}
