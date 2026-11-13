package com.ludo.voicedictation.asr

import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.audio.AudioSource
import com.google.mlkit.genai.speechrecognition.SpeechRecognition
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerOptions
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerResponse
import com.google.mlkit.genai.speechrecognition.speechRecognizerOptions
import com.google.mlkit.genai.speechrecognition.speechRecognizerRequest
import com.ludo.voicedictation.data.RecordingLimits
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

/** A single Advanced-only ML Kit client; callers own and close each instance. */
class AdvancedSpeechModel(locale: Locale) : AutoCloseable {
    private val recognizer = SpeechRecognition.getClient(speechRecognizerOptions {
        this.locale = locale
        preferredMode = SpeechRecognizerOptions.Mode.MODE_ADVANCED
    })

    suspend fun checkStatus(): Int {
        return try {
            recognizer.checkStatus().also { status ->
                RecognitionDiagnostics.record("status", when (status) {
                    FeatureStatus.AVAILABLE -> "available"
                    FeatureStatus.DOWNLOADABLE -> "downloadable"
                    FeatureStatus.DOWNLOADING -> "downloading"
                    FeatureStatus.UNAVAILABLE -> "unavailable"
                    else -> "unknown_status"
                }, status)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            RecognitionDiagnostics.recordFailure("status", e)
            throw e
        }
    }
    fun download(): Flow<DownloadStatus> = recognizer.download()
    override fun close() = recognizer.close()

    internal suspend fun requireAvailable() {
        val status = checkStatus()
        check(status == FeatureStatus.AVAILABLE) {
            when (status) {
                FeatureStatus.DOWNLOADABLE -> "Download the ML Kit Advanced model in Settings."
                FeatureStatus.DOWNLOADING -> "The ML Kit Advanced model is still downloading."
                else -> "ML Kit Advanced is unavailable for this device or language."
            }
        }
    }

    /** PCM is the bounded original capture. The unlinked file is owned only by the descriptor. */
    internal suspend fun transcribe(samples: FloatArray, cacheDir: File): String {
        require(samples.isNotEmpty() && samples.size <= RecordingLimits.MAX_LIVE_SAMPLES)
        requireAvailable() // Recheck immediately before inference, not just at session startup.
        var stage = "prepare"
        var file: File? = null
        var fd: ParcelFileDescriptor? = null
        var source: AudioSource? = null
        try {
            file = File.createTempFile("mlkit-audio-", ".pcm", cacheDir)
            val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_WRITE)
            fd = descriptor
            // Unlink before writing any PCM, including if the process dies during preparation.
            check(file.delete()) { "Could not securely release temporary audio file." }
            file = null
            ParcelFileDescriptor.AutoCloseOutputStream(
                ParcelFileDescriptor.dup(descriptor.fileDescriptor)
            ).buffered().use { output ->
                val bytes = ByteArray(8192)
                var offset = 0
                while (offset < samples.size) {
                    currentCoroutineContext().ensureActive()
                    val count = minOf(bytes.size / 2, samples.size - offset)
                    for (i in 0 until count) {
                        val value = (samples[offset + i] * 32768f).roundToInt().coerceIn(-32768, 32767)
                        bytes[i * 2] = value.toByte()
                        bytes[i * 2 + 1] = (value shr 8).toByte()
                    }
                    output.write(bytes, 0, count * 2)
                    offset += count
                }
            }
            currentCoroutineContext().ensureActive()
            // dup shares its file offset with the read descriptor.
            Os.lseek(descriptor.fileDescriptor, 0, OsConstants.SEEK_SET)
            val input = AudioSource.fromPfd(descriptor, AudioSource.Mode.ONE_SHOT)
            source = input
            fd = null // AudioSource owns the descriptor.
            val request = speechRecognizerRequest { audioSource = input }
            val finalParts = mutableListOf<String>()
            var completed = false
            stage = "recognition"
            RecognitionDiagnostics.record(stage, "started")
            recognizer.startRecognition(request).collect { response ->
                currentCoroutineContext().ensureActive()
                when (response) {
                    is SpeechRecognizerResponse.FinalTextResponse -> {
                        RecognitionDiagnostics.record(stage, if (response.text.isBlank()) "empty_result" else "final_text")
                        finalParts.add(response.text)
                    }
                    is SpeechRecognizerResponse.ErrorResponse -> {
                        RecognitionDiagnostics.recordRecognitionError(response.e)
                        throw response.e
                    }
                    is SpeechRecognizerResponse.CompletedResponse -> {
                        completed = true
                        RecognitionDiagnostics.record(stage, "completed")
                    }
                    else -> Unit
                }
            }
            if (!completed) RecognitionDiagnostics.record("completion", "incomplete")
            check(completed) { "ML Kit Advanced recognition did not complete." }
            return finalParts.joinToString(" ").trim().also {
                RecognitionDiagnostics.record("completion", if (it.isEmpty()) "empty_result" else "success")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            RecognitionDiagnostics.recordFailure(stage, e)
            throw e
        } finally {
            // Drain the SDK's session before dropping our descriptor, including on cancellation.
            withContext(NonCancellable) {
                runCatching { withTimeoutOrNull(3000) { recognizer.stopRecognition() } }
                runCatching { source?.close() }
                runCatching { fd?.close() }
                file?.delete()
            }
        }
    }
}
