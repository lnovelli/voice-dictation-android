package com.ludo.voicedictation.asr

import android.content.Context
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.File
import java.security.MessageDigest

internal data class SpeechActivityUpdate(
    val processedSamples: Int,
    val detected: Boolean,
    val speechEnded: Boolean
)

/** Silero endpoint detection; VAD locates boundaries but never filters authoritative PCM. */
internal class SpeechActivityDetector(
    context: Context,
    private val tdtChunking: Boolean = false
) : AutoCloseable {
    companion object {
        private const val SAMPLE_RATE = 16_000
        private const val WINDOW_SIZE = 512
        private const val SPEECH_THRESHOLD = 0.5f
        private const val MIN_SILENCE_SECONDS = 0.25f
        private const val MIN_SPEECH_SECONDS = 0.15f
        private const val DEFAULT_MAX_SPEECH_SECONDS = 5f
        private const val TDT_MAX_SPEECH_SECONDS = 15f
        private const val ASSET_NAME = "vad/silero_vad.int8.onnx"
        private const val EXPECTED_SHA256 = "c36d490aff5ab924ca6c7aeec4d8f6bd3d22db6fa17611b9c5b17eae58ac3a20"
        private const val MAX_MODEL_BYTES = 500_000L
    }

    private val vad: Vad
    private val frame = FloatArray(WINDOW_SIZE)
    private var frameCount = 0
    private var processedSamples = 0
    private var speechStartSample = 0
    private var speechStartCandidate = 0
    private var silenceStartSample = 0
    private var triggered = false
    private var lastDetected = false
    private var closed = false

    init {
        val modelFile = ensureModelFile(context.applicationContext)
        val modelConfig = SileroVadModelConfig(
            model = modelFile.absolutePath,
            threshold = SPEECH_THRESHOLD,
            minSilenceDuration = MIN_SILENCE_SECONDS,
            minSpeechDuration = MIN_SPEECH_SECONDS,
            windowSize = WINDOW_SIZE,
            maxSpeechDuration = if (tdtChunking) TDT_MAX_SPEECH_SECONDS else DEFAULT_MAX_SPEECH_SECONDS
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

    /**
     * Returns state transitions from every complete 512-sample frame. TDT endpoint mode uses
     * the probability API to avoid Sherpa retaining/copying an arbitrarily long active segment;
     * ordinary visual-only capture keeps the established segmenting API and its 5-second config.
     */
    fun acceptPcm16(samples: ShortArray, offset: Int, length: Int): List<SpeechActivityUpdate> {
        check(!closed) { "Speech activity detector is closed" }
        require(offset >= 0 && length >= 0 && offset + length <= samples.size)
        val updates = ArrayList<SpeechActivityUpdate>(length / WINDOW_SIZE + 1)
        val initialDetected = lastDetected
        val end = offset + length
        for (index in offset until end) {
            frame[frameCount++] = samples[index] / 32768.0f
            if (frameCount == WINDOW_SIZE) {
                val previousDetected = lastDetected
                processedSamples += WINDOW_SIZE
                val detected = if (tdtChunking) {
                    // The max-duration trigger is intentionally an adaptive VAD policy, not a
                    // forced speech boundary. Its onset is approximate to sherpa's buffered size.
                    val adaptive = triggered &&
                        processedSamples - speechStartSample > TDT_MAX_SPEECH_SECONDS * SAMPLE_RATE
                    val threshold = if (adaptive) 0.9f else SPEECH_THRESHOLD
                    val minSilenceSamples = (SAMPLE_RATE * if (adaptive) 0.1f else MIN_SILENCE_SECONDS).toInt()
                    updateSpeechState(vad.compute(frame), threshold, minSilenceSamples)
                } else {
                    vad.acceptWaveform(frame)
                    val state = vad.isSpeechDetected()
                    // Keep the visual-only path's existing native segmenter bounded as before.
                    while (!vad.empty()) vad.pop()
                    state
                }
                lastDetected = detected
                if (tdtChunking && detected != previousDetected) {
                    updates += SpeechActivityUpdate(
                        processedSamples = processedSamples,
                        detected = detected,
                        speechEnded = previousDetected && !detected
                    )
                }
                frameCount = 0
            }
        }
        if (!tdtChunking && lastDetected != initialDetected) {
            updates += SpeechActivityUpdate(
                processedSamples = processedSamples,
                detected = lastDetected,
                speechEnded = initialDetected && !lastDetected
            )
        }
        return updates
    }

    /** Mirrors sherpa-onnx 1.13.8 Silero's IsSpeech() hysteresis and duration fallback. */
    private fun updateSpeechState(probability: Float, threshold: Float, minSilenceSamples: Int): Boolean {
        if (probability > threshold && silenceStartSample != 0) silenceStartSample = 0

        if (probability > threshold && speechStartCandidate == 0) {
            speechStartCandidate = processedSamples
            return false
        }

        if (probability > threshold && speechStartCandidate != 0 && !triggered) {
            if (processedSamples - speechStartCandidate < SAMPLE_RATE * MIN_SPEECH_SECONDS) return false
            triggered = true
            speechStartSample = speechStartCandidate
            return true
        }

        if (probability < threshold && !triggered) {
            speechStartCandidate = 0
            silenceStartSample = 0
            return false
        }

        val negativeThreshold = maxOf(threshold - 0.15f, 0.01f)
        if (probability > negativeThreshold && triggered) return true

        if (probability > threshold && !triggered) {
            triggered = true
            speechStartSample = processedSamples
            return true
        }

        if (probability < threshold && triggered) {
            if (silenceStartSample == 0) silenceStartSample = processedSamples
            if (processedSamples - silenceStartSample < minSilenceSamples) return true
            speechStartCandidate = 0
            speechStartSample = 0
            silenceStartSample = 0
            triggered = false
            return false
        }

        return false
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
