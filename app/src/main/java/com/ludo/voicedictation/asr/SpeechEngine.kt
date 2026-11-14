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
import android.system.ErrnoException
import android.system.StructPollfd
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.k2fsa.sherpa.onnx.*
import com.ludo.voicedictation.data.AppPreferences
import com.ludo.voicedictation.data.HistoryAudioStore
import com.ludo.voicedictation.data.RecordingLimits
import com.ludo.voicedictation.model.ModelInfo
import com.ludo.voicedictation.model.ModelManager
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.security.MessageDigest
import java.util.Locale

class SpeechEngine private constructor(private val context: Context) {
    companion object {
        private const val TAG = "SpeechEngine"
        private const val SAMPLE_RATE = RecordingLimits.SAMPLE_RATE
        private const val MAX_FILE_BYTES = 3_840_000L // Completed-file IPC input is limited to 120 seconds.
        private const val MAX_REMOTE_PCM_BYTES = 9_600_000L // Five minutes of PCM16.
        // Never truncate an interim hypothesis: omit it if it cannot fit this IPC bound.
        private const val MAX_REMOTE_PREVIEW_CHARS = 4096
        private const val CAPTURE_READ_SAMPLES = 1024
        // Batch PCM into 200 ms queue items; size the queue for the full bounded capture.
        private const val STREAM_CHUNK_SAMPLES = SAMPLE_RATE / 5
        // NeMo's 1120 ms encoder needs a complete final chunk plus right context.
        // Padding is decoder-only; captured/history PCM is never modified.
        private const val NEMOTRON_LEFT_PADDING_SAMPLES = SAMPLE_RATE / 2
        private const val NEMOTRON_TAIL_PADDING_SAMPLES = SAMPLE_RATE * 128 / 100
        private const val MAX_LIVE_SAMPLES = RecordingLimits.MAX_LIVE_SAMPLES // 10 minutes of mono PCM16 capture.
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
        /** Unvalidated, replaceable full-text hypothesis for a remote PCM stream only. */
        fun onPartial(text: String) {}
        // Called only after capture, final decode, native-lock and coroutine cleanup finish.
        // Both values are null when cancelled. Empty speech is reported as an error.
        fun onFinished(text: String?, error: String?)
    }

    private class Session(
        val owner: Any? = null,
        val listener: ExternalListener? = null,
        val audioFile: ParcelFileDescriptor? = null,
        val remotePcm: Boolean = false,
        val historyAudioInputFileName: String? = null,
        val historyReprocessOwner: Any? = null,
        val historyPolishingConfiguration: TranscriptPolishingConfiguration? = null
    ) {
        var preferredMicrophone: String? = null
        lateinit var decoderConfig: DecoderConfig
        var advancedLocale: Locale? = null
        var previewEnabled = false
        // Accessed only on the main thread.
        var publishedPreview = ""
        @Volatile var advancedJob: Job? = null
        val stopRequested = CompletableDeferred<Unit>()
        val remoteFinish = CompletableDeferred<RemoteFinish?>()
        @Volatile var remoteValidated = false
        @Volatile var remoteInputEnded = false
        val remotePreviewLock = Any()
        var pendingRemotePreview: String? = null // protected by remotePreviewLock
        var remotePreviewPosted = false // protected by remotePreviewLock
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
        @Volatile var stopDrainTimeout: Runnable? = null

        @Synchronized fun closeAudioFile() { runCatching { audioFile?.close() } }
    }

    private data class RemoteFinish(val byteCount: Long, val sha256: String)

    private data class DecoderConfig(
        val model: ModelInfo,
        val threadCount: Int,
        val hotwords: List<String>,
        val maxActivePaths: Int,
        val hotwordsScore: Float
    )

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
    private var onlineRecognizer: OnlineRecognizer? = null
    private var loadedDecoderConfig: DecoderConfig? = null
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
    var onStateChanged: ((Boolean, Boolean) -> Unit)? = null
    var onRecordingLimit: (() -> Unit)? = null
    var onFinalResult: ((String) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    /** Local live sessions only; called on main, with an empty string to clear. */
    var onPreviewTextChanged: ((String) -> Unit)? = null

    /** ML Kit currently uses the completed-audio, one-shot path. */
    val isStreamingPreviewSupported: Boolean
        get() = prefs.selectedModelId != AppPreferences.MODEL_MLKIT_ADVANCED

    fun isModelReady(): Boolean =
        prefs.selectedModelId != AppPreferences.MODEL_MLKIT_ADVANCED &&
            modelManager.isModelDownloaded(ModelInfo.getModelById(prefs.selectedModelId))

    fun startRecording(): Boolean = startSession(Session())

    /** A private identity token keeps external control separate from the floating overlay. */
    fun startExternal(owner: Any, listener: ExternalListener): Boolean =
        startSession(Session(owner, listener))

    /** On success the session owns the descriptor, including cancellation and error cleanup. */
    fun startExternalFile(owner: Any, listener: ExternalListener, audioFile: ParcelFileDescriptor): Boolean =
        startSession(Session(owner, listener, audioFile))

    /** The pipe is owned by this session on success; it never opens the microphone. */
    fun startExternalPcm(owner: Any, listener: ExternalListener, audioFile: ParcelFileDescriptor): Boolean =
        startSession(Session(owner, listener, audioFile, remotePcm = true))

    /** User-requested retry; results never reach overlay insertion or clipboard. */
    internal fun startHistoryReprocess(
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
        val hasCompletedAudio = (current.audioFile != null && !current.remotePcm) || current.historyAudioInputFileName != null
        if (current.historyAudioInputFileName != null &&
            HistoryAudioStore.find(context, current.historyAudioInputFileName) == null) return false
        if (!hasCompletedAudio && !current.remotePcm &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            if (current.listener == null) onError?.invoke("Grant microphone permission in Settings before dictating.")
            return false
        }
        val hotwords = if (prefs.acousticVocabularyEnabled) prefs.getVocabulary().asSequence()
            .map { it.trim().replace(Regex("\\s+"), " ") }
            .filter { it.length in 1..80 && HOTWORD_PATTERN.matches(it) }
            .distinct().sorted().toList() else emptyList()
        val selectedModelId = prefs.selectedModelId
        if (current.remotePcm && selectedModelId == AppPreferences.MODEL_MLKIT_ADVANCED) return false
        current.advancedLocale = if (selectedModelId == AppPreferences.MODEL_MLKIT_ADVANCED)
            Locale.forLanguageTag(prefs.mlKitLocaleTag) else null
        if (current.advancedLocale != null) {
            RecognitionDiagnostics.record(if (hasCompletedAudio) "file" else "live", "started")
        }
        current.previewEnabled = current.listener == null && current.owner == null &&
            !hasCompletedAudio && current.advancedLocale == null && prefs.streamingPreviewEnabled
        current.decoderConfig = DecoderConfig(
            model = ModelInfo.getModelById(selectedModelId),
            threadCount = prefs.asrThreadCount.coerceIn(2, 4),
            hotwords = hotwords,
            maxActivePaths = prefs.acousticMaxActivePaths,
            hotwordsScore = prefs.acousticHotwordsScore
        )
        current.preferredMicrophone = prefs.preferredMicrophone
        session = current
        // Keep the control active during mic startup, but expose a distinct not-yet-recording state.
        setState(!hasCompletedAudio, true, if (hasCompletedAudio) "loading" else "starting")
        val job = scope.launch {
            try {
                // ML Kit readiness is asynchronous and must be checked before opening the mic.
                current.advancedLocale?.let { locale ->
                    withAdvancedJob(current) { AdvancedSpeechModel(locale).use { it.requireAvailable() } }
                }
                val samples = if (!hasCompletedAudio) {
                    if (current.advancedLocale != null) {
                        val captured = withContext(Dispatchers.IO) { captureRecording(current) { } }
                        if (!current.cancelled) RecognitionDiagnostics.record("input", if (captured.isEmpty()) "empty_input" else "ready")
                        if (!current.cancelled && prefs.serviceEnabled && captured.isNotEmpty()) {
                            current.text = decodeAdvanced(captured, current)
                        }
                        captured
                    } else {
                        val capture = captureAndTranscribeStreaming(current)
                        current.text = capture.text
                        capture.samples
                    }
                } else {
                    val completedSamples = withContext(Dispatchers.IO) {
                        if (current.audioFile != null) readAudioFile(current)
                        else HistoryAudioStore.readSamples(context, current.historyAudioInputFileName)
                    }
                    if (current.advancedLocale != null && !current.cancelled) {
                        RecognitionDiagnostics.record("input", if (completedSamples.isEmpty()) "empty_input" else "ready")
                    }
                    if (!current.cancelled && prefs.serviceEnabled && completedSamples.isNotEmpty()) {
                        setState(false, true, "loading")
                        current.text = if (current.advancedLocale != null) {
                            snapshotFinalizationSettings(current)
                            decodeAdvanced(completedSamples, current)
                        } else {
                            withContext(Dispatchers.Default) {
                                nativeLock.withLock {
                                    if (current.cancelled || !prefs.serviceEnabled) return@withLock ""
                                    initializeEngine(current.decoderConfig)
                                    if (current.cancelled || !prefs.serviceEnabled) return@withLock ""
                                    withContext(Dispatchers.Main) {
                                        if (!current.cancelled) setState(false, true, "processing")
                                    }
                                    decodeOnline(completedSamples, current)
                                }
                            }
                        }
                    }
                    completedSamples
                }
                if (!current.cancelled && prefs.serviceEnabled && samples.isNotEmpty()) {
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
                                systemPrompt = polishingConfiguration.systemPrompt,
                                chunks = null
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
                if (current.advancedLocale != null) {
                    // SDK exceptions can contain private text or audio metadata; do not log them.
                    Log.e(TAG, "ML Kit Advanced dictation failed")
                    RecognitionDiagnostics.recordFailure(if (hasCompletedAudio) "file" else "live", e)
                    current.error = when (e.message) {
                        "Download the ML Kit Advanced model in Settings.",
                        "The ML Kit Advanced model is still downloading.",
                        "ML Kit Advanced is unavailable for this device or language." -> e.message
                        else -> "ML Kit Advanced dictation failed. Check device and language availability."
                    }
                } else {
                    Log.e(TAG, "Dictation failed", e)
                    current.error = "Dictation failed: ${e.message}"
                }
            } finally {
                current.closeAudioFile()
            }
        }
        sessionJob = job
        job.invokeOnCompletion { cause ->
            // No unexpected coroutine/native failure may publish an earlier draft as success.
            if (cause != null && !current.cancelled) {
                current.error = if (current.advancedLocale != null) {
                    RecognitionDiagnostics.recordFailure("completion", cause)
                    "ML Kit Advanced dictation failed. See ML Kit diagnostics in Settings."
                } else "Dictation failed: ${cause.message}"
            }
            // Post even when already on Main: ownership cannot be released until the job is complete.
            mainHandler.post {
                if (session !== current) return@post
                clearPreview(current)
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
                    if (current.advancedLocale != null && current.error == null) {
                        RecognitionDiagnostics.record("completion", if (text == null) "empty_result" else "success")
                    }
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
    private fun initializeEngine(config: DecoderConfig, deferTranscriptTransforms: Boolean = false) {
        if (!deferTranscriptTransforms) snapshotTranscriptTransforms()
        if (onlineRecognizer != null && loadedDecoderConfig == config) return

        releaseNative()
        val model = config.model
        check(modelManager.isModelDownloaded(model)) { "Download the Nemotron model in Settings." }
        val dir = modelManager.getModelDir(model)
        val tokensFile = File(dir, model.tokensFileName)
        var bpeFile: File? = null
        var hotwordsFile: File? = null
        try {
            var useBias = false
            if (config.hotwords.isNotEmpty()) {
                // The release contains token IDs, not SentencePiece scores. Upstream's NeMo
                // example derives an equal-score vocabulary (longest matching) from these IDs;
                // it is an approximation, not the original SentencePiece merge priorities.
                val pieces = tokensFile.readLines().filter { it.isNotBlank() }.map { line ->
                    val split = line.lastIndexOf(' ')
                    require(split > 0 && line.substring(split + 1).toIntOrNull() != null) {
                        "Invalid model token table."
                    }
                    line.substring(0, split).also { token ->
                        require(token.isNotEmpty() && token.none { it.isWhitespace() }) { "Invalid model token table." }
                    }
                }
                require(pieces.isNotEmpty() && pieces.distinct().size == pieces.size) { "Invalid model token table." }
                val tokenSet = pieces.toSet()
                // sherpa may log unknown BPE fragments. Accept only phrases whose individual
                // code points and word boundary are all representable, so its encoder cannot
                // emit an unknown fragment containing private vocabulary.
                val supported = config.hotwords.filter { phrase ->
                    "▁" in tokenSet && phrase.split(' ').all { word ->
                        word.codePoints().toArray().all { codePoint ->
                            String(Character.toChars(codePoint)) in tokenSet
                        }
                    }
                }
                if (supported.isNotEmpty()) {
                    bpeFile = File.createTempFile("nemotron-bpe-", ".vocab", context.cacheDir)
                    hotwordsFile = File.createTempFile("nemotron-hotwords-", ".txt", context.cacheDir)
                    bpeFile.writeText(pieces.joinToString("\n", postfix = "\n") { "$it\t-1.0" }, Charsets.UTF_8)
                    hotwordsFile.writeText(supported.joinToString("\n", postfix = "\n"), Charsets.UTF_8)
                    useBias = true
                }
            }
            onlineRecognizer = OnlineRecognizer(null, OnlineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 128, dither = 0f),
                modelConfig = OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(
                        encoder = File(dir, model.encoderFileName).absolutePath,
                        decoder = File(dir, model.decoderFileName).absolutePath,
                        joiner = File(dir, model.joinerFileName).absolutePath
                    ),
                    tokens = tokensFile.absolutePath,
                    numThreads = config.threadCount,
                    provider = "cpu",
                    modelingUnit = if (useBias) "bpe" else "",
                    bpeVocab = bpeFile?.absolutePath ?: ""
                ),
                enableEndpoint = false,
                decodingMethod = if (useBias) "modified_beam_search" else "greedy_search",
                maxActivePaths = if (useBias) config.maxActivePaths else 1,
                hotwordsFile = hotwordsFile?.absolutePath ?: "",
                hotwordsScore = if (useBias) config.hotwordsScore else 0f
            ))
            loadedDecoderConfig = config
            Log.i(TAG, "Loaded ${model.id}; Nemotron streaming decoder")
        } catch (e: Exception) {
            releaseNative()
            throw e
        } finally {
            hotwordsFile?.delete()
            bpeFile?.delete()
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
        stringReplacer = CustomStringReplacer(
            if (prefs.stringReplacementEnabled) prefs.getStringReplacementRules() else emptyList()
        )
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

    private data class StreamingCaptureResult(val samples: FloatArray, val text: String)

    /** Cancellation of an ML Kit request also cancels its Flow and performs scoped cleanup. */
    private suspend fun <T> withAdvancedJob(current: Session, block: suspend () -> T): T =
        withContext(Dispatchers.IO) {
            current.advancedJob = coroutineContext.job
            try {
                if (current.cancelled || !prefs.serviceEnabled) throw CancellationException("Session cancelled")
                block()
            } finally {
                current.advancedJob = null
            }
        }

    private suspend fun decodeAdvanced(samples: FloatArray, current: Session): String {
        if (current.cancelled || !prefs.serviceEnabled) return ""
        setState(false, true, "processing")
        val locale = checkNotNull(current.advancedLocale)
        return withAdvancedJob(current) {
            AdvancedSpeechModel(locale).use { it.transcribe(samples, context.cacheDir) }
        }
    }

    /** Keep raw PCM capture separate from the streaming decoder. */
    private suspend fun captureAndTranscribeStreaming(current: Session): StreamingCaptureResult =
        withContext(Dispatchers.Default) {
            nativeLock.withLock {
                if (current.cancelled || !prefs.serviceEnabled) {
                    return@withLock StreamingCaptureResult(floatArrayOf(), "")
                }
                initializeEngine(current.decoderConfig, deferTranscriptTransforms = true)
                val engine = checkNotNull(onlineRecognizer)
                val stream = createOnlineStream(engine)
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
                            var lastPreviewCheck = 0L
                            var lastPreview = ""
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
                                    val now = SystemClock.elapsedRealtime()
                                    val remotePreviewActive = current.remotePcm && !current.remoteInputEnded
                                    if ((current.previewEnabled || remotePreviewActive) && !current.stopped && !current.cancelled &&
                                        prefs.serviceEnabled && now - lastPreviewCheck >= 250L) {
                                        lastPreviewCheck = now
                                        // A preview failure must not replace the existing final decode.
                                        val preview = try { engine.getResult(stream).text.trim() }
                                            catch (_: Exception) { null }
                                        if (preview != null && preview != lastPreview &&
                                            (!current.remotePcm || preview.length <= MAX_REMOTE_PREVIEW_CHARS)) {
                                            lastPreview = preview
                                            if (current.remotePcm) postRemotePreview(current, preview)
                                            else postPreview(current, preview)
                                        }
                                    }
                                }
                                if (receivedAudio && !current.cancelled && prefs.serviceEnabled &&
                                    (!current.remotePcm || current.remoteValidated)) {
                                    finalText = finishOnlineStream(engine, stream, current)
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
                                val sendChunk: suspend (ShortArray) -> Unit = { chunk ->
                                    if (!current.cancelled) chunks.send(chunk)
                                }
                                if (current.remotePcm) readRemotePcm(current, sendChunk)
                                else captureRecording(current, sendChunk)
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

    /** Read a pipe without allowing an unresponsive writer to trap cancellation in read(). */
    private suspend fun readRemotePcm(current: Session, onStreamingChunk: suspend (ShortArray) -> Unit): FloatArray {
        val file = checkNotNull(current.audioFile)
        val fd = file.fileDescriptor
        require(OsConstants.S_ISFIFO(Os.fstat(fd).st_mode)) { "audio_fd must be a readable pipe." }
        val flags = Os.fcntlInt(fd, OsConstants.F_GETFL, 0)
        require((flags and OsConstants.O_ACCMODE) == OsConstants.O_RDONLY) { "audio_fd must be a readable pipe." }
        Os.fcntlInt(fd, OsConstants.F_SETFL, flags or OsConstants.O_NONBLOCK)
        withContext(Dispatchers.Main.immediate) {
            if (session === current && !current.cancelled && !current.stopped) setState(true, false, "recording")
        }
        val recording = ShortArray((MAX_REMOTE_PCM_BYTES / 2).toInt())
        val buffer = ByteArray(8192)
        val digest = MessageDigest.getInstance("SHA-256")
        var byteCount = 0L
        var sampleCount = 0
        var lowByte = -1
        var chunk = ShortArray(STREAM_CHUNK_SAMPLES)
        var chunkCount = 0
        val poll = StructPollfd().apply { this.fd = fd; events = OsConstants.POLLIN.toShort() }
        try {
            while (!current.cancelled && prefs.serviceEnabled) {
                val ready = Os.poll(arrayOf(poll), 200)
                if (ready == 0) continue
                val count = try { Os.read(fd, buffer, 0, buffer.size) } catch (e: ErrnoException) {
                    if (e.errno == OsConstants.EAGAIN || e.errno == OsConstants.EINTR) continue
                    throw e
                }
                if (count == 0) break
                byteCount += count
                require(byteCount <= MAX_REMOTE_PCM_BYTES) { "Remote PCM exceeds the five-minute limit." }
                digest.update(buffer, 0, count)
                for (i in 0 until count) {
                    val value = buffer[i].toInt() and 0xff
                    if (lowByte < 0) lowByte = value else {
                        val sample = ((value shl 8) or lowByte).toShort()
                        lowByte = -1
                        recording[sampleCount++] = sample
                        chunk[chunkCount++] = sample
                        if (chunkCount == chunk.size) {
                            onStreamingChunk(chunk)
                            chunk = ShortArray(STREAM_CHUNK_SAMPLES)
                            chunkCount = 0
                        }
                    }
                }
            }
        } finally {
            // Stop queued previews at EOF or any input failure, independently of FINISH.
            synchronized(current.remotePreviewLock) {
                current.remoteInputEnded = true
                current.pendingRemotePreview = null
            }
        }
        current.closeAudioFile()
        if (current.cancelled || !prefs.serviceEnabled) return floatArrayOf()
        // EOF can race FINISH. EOF is never itself permission to flush or publish.
        val finish = current.remoteFinish.await() ?: return floatArrayOf()
        if (current.cancelled || !prefs.serviceEnabled) return floatArrayOf()
        val hash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        require(byteCount >= 2 && lowByte < 0 && byteCount == finish.byteCount && hash == finish.sha256) {
            "Remote PCM count or SHA-256 did not match the complete stream."
        }
        current.remoteValidated = true
        if (chunkCount > 0) onStreamingChunk(chunk.copyOf(chunkCount))
        return FloatArray(sampleCount) { recording[it] / 32768f }
    }

    /** Capture every PCM sample; the decoder queue receives independent PCM blocks. */
    @SuppressLint("MissingPermission")
    private suspend fun captureRecording(
        current: Session,
        onStreamingChunk: suspend (ShortArray) -> Unit
    ): FloatArray {
        if (current.stopped || current.cancelled) return floatArrayOf()
        val minBytes = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(minBytes > 0) { "Microphone format is unsupported ($minBytes)." }
        val recording = ShortArray(MAX_LIVE_SAMPLES)
        var count = 0
        var streamChunk = ShortArray(STREAM_CHUNK_SAMPLES)
        var streamChunkCount = 0
        val audio = AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(minBytes * 4, SAMPLE_RATE * 2))
        suspend fun flushStreamingChunk() {
            val pending = streamChunk
            if (streamChunkCount > 0 && !current.cancelled) {
                onStreamingChunk(pending.copyOf(streamChunkCount))
                streamChunkCount = 0
            }
        }
        try {
            val preferredDevice = current.preferredMicrophone?.let { MicrophoneDevices.resolve(context, it) }
            var routeWarningSent = false
            var nextRouteCheck = 0L
            fun checkPreferredRoute() {
                if (preferredDevice == null || current.stopped || current.cancelled) return
                val routed = audio.routedDevice
                if (routed == null) {
                    if (!routeWarningSent) {
                        routeWarningSent = true
                        mainHandler.post {
                            if (session === current && !current.cancelled) {
                                val message = "Android could not verify the selected microphone route."
                                val notify = onError
                                if (notify != null) notify(message)
                                else Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                } else {
                    check(routed.id == preferredDevice.id) {
                        "Android routed audio to a different microphone. Choose another microphone or System default."
                    }
                    routeWarningSent = false
                }
            }
            synchronized(recorderLock) {
                check(audio.state == AudioRecord.STATE_INITIALIZED) { "Microphone could not be initialized." }
                if (!current.stopped && !current.cancelled) {
                    if (preferredDevice != null) {
                        check(audio.setPreferredDevice(preferredDevice)) {
                            "Android rejected the selected microphone. Choose another microphone or System default."
                        }
                    }
                    audio.startRecording()
                    check(audio.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Microphone did not start." }
                    recorder = audio
                    // Check after capture delivers its first buffer: an immediate routing query
                    // may run before Android has settled the newly started input route.
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
                    if (preferredDevice != null && SystemClock.elapsedRealtime() >= nextRouteCheck) {
                        nextRouteCheck = SystemClock.elapsedRealtime() + 1000L
                        checkPreferredRoute()
                    }
                    val readStart = count
                    count += read
                    if (!current.cancelled) {
                        var sourceOffset = readStart
                        while (sourceOffset < count) {
                            val target = streamChunk
                            val copied = minOf(target.size - streamChunkCount, count - sourceOffset)
                            System.arraycopy(recording, sourceOffset, target, streamChunkCount, copied)
                            sourceOffset += copied
                            streamChunkCount += copied
                            if (streamChunkCount == target.size) {
                                onStreamingChunk(target)
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
            current.stopDrainTimeout?.let(mainHandler::removeCallbacks)
            current.stopDrainTimeout = null
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
        flushStreamingChunk()
        return FloatArray(count) { recording[it] / 32768f }
    }

    /** Feed completed audio through the online recognizer for watch-file and History paths. */
    private fun decodeOnline(samples: FloatArray, current: Session): String {
        val engine = checkNotNull(onlineRecognizer)
        val stream = createOnlineStream(engine)
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
            finishOnlineStream(engine, stream, current)
        } finally { stream.release() }
    }

    /** sherpa's multilingual NeMo stream defaults to language auto when unset. */
    private fun createOnlineStream(engine: OnlineRecognizer): OnlineStream {
        val stream = engine.createStream()
        try {
            stream.acceptWaveform(FloatArray(NEMOTRON_LEFT_PADDING_SAMPLES), SAMPLE_RATE)
            return stream
        } catch (e: Throwable) {
            stream.release()
            throw e
        }
    }

    private fun finishOnlineStream(engine: OnlineRecognizer, stream: OnlineStream, current: Session): String {
        if (current.cancelled || !prefs.serviceEnabled) return ""
        // InputFinished alone does not flush a partially filled NeMo encoder chunk.
        stream.acceptWaveform(FloatArray(NEMOTRON_TAIL_PADDING_SAMPLES), SAMPLE_RATE)
        stream.inputFinished()
        while (engine.isReady(stream)) {
            if (current.cancelled || !prefs.serviceEnabled) return ""
            engine.decode(stream)
        }
        return if (!current.cancelled && prefs.serviceEnabled) engine.getResult(stream).text.trim() else ""
    }

    fun stopRecording() {
        val current = session ?: return
        if (current.owner != null) return
        stopSession(current)
    }

    fun stopExternal(owner: Any) {
        session?.takeIf { it.owner === owner }?.let { stopSession(it) }
    }

    fun finishExternalPcm(owner: Any, byteCount: Long, sha256: String) {
        session?.takeIf { it.owner === owner && it.remotePcm && !it.cancelled }?.let {
            snapshotFinalizationSettings(it)
            it.stopped = true
            synchronized(it.remotePreviewLock) { it.pendingRemotePreview = null }
            it.remoteFinish.complete(RemoteFinish(byteCount, sha256))
            setState(false, true, "processing")
        }
    }

    fun cancelExternal(owner: Any) {
        session?.takeIf { it.owner === owner }?.let {
            it.cancelled = true
            synchronized(it.remotePreviewLock) { it.pendingRemotePreview = null }
            stopSession(it)
        }
    }

    /** Coalesce while Main is blocked; never queue unbounded private text in callbacks. */
    private fun postRemotePreview(current: Session, text: String) {
        synchronized(current.remotePreviewLock) {
            if (current.stopped || current.cancelled || current.remoteInputEnded) return
            current.pendingRemotePreview = text
            if (current.remotePreviewPosted) return
            current.remotePreviewPosted = true
        }
        mainHandler.post {
            synchronized(current.remotePreviewLock) {
                current.remotePreviewPosted = false
                val pending = current.pendingRemotePreview
                current.pendingRemotePreview = null
                if (pending != null && session === current && current.remotePcm &&
                    !current.stopped && !current.cancelled && !current.remoteInputEnded && prefs.serviceEnabled) {
                    current.listener?.onPartial(pending)
                }
            }
        }
    }

    private fun postPreview(current: Session, text: String) {
        mainHandler.post {
            if (session === current && current.previewEnabled && !current.stopped &&
                !current.cancelled && prefs.serviceEnabled && current.publishedPreview != text) {
                current.publishedPreview = text
                onPreviewTextChanged?.invoke(text)
            }
        }
    }

    private fun clearPreview(current: Session) {
        if (current.previewEnabled && current.publishedPreview.isNotEmpty()) {
            current.publishedPreview = ""
            onPreviewTextChanged?.invoke("")
        }
    }

    private fun stopSession(current: Session) {
        if (current.cancelled) {
            current.closeAudioFile()
            current.remoteFinish.complete(null)
            current.advancedJob?.cancel()
        }
        // Completed files already exist: STOP is a no-op; only CANCEL discards their result.
        if (current.audioFile != null || current.historyAudioInputFileName != null) return
        if (!current.cancelled && !current.finalTextSettingsCaptured) {
            snapshotFinalizationSettings(current)
        }
        current.stopped = true
        clearPreview(current)
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
            setState(false, true, "processing")
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
        // Each session snapshots vocabulary; the next session reloads if decoder settings changed.
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
        val oldOnlineRecognizer = onlineRecognizer
        onlineRecognizer = null
        loadedDecoderConfig = null
        oldOnlineRecognizer?.release()
    }

    private fun setState(recording: Boolean, processing: Boolean, state: String = if (recording) "recording" else "processing") {
        isRecording = recording
        isAwaitingStop = state == "recording_limit"
        isProcessing = processing
        currentStatus = state
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

}
