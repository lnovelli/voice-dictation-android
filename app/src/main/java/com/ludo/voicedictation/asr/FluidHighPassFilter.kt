package com.ludo.voicedictation.asr

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Causal second-order Butterworth high-pass for the entire 16 kHz final waveform. */
internal object FluidHighPassFilter {
    fun apply(samples: FloatArray, isCancelled: () -> Boolean): FloatArray? {
        val omega = 2.0 * PI * 80.0 / 16_000.0
        val cosine = cos(omega)
        val alpha = sin(omega) / sqrt(2.0)
        val a0 = 1.0 + alpha
        val b0 = (1.0 + cosine) / (2.0 * a0)
        val b1 = -(1.0 + cosine) / a0
        val b2 = b0
        val a1 = -2.0 * cosine / a0
        val a2 = (1.0 - alpha) / a0
        val output = FloatArray(samples.size)
        var x1 = 0.0
        var x2 = 0.0
        var y1 = 0.0
        var y2 = 0.0
        for (i in samples.indices) {
            if (i % 16_384 == 0 && isCancelled()) return null
            val x = samples[i].toDouble()
            val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            output[i] = y.toFloat()
            x2 = x1
            x1 = x
            y2 = y1
            y1 = y
        }
        return output
    }
}
