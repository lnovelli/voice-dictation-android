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
import android.system.Os
import android.system.OsConstants
import android.util.Log
import androidx.core.content.ContextCompat
import com.k2fsa.sherpa.onnx.*
import com.ludo.voicedictation.data.AppPreferences
import com.ludo.voicedictation.data.HistoryAudioStore
import com.ludo.voicedictation.model.ModelInfo
import com.ludo.voicedictation.model.ModelManager
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

class SpeechEngine private constructor(private val context: Context) {
    companion object {
        private const val TAG = "SpeechEngine"
        private const val SAMPLE_RATE = 16000
        // 120 seconds of mono, signed 16-bit little-endian raw PCM. No WAV header.
        private const val MAX_FILE_BYTES = 3_840_000L
        private const val CAPTURE_READ_SAMPLES = 1024
        private const val MAX_LIVE_SAMPLES = SAMPLE_RATE * 120
        private val HOTWORD_PATTERN = Regex("""^[\p{L}\p{M}\p{N}]+(?:[- '’][\p{L}\p{M}\p{N}]+)*$""")
        @Volatile private var instance: SpeechEngine? = null
        fun getInstance(context: Context): SpeechEngine = instance ?: synchronized(this) {
            instance ?: SpeechEngine(context.applicationContext).also { instance = it }
        }
    }

    /** All control methods and listener callbacks run on the main thread. */
    interface ExternalListener {
        fun onState(state: String)
        // Called only after capture, final decode, native-lock and coroutine cleanup finish.
        // Both values are null when cancelled. Empty speech is reported as an error.
        fun onFinished(text: String?, error: String?)
    }

    private class Session(
        val owner: Any? = null,
        val listener: ExternalListener? = null,
        val audioFile: ParcelFileDescriptor? = null
    ) {
        val stopRequested = CompletableDeferred<Unit>()
        @Volatile var stopped = false
        @Volatile var cancelled = false
        var text: String? = null
        var error: String? = null
        var historyAudioFileName: String? = null

        @Synchronized fun closeAudioFile() { runCatching { audioFile?.close() } }
    }

    private data class DecoderConfigKey(
        val modelId: String,
        val acousticBiasEnabled: Boolean,
        val maxActivePaths: Int,
        val hotwordsScore: Float,
        val hotwords: List<String>
    )

    val isExternalSessionActive: Boolean get() = session?.owner != null
    val isLocalSessionActive: Boolean get() = session?.let { it.owner == null && !it.cancelled } == true
    val isBusy: Boolean get() = session != null || sessionJob?.isActive == true || releaseJob?.isActive == true
    private val mainHandler = Handler(Looper.getMainLooper())

    private val prefs = AppPreferences(context)
    private val modelManager = ModelManager(context)
    private val transcriptPolisher = TranscriptPolisher()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val nativeLock = Mutex()
    private val recorderLock = Any()
    private var recognizer: OfflineRecognizer? = null
    private var loadedConfigKey: DecoderConfigKey? = null
    private var spellingHints = RecognitionPolicy.SpellingHints(emptySet())
    private var transcriptFilter = TranscriptFilter(emptyList())
    private var stringReplacer = CustomStringReplacer(emptyList())
    private var recorder: AudioRecord? = null
    private var session: Session? = null
    private var sessionJob: Job? = null
    private var releaseJob: Job? = null

    @Volatile var isRecording = false
        private set
    /** Capture is closed at the cap, but Stop/Cancel still owns the decision. */
    @Volatile var isAwaitingStop = false
        private set
    @Volatile var isProcessing = false
        private set
    @Volatile var currentStatus: String = "ready"
        private set
    var onStateChanged: ((Boolean, Boolean) -> Unit)? = null
    var onRecordingLimit: (() -> Unit)? = null
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
        // Live capture is PCM-only: do not load a model or touch native ASR until Stop.
        setState(current.audioFile == null, current.audioFile != null,
            if (current.audioFile == null) "recording" else "loading")
        val job = scope.launch {
            try {
                val samples = withContext(Dispatchers.IO) {
                    if (current.audioFile != null) readAudioFile(current) else captureRecording(current)
                }
                if (!current.cancelled && prefs.serviceEnabled && samples.isNotEmpty()) {
                    setState(false, true, "loading")
                    current.text = withContext(Dispatchers.Default) {
                        nativeLock.withLock {
                            if (current.cancelled || !prefs.serviceEnabled) return@withLock ""
                            initializeEngine()
                            if (current.cancelled || !prefs.serviceEnabled) return@withLock ""
                            withContext(Dispatchers.Main) {
                                if (!current.cancelled) setState(false, true, "processing")
                            }
                            decodeRecording(samples, current)
                        }
                    }
                    val originalText = current.text.orEmpty()
                    if (!current.cancelled && prefs.serviceEnabled && originalText.isNotBlank()) {
                        if (prefs.transcriptPolishingEnabled) {
                            val polishedText = transcriptPolisher.polish(
                                baseUrl = prefs.transcriptPolishingEndpoint,
                                model = prefs.transcriptPolishingModel,
                                vocabulary = prefs.getVocabulary(),
                                transcript = originalText
                            )
                            current.text = if (!current.cancelled && prefs.transcriptPolishingEnabled) {
                                polishedText ?: originalText
                            } else originalText
                        }
                        if (!current.cancelled && prefs.serviceEnabled) {
                            current.text = transcriptFilter.apply(
                                stringReplacer.apply(spellingHints.apply(current.text.orEmpty()))
                            )
                        }
                        if (!current.cancelled && prefs.serviceEnabled && !current.text.isNullOrBlank()) {
                            val savedAudioFileName = withContext(Dispatchers.IO) {
                                HistoryAudioStore.save(context, samples)
                            }
                            if (!current.cancelled && prefs.serviceEnabled) {
                                current.historyAudioFileName = savedAudioFileName
                            } else if (savedAudioFileName != null) {
                                withContext(Dispatchers.IO) {
                                    HistoryAudioStore.delete(context, savedAudioFileName)
                                }
                            }
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
                isAwaitingStop = false
                isProcessing = false
                currentStatus = "ready"
                onStateChanged?.invoke(false, false)
                if (current.cancelled) {
                    HistoryAudioStore.delete(context, current.historyAudioFileName)
                    current.listener?.onFinished(null, null)
                } else {
                    val text = current.text?.takeIf { it.isNotBlank() }
                    val error = current.error ?: when {
                        !prefs.serviceEnabled -> "Dictation service is disabled."
                        text == null -> "Nothing was transcribed. Long quiet recordings can lose speech, especially with the multilingual model. Try a shorter recording."
                        else -> null
                    }
                    if (error == null && text != null) {
                        prefs.addHistoryItem(text, current.historyAudioFileName)
                        if (current.listener != null) current.listener.onFinished(text, null)
                        else onFinalResult?.invoke(text)
                    } else {
                        HistoryAudioStore.delete(context, current.historyAudioFileName)
                        if (current.listener != null) {
                            current.listener.onFinished(null, error)
                        } else {
                            onError?.invoke(error ?: "Dictation failed.")
                        }
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
        // Snapshot vocabulary and post-processing options only after explicit Stop/completed-file capture.
        val vocabulary = prefs.getVocabulary().toSet()
        spellingHints = RecognitionPolicy.SpellingHints(
            if (prefs.vocabularySpellingEnabled) vocabulary else emptySet()
        )
        transcriptFilter = TranscriptFilter(
            if (prefs.transcriptRemovalEnabled) prefs.transcriptRemovalWords else emptyList()
        )
        stringReplacer = CustomStringReplacer(prefs.getStringReplacementRules())

        val safeHotwords = if (prefs.acousticVocabularyEnabled) {
            vocabulary.asSequence()
                .map { it.trim().replace(Regex("""\s+"""), " ") }
                .filter { it.length in 1..80 && HOTWORD_PATTERN.matches(it) }
                .distinct()
                .sorted()
                .toList()
        } else emptyList()
        val useAcousticBias = safeHotwords.isNotEmpty()
        val configKey = DecoderConfigKey(
            modelId = model.id,
            acousticBiasEnabled = useAcousticBias,
            maxActivePaths = if (useAcousticBias) prefs.acousticMaxActivePaths else 1,
            hotwordsScore = if (useAcousticBias) prefs.acousticHotwordsScore else 0f,
            hotwords = safeHotwords
        )
        if (recognizer != null && loadedConfigKey == configKey) return

        releaseNative()
        check(modelManager.isModelDownloaded(model)) { "Download the selected model in Settings." }
        val dir = modelManager.getModelDir(model)
        try {
            val bpeVocab = if (useAcousticBias) installBpeVocab(model) else null
            val hotwordsFile = if (useAcousticBias) writeHotwordsFile(safeHotwords) else ""
            recognizer = OfflineRecognizer(null, OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 128),
                modelConfig = OfflineModelConfig(
                    transducer = OfflineTransducerModelConfig(
                        encoder = File(dir, model.encoderFileName).absolutePath,
                        decoder = File(dir, model.decoderFileName).absolutePath,
                        joiner = File(dir, model.joinerFileName).absolutePath
                    ),
                    tokens = File(dir, model.tokensFileName).absolutePath,
                    numThreads = 2,
                    provider = "cpu",
                    modelType = "nemo_transducer",
                    modelingUnit = if (useAcousticBias) "bpe" else "",
                    bpeVocab = bpeVocab?.absolutePath ?: ""
                ),
                decodingMethod = if (useAcousticBias) "modified_beam_search" else "greedy_search",
                maxActivePaths = configKey.maxActivePaths,
                hotwordsFile = hotwordsFile,
                hotwordsScore = configKey.hotwordsScore,
                blankPenalty = 0.0f
            ))
            loadedConfigKey = configKey
            Log.i(TAG, if (useAcousticBias) {
                "Loaded ${model.id}; experimental modified beam search (paths=${configKey.maxActivePaths}, score=${configKey.hotwordsScore}, blank=0.0)"
            } else {
                "Loaded ${model.id}; unbiased TDT greedy decoder"
            })
        } catch (e: Exception) {
            releaseNative()
            throw e
        }
    }

    /** Materialize the selected model's matching BPE vocabulary as a private file for sherpa-onnx. */
    private fun installBpeVocab(model: ModelInfo): File {
        val assetName = if (model.id == AppPreferences.MODEL_PARAKEET_V3) {
            "parakeet_v3.vocab"
        } else {
            "parakeet_v2.vocab"
        }
        val directory = File(context.cacheDir, "asr-tokenizers")
        check(directory.isDirectory || directory.mkdirs()) { "Could not create tokenizer directory." }
        val destination = File(directory, assetName)
        val temporary = File(directory, "$assetName.tmp")
        if (temporary.exists()) check(temporary.delete()) { "Could not replace temporary tokenizer file." }
        context.assets.open("tokenizers/$assetName").use { input ->
            temporary.outputStream().use { output -> input.copyTo(output) }
        }
        check(temporary.length() > 100L) { "Bundled BPE vocabulary is missing or incomplete." }
        if (destination.exists()) check(destination.delete()) { "Could not replace cached tokenizer file." }
        check(temporary.renameTo(destination)) { "Could not install BPE vocabulary." }
        return destination
    }

    /** sherpa's file-based hotword parser reserves slash, colon-score syntax and line breaks. */
    private fun writeHotwordsFile(hotwords: List<String>): String {
        require(hotwords.isNotEmpty())
        val directory = File(context.cacheDir, "asr-context")
        check(directory.isDirectory || directory.mkdirs()) { "Could not create ASR context directory." }
        val file = File(directory, "hotwords.txt")
        file.writeText(hotwords.joinToString(System.lineSeparator(), postfix = System.lineSeparator()), Charsets.UTF_8)
        return file.absolutePath
    }

    /** PCM only. No ASR/VAD model, waveform queue, padding or inference during capture. */
    @SuppressLint("MissingPermission")
    private suspend fun captureRecording(current: Session): FloatArray {
        if (current.stopped || current.cancelled) return floatArrayOf()
        val minBytes = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(minBytes > 0) { "Microphone format is unsupported ($minBytes)." }
        val recording = ShortArray(MAX_LIVE_SAMPLES)
        var count = 0
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
            while (!current.stopped && !current.cancelled && count < recording.size) {
                // Write successful short reads directly into the authoritative bounded PCM.
                val read = audio.read(recording, count, minOf(CAPTURE_READ_SAMPLES, recording.size - count))
                if (current.cancelled) break
                if (read <= 0 && current.stopped) break
                check(read > 0) { "Microphone read failed ($read)." }
                count += read
            }
        } finally {
            // Release the microphone BEFORE waiting at the cap or initializing native ASR.
            synchronized(recorderLock) {
                if (recorder === audio) recorder = null
                try {
                    if (audio.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        try { audio.stop() } catch (e: IllegalStateException) { Log.w(TAG, "Microphone cleanup failed", e) }
                    }
                } finally { audio.release() }
            }
        }
        if (!current.stopped && !current.cancelled && count == MAX_LIVE_SAMPLES) {
            withContext(Dispatchers.Main) {
                if (!current.stopped && !current.cancelled && session === current) {
                    // Keep Stop/Cancel controls active. A resource cap is NOT consent to decode.
                    setState(true, false, "recording_limit")
                    if (current.listener == null) onRecordingLimit?.invoke()
                }
            }
            current.stopRequested.await()
        }
        if (current.cancelled || !prefs.serviceEnabled) return floatArrayOf()
        check(current.stopped) { "Recording must be explicitly stopped before transcription." }
        return FloatArray(count) { recording[it] / 32768f }
    }

    /** Exactly one contiguous-recording native call, after lossless endpoint selection.
     * Native inference cannot be interrupted; retain ownership until cleanup returns.
     */
    private fun decodeRecording(samples: FloatArray, current: Session): String {
        if (current.cancelled || !prefs.serviceEnabled) return ""
        require(samples.isNotEmpty() && samples.size <= MAX_LIVE_SAMPLES) { "Recording duration exceeded." }
        // Only exact-zero endpoints are eligible, once after explicit Stop/completed-file
        // capture. Preserve every nonzero sample and every internal pause in one range.
        val endpoints = FinalAudioPolicy.endpointRange(samples, SAMPLE_RATE)
        val finalSamples = if (endpoints.start == 0 && endpoints.endExclusive == samples.size) samples
            else samples.copyOfRange(endpoints.start, endpoints.endExclusive)
        if (current.cancelled || !prefs.serviceEnabled) return ""
        Log.i(TAG, "Final PCM endpoints ${endpoints.start}..${endpoints.endExclusive} of ${samples.size}; exact-zero only")
        val text = decode(finalSamples, current)
        if (current.cancelled || !prefs.serviceEnabled) return ""
        return text
    }

    private fun decode(samples: FloatArray, current: Session): String {
        require(samples.isNotEmpty() && samples.size <= MAX_LIVE_SAMPLES) { "Recording duration exceeded." }
        val engine = checkNotNull(recognizer)
        val stream = engine.createStream()
        return try {
            stream.acceptWaveform(samples, SAMPLE_RATE)
            if (current.cancelled || !prefs.serviceEnabled) return ""
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
        current.stopRequested.complete(Unit)
        stopMicrophone()
        if (sessionJob?.isActive == true) setState(false, true, "loading")
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
        // Spelling hints are snapshotted at finalization. Never destroy a recognizer during a decode.
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
        recognizer = null
        loadedConfigKey = null
        oldRecognizer?.release()
    }

    private fun setState(recording: Boolean, processing: Boolean, state: String = if (recording) "recording" else "processing") {
        isRecording = recording
        isAwaitingStop = state == "recording_limit"
        isProcessing = processing
        currentStatus = state
        onStateChanged?.invoke(recording, processing)
        val current = session
        if (current?.listener != null) {
            if (!current.cancelled) current.listener.onState(state)
        }
    }
}
