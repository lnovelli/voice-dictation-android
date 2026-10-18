package com.ludo.voicedictation.asr

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
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
        // 120 seconds of mono, signed 16-bit little-endian raw PCM. No WAV header.
        private const val MAX_FILE_BYTES = 3_840_000L
        private const val WINDOW_SIZE = 512
        private const val MAX_LIVE_SAMPLES = SAMPLE_RATE * 120
        private const val PREVIEW_SAMPLES = SAMPLE_RATE * 12
        private const val PREVIEW_INTERVAL_MS = 2_000L
        @Volatile private var instance: SpeechEngine? = null
        fun getInstance(context: Context): SpeechEngine = instance ?: synchronized(this) {
            instance ?: SpeechEngine(context.applicationContext).also { instance = it }
        }
    }

    /** All control methods and listener callbacks run on the main thread. */
    interface ExternalListener {
        fun onState(state: String)
        fun onPartial(text: String)
        // Called only after capture, final decode, native-lock and coroutine cleanup finish.
        // Both values are null when cancelled. Empty speech is reported as an error.
        fun onFinished(text: String?, error: String?)
    }

    private class Session(
        val owner: Any? = null,
        val listener: ExternalListener? = null,
        val audioFile: ParcelFileDescriptor? = null
    ) {
        @Volatile var stopped = false
        @Volatile var cancelled = false
        var text: String? = null
        var error: String? = null

        @Synchronized fun closeAudioFile() { runCatching { audioFile?.close() } }
    }

    val isExternalSessionActive: Boolean get() = session?.owner != null
    val isLocalSessionActive: Boolean get() = session?.let { it.owner == null && !it.cancelled } == true
    val isBusy: Boolean get() = session != null || sessionJob?.isActive == true || releaseJob?.isActive == true
    private val mainHandler = Handler(Looper.getMainLooper())

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

    fun startRecording(): Boolean = startSession(Session())

    /** A private identity token keeps external control separate from the floating overlay. */
    fun startExternal(owner: Any, listener: ExternalListener): Boolean =
        startSession(Session(owner, listener))

    /** On success the session owns the descriptor, including cancellation and error cleanup. */
    fun startExternalFile(owner: Any, listener: ExternalListener, audioFile: ParcelFileDescriptor): Boolean =
        startSession(Session(owner, listener, audioFile))

    private fun startSession(current: Session): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (!prefs.serviceEnabled || isBusy) return false
        if (current.audioFile == null &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            if (current.listener == null) onError?.invoke("Grant microphone permission in Settings before dictating.")
            return false
        }
        session = current
        setState(false, true, "loading")
        val job = scope.launch {
            try {
                val samples = if (current.audioFile != null) {
                    withContext(Dispatchers.IO) { readAudioFile(current) }
                } else null
                current.text = withContext(Dispatchers.Default) {
                    nativeLock.withLock {
                        if (current.stopped || current.cancelled || !prefs.serviceEnabled) return@withLock ""
                        initializeEngine()
                        if (current.stopped || current.cancelled || !prefs.serviceEnabled) return@withLock ""
                        if (samples == null) recognize(current) else {
                            withContext(Dispatchers.Main) {
                                if (!current.cancelled) setState(false, true)
                            }
                            // A single final pass preserves full context/punctuation and avoids
                            // repeatedly decoding an ever-growing file (quadratic partial work).
                            if (current.cancelled || !prefs.serviceEnabled) "" else decode(samples)
                        }
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) {
                    current.cancelled = true
                    throw e
                }
                Log.e(TAG, "Dictation failed", e)
                current.error = "Dictation failed: ${e.message}"
            } finally {
                current.closeAudioFile()
            }
        }
        sessionJob = job
        job.invokeOnCompletion { cause ->
            // No unexpected coroutine/native failure may publish an earlier draft as success.
            if (cause != null && !current.cancelled) current.error = "Dictation failed: ${cause.message}"
            // Post even when already on Main: ownership cannot be released until the job is complete.
            mainHandler.post {
                if (session !== current) return@post
                session = null
                sessionJob = null
                isRecording = false
                isProcessing = false
                if (current.listener == null) onStateChanged?.invoke(false, false)
                if (current.cancelled) {
                    current.listener?.onFinished(null, null)
                } else {
                    val text = current.text?.takeIf { it.isNotBlank() }
                    val error = current.error ?: when {
                        !prefs.serviceEnabled -> "Dictation service is disabled."
                        text == null -> "No speech was recognized."
                        else -> null
                    }
                    if (error == null && text != null) {
                        prefs.addHistoryItem(text)
                        if (current.listener != null) current.listener.onFinished(text, null)
                        else onFinalResult?.invoke(text)
                    } else if (current.listener != null) {
                        current.listener.onFinished(null, error)
                    } else {
                        onError?.invoke(error ?: "Dictation failed.")
                    }
                }
            }
        }
        return true
    }

    /** Bounded positional reads: ignore the sender's seek offset and never open a URI/path. */
    private fun readAudioFile(current: Session): FloatArray {
        val file = checkNotNull(current.audioFile)
        try {
            if (current.cancelled) return floatArrayOf()
            val fd = file.fileDescriptor
            val stat = Os.fstat(fd)
            require(OsConstants.S_ISREG(stat.st_mode)) { "audio_fd must refer to a regular file, not a pipe or device." }
            val size = stat.st_size
            require(size > 0 && size <= MAX_FILE_BYTES && size % 2L == 0L) {
                "PCM file must have a nonzero even size of at most $MAX_FILE_BYTES bytes (120 seconds)."
            }
            val pcm = ByteArray(size.toInt())
            var offset = 0
            while (offset < pcm.size) {
                if (current.cancelled) return floatArrayOf()
                val count = Os.pread(fd, pcm, offset, minOf(8192, pcm.size - offset), offset.toLong())
                check(count > 0) { "PCM file was truncated while reading." }
                offset += count
            }
            if (current.cancelled) return floatArrayOf()
            // Do not silently accept a concurrently grown file or bypass the duration bound.
            require(Os.fstat(fd).st_size == size && Os.pread(fd, ByteArray(1), 0, 1, size) == 0) {
                "PCM file changed while reading; send a completed immutable recording."
            }
            return FloatArray(pcm.size / 2) { index ->
                val low = pcm[index * 2].toInt() and 0xff
                val high = pcm[index * 2 + 1].toInt()
                ((high shl 8) or low) / 32768f
            }
        } finally {
            current.closeAudioFile()
        }
    }

    // All native creation, decoding and destruction run under nativeLock, never on the UI thread.
    private fun initializeEngine() {
        val model = ModelInfo.getModelById(prefs.selectedModelId)
        val words = prefs.getVocabulary().toSet()
        val settings = Triple(model.id, words, prefs.hotwordsScore)
        if (recognizer != null && loadedSettings == settings) return
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
            // VAD is a preview optimization, not a dependency of full-context recognition.
            vad = try { Vad(null, VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(
                    model = modelManager.vadFile.absolutePath, threshold = 0.5f,
                    minSilenceDuration = 0.35f, minSpeechDuration = 0.2f,
                    windowSize = WINDOW_SIZE, maxSpeechDuration = 8f
                ), sampleRate = SAMPLE_RATE, numThreads = 1, provider = "cpu"
            )) } catch (e: Exception) {
                Log.w(TAG, "VAD initialization failed; previews will be ungated", e)
                null
            }
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
        // VAD and rolling decodes are advisory only. They never trim the recording,
        // decide whether a final decode runs, or contribute text to the final result.
        var detector = vad
        var previewsEnabled = true
        try { detector?.reset() } catch (e: Exception) {
            Log.w(TAG, "VAD unavailable; continuing with full-recording decode", e)
            detector = null
        }
        val minBytes = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(minBytes > 0) { "Microphone format is unsupported ($minBytes)." }
        // Preserve the exact PCM, including pauses and the final short read. Only
        // preview frames are zero-padded. Fixed capacity: 120 s / 3,840,000 PCM bytes.
        val recording = ShortArray(MAX_LIVE_SAMPLES)
        val capturedSamples = java.util.concurrent.atomic.AtomicInteger()
        // Enough for the ENTIRE bounded recording even if one preview is very slow.
        // Unlike the old 10-second queue, slow inference cannot discard final audio.
        val frames = Channel<FloatArray>(MAX_LIVE_SAMPLES / WINDOW_SIZE + 1)
        val audio = AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(minBytes * 4, SAMPLE_RATE * 2))
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
            val capture = launch(Dispatchers.IO) {
                try {
                    val pcm = ShortArray(WINDOW_SIZE)
                    var filled = 0
                    var total = 0
                    while (!current.stopped && !current.cancelled && total < MAX_LIVE_SAMPLES) {
                        val count = audio.read(pcm, filled, minOf(WINDOW_SIZE - filled, MAX_LIVE_SAMPLES - total))
                        if (current.cancelled) break
                        if (count <= 0 && current.stopped) break
                        check(count > 0) { "Microphone read failed ($count)." }
                        pcm.copyInto(recording, total, filled, filled + count)
                        total += count
                        capturedSamples.set(total)
                        filled += count
                        if (filled == WINDOW_SIZE) {
                            check(frames.trySend(FloatArray(WINDOW_SIZE) { pcm[it] / 32768f }).isSuccess) {
                                "Bounded preview audio queue failed."
                            }
                            filled = 0
                        }
                    }
                    if (filled > 0 && !current.cancelled) {
                        check(frames.trySend(FloatArray(WINDOW_SIZE) { if (it < filled) pcm[it] / 32768f else 0f }).isSuccess) {
                            "Bounded preview audio queue failed."
                        }
                    }
                    frames.close()
                } catch (e: Exception) {
                    frames.close(e)
                } finally {
                    current.stopped = true
                    synchronized(recorderLock) {
                        if (recorder === audio) recorder = null
                        if (audio.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                            try { audio.stop() } catch (e: IllegalStateException) { Log.w(TAG, "Microphone cleanup failed", e) }
                        }
                    }
                    withContext(NonCancellable + Dispatchers.Main) {
                        if (session === current && !current.cancelled) setState(false, true)
                    }
                }
            }
            try {
                val pending = SampleWindow(PREVIEW_SAMPLES)
                var consumed = 0
                var lastDecode = SystemClock.elapsedRealtime()
                var previousPartial = ""
                for (frame in frames) {
                    if (current.cancelled) break
                    consumed += frame.size
                    pending.keepLast(PREVIEW_SAMPLES - frame.size)
                    pending.append(frame)
                    if (!previewsEnabled || current.stopped) continue
                    try {
                        val previewVad = detector
                        previewVad?.acceptWaveform(frame)
                        // Pop every completed segment to bound native VAD storage. No stitching.
                        var endedSpeech = false
                        if (previewVad != null) {
                            while (!previewVad.empty()) {
                                endedSpeech = true
                                previewVad.pop()
                            }
                        }
                        val now = SystemClock.elapsedRealtime()
                        if ((previewVad == null || endedSpeech || previewVad.isSpeechDetected()) &&
                            now - lastDecode >= PREVIEW_INTERVAL_MS && pending.size >= SAMPLE_RATE / 2 &&
                            consumed >= capturedSamples.get() - WINDOW_SIZE * 2) {
                            val partial = decode(pending.snapshot())
                            lastDecode = SystemClock.elapsedRealtime()
                            if (partial.isNotBlank() && partial != previousPartial) {
                                previousPartial = partial
                                withContext(Dispatchers.Main) {
                                    if (!current.cancelled && !current.stopped && prefs.serviceEnabled) {
                                        if (current.listener != null) current.listener.onPartial(partial)
                                        else onPartialResult?.invoke(partial)
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        // An advisory preview failure must not substitute a stitched result
                        // or discard otherwise valid captured PCM. Try the final decode once.
                        Log.w(TAG, "Streaming preview disabled for this recording", e)
                        previewsEnabled = false
                    }
                }
                capture.join() // Publish all PCM writes before the authoritative snapshot.
                if (current.cancelled || !prefs.serviceEnabled) "" else {
                    withContext(Dispatchers.Main) {
                        if (!current.cancelled) setState(false, true)
                    }
                    if (current.cancelled || !prefs.serviceEnabled) "" else {
                        val count = capturedSamples.get()
                        if (count == 0) "" else decode(FloatArray(count) { recording[it] / 32768f })
                    }
                }
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
            try { detector?.reset() } catch (e: Exception) { Log.w(TAG, "VAD reset failed", e) }
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
        val current = session ?: return
        if (current.owner != null) return
        stopSession(current)
    }

    fun stopExternal(owner: Any) {
        session?.takeIf { it.owner === owner }?.let { stopSession(it) }
    }

    fun cancelExternal(owner: Any) {
        session?.takeIf { it.owner === owner }?.let {
            it.cancelled = true
            stopSession(it)
        }
    }

    private fun stopSession(current: Session) {
        if (current.cancelled) current.closeAudioFile()
        // A file is already complete: STOP is a safe no-op, only CANCEL discards its result.
        if (current.audioFile != null) return
        current.stopped = true
        stopMicrophone()
        if (sessionJob?.isActive == true) setState(false, true)
    }

    fun cancelRecording() {
        val current = session ?: return
        if (current.owner != null) return
        current.cancelled = true
        stopSession(current)
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
        session?.let {
            it.cancelled = true
            stopSession(it)
        }
        releaseJob?.takeIf { it.isActive }?.let { return it }
        val running = sessionJob
        return scope.launch {
            running?.join()
            withContext(Dispatchers.Default) { nativeLock.withLock { releaseNative() } }
        }.also { releaseJob = it }
    }

    private fun releaseNative() {
        // Detach before destruction: even a native release exception cannot leave a
        // stale object available for a later session or a second release.
        val oldRecognizer = recognizer
        val oldVad = vad
        recognizer = null
        vad = null
        loadedSettings = null
        try { oldRecognizer?.release() } finally { oldVad?.release() }
    }

    private fun setState(recording: Boolean, processing: Boolean, state: String = if (recording) "recording" else "processing") {
        isRecording = recording
        isProcessing = processing
        val current = session
        if (current?.listener != null) {
            if (!current.cancelled) current.listener.onState(state)
        } else {
            onStateChanged?.invoke(recording, processing)
        }
    }
}
