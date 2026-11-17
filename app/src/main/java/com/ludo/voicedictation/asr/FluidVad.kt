package com.ludo.voicedictation.asr

import android.content.Context
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.Closeable
import java.io.File
import java.security.DigestInputStream
import java.security.MessageDigest

/** Silero probabilities are boundary hints only: neither capture nor decoding filters PCM. */
internal class FluidVad private constructor(private val vad: Vad) : Closeable {
    companion object {
        const val WINDOW = 512 // 32 ms at 16 kHz, Silero V4
        private const val RATE = 16000
        private const val PAUSE_SAMPLES = RATE * 300 / 1000
        private const val ONSET_SAMPLES = RATE * 64 / 1000
        // The native runtime can terminate the process on an unsupported graph.
        private const val MODEL_SHA256 = "c36d490aff5ab924ca6c7aeec4d8f6bd3d22db6fa17611b9c5b17eae58ac3a20"

        fun open(context: Context): FluidVad {
            // Use a private, unique temporary copy; the model is only read at construction.
            val file = File.createTempFile("fluid-vad-", ".onnx", context.cacheDir)
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                DigestInputStream(context.assets.open("silero_vad.onnx"), digest).use { input ->
                    file.outputStream().use { input.copyTo(it) }
                }
                val hash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
                check(hash == MODEL_SHA256) { "Unexpected VAD model" }
                val config = VadModelConfig(
                    sileroVadModelConfig = SileroVadModelConfig(model = file.absolutePath, windowSize = WINDOW),
                    sampleRate = RATE, numThreads = 1, provider = "cpu", debug = false
                )
                return FluidVad(Vad(null, config))
            } finally {
                file.delete()
            }
        }

        /** Return contiguous exclusive end offsets, including exactly one final tail. */
        fun boundaries(samples: FloatArray, maxSamples: Int, context: Context, cancelled: () -> Boolean): List<Int> {
            if (samples.size <= maxSamples) return listOf(samples.size)
            val pauses = mutableListOf<IntRange>()
            open(context).use { detector ->
                var lowStart = -1
                var offset = 0
                while (offset + WINDOW <= samples.size) {
                    if (cancelled()) return emptyList()
                    val low = detector.compute(samples.copyOfRange(offset, offset + WINDOW)).first
                    if (low && lowStart < 0) lowStart = offset
                    if (!low && lowStart >= 0) {
                        if (offset - lowStart >= PAUSE_SAMPLES) pauses.add(lowStart until offset)
                        lowStart = -1
                    }
                    offset += WINDOW
                }
                if (lowStart >= 0 && offset - lowStart >= PAUSE_SAMPLES) pauses.add(lowStart until offset)
            }
            val result = mutableListOf<Int>()
            var start = 0
            var pauseIndex = 0
            while (samples.size - start > maxSamples) {
                if (cancelled()) return emptyList()
                val limit = start + maxSamples
                val earliest = start + maxSamples * 3 / 5
                while (pauseIndex < pauses.size && pauses[pauseIndex].last + 1 <= earliest) pauseIndex++
                var chosen = -1
                var i = pauseIndex
                while (i < pauses.size && pauses[i].first < limit) {
                    val pause = pauses[i]
                    val pauseEnd = minOf(pause.last + 1, limit)
                    // The 300 ms qualification must be observed before this chunk's limit.
                    if (pauseEnd - pause.first >= PAUSE_SAMPLES) {
                        val midpoint = pause.first + (pauseEnd - pause.first) / 2
                        val candidate = maxOf(earliest, midpoint)
                        if (candidate < pauseEnd && candidate <= limit) chosen = candidate
                    }
                    i++
                }
                start = if (chosen > start) chosen else limit
                result.add(start)
            }
            result.add(samples.size)
            return result
        }
    }

    private var speaking = false
    private var highSamples = 0
    private var lowSamples = 0

    /** A frame yields raw low-probability status and hysteretic live speaking state. */
    fun compute(frame: FloatArray): Pair<Boolean, Boolean> {
        require(frame.size == WINDOW)
        val probability = vad.compute(frame)
        check(probability.isFinite() && probability in 0f..1f) { "Invalid VAD probability" }
        val low = probability < 0.35f
        if (speaking) {
            lowSamples = if (low) lowSamples + WINDOW else 0
            if (lowSamples >= PAUSE_SAMPLES) {
                speaking = false
                highSamples = 0
            }
        } else {
            highSamples = if (probability >= 0.50f) highSamples + WINDOW else 0
            if (highSamples >= ONSET_SAMPLES) {
                speaking = true
                lowSamples = 0
            }
        }
        return low to speaking
    }

    override fun close() = vad.release()
}
