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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

class SpeechEngine private constructor(private val context: Context) {
    companion object {
        private const val TAG = "SpeechEngine"
        private const val SAMPLE_RATE = 16000
        private const val MAX_FILE_BYTES = 3_840_000L // Completed-file IPC input is limited to 120 seconds.
        private const val CAPTURE_READ_SAMPLES = 1024
        // Batch PCM into half-second queue items; size the queue for the full bounded capture.
        private const val STREAM_CHUNK_SAMPLES = SAMPLE_RATE / 2
        private const val MAX_LIVE_SAMPLES = SAMPLE_RATE * 600 // 10 minutes of mono PCM16 capture.
        private const val VAD_FRAME_SAMPLES = 512
        private const val MIN_TDT_CHUNK_SPEECH_SAMPLES = SAMPLE_RATE * 5
        private const val RAW_PCM_QUEUE_CAPACITY = MAX_LIVE_SAMPLES / CAPTURE_READ_SAMPLES + 2
        private const val TDT_CHUNK_QUEUE_CAPACITY = MAX_LIVE_SAMPLES / VAD_FRAME_SAMPLES + 2
        private const val STOP_DRAIN_TIMEOUT_MS = 200L
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
        val audioFile: ParcelFileDescriptor? = null,
        var modelId: String = AppPreferences.MODEL_PARAKEET_V2,
        val historyAudioInputFileName: String? = null,
        val historyReprocessOwner: Any? = null,
        val historyPolishingConfiguration: TranscriptPolishingConfiguration? = null
    ) {
        val stopRequested = CompletableDeferred<Unit>()
        @Volatile var stopped = false
        @Volatile var cancelled = false
        var text: String? = null
        var error: String? = null
        var historyAudioFileName: String? = null
        var finalTextSettingsCaptured = false
        var finalPolishingEnabled = false
        var finalPolishingMinimumWords: Int? = null
        var finalPolishingAutoNumberSequences: Boolean? = null
        var finalPolishingConfiguration: TranscriptPolishingConfiguration? = null
        var acousticDecoderSettings: AcousticDecoderSettings? = null
        @Volatile var stopDrainTimeout: Runnable? = null

        @Synchronized fun closeAudioFile() { runCatching { audioFile?.close() } }
    }

    private data class DecoderConfigKey(
        val modelId: String,
        val acousticBiasEnabled: Boolean,
        val maxActivePaths: Int,
        val hotwordsScore: Float,
        val hotwords: List<String>
    )

    private data class AcousticDecoderSettings(
        val vocabulary: Set<String>,
        val vocabularyEnabled: Boolean,
        val maxActivePaths: Int,
        val hotwordsScore: Float
    )

    private data class CapturedPcmRange(
        val samples: ShortArray,
        val startSample: Int,
        val endExclusive: Int
    )

    private data class TdtPcmChunk(
        val sequence: Int,
        val samples: ShortArray,
        val startSample: Int,
        val endExclusive: Int
    )

    private data class TdtCaptureResult(val samples: FloatArray, val text: String)

    val isExternalSessionActive: Boolean get() = session?.let {
        it.owner != null && it.owner !== historyReprocessOwnerMarker
    } == true
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
    private var onlineRecognizer: OnlineRecognizer? = null
    private var loadedConfigKey: DecoderConfigKey? = null
    private var spellingHints = RecognitionPolicy.SpellingHints(emptySet())
    private var transcriptFilter = TranscriptFilter(emptyList())
    private var stringReplacer = CustomStringReplacer(emptyList())
    private var staticNumericCorrector: StaticNumericCorrector? = null
    private var recorder: AudioRecord? = null
    private var session: Session? = null
    private var sessionJob: Job? = null
    private var releaseJob: Job? = null
    private val historyReprocessOwnerMarker = Any()

    @Volatile var isRecording = false
        private set
    /** Capture is closed at the cap, but Stop/Cancel still owns the decision. */
    @Volatile var isAwaitingStop = false
        private set
    @Volatile var isProcessing = false
        private set
    @Volatile var currentStatus: String = "ready"
        private set
    @Volatile var isSpeechDetected: Boolean = false
        private set
    @Volatile var speechActivityDetectionAvailable: Boolean = false
        private set
    var onStateChanged: ((Boolean, Boolean) -> Unit)? = null
    var onSpeechActivityChanged: (() -> Unit)? = null
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

    /** User-requested retry; results never reach overlay insertion or clipboard. */
    fun startHistoryReprocess(
        owner: Any,
        audioFileName: String,
        listener: ExternalListener,
        polishingConfiguration: TranscriptPolishingConfiguration? = null
    ): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (!HistoryAudioStore.isSafeFileName(audioFileName) ||
            HistoryAudioStore.find(context, audioFileName) == null) return false
        return startSession(Session(
            owner = historyReprocessOwnerMarker,
            listener = listener,
            historyAudioInputFileName = audioFileName,
            historyReprocessOwner = owner,
            historyPolishingConfiguration = polishingConfiguration
        ))
    }

    fun cancelHistoryReprocess(owner: Any) {
        session?.takeIf { it.historyReprocessOwner === owner }?.let {
            it.cancelled = true
            stopSession(it)
        }
    }

    private fun startSession(current: Session): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (!prefs.serviceEnabled || isBusy) return false
        val hasCompletedAudio = current.audioFile != null || current.historyAudioInputFileName != null
        if (current.historyAudioInputFileName != null &&
            HistoryAudioStore.find(context, current.historyAudioInputFileName) == null) return false
        if (!hasCompletedAudio &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            if (current.listener == null) onError?.invoke("Grant microphone permission in Settings before dictating.")
            return false
        }
        current.modelId = ModelInfo.getModelById(prefs.selectedModelId).id
        val selectedModel = ModelInfo.getModelById(current.modelId)
        val isTdtModel = current.modelId == AppPreferences.MODEL_PARAKEET_V2 ||
            current.modelId == AppPreferences.MODEL_PARAKEET_V3
        val liveTdt = isTdtModel && !selectedModel.streaming && current.audioFile == null &&
            current.historyAudioInputFileName == null
        if (liveTdt) current.acousticDecoderSettings = snapshotAcousticDecoderSettings()
        session = current
        // Keep the control active during mic startup, but expose a distinct not-yet-recording state.
        setState(!hasCompletedAudio, true, if (hasCompletedAudio) "loading" else "starting")
        val job = scope.launch {
            try {
                val model = selectedModel
                val liveStreaming = model.streaming && current.audioFile == null &&
                    current.historyAudioInputFileName == null
                val samples = if (liveStreaming) {
                    val capture = captureAndTranscribeStreaming(current)
                    current.text = capture.text
                    capture.samples
                } else if (liveTdt) {
                    val capture = captureAndTranscribeTdtChunks(current)
                    current.text = capture.text
                    capture.samples
                } else withContext(Dispatchers.IO) {
                    when {
                        current.audioFile != null -> readAudioFile(current)
                        current.historyAudioInputFileName != null ->
                            HistoryAudioStore.readSamples(context, current.historyAudioInputFileName)
                        else -> captureRecording(current)
                    }
                }
                if (!current.cancelled && prefs.serviceEnabled && samples.isNotEmpty()) {
                    if (current.text == null) {
                        setState(false, true, "loading")
                        current.text = withContext(Dispatchers.Default) {
                            nativeLock.withLock {
                                if (current.cancelled || !prefs.serviceEnabled) return@withLock ""
                                initializeEngine(
                                    forceUnbiased = current.historyAudioInputFileName != null,
                                    modelId = current.modelId
                                )
                                if (current.cancelled || !prefs.serviceEnabled) return@withLock ""
                                withContext(Dispatchers.Main) {
                                    if (!current.cancelled) setState(false, true, "processing")
                                }
                                decodeRecording(
                                    samples,
                                    current,
                                    reprocessInWindows = current.historyAudioInputFileName != null
                                )
                            }
                        }
                    }
                    val originalText = current.text.orEmpty()
                    if (!current.cancelled && prefs.serviceEnabled && originalText.isNotBlank()) {
                        val preNumericText = stringReplacer.apply(spellingHints.apply(originalText))
                        val thresholdText = transcriptFilter.apply(preNumericText)
                        val correctedText = applyTranscriptTransforms(originalText)
                        val minimumWords = current.finalPolishingMinimumWords
                            ?: prefs.transcriptPolishingMinimumWords
                        val autoNumberSequences = current.finalPolishingAutoNumberSequences
                            ?: prefs.transcriptPolishingAutoNumberSequences
                        val polishingThresholdMet = countTranscriptWords(thresholdText) >= minimumWords
                        val autoNumberPolishTriggered = autoNumberSequences && !polishingThresholdMet &&
                            (StaticNumericCorrector.containsNearbyNumbers(thresholdText) ||
                                StaticNumericCorrector.containsAmbiguousOne(thresholdText))
                        val polishingEnabled = if (current.finalTextSettingsCaptured) {
                            current.finalPolishingEnabled
                        } else {
                            prefs.transcriptPolishingEnabled
                        }
                        val polishingConfiguration = current.historyPolishingConfiguration ?:
                            current.finalPolishingConfiguration ?:
                            if (current.historyAudioInputFileName == null && polishingEnabled) {
                                TranscriptPolishingConfiguration(
                                    baseUrl = prefs.transcriptPolishingEndpoint,
                                    model = prefs.transcriptPolishingModel,
                                    vocabulary = prefs.getVocabulary().toSet(),
                                    systemPrompt = prefs.customTranscriptPolishingSystemPrompt ?: TranscriptPolisher.SYSTEM_PROMPT
                                )
                            } else null
                        val polishingAllowed = polishingEnabled &&
                            (current.historyAudioInputFileName == null || current.historyPolishingConfiguration != null)
                        val shouldPolish = polishingThresholdMet || autoNumberPolishTriggered
                        if (!current.cancelled && polishingAllowed && shouldPolish && polishingConfiguration != null) {
                            setState(false, true, "polishing")
                            val polishedText = transcriptPolisher.polish(
                                baseUrl = polishingConfiguration.baseUrl,
                                model = polishingConfiguration.model,
                                vocabulary = polishingConfiguration.vocabulary,
                                transcript = originalText,
                                systemPrompt = polishingConfiguration.systemPrompt
                            )
                            if (!current.cancelled) setState(false, true, "processing")
                            val stillEnabled = if (current.finalTextSettingsCaptured) {
                                current.finalPolishingEnabled
                            } else {
                                prefs.transcriptPolishingEnabled
                            }
                            current.text = if (!current.cancelled && stillEnabled) {
                                applyTranscriptTransforms(polishedText ?: originalText)
                            } else correctedText
                        } else {
                            current.text = correctedText
                        }
                        if (!current.cancelled && prefs.serviceEnabled && !current.text.isNullOrBlank() &&
                            current.historyAudioInputFileName == null) {
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
                clearSpeechActivity()
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
                        // History reprocessing replaces the selected row through its listener;
                        // it must not also create a fresh entry here.
                        if (current.historyAudioInputFileName == null) {
                            prefs.addHistoryItem(text, current.historyAudioFileName)
                        }
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
    private fun initializeEngine(
        forceUnbiased: Boolean = false,
        modelId: String = prefs.selectedModelId,
        deferTranscriptTransforms: Boolean = false,
        decoderSettings: AcousticDecoderSettings? = null
    ) {
        val model = ModelInfo.getModelById(modelId)
        if (!deferTranscriptTransforms) snapshotTranscriptTransforms()
        // Decoder context is independent of final-text transforms.
        val vocabulary = decoderSettings?.vocabulary ?: prefs.getVocabulary().toSet()

        val acousticVocabularyEnabled = decoderSettings?.vocabularyEnabled ?: prefs.acousticVocabularyEnabled
        val safeHotwords = if (!forceUnbiased && !model.streaming && acousticVocabularyEnabled) {
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
            maxActivePaths = if (useAcousticBias) decoderSettings?.maxActivePaths ?: prefs.acousticMaxActivePaths else 1,
            hotwordsScore = if (useAcousticBias) decoderSettings?.hotwordsScore ?: prefs.acousticHotwordsScore else 0f,
            hotwords = safeHotwords
        )
        val recognizerLoaded = if (model.streaming) onlineRecognizer != null else recognizer != null
        if (recognizerLoaded && loadedConfigKey == configKey) return

        releaseNative()
        check(modelManager.isModelDownloaded(model)) { "Download the selected model in Settings." }
        val dir = modelManager.getModelDir(model)
        try {
            if (model.streaming) {
                onlineRecognizer = OnlineRecognizer(null, OnlineRecognizerConfig(
                    featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 128),
                    modelConfig = OnlineModelConfig(
                        transducer = OnlineTransducerModelConfig(
                            encoder = File(dir, model.encoderFileName).absolutePath,
                            decoder = File(dir, model.decoderFileName).absolutePath,
                            joiner = File(dir, model.joinerFileName).absolutePath
                        ),
                        tokens = File(dir, model.tokensFileName).absolutePath,
                        numThreads = 2,
                        provider = "cpu"
                    ),
                    // Keep one transcript across the recording; explicit Stop performs the flush.
                    enableEndpoint = false,
                    decodingMethod = "greedy_search"
                ))
                loadedConfigKey = configKey
                Log.i(TAG, "Loaded ${model.id}; buffered Parakeet Unified streaming greedy decoder")
                return
            }
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

    /** Snapshot post-recognition rules when finalization starts, not while streaming capture is active. */
    private fun snapshotTranscriptTransforms() {
        val vocabulary = prefs.getVocabulary().toSet()
        spellingHints = RecognitionPolicy.SpellingHints(
            if (prefs.vocabularySpellingEnabled) vocabulary else emptySet()
        )
        transcriptFilter = TranscriptFilter(
            if (prefs.transcriptRemovalEnabled) prefs.transcriptRemovalWords else emptyList()
        )
        stringReplacer = CustomStringReplacer(prefs.getStringReplacementRules())
        staticNumericCorrector = if (prefs.staticNumericCorrectionsEnabled) StaticNumericCorrector() else null
    }

    private fun applyTranscriptTransforms(text: String): String {
        val spellingCorrected = spellingHints.apply(text)
        val replaced = stringReplacer.apply(spellingCorrected)
        val numericallyCorrected = staticNumericCorrector?.apply(replaced) ?: replaced
        return transcriptFilter.apply(numericallyCorrected)
    }

    private fun countTranscriptWords(text: String): Int =
        Regex("[\\p{L}\\p{M}\\p{N}]+(?:['’][\\p{L}\\p{M}\\p{N}]+)*")
            .findAll(text)
            .count()

    private fun snapshotAcousticDecoderSettings() = AcousticDecoderSettings(
        vocabulary = prefs.getVocabulary().toSet(),
        vocabularyEnabled = prefs.acousticVocabularyEnabled,
        maxActivePaths = prefs.acousticMaxActivePaths,
        hotwordsScore = prefs.acousticHotwordsScore
    )

    /** Freeze final-text behavior at explicit Stop, while chunk ASR may still be draining. */
    private fun snapshotFinalizationSettings(current: Session) {
        snapshotTranscriptTransforms()
        current.finalPolishingEnabled = prefs.transcriptPolishingEnabled
        current.finalPolishingMinimumWords = prefs.transcriptPolishingMinimumWords
        current.finalPolishingAutoNumberSequences = prefs.transcriptPolishingAutoNumberSequences
        current.finalPolishingConfiguration = if (current.finalPolishingEnabled) {
            TranscriptPolishingConfiguration(
                baseUrl = prefs.transcriptPolishingEndpoint,
                model = prefs.transcriptPolishingModel,
                vocabulary = prefs.getVocabulary().toSet(),
                systemPrompt = prefs.customTranscriptPolishingSystemPrompt ?: TranscriptPolisher.SYSTEM_PROMPT
            )
        } else null
        current.finalTextSettingsCaptured = true
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

    private data class StreamingCaptureResult(val samples: FloatArray, val text: String)

    /**
     * Capture stays independent of both VAD and offline TDT JNI work. The raw-range queue holds
     * descriptors into the fixed capture buffer and is sized for the complete 120-second cap.
     */
    private suspend fun captureAndTranscribeTdtChunks(current: Session): TdtCaptureResult =
        withContext(Dispatchers.Default) {
            nativeLock.withLock {
                if (current.cancelled || !prefs.serviceEnabled) {
                    return@withLock TdtCaptureResult(floatArrayOf(), "")
                }
                coroutineScope {
                    val pcmInput = Channel<CapturedPcmRange>(RAW_PCM_QUEUE_CAPACITY)
                    val tdtChunks = Channel<TdtPcmChunk>(TDT_CHUNK_QUEUE_CAPACITY)
                    var segmentationFailure: Exception? = null
                    var decoderFailure: Exception? = null
                    var decodedChunkCount = 0
                    var decodedEndSample = 0
                    val decodedTexts = ArrayList<String>()

                    val segmenter = launch(Dispatchers.Default) {
                        try {
                            segmentLiveTdtAudio(current, pcmInput, tdtChunks)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            segmentationFailure = e
                            // Keep draining capture descriptors; full-recording decode will recover.
                            for (ignored in pcmInput) Unit
                        } catch (e: LinkageError) {
                            segmentationFailure = IllegalStateException("Silero VAD failed", e)
                            for (ignored in pcmInput) Unit
                        } finally {
                            tdtChunks.close()
                        }
                    }

                    val decoder = launch(Dispatchers.Default) {
                        var expectedSequence = 0
                        var expectedStart = 0
                        var failure: Exception? = null
                        var initialized = false
                        for (chunk in tdtChunks) {
                            if (current.cancelled || !prefs.serviceEnabled) continue
                            if (chunk.sequence != expectedSequence || chunk.startSample != expectedStart ||
                                chunk.endExclusive <= chunk.startSample || chunk.endExclusive > chunk.samples.size) {
                                failure = IllegalStateException("TDT chunk ranges are not contiguous and ordered.")
                            }
                            expectedSequence = chunk.sequence + 1
                            expectedStart = chunk.endExclusive
                            decodedChunkCount = expectedSequence
                            decodedEndSample = expectedStart
                            if (failure != null) continue
                            try {
                                if (!initialized) {
                                    initializeEngine(
                                        modelId = current.modelId,
                                        deferTranscriptTransforms = true,
                                        decoderSettings = current.acousticDecoderSettings
                                    )
                                    initialized = true
                                }
                                val floatChunk = FloatArray(chunk.endExclusive - chunk.startSample) { index ->
                                    chunk.samples[chunk.startSample + index] / 32768f
                                }
                                val text = decode(floatChunk, current).trim()
                                if (!current.cancelled && prefs.serviceEnabled && text.isNotBlank()) {
                                    decodedTexts += text
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                failure = e
                            } catch (e: LinkageError) {
                                failure = IllegalStateException("Native TDT chunk decode failed", e)
                            }
                        }
                        decoderFailure = failure
                    }

                    val samples = try {
                        withContext(Dispatchers.IO) {
                            captureRecording(current, onVadInput = { range -> pcmInput.send(range) })
                        }
                    } finally {
                        pcmInput.close()
                    }
                    segmenter.join()
                    if (!current.cancelled && decoder.isActive) {
                        withContext(Dispatchers.Main) {
                            if (session === current && !current.cancelled) {
                                setState(false, true, "processing")
                            }
                        }
                    }
                    decoder.join()
                    if (current.cancelled || !prefs.serviceEnabled || samples.isEmpty()) {
                        return@coroutineScope TdtCaptureResult(samples, "")
                    }

                    val failure = segmentationFailure ?: decoderFailure
                    val completeCoverage = decodedChunkCount > 0 && decodedEndSample == samples.size
                    val text = if (failure != null || !completeCoverage) {
                        Log.w(TAG, "Chunked TDT path incomplete (${failure?.javaClass?.simpleName ?: "range coverage"}); retrying full PCM")
                        releaseNative()
                        initializeEngine(
                            modelId = current.modelId,
                            deferTranscriptTransforms = true,
                            decoderSettings = current.acousticDecoderSettings
                        )
                        // Preserve every captured sample; do not apply per-recording endpoint trimming.
                        decode(samples, current)
                    } else {
                        decodedTexts.joinToString(" ")
                    }
                    TdtCaptureResult(samples, text)
                }
            }
        }

    /** Emit contiguous PCM at endpoints after 5 seconds of cumulative speech; flush the tail once. */
    private suspend fun segmentLiveTdtAudio(
        current: Session,
        pcmInput: Channel<CapturedPcmRange>,
        tdtChunks: Channel<TdtPcmChunk>
    ) {
        var detector: SpeechActivityDetector? = null
        var lastDetected = false
        var nextChunkStart = 0
        var chunkSpeechStart = 0
        var expectedInputStart = 0
        var sequence = 0
        var recording: ShortArray? = null
        try {
            try {
                detector = SpeechActivityDetector(context, tdtChunking = true)
                updateSpeechActivity(current, available = true, detected = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: LinkageError) {
                Log.w(TAG, "Speech activity native code unavailable; preserving full PCM for TDT", e)
                updateSpeechActivity(current, available = false, detected = false)
            } catch (e: Exception) {
                Log.w(TAG, "Speech activity detector unavailable; preserving full PCM for TDT", e)
                updateSpeechActivity(current, available = false, detected = false)
            }

            for (range in pcmInput) {
                check(range.startSample == expectedInputStart && range.endExclusive > range.startSample)
                check(range.endExclusive <= range.samples.size)
                if (recording == null) recording = range.samples else check(recording === range.samples)
                expectedInputStart = range.endExclusive
                val activeDetector = detector
                if (activeDetector == null || current.cancelled) continue

                val updates = try {
                    activeDetector.acceptPcm16(
                        range.samples,
                        range.startSample,
                        range.endExclusive - range.startSample
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: LinkageError) {
                    Log.w(TAG, "Speech activity native code stopped; preserving remaining PCM", e)
                    activeDetector.close()
                    detector = null
                    updateSpeechActivity(current, available = false, detected = false)
                    continue
                } catch (e: Exception) {
                    Log.w(TAG, "Speech activity detection stopped; preserving remaining PCM", e)
                    activeDetector.close()
                    detector = null
                    updateSpeechActivity(current, available = false, detected = false)
                    continue
                }

                for (update in updates) {
                    if (update.detected != lastDetected) {
                        lastDetected = update.detected
                        updateSpeechActivity(current, available = true, detected = update.detected)
                    }
                    if (update.speechEnded && !current.cancelled &&
                        update.speechSamples - chunkSpeechStart >= MIN_TDT_CHUNK_SPEECH_SAMPLES) {
                        check(update.processedSamples in nextChunkStart..expectedInputStart)
                        if (update.processedSamples > nextChunkStart) {
                            tdtChunks.send(TdtPcmChunk(
                                sequence = sequence++,
                                samples = range.samples,
                                startSample = nextChunkStart,
                                endExclusive = update.processedSamples
                            ))
                            nextChunkStart = update.processedSamples
                            chunkSpeechStart = update.speechSamples
                        }
                    }
                }
            }

            val finalSamples = recording
            if (!current.cancelled && finalSamples != null && expectedInputStart > nextChunkStart) {
                tdtChunks.send(TdtPcmChunk(
                    sequence = sequence,
                    samples = finalSamples,
                    startSample = nextChunkStart,
                    endExclusive = expectedInputStart
                ))
            }
        } finally {
            detector?.close()
        }
    }

    /** Run only for the selected streaming model; keep raw PCM capture separate from ASR work. */
    private suspend fun captureAndTranscribeStreaming(current: Session): StreamingCaptureResult =
        withContext(Dispatchers.Default) {
            nativeLock.withLock {
                if (current.cancelled || !prefs.serviceEnabled) {
                    return@withLock StreamingCaptureResult(floatArrayOf(), "")
                }
                initializeEngine(modelId = current.modelId, deferTranscriptTransforms = true)
                val engine = checkNotNull(onlineRecognizer)
                val stream = engine.createStream()
                try {
                    coroutineScope {
                        // Capacity covers the entire bounded recording if inference temporarily
                        // falls behind. This keeps AudioRecord independent from JNI latency.
                        val chunks = Channel<ShortArray>(
                            capacity = MAX_LIVE_SAMPLES / STREAM_CHUNK_SAMPLES + 2
                        )
                        var workerFailure: Exception? = null
                        var finalText = ""
                        val worker = launch(Dispatchers.Default) {
                            var receivedAudio = false
                            try {
                                for (chunk in chunks) {
                                    if (current.cancelled || !prefs.serviceEnabled) break
                                    val floatChunk = FloatArray(chunk.size) { chunk[it] / 32768f }
                                    stream.acceptWaveform(floatChunk, SAMPLE_RATE)
                                    receivedAudio = true
                                    while (engine.isReady(stream)) {
                                        if (current.cancelled || !prefs.serviceEnabled) break
                                        engine.decode(stream)
                                    }
                                }
                                if (receivedAudio && !current.cancelled && prefs.serviceEnabled) {
                                    stream.inputFinished()
                                    while (engine.isReady(stream)) {
                                        if (current.cancelled || !prefs.serviceEnabled) break
                                        engine.decode(stream)
                                    }
                                    if (!current.cancelled && prefs.serviceEnabled) {
                                        finalText = engine.getResult(stream).text.trim()
                                    }
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                // Do not cancel microphone capture on a recoverable native error.
                                // The bounded queue can retain the remaining raw input until Stop.
                                workerFailure = e
                            }
                        }

                        val samples = try {
                            withContext(Dispatchers.IO) {
                                captureRecording(current, onStreamingChunk = { chunk ->
                                    if (!current.cancelled) chunks.send(chunk)
                                })
                            }
                        } finally {
                            chunks.close()
                        }
                        worker.join()
                        workerFailure?.let { throw it }
                        StreamingCaptureResult(samples, finalText)
                    }
                } finally {
                    stream.release()
                }
            }
        }

    /** Capture every PCM sample; optional queues receive immutable ranges into the capture buffer. */
    @SuppressLint("MissingPermission")
    private suspend fun captureRecording(
        current: Session,
        onStreamingChunk: (suspend (ShortArray) -> Unit)? = null,
        onVadInput: (suspend (CapturedPcmRange) -> Unit)? = null
    ): FloatArray {
        if (current.stopped || current.cancelled) return floatArrayOf()
        val minBytes = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(minBytes > 0) { "Microphone format is unsupported ($minBytes)." }
        val recording = ShortArray(MAX_LIVE_SAMPLES)
        var count = 0
        var streamChunk = if (onStreamingChunk == null) null else ShortArray(STREAM_CHUNK_SAMPLES)
        var streamChunkCount = 0
        var vadInputEnd = 0
        val audio = AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(minBytes * 4, SAMPLE_RATE * 2))
        var speechDetector: SpeechActivityDetector? = null
        if (onVadInput == null) {
            try {
                speechDetector = SpeechActivityDetector(context)
                updateSpeechActivity(current, available = true, detected = false)
            } catch (e: CancellationException) {
                speechDetector?.close()
                audio.release()
                throw e
            } catch (e: LinkageError) {
                speechDetector?.close()
                speechDetector = null
                Log.w(TAG, "Speech activity native code unavailable; continuing capture", e)
                updateSpeechActivity(current, available = false, detected = false)
            } catch (e: Exception) {
                speechDetector?.close()
                speechDetector = null
                Log.w(TAG, "Speech activity detector unavailable; continuing capture", e)
                updateSpeechActivity(current, available = false, detected = false)
            }
        }
        suspend fun flushStreamingChunk() {
            val pending = streamChunk ?: return
            if (streamChunkCount > 0 && !current.cancelled) {
                onStreamingChunk?.invoke(pending.copyOf(streamChunkCount))
                streamChunkCount = 0
            }
        }
        try {
            synchronized(recorderLock) {
                check(audio.state == AudioRecord.STATE_INITIALIZED) { "Microphone could not be initialized." }
                if (!current.stopped && !current.cancelled) {
                    audio.startRecording()
                    check(audio.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Microphone did not start." }
                    recorder = audio
                }
            }
            if (audio.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                withContext(Dispatchers.Main.immediate) {
                    if (session === current && !current.stopped && !current.cancelled) {
                        setState(true, false, "recording")
                    }
                }
            }
            while (!current.cancelled && count < recording.size) {
                // Stop may release a blocked read while captured frames remain buffered.
                // Retain its result, then drain available frames without blocking.
                val stopped = current.stopped
                val requested = minOf(CAPTURE_READ_SAMPLES, recording.size - count)
                val read = if (stopped) {
                    audio.read(recording, count, requested, AudioRecord.READ_NON_BLOCKING)
                } else {
                    audio.read(recording, count, requested, AudioRecord.READ_BLOCKING)
                }
                if (read > 0) {
                    val readStart = count
                    count += read
                    val detector = speechDetector
                    if (detector != null && !current.cancelled && !current.stopped) {
                        try {
                            for (update in detector.acceptPcm16(recording, readStart, read)) {
                                updateSpeechActivity(current, available = true, detected = update.detected)
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: LinkageError) {
                            Log.w(TAG, "Speech activity native code stopped; continuing capture", e)
                            detector.close()
                            speechDetector = null
                            updateSpeechActivity(current, available = false, detected = false)
                        } catch (e: Exception) {
                            Log.w(TAG, "Speech activity detection stopped; continuing capture", e)
                            detector.close()
                            speechDetector = null
                            updateSpeechActivity(current, available = false, detected = false)
                        }
                    }
                    if (onVadInput != null && !current.cancelled) {
                        while (count - vadInputEnd >= CAPTURE_READ_SAMPLES) {
                            val endExclusive = vadInputEnd + CAPTURE_READ_SAMPLES
                            onVadInput(CapturedPcmRange(recording, vadInputEnd, endExclusive))
                            vadInputEnd = endExclusive
                        }
                    }
                    val pending = streamChunk
                    if (pending != null && !current.cancelled) {
                        var sourceOffset = readStart
                        while (sourceOffset < count) {
                            val target = streamChunk ?: break
                            val copied = minOf(target.size - streamChunkCount, count - sourceOffset)
                            System.arraycopy(recording, sourceOffset, target, streamChunkCount, copied)
                            sourceOffset += copied
                            streamChunkCount += copied
                            if (streamChunkCount == target.size) {
                                onStreamingChunk?.invoke(target)
                                streamChunk = ShortArray(STREAM_CHUNK_SAMPLES)
                                streamChunkCount = 0
                            }
                        }
                    }
                    continue
                }
                if (current.cancelled) break
                if (current.stopped) break
                check(read > 0) { "Microphone read failed ($read)." }
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
            speechDetector?.close()
            current.stopDrainTimeout?.let(mainHandler::removeCallbacks)
            current.stopDrainTimeout = null
        }
        if (!current.stopped && !current.cancelled && count == recording.size && speechActivityDetectionAvailable) {
            // Capture has ended at its explicit duration cap; show silence while awaiting Stop/Cancel.
            updateSpeechActivity(current, available = true, detected = false)
        }
        if (!current.stopped && !current.cancelled && count == MAX_LIVE_SAMPLES) {
            flushStreamingChunk()
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
        if (onVadInput != null && vadInputEnd < count) {
            onVadInput(CapturedPcmRange(recording, vadInputEnd, count))
        }
        flushStreamingChunk()
        return FloatArray(count) { recording[it] / 32768f }
    }

    /** Final-only decode; history reprocessing may use contiguous bounded windows. */
    private fun decodeRecording(
        samples: FloatArray,
        current: Session,
        reprocessInWindows: Boolean = false
    ): String {
        if (current.cancelled || !prefs.serviceEnabled) return ""
        require(samples.isNotEmpty() && samples.size <= MAX_LIVE_SAMPLES) { "Recording duration exceeded." }
        val endpoints = FinalAudioPolicy.endpointRange(samples, SAMPLE_RATE)
        val finalSamples = if (endpoints.start == 0 && endpoints.endExclusive == samples.size) samples
            else samples.copyOfRange(endpoints.start, endpoints.endExclusive)
        if (current.cancelled || !prefs.serviceEnabled) return ""
        Log.i(TAG, "Final PCM endpoints ${endpoints.start}..${endpoints.endExclusive} of ${samples.size}; exact-zero only")
        val text = if (reprocessInWindows) decodeInWindows(finalSamples, current) else decode(finalSamples, current)
        if (current.cancelled || !prefs.serviceEnabled) return ""
        return text
    }

    private fun decodeInWindows(samples: FloatArray, current: Session): String {
        val windows = FinalAudioPolicy.reprocessWindows(samples, SAMPLE_RATE)
        Log.i(TAG, "History retry using ${windows.size} decode window(s) for ${samples.size} samples")
        val results = ArrayList<String>(windows.size)
        for (window in windows) {
            if (current.cancelled || !prefs.serviceEnabled) return ""
            val text = decode(samples.copyOfRange(window.start, window.endExclusive), current).trim()
            if (current.cancelled || !prefs.serviceEnabled) return ""
            if (text.isNotBlank()) results += text
        }
        return results.joinToString(" ")
    }

    private fun decode(samples: FloatArray, current: Session): String {
        require(samples.isNotEmpty() && samples.size <= MAX_LIVE_SAMPLES) { "Recording duration exceeded." }
        if (ModelInfo.getModelById(current.modelId).streaming) {
            return decodeOnline(samples, current)
        }
        val engine = checkNotNull(recognizer)
        val stream = engine.createStream()
        return try {
            stream.acceptWaveform(samples, SAMPLE_RATE)
            if (current.cancelled || !prefs.serviceEnabled) return ""
            engine.decode(stream)
            engine.getResult(stream).text.trim()
        } finally { stream.release() }
    }

    /** Feed completed audio through the online recognizer for watch-file and History paths. */
    private fun decodeOnline(samples: FloatArray, current: Session): String {
        val engine = checkNotNull(onlineRecognizer)
        val stream = engine.createStream()
        return try {
            var offset = 0
            while (offset < samples.size) {
                if (current.cancelled || !prefs.serviceEnabled) return ""
                val end = minOf(offset + STREAM_CHUNK_SAMPLES, samples.size)
                stream.acceptWaveform(samples.copyOfRange(offset, end), SAMPLE_RATE)
                while (engine.isReady(stream)) {
                    if (current.cancelled || !prefs.serviceEnabled) return ""
                    engine.decode(stream)
                }
                offset = end
            }
            if (current.cancelled || !prefs.serviceEnabled) return ""
            stream.inputFinished()
            while (engine.isReady(stream)) {
                if (current.cancelled || !prefs.serviceEnabled) return ""
                engine.decode(stream)
            }
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
        // Completed files already exist: STOP is a no-op; only CANCEL discards their result.
        if (current.audioFile != null || current.historyAudioInputFileName != null) return
        val model = ModelInfo.getModelById(current.modelId)
        val liveTdt = current.modelId == AppPreferences.MODEL_PARAKEET_V2 ||
            current.modelId == AppPreferences.MODEL_PARAKEET_V3
        if (!current.cancelled && (model.streaming || liveTdt) && !current.finalTextSettingsCaptured) {
            // Freeze all final-text transforms and polishing gates at explicit Stop while the
            // live recognizer/chunk queue drains.
            snapshotFinalizationSettings(current)
        }
        current.stopped = true
        current.stopRequested.complete(Unit)
        // On ordinary Stop, let the capture thread consume the in-flight read and
        // non-blockingly drain queued frames before it stops/releases AudioRecord.
        // Cancellation remains immediate and discards all collected PCM.
        val noActiveRecorder = synchronized(recorderLock) { recorder == null }
        if (current.cancelled || noActiveRecorder) {
            stopMicrophone()
        } else {
            val fallbackStop = Runnable {
                if (session === current && current.stopped && !current.cancelled) stopMicrophone()
            }
            current.stopDrainTimeout = fallbackStop
            mainHandler.postDelayed(fallbackStop, STOP_DRAIN_TIMEOUT_MS)
        }
        if (sessionJob?.isActive == true) {
            setState(false, true, if (ModelInfo.getModelById(current.modelId).streaming) "processing" else "loading")
        }
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
        val oldOnlineRecognizer = onlineRecognizer
        recognizer = null
        onlineRecognizer = null
        loadedConfigKey = null
        try {
            oldRecognizer?.release()
        } finally {
            oldOnlineRecognizer?.release()
        }
    }

    private fun setState(recording: Boolean, processing: Boolean, state: String = if (recording) "recording" else "processing") {
        isRecording = recording
        isAwaitingStop = state == "recording_limit"
        isProcessing = processing
        currentStatus = state
        if (!recording) clearSpeechActivity()
        onStateChanged?.invoke(recording, processing)
        val current = session
        if (current?.listener != null) {
            if (!current.cancelled) {
                // Keep IPC and retry listeners on their existing protocol states; the local
                // floating overlay reads currentStatus directly for the polishing color.
                current.listener.onState(if (state == "polishing") "processing" else state)
            }
        }
    }

    private suspend fun updateSpeechActivity(current: Session, available: Boolean, detected: Boolean) {
        withContext(Dispatchers.Main.immediate) {
            if (session !== current || current.cancelled || current.stopped) return@withContext
            val asynchronousTdt = current.audioFile == null && current.historyAudioInputFileName == null &&
                (current.modelId == AppPreferences.MODEL_PARAKEET_V2 ||
                    current.modelId == AppPreferences.MODEL_PARAKEET_V3)
            val nextDetected = available && detected && !(asynchronousTdt && isAwaitingStop)
            val changed = speechActivityDetectionAvailable != available || isSpeechDetected != nextDetected
            speechActivityDetectionAvailable = available
            isSpeechDetected = nextDetected
            if (changed) onSpeechActivityChanged?.invoke()
        }
    }

    private fun clearSpeechActivity() {
        val changed = speechActivityDetectionAvailable || isSpeechDetected
        speechActivityDetectionAvailable = false
        isSpeechDetected = false
        if (changed) onSpeechActivityChanged?.invoke()
    }
}
