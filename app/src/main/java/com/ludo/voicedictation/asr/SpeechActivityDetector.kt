package com.ludo.voicedictation.asr

import android.content.Context
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.File
import java.security.MessageDigest

/** Visual-only speech activity detection; it never changes or trims the captured PCM. */
internal class SpeechActivityDetector(context: Context) : AutoCloseable {
    companion object {
        private const val SAMPLE_RATE = 16_000
        private const val WINDOW_SIZE = 512
        private const val ASSET_NAME = "vad/silero_vad.int8.onnx"
        private const val EXPECTED_SHA256 = "c36d490aff5ab924ca6c7aeec4d8f6bd3d22db6fa17611b9c5b17eae58ac3a20"
        private const val MAX_MODEL_BYTES = 500_000L
    }

    private val vad: Vad
    private val frame = FloatArray(WINDOW_SIZE)
    private var frameCount = 0
    private var closed = false

    init {
        val modelFile = ensureModelFile(context.applicationContext)
        val modelConfig = SileroVadModelConfig(
            model = modelFile.absolutePath,
            threshold = 0.5f,
            minSilenceDuration = 0.25f,
            minSpeechDuration = 0.15f,
            windowSize = WINDOW_SIZE,
            maxSpeechDuration = 5.0f
        )
        vad = Vad(
            null,
            VadModelConfig(
                sileroVadModelConfig = modelConfig,
                sampleRate = SAMPLE_RATE,
                numThreads = 1,
                provider = "cpu",
                debug = false
            )
        )
    }

    /** Returns the latest VAD state when a complete 512-sample frame was processed. */
    fun acceptPcm16(samples: ShortArray, offset: Int, length: Int): Boolean? {
        check(!closed) { "Speech activity detector is closed" }
        require(offset >= 0 && length >= 0 && offset + length <= samples.size)
        var result: Boolean? = null
        val end = offset + length
        for (index in offset until end) {
            frame[frameCount++] = samples[index] / 32768.0f
            if (frameCount == WINDOW_SIZE) {
                vad.acceptWaveform(frame)
                result = vad.isSpeechDetected()
                // Segments are not used for ASR; pop them promptly so VAD cannot retain
                // speech audio in its internal queue throughout a long recording.
                while (!vad.empty()) vad.pop()
                frameCount = 0
            }
        }
        return result
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { vad.release() }
    }

    private fun ensureModelFile(context: Context): File {
        val directory = File(context.filesDir, "models/vad")
        check(directory.isDirectory || directory.mkdirs()) { "Could not create VAD model directory." }
        val destination = File(directory, "silero_vad.int8.onnx")
        if (destination.isFile && sha256(destination) == EXPECTED_SHA256) return destination

        val temporary = File(directory, "silero_vad.int8.onnx.tmp")
        if (temporary.exists()) check(temporary.delete()) { "Could not replace temporary VAD model." }
        context.assets.open(ASSET_NAME).use { input ->
            temporary.outputStream().use { output -> input.copyTo(output) }
        }
        check(temporary.length() in 1..MAX_MODEL_BYTES) { "Bundled Silero VAD model has an invalid size." }
        check(sha256(temporary) == EXPECTED_SHA256) { "Bundled Silero VAD model failed integrity verification." }
        if (destination.exists()) check(destination.delete()) { "Could not replace the cached VAD model." }
        check(temporary.renameTo(destination)) { "Could not install the Silero VAD model." }
        return destination
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    }
}
