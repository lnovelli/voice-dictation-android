package com.ludo.voicedictation.asr

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.k2fsa.sherpa.onnx.*
import com.ludo.voicedictation.data.AppPreferences
import com.ludo.voicedictation.model.ModelInfo
import com.ludo.voicedictation.model.ModelManager
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

class SpeechEngine private constructor(private val context: Context) {
    companion object {
        private const val TAG = "SpeechEngine"
        private const val SAMPLE_RATE = 16000
        private const val WINDOW_SIZE = 512
        @Volatile private var instance: SpeechEngine? = null
        fun getInstance(context: Context): SpeechEngine = instance ?: synchronized(this) {
            instance ?: SpeechEngine(context.applicationContext).also { instance = it }
        }
    }

    private class Session {
        @Volatile var stopped = false
        @Volatile var cancelled = false
    }

    private val prefs = AppPreferences(context)
    private val modelManager = ModelManager(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val nativeLock = Mutex()
    private val recorderLock = Any()
    private var recognizer: OfflineRecognizer? = null
    private var vad: Vad? = null
    private var loadedSettings: Triple<String, Set<String>, Float>? = null
    private var recorder: AudioRecord? = null
    private var session: Session? = null
    private var sessionJob: Job? = null
    private var releaseJob: Job? = null

    @Volatile var isRecording = false
        private set
    @Volatile var isProcessing = false
        private set
    var onStateChanged: ((Boolean, Boolean) -> Unit)? = null
    var onPartialResult: ((String) -> Unit)? = null
    var onFinalResult: ((String) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    fun isModelReady(): Boolean =
        modelManager.isModelDownloaded(ModelInfo.getModelById(prefs.selectedModelId))

    fun startRecording(): Boolean {
        if (!prefs.serviceEnabled || isProcessing || isRecording || sessionJob?.isActive == true || releaseJob?.isActive == true) return false
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            onError?.invoke("Grant microphone permission in Settings before dictating.")
            return false
        }
        val current = Session()
        session = current
        setState(false, true)
        sessionJob = scope.launch {
            try {
                val text = withContext(Dispatchers.Default) {
                    nativeLock.withLock {
                        initializeEngine()
                        if (current.stopped || current.cancelled || !prefs.serviceEnabled) return@withLock ""
                        recognize(current)
                    }
                }
                if (!current.cancelled && prefs.serviceEnabled && text.isNotBlank()) {
                    prefs.addHistoryItem(text)
                    onFinalResult?.invoke(text)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e(TAG, "Dictation failed", e)
                if (!current.cancelled) onError?.invoke("Dictation failed: ${e.message}")
            } finally {
                session = null
                setState(false, false)
            }
        }
        return true
    }

    // All native creation, decoding and destruction run under nativeLock, never on the UI thread.
    private fun initializeEngine() {
        val model = ModelInfo.getModelById(prefs.selectedModelId)
        val words = prefs.getVocabulary().toSet()
        val settings = Triple(model.id, words, prefs.hotwordsScore)
        if (recognizer != null && vad != null && loadedSettings == settings) return
        releaseNative()
        check(modelManager.isModelDownloaded(model)) { "Download the selected model in Settings." }
        val dir = modelManager.getModelDir(model)
        val tokenizer = prepareTokenizer(model, File(dir, model.tokensFileName))
        val hotwords = File(context.filesDir, "hotwords.txt")
        require(words.all { it.isNotBlank() && !it.contains('\n') && !it.contains('\r') && !it.contains(':') }) {
            "Vocabulary entries must be single-line words or phrases without a colon."
        }
        if (words.isNotEmpty()) hotwords.writeText(words.sorted().joinToString("\n"), Charsets.UTF_8)
        try {
            recognizer = OfflineRecognizer(null, OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 128),
                modelConfig = OfflineModelConfig(
                    transducer = OfflineTransducerModelConfig(
                        encoder = File(dir, model.encoderFileName).absolutePath,
                        decoder = File(dir, model.decoderFileName).absolutePath,
                        joiner = File(dir, model.joinerFileName).absolutePath
                    ),
                    tokens = File(dir, model.tokensFileName).absolutePath,
                    numThreads = 2, provider = "cpu", modelType = "nemo_transducer",
                    modelingUnit = "bpe", bpeVocab = tokenizer.absolutePath
                ),
                decodingMethod = if (words.isEmpty()) "greedy_search" else "modified_beam_search",
                hotwordsFile = if (words.isEmpty()) "" else hotwords.absolutePath,
                hotwordsScore = settings.third, maxActivePaths = 4
            ))
            vad = Vad(null, VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(
                    model = modelManager.vadFile.absolutePath, threshold = 0.5f,
                    minSilenceDuration = 0.35f, minSpeechDuration = 0.2f,
                    windowSize = WINDOW_SIZE, maxSpeechDuration = 8f
                ), sampleRate = SAMPLE_RATE, numThreads = 1, provider = "cpu"
            ))
            loadedSettings = settings
            Log.i(TAG, "Loaded ${model.id}; contextual terms=${words.size}")
        } catch (e: Exception) {
            releaseNative()
            throw e
        }
    }

    private fun prepareTokenizer(model: ModelInfo, tokens: File): File {
        val content = context.assets.open("tokenizers/${model.id}.vocab").bufferedReader().use { it.readText() }
        TokenizerValidator.validate(content, tokens.readText())
        val file = File(context.filesDir, "${model.id}.vocab")
        file.writeText(content, Charsets.UTF_8)
        return file
    }

    @SuppressLint("MissingPermission")
    private suspend fun recognize(current: Session): String = coroutineScope {
        val detector = checkNotNull(vad)
        detector.reset()
        val minBytes = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(minBytes > 0) { "Microphone format is unsupported ($minBytes)." }
        val audio = AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(minBytes * 4, SAMPLE_RATE * 2))
        val frames = Channel<FloatArray>(320)
        try {
            synchronized(recorderLock) {
                check(audio.state == AudioRecord.STATE_INITIALIZED) { "Microphone could not be initialized." }
                if (!current.stopped && !current.cancelled) {
                    audio.startRecording()
                    check(audio.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Microphone did not start." }
                    recorder = audio
                }
            }
            if (current.stopped || current.cancelled) return@coroutineScope ""
            withContext(Dispatchers.Main) {
                if (!current.stopped && !current.cancelled) setState(true, false)
            }
            // Capture continues while inference runs; a bounded queue fails explicitly if inference cannot keep up.
            val capture = launch(Dispatchers.IO) {
                try {
                    val pcm = ShortArray(WINDOW_SIZE)
                    var filled = 0
                    while (!current.stopped && !current.cancelled) {
                        val count = audio.read(pcm, filled, WINDOW_SIZE - filled)
                        if (current.cancelled) break
                        if (count <= 0 && current.stopped) break
                        check(count > 0) { "Microphone read failed ($count)." }
                        filled += count
                        if (filled == WINDOW_SIZE) {
                            val frame = FloatArray(WINDOW_SIZE) { pcm[it] / 32768f }
                            check(frames.trySend(frame).isSuccess) { "Inference cannot keep up with recording. Stop and try shorter dictation or fewer vocabulary terms." }
                            filled = 0
                        }
                    }
                    if (filled > 0 && !current.cancelled) {
                        check(frames.trySend(FloatArray(WINDOW_SIZE) { if (it < filled) pcm[it] / 32768f else 0f }).isSuccess) { "Audio queue is full." }
                    }
                    frames.close()
                } catch (e: Exception) {
                    frames.close(e)
                } finally {
                    synchronized(recorderLock) {
                        if (recorder === audio) recorder = null
                        if (audio.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                            try { audio.stop() } catch (e: IllegalStateException) { Log.w(TAG, "Microphone cleanup failed", e) }
                        }
                    }
                }
            }
            try {
                val pending = SampleWindow(SAMPLE_RATE * 10)
                val completed = mutableListOf<String>()
                var lastDecode = SystemClock.elapsedRealtime()
                var active = false
                var previousPartial = ""
                suspend fun consumeSegments() {
                    while (!detector.empty()) {
                        val segment = detector.front()
                        detector.pop()
                        if (!current.cancelled) {
                            val text = decode(segment.samples)
                            if (text.isNotBlank()) completed.add(text)
                        }
                        pending.discardBefore(segment.start.toLong() + segment.samples.size)
                        active = false
                    }
                }
                for (frame in frames) {
                    if (current.cancelled) break
                    pending.append(frame)
                    detector.acceptWaveform(frame)
                    consumeSegments()
                    if (detector.isSpeechDetected()) active = true
                    if (!active) pending.keepLast(SAMPLE_RATE / 2)
                    val now = SystemClock.elapsedRealtime()
                    if (active && !current.stopped && now - lastDecode >= 750 && pending.size >= SAMPLE_RATE / 2) {
                        val partial = (completed + decode(pending.snapshot())).filter { it.isNotBlank() }.joinToString(" ")
                        lastDecode = SystemClock.elapsedRealtime()
                        if (partial != previousPartial) {
                            previousPartial = partial
                            withContext(Dispatchers.Main) { if (!current.cancelled && prefs.serviceEnabled) onPartialResult?.invoke(partial) }
                        }
                    }
                }
                if (!current.cancelled) {
                    withContext(Dispatchers.Main) { setState(false, true) }
                    detector.flush()
                    consumeSegments()
                }
                completed.joinToString(" ")
            } finally {
                current.stopped = true
                stopMicrophone()
                withContext(NonCancellable) { capture.join() }
                frames.cancel()
            }
        } finally {
            synchronized(recorderLock) {
                if (recorder === audio) recorder = null
                audio.release()
            }
            detector.reset()
        }
    }

    private fun decode(samples: FloatArray): String {
        val engine = checkNotNull(recognizer)
        val stream = engine.createStream()
        return try {
            stream.acceptWaveform(samples, SAMPLE_RATE)
            engine.decode(stream)
            engine.getResult(stream).text.trim()
        } finally { stream.release() }
    }

    fun stopRecording() {
        session?.stopped = true
        stopMicrophone()
        if (sessionJob?.isActive == true) setState(false, true)
    }

    fun cancelRecording() {
        session?.cancelled = true
        stopRecording()
    }

    private fun stopMicrophone() {
        synchronized(recorderLock) {
            val audio = recorder ?: return
            if (audio.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                try { audio.stop() } catch (e: IllegalStateException) { Log.w(TAG, "Microphone stop failed", e) }
            }
        }
    }

    fun reloadVocabulary() {
        // Settings are snapshotted at session start. Never destroy a recognizer during a decode.
    }

    fun releaseEngine(): Job {
        cancelRecording()
        val running = sessionJob
        return scope.launch {
            running?.join()
            withContext(Dispatchers.Default) { nativeLock.withLock { releaseNative() } }
        }.also { releaseJob = it }
    }

    private fun releaseNative() {
        recognizer?.release()
        recognizer = null
        vad?.release()
        vad = null
        loadedSettings = null
    }

    private fun setState(recording: Boolean, processing: Boolean) {
        isRecording = recording
        isProcessing = processing
        onStateChanged?.invoke(recording, processing)
    }
}
