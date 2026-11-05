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
import java.util.Locale

internal enum class HistoryVadMode { STANDARD, REMOVE_SILENCE }

class SpeechEngine private constructor(private val context: Context) {
    companion object {
        private const val TAG = "SpeechEngine"
        private const val SAMPLE_RATE = RecordingLimits.SAMPLE_RATE
        private const val MAX_FILE_BYTES = 3_840_000L // Completed-file IPC input is limited to 120 seconds.
        private const val CAPTURE_READ_SAMPLES = 1024
        // Batch PCM into half-second queue items; size the queue for the full bounded capture.
        private const val STREAM_CHUNK_SAMPLES = SAMPLE_RATE / 2
        // NeMo's 1120 ms encoder needs a complete final chunk plus right context.
        // Padding is decoder-only; captured/history PCM is never modified.
        private const val NEMOTRON_LEFT_PADDING_SAMPLES = SAMPLE_RATE / 2
        private const val NEMOTRON_TAIL_PADDING_SAMPLES = SAMPLE_RATE * 128 / 100
        private const val MAX_LIVE_SAMPLES = RecordingLimits.MAX_LIVE_SAMPLES // 10 minutes of mono PCM16 capture.
        // A retry adds at most 100 ms context on each side.
        private const val MAX_DECODER_SAMPLES = MAX_LIVE_SAMPLES + TdtEmptyRetry.EXTRA_SAMPLES
        private const val VAD_FRAME_SAMPLES = 512
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
        // Local History-only diagnostic data. Never sent over IPC or logged.
        fun onHistoryVadDiagnostics(diagnostics: String) = Unit
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
        val historyPolishingConfiguration: TranscriptPolishingConfiguration? = null,
        val historyUseVad: Boolean = false,
        val historyVadMode: HistoryVadMode = HistoryVadMode.STANDARD
    ) {
        var preferredMicrophone: String? = null
        var asrThreadCount = 2
        var initialTdtDither = 0f
        var liveTdtRemoveSilence = false
        val stopRequested = CompletableDeferred<Unit>()
        @Volatile var stopped = false
        @Volatile var cancelled = false
        var text: String? = null
        var transcriptChunks: List<String>? = null
        var error: String? = null
        var historyAudioFileName: String? = null
        var finalTextSettingsCaptured = false
        var finalPolishingEnabled = false
        var finalPolishingMinimumWords: Int? = null
        var finalPolishingAutoNumberSequences: Boolean? = null
        var finalPolishingConfiguration: TranscriptPolishingConfiguration? = null
        var acousticDecoderSettings: AcousticDecoderSettings? = null
        val vadReport = if (historyUseVad) HistoryVadReport() else null
        @Volatile var stopDrainTimeout: Runnable? = null

        @Synchronized fun closeAudioFile() { runCatching { audioFile?.close() } }
    }

    private data class DecoderConfigKey(
        val modelId: String,
        val asrThreadCount: Int,
        val acousticBiasEnabled: Boolean,
        val maxActivePaths: Int,
        val hotwordsScore: Float,
        val hotwords: List<String>,
        val dither: Float
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
        val endExclusive: Int,
        val skipSpeechlessTail: Boolean = false,
        val boundaryReason: String = "end of recording",
        val confirmedSpeechSamples: Int = 0,
        val possibleSpeechSamples: Int = 0,
        val liveNegativeRuns: List<HistoryNegativeRun> = emptyList(),
        val trailingTrim: VadTrailingTrim.Plan
    )

    private data class HistoryVadChunk(
        val start: Int,
        val end: Int,
        val reason: String,
        val confirmedSpeechSamples: Int,
        val possibleSpeechSamples: Int,
        val result: String,
        val tokens: String = "not available",
        val inputSamples: Int? = null,
        val retry: String = "not needed",
        val trim: VadTrailingTrim.Plan,
        val decoderEnd: Int?
    )

    private data class VadInterval(
        val start: Int, var end: Int, val kind: String,
        var minProbability: Float, var maxProbability: Float, var sumProbability: Double, var frames: Int
    )

    /** This report stays in memory and is shown only in the History UI on user request. */
    private class HistoryVadReport {
        var sampleCount = 0
        var vadStatus = "not initialized"
        var decoder = "not initialized"
        var pipeline = "not completed"
        var fallbackText: String? = null
        var rawText: String? = null
        var polishing = "not attempted"
        val chunks = ArrayList<HistoryVadChunk>()
        val intervals = ArrayList<VadInterval>()
        var intervalsTruncated = false
        var intervalCaptureError: String? = null
        var lastTokenSeconds: Double? = null
        var timestampsReliable = true
        var displayedTokens = 0
        var levels = "not calculated"
        var baseline = "not attempted"
        var isolated = "not attempted"

        fun observe(update: SpeechActivityUpdate) {
            val probability = update.probability ?: return
            val kind = when {
                update.confirmedSpeechInFrame -> "confirmed"
                update.possibleSpeechInFrame -> "possible"
                else -> return
            }
            val start = update.processedSamples - VAD_FRAME_SAMPLES
            val last = intervals.lastOrNull()
            if (last != null && last.end == start && last.kind == kind) {
                last.end = update.processedSamples
                last.minProbability = minOf(last.minProbability, probability)
                last.maxProbability = maxOf(last.maxProbability, probability)
                last.sumProbability += probability
                last.frames++
            } else if (intervals.size < 512) {
                intervals += VadInterval(start, update.processedSamples, kind, probability, probability, probability.toDouble(), 1)
            } else intervalsTruncated = true
        }

        fun tokenDetails(result: OfflineRecognizerResult, start: Int, end: Int): String {
            val tokens = result.tokens
            val times = result.timestamps
            val duration = (end - start).toDouble() / SAMPLE_RATE
            if (tokens.size != times.size || (tokens.isEmpty() && result.text.isNotBlank()) ||
                times.any { !it.isFinite() || it < 0f || it.toDouble() > duration } ||
                (1 until times.size).any { times[it] < times[it - 1] }) {
                timestampsReliable = false
                return "unavailable: invalid, missing, or inconsistent token/timestamp arrays"
            }
            if (tokens.isEmpty()) return "none"
            lastTokenSeconds = start.toDouble() / SAMPLE_RATE + times.last().toDouble()
            val remaining = (4096 - displayedTokens).coerceAtLeast(0)
            val count = minOf(tokens.size, remaining, 512)
            displayedTokens += count
            val details = (0 until count).joinToString("; ") { i ->
                val token = tokens[i].replace("\n", "\\n").replace("\r", "\\r")
                String.format(Locale.ROOT, "%.3f %s", start.toDouble() / SAMPLE_RATE + times[i], token)
            }
            return details + if (count < tokens.size) "; <token display truncated>" else ""
        }

        fun format(current: Session): String = buildString {
            fun time(samples: Int): String = String.format(Locale.ROOT, "%.3f", samples.toDouble() / SAMPLE_RATE)
            appendLine("VAD reprocessing diagnostics (contains private transcript text)")
            appendLine("Mode: ${current.historyVadMode}; REMOVE_SILENCE trims whole-recording runs of complete 32 ms frames with p < 0.5 lasting at least 200 ms, including across chunk boundaries. It retains the final 200 ms of each run, the first 200 ms of each chunk, and any unclassified final partial frame.")
            appendLine("Model: ${current.modelId}; recognizer threads: ${current.asrThreadCount}; audio: ${time(sampleCount)} s; sample rate: $SAMPLE_RATE Hz")
            appendLine("VAD: $vadStatus; decoder: $decoder; result: $pipeline")
            appendLine("Polishing: $polishing; VAD counts use 32 ms frames and may include false positives/negatives.")
            appendLine("TDT VAD chunks end at the last complete frame with raw p >= 0.5; quiet final speech can be cut. With no positive frame or incomplete VAD observations the original range is retained. An unclassified final partial frame is appended unchanged, potentially creating a discontinuity. Original recorded PCM is unchanged.")
            appendLine("TDT initial dither=${current.initialTdtDither}; an empty output may be retried once with dither=${TdtEmptyRetry.DITHER}. With a trimmed tail: up to 100 ms original left context only, no right context or artificial zeros. Otherwise: 100 ms original context per side, zero-filled at recording boundaries. No extra outer silence. Context can duplicate words; correctness is not guaranteed.")
            appendLine("Possible-speech counts use p >= 0.5; possible intervals also include active hysteresis frames. Final partial frame is not listed.")
            appendLine("VAD intervals (complete 32 ms frames only; confirmed and possible are exclusive labels, probability min/mean/max):")
            if (intervals.isEmpty()) appendLine("  none captured")
            intervals.forEach { interval ->
                appendLine(String.format(Locale.ROOT, "  %s–%s s %s p=%.3f/%.3f/%.3f",
                    time(interval.start), time(interval.end), interval.kind, interval.minProbability,
                    interval.sumProbability / interval.frames, interval.maxProbability))
            }
            if (intervalsTruncated) appendLine("  <interval list truncated at 512>")
            if (intervalCaptureError != null) appendLine("  <interval capture failed: $intervalCaptureError>")
            if (chunks.isEmpty()) appendLine("No chunks decoded.")
            chunks.forEachIndexed { index, chunk ->
                appendLine()
                appendLine("Chunk ${index + 1}: ${time(chunk.start)}–${time(chunk.end)} s " +
                    "(samples ${chunk.start}–${chunk.end}, end exclusive); boundary: ${chunk.reason}")
                appendLine("VAD confirmed speech: ${time(chunk.confirmedSpeechSamples)} s; " +
                    "possible speech: ${time(chunk.possibleSpeechSamples)} s")
                appendLine("Raw ASR: ${chunk.result}")
                if (chunk.inputSamples != null) appendLine("Decoder input: ${chunk.inputSamples} samples; original range: ${chunk.end - chunk.start} samples")
                if (chunk.inputSamples != null) appendLine("Decoder prefix cutoff: ${time(chunk.trim.prefixEnd)} s (sample ${chunk.trim.prefixEnd}, end exclusive); dropped trailing-negative samples: ${chunk.trim.droppedSamples}; ${chunk.trim.reason}." +
                    (if (chunk.trim.partialSamples > 0) " Appended unclassified original ${time(chunk.trim.partialStart)}–${time(chunk.trim.originalEnd)} s (${chunk.trim.partialSamples} samples); discontinuity=${chunk.trim.droppedSamples > 0}." else "") +
                    " Actual decoder end: ${time(checkNotNull(chunk.decoderEnd))} s (sample ${chunk.decoderEnd}, end exclusive; before retry).")
                appendLine("Empty-output retry: ${chunk.retry}")
                val tokenLabel = if (current.historyVadMode == HistoryVadMode.STANDARD)
                    "Token starts (approximate absolute seconds, not word ends)" else "Original-audio token starts"
                appendLine("$tokenLabel: ${chunk.tokens}")
            }
            if (fallbackText != null) appendLine("\nFull-audio fallback ASR: $fallbackText")
            if (rawText != null) appendLine("\nAssembled raw ASR: $rawText")
            appendLine("\nAudio levels (original samples, dBFS):\n$levels")
            appendLine("\nOrdinary History comparison (diagnostic only):\n$baseline")
            appendLine("\nIsolated replay (diagnostic only; never merged into transcript):\n$isolated")
            appendLine("\nFinal transcript: ${current.text?.takeIf { it.isNotBlank() } ?: "<empty>"}")
            if (current.error != null) appendLine("Processing error: ${current.error}")
        }
    }

    private data class TdtCaptureResult(val samples: FloatArray, val text: String, val chunks: List<String>? = null)
    private data class DecodeResult(val text: String, val chunks: List<String>? = null)

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
    /** Live streaming deliberately skips speech detection; the active session snapshots its model. */
    val speechActivityDetectionDisabled: Boolean
        get() = session?.let {
            it.audioFile == null && it.historyAudioInputFileName == null &&
                ModelInfo.getModelById(it.modelId).streaming
        } ?: false
    /** True only while a live TDT chunk is actively being decoded during capture. */
    @Volatile var isChunkProcessing: Boolean = false
        private set
    var onStateChanged: ((Boolean, Boolean) -> Unit)? = null
    var onSpeechActivityChanged: (() -> Unit)? = null
    var onChunkProcessingChanged: (() -> Unit)? = null
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
    internal fun startHistoryReprocess(
        owner: Any,
        audioFileName: String,
        listener: ExternalListener,
        polishingConfiguration: TranscriptPolishingConfiguration? = null,
        useVad: Boolean = false,
        vadMode: HistoryVadMode = HistoryVadMode.STANDARD
    ): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper())
        require(useVad || vadMode == HistoryVadMode.STANDARD) { "Experimental modes require VAD." }
        if (!HistoryAudioStore.isSafeFileName(audioFileName) ||
            HistoryAudioStore.find(context, audioFileName) == null) return false
        return startSession(Session(
            owner = historyReprocessOwnerMarker,
            listener = listener,
            historyAudioInputFileName = audioFileName,
            historyReprocessOwner = owner,
            historyPolishingConfiguration = polishingConfiguration,
            historyUseVad = useVad,
            historyVadMode = vadMode
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
        current.asrThreadCount = prefs.asrThreadCount.coerceIn(2, 4)
        current.initialTdtDither = if (prefs.alwaysTdtDitherEnabled) TdtEmptyRetry.DITHER else 0f
        current.preferredMicrophone = prefs.preferredMicrophone
        val selectedModel = ModelInfo.getModelById(current.modelId)
        val isTdtModel = current.modelId == AppPreferences.MODEL_PARAKEET_V2 ||
            current.modelId == AppPreferences.MODEL_PARAKEET_V3
        // Streaming never uses VAD. Do not silently run a different history algorithm.
        if (current.historyUseVad && !isTdtModel) return false
        val liveTdt = isTdtModel && !selectedModel.streaming && current.audioFile == null &&
            current.historyAudioInputFileName == null
        // Decide once per session, so settings changes cannot switch pipelines mid-capture.
        val chunkLiveTdt = liveTdt && prefs.liveTdtVadChunkingEnabled
        current.liveTdtRemoveSilence = chunkLiveTdt && prefs.liveTdtRemoveSilenceEnabled
        if (liveTdt || current.historyUseVad) current.acousticDecoderSettings = snapshotAcousticDecoderSettings()
        if (current.historyUseVad) snapshotFinalizationSettings(current)
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
                } else if (chunkLiveTdt) {
                    val capture = captureAndTranscribeTdtChunks(current)
                    current.text = capture.text
                    current.transcriptChunks = capture.chunks
                    capture.samples
                } else if (current.historyUseVad) {
                    val savedSamples = withContext(Dispatchers.IO) {
                        HistoryAudioStore.readSamples(context, current.historyAudioInputFileName)
                    }
                    current.vadReport?.sampleCount = savedSamples.size
                    val capture = captureAndTranscribeTdtChunks(current, savedSamples)
                    current.text = capture.text
                    current.transcriptChunks = capture.chunks
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
                        val decoded = withContext(Dispatchers.Default) {
                            nativeLock.withLock {
                                if (current.cancelled || !prefs.serviceEnabled) return@withLock DecodeResult("")
                                initializeEngine(current = current,
                                    forceUnbiased = current.historyAudioInputFileName != null,
                                    modelId = current.modelId,
                                    asrThreadCount = current.asrThreadCount,
                                    deferTranscriptTransforms = current.finalTextSettingsCaptured,
                                    decoderSettings = current.acousticDecoderSettings
                                )
                                if (current.cancelled || !prefs.serviceEnabled) return@withLock DecodeResult("")
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
                        current.text = decoded.text
                        current.transcriptChunks = decoded.chunks
                    }
                    val originalText = current.text.orEmpty()
                    current.vadReport?.rawText = originalText.ifBlank { "<empty>" }
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
                        current.vadReport?.polishing = when {
                            !polishingEnabled -> "disabled"
                            !polishingAllowed || polishingConfiguration == null -> "not authorized"
                            !shouldPolish -> "enabled but threshold not met"
                            else -> "attempted"
                        }
                        if (!current.cancelled && polishingAllowed && shouldPolish && polishingConfiguration != null) {
                            setState(false, true, "polishing")
                            val polishedText = transcriptPolisher.polish(
                                baseUrl = polishingConfiguration.baseUrl,
                                model = polishingConfiguration.model,
                                vocabulary = polishingConfiguration.vocabulary,
                                transcript = originalText,
                                systemPrompt = polishingConfiguration.systemPrompt,
                                chunks = current.transcriptChunks
                            )
                            current.vadReport?.polishing = if (polishedText == null) "failed; raw ASR retained" else "applied"
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
                    // The History-only comparison runs after the authoritative result is finalized.
                    // It may change the cached native recognizer but never text/transforms or audio.
                    if (current.vadReport != null && !current.cancelled && prefs.serviceEnabled) {
                        collectHistoryDiagnostics(current, samples)
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
                    current.vadReport?.let { report ->
                        // Diagnostics are advisory and must never prevent History delivery.
                        runCatching { current.listener?.onHistoryVadDiagnostics(report.format(current)) }
                            .onFailure { Log.w(TAG, "Could not display VAD diagnostics (${it.javaClass.simpleName})") }
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
        current: Session,
        asrThreadCount: Int,
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
            asrThreadCount = asrThreadCount,
            acousticBiasEnabled = useAcousticBias,
            maxActivePaths = if (useAcousticBias) decoderSettings?.maxActivePaths ?: prefs.acousticMaxActivePaths else 1,
            hotwordsScore = if (useAcousticBias) decoderSettings?.hotwordsScore ?: prefs.acousticHotwordsScore else 0f,
            hotwords = safeHotwords,
            dither = if (model.streaming) 0f else current.initialTdtDither
        )
        val recognizerLoaded = if (model.streaming) onlineRecognizer != null else recognizer != null
        if (recognizerLoaded && loadedConfigKey == configKey &&
            (model.streaming || recognizer?.config?.featConfig?.dither == configKey.dither)) return

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
                        numThreads = asrThreadCount,
                        provider = "cpu"
                    ),
                    // Keep one transcript across the recording; explicit Stop performs the flush.
                    enableEndpoint = false,
                    decodingMethod = "greedy_search"
                ))
                loadedConfigKey = configKey
                Log.i(TAG, "Loaded ${model.id}; Nemotron streaming greedy decoder")
                return
            }
            val bpeVocab = if (useAcousticBias) installBpeVocab(model) else null
            val hotwordsFile = if (useAcousticBias) writeHotwordsFile(safeHotwords) else ""
            recognizer = OfflineRecognizer(null, OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 128, dither = configKey.dither),
                modelConfig = OfflineModelConfig(
                    transducer = OfflineTransducerModelConfig(
                        encoder = File(dir, model.encoderFileName).absolutePath,
                        decoder = File(dir, model.decoderFileName).absolutePath,
                        joiner = File(dir, model.joinerFileName).absolutePath
                    ),
                    tokens = File(dir, model.tokensFileName).absolutePath,
                    numThreads = asrThreadCount,
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
     * descriptors into the fixed capture buffer and is sized for the complete capture cap.
     */
    private suspend fun captureAndTranscribeTdtChunks(
        current: Session,
        replaySamples: FloatArray? = null
    ): TdtCaptureResult =
        withContext(Dispatchers.Default) {
            nativeLock.withLock {
                if (current.cancelled || !prefs.serviceEnabled) {
                    return@withLock TdtCaptureResult(floatArrayOf(), "")
                }
                coroutineScope {
                    val pcmInput = Channel<CapturedPcmRange>(RAW_PCM_QUEUE_CAPACITY)
                    val tdtChunks = Channel<TdtPcmChunk>(TDT_CHUNK_QUEUE_CAPACITY)
                    val capturedProgress = CapturedAudioProgress()
                    val historyNegativeRuns = HistoryNegativeRuns()
                    var segmentationFailure: Exception? = null
                    var decoderFailure: Exception? = null
                    var decodedChunkCount = 0
                    var decodedEndSample = 0
                    val decodedTexts = ArrayList<String>()

                    val segmenter = launch(Dispatchers.Default) {
                        try {
                            segmentLiveTdtAudio(current, pcmInput, tdtChunks, historyNegativeRuns)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            segmentationFailure = e
                            current.vadReport?.vadStatus = "segmentation error: ${e.javaClass.simpleName}"
                            if (current.liveTdtRemoveSilence) updateSpeechActivity(current, available = false, detected = false)
                            // Keep draining capture descriptors; full-recording decode will recover.
                            for (ignored in pcmInput) Unit
                        } catch (e: LinkageError) {
                            segmentationFailure = IllegalStateException("Silero VAD failed", e)
                            current.vadReport?.vadStatus = "native segmentation error: ${e.javaClass.simpleName}"
                            if (current.liveTdtRemoveSilence) updateSpeechActivity(current, available = false, detected = false)
                            for (ignored in pcmInput) Unit
                        } finally {
                            tdtChunks.close()
                        }
                    }

                    val decoder = launch(Dispatchers.Default) {
                        // History removal needs full runs, including those spanning a chunk
                        // boundary. The bounded queue can hold every frame-boundary chunk.
                        val negativeRuns = if (current.historyVadMode == HistoryVadMode.REMOVE_SILENCE) {
                            segmenter.join()
                            historyNegativeRuns.drain()
                        } else emptyList()
                        var expectedSequence = 0
                        var expectedStart = 0
                        var failure: Exception? = null
                        var initialized = false
                        for (chunk in tdtChunks) {
                            if (current.cancelled || !prefs.serviceEnabled) continue
                            var tokenDetails = "not available"
                            var inputSamples: Int? = null
                            var decoderEnd: Int? = null
                            var retryDetails = "not needed"
                            fun recordDiagnostic(result: String) {
                                current.vadReport?.chunks?.add(HistoryVadChunk(
                                    chunk.startSample, chunk.endExclusive, chunk.boundaryReason,
                                    chunk.confirmedSpeechSamples, chunk.possibleSpeechSamples, result, tokenDetails, inputSamples, retryDetails, chunk.trailingTrim, decoderEnd
                                ))
                            }
                            if (chunk.sequence != expectedSequence || chunk.startSample != expectedStart ||
                                chunk.endExclusive <= chunk.startSample || chunk.endExclusive > chunk.samples.size) {
                                failure = IllegalStateException("TDT chunk ranges are not contiguous and ordered.")
                            }
                            expectedSequence = chunk.sequence + 1
                            expectedStart = chunk.endExclusive
                            decodedChunkCount = expectedSequence
                            decodedEndSample = expectedStart
                            // Count the intentionally skipped tail in range coverage, but do not
                            // pass it to the recognizer or remove it from the saved recording.
                            if (failure != null || chunk.skipSpeechlessTail) {
                                recordDiagnostic(if (failure != null) "<not decoded: earlier error>" else "<skipped short speechless tail>")
                                continue
                            }
                            try {
                                val runs = when {
                                    current.liveTdtRemoveSilence -> chunk.liveNegativeRuns
                                    current.historyVadMode == HistoryVadMode.REMOVE_SILENCE -> negativeRuns
                                    else -> null
                                }
                                val floatChunk = VadTrailingTrim.input(chunk.samples, chunk.startSample, chunk.trailingTrim, runs, SAMPLE_RATE)
                                inputSamples = floatChunk.size
                                decoderEnd = VadTrailingTrim.decoderEnd(chunk.startSample, chunk.trailingTrim, runs, SAMPLE_RATE)
                                Log.i(TAG, "TDT VAD original=${chunk.startSample}..${chunk.endExclusive}, decoder end=$decoderEnd, dropped trailing=${chunk.trailingTrim.droppedSamples}, partial=${chunk.trailingTrim.partialSamples}; ${chunk.trailingTrim.reason}; quiet final speech can be cut")
                                if (current.historyVadMode != HistoryVadMode.STANDARD ||
                                    (chunk.trailingTrim.droppedSamples > 0 && chunk.trailingTrim.partialSamples > 0)) {
                                    current.vadReport?.timestampsReliable = false
                                    tokenDetails = "unavailable: transformed input does not provide reliable original-audio timestamps"
                                }
                                if (floatChunk.isEmpty()) {
                                    recordDiagnostic("<all samples removed; no decoder input>")
                                    continue
                                }
                                if (current.cancelled || !prefs.serviceEnabled) continue
                                if (!initialized) {
                                    initializeEngine(current = current,
                                        modelId = current.modelId,
                                        asrThreadCount = current.asrThreadCount,
                                        deferTranscriptTransforms = true,
                                        decoderSettings = current.acousticDecoderSettings
                                    )
                                    initialized = true
                                    current.vadReport?.decoder = loadedConfigKey?.let {
                                        if (it.acousticBiasEnabled) "modified beam, acoustic bias enabled, paths=${it.maxActivePaths}, score=${it.hotwordsScore}"
                                        else "greedy, acoustic bias off"
                                    } ?: "unknown"
                                }
                                postChunkProcessing(current, true)
                                var text = try {
                                    decode(floatChunk, current, if (current.vadReport == null) null else { result ->
                                        try {
                                            if (current.historyVadMode == HistoryVadMode.STANDARD &&
                                                !(chunk.trailingTrim.droppedSamples > 0 && chunk.trailingTrim.partialSamples > 0)) {
                                                tokenDetails = current.vadReport.tokenDetails(result, chunk.startSample, checkNotNull(decoderEnd))
                                            }
                                        } catch (e: Exception) {
                                            current.vadReport.timestampsReliable = false
                                            tokenDetails = "unavailable: ${e.javaClass.simpleName}"
                                        } catch (e: LinkageError) {
                                            current.vadReport.timestampsReliable = false
                                            tokenDetails = "unavailable: ${e.javaClass.simpleName}"
                                        }
                                    }).trim()
                                } finally {
                                    postChunkProcessing(current, false)
                                }
                                // Capture publishes independently of VAD/JNI; waiting cannot block
                                // the producer, including on VAD failure or a partial final read.
                                val retry = TdtEmptyRetry.live(text, floatChunk, chunk.startSample, chunk.endExclusive,
                                    MAX_DECODER_SAMPLES, chunk.trailingTrim.droppedSamples > 0, capturedProgress, { chunk.samples[it] / 32768f },
                                    { !current.cancelled && prefs.serviceEnabled }) { audio ->
                                    Log.i(TAG, "Retrying empty TDT chunk: initial dither=${current.initialTdtDither}, retry dither=${TdtEmptyRetry.DITHER}; trimmed tail=${chunk.trailingTrim.droppedSamples} samples; no extra outer silence; correctness not guaranteed")
                                    current.vadReport?.timestampsReliable = false
                                    tokenDetails = "unavailable: retry input includes adjacent audio"
                                    postChunkProcessing(current, true)
                                    try { decodeDitheredRetry(audio, current).trim() }
                                    finally { postChunkProcessing(current, false) }
                                }
                                text = retry.text
                                retryDetails = when (retry.status) {
                                    TdtEmptyRetry.Status.NOT_NEEDED -> "not needed"
                                    TdtEmptyRetry.Status.CANCELLED -> "cancelled before retry"
                                    TdtEmptyRetry.Status.LIMIT -> {
                                        Log.w(TAG, "Empty TDT chunk retry skipped: decoder sample limit")
                                        "skipped: decoder sample limit"
                                    }
                                    TdtEmptyRetry.Status.ATTEMPTED ->
                                        if (chunk.trailingTrim.droppedSamples > 0)
                                            "initial dither=${current.initialTdtDither}; retry dither=${TdtEmptyRetry.DITHER}; left original context=${minOf(chunk.startSample, TdtEmptyRetry.CONTEXT)} samples; no right context or artificial zeros; trimmed tail not reintroduced; correctness not guaranteed"
                                        else "initial dither=${current.initialTdtDither}; retry dither=${TdtEmptyRetry.DITHER}; original context: left=${minOf(chunk.startSample, TdtEmptyRetry.CONTEXT)}, right=${minOf(retry.available - chunk.endExclusive, TdtEmptyRetry.CONTEXT)} samples, remainder zero-filled; no extra outer silence; correctness not guaranteed"
                                }
                                recordDiagnostic(text.ifBlank { "<empty>" })
                                if (!current.cancelled && prefs.serviceEnabled && text.isNotBlank()) {
                                    decodedTexts += text
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                recordDiagnostic("<decode error: ${e.javaClass.simpleName}>")
                                failure = e
                            } catch (e: LinkageError) {
                                recordDiagnostic("<native decode error: ${e.javaClass.simpleName}>")
                                failure = IllegalStateException("Native TDT chunk decode failed", e)
                            }
                        }
                        decoderFailure = failure
                    }

                    val samples = try {
                        if (replaySamples == null) {
                            withContext(Dispatchers.IO) {
                                captureRecording(current, onVadInput = { range -> pcmInput.send(range) },
                                    onCaptureProgress = capturedProgress::publish,
                                    onCaptureFinished = capturedProgress::finish)
                            }
                        } else {
                            // History stores the original PCM16 exactly. Replay the same 1024-sample
                            // input blocks into the live segmenter and concurrent decoder; do not
                            // trim silence or use the ordinary History retry's windowing policy.
                            require(replaySamples.isNotEmpty() && replaySamples.size <= MAX_LIVE_SAMPLES)
                            val pcm = ShortArray(replaySamples.size) { (replaySamples[it] * 32768f).toInt().toShort() }
                            capturedProgress.publish(pcm.size)
                            capturedProgress.finish()
                            var start = 0
                            while (start < pcm.size && !current.cancelled && prefs.serviceEnabled) {
                                val end = minOf(start + CAPTURE_READ_SAMPLES, pcm.size)
                                pcmInput.send(CapturedPcmRange(pcm, start, end))
                                start = end
                            }
                            replaySamples
                        }
                    } finally {
                        capturedProgress.finish()
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
                    val result = if (failure != null || !completeCoverage) {
                        if (current.historyVadMode == HistoryVadMode.REMOVE_SILENCE) {
                            current.vadReport?.pipeline = "failed: silence removal could not be completed; no untransformed fallback"
                            throw IllegalStateException("Silence-removal retry failed; no untransformed fallback was used.", failure)
                        }
                        current.vadReport?.pipeline = "full-audio fallback: ${failure?.javaClass?.simpleName ?: "incomplete range coverage"}"
                        Log.w(TAG, "Chunked TDT path incomplete (${failure?.javaClass?.simpleName ?: "range coverage"}); retrying full PCM")
                        if (current.cancelled || !prefs.serviceEnabled) return@coroutineScope TdtCaptureResult(samples, "")
                        releaseNative()
                        initializeEngine(current = current,
                            modelId = current.modelId,
                            asrThreadCount = current.asrThreadCount,
                            deferTranscriptTransforms = true,
                            decoderSettings = current.acousticDecoderSettings
                        )
                        if (current.cancelled || !prefs.serviceEnabled) return@coroutineScope TdtCaptureResult(samples, "")
                        // Preserve every captured sample; do not apply per-recording endpoint trimming.
                        val fallbackText = decodeStaticWithRetry(samples, samples, 0, samples.size, current, recordInPipeline = true)
                        current.vadReport?.fallbackText = fallbackText.ifBlank { "<empty>" }
                        DecodeResult(fallbackText)
                    } else {
                        current.vadReport?.pipeline = "ordered original ranges accounted for (short speechless tail may be skipped)"

                        val chunks = decodedTexts.toList()
                        DecodeResult(chunks.joinToString(" "), chunks)
                    }
                    TdtCaptureResult(samples, result.text, result.chunks)
                }
            }
        }

    /** Emit contiguous PCM after five seconds of speech and a pause, or a three-second silence. */
    private suspend fun segmentLiveTdtAudio(
        current: Session,
        pcmInput: Channel<CapturedPcmRange>,
        tdtChunks: Channel<TdtPcmChunk>,
        negativeRuns: HistoryNegativeRuns
    ) {
        var detector: SpeechActivityDetector? = null
        var lastDetected = false
        var nextChunkStart = 0
        val chunkPolicy = TdtChunkPolicy(SAMPLE_RATE)
        var chunkPossibleSpeechStart = 0
        var chunkConfirmedSpeechStart = 0
        var lastConfirmedSpeechSamples = 0
        var lastPossibleSpeechSamples = 0
        var expectedInputStart = 0
        var sequence = 0
        var recording: ShortArray? = null
        val liveRuns = if (current.liveTdtRemoveSilence) LiveNegativeRuns(SAMPLE_RATE) else null
        val trailingTrim = VadTrailingTrim(VAD_FRAME_SAMPLES)
        val pendingChunks = java.util.ArrayDeque<TdtPcmChunk>()
        suspend fun flushResolvedChunks() {
            val tracker = liveRuns ?: return
            while (pendingChunks.isNotEmpty() && checkNotNull(pendingChunks.peekFirst()).endExclusive <= tracker.resolvedThrough()) {
                val chunk = pendingChunks.removeFirst()
                val runs = tracker.takeForChunk(chunk.startSample, chunk.endExclusive)
                tdtChunks.send(chunk.copy(liveNegativeRuns = runs))
            }
        }
        suspend fun emitChunk(chunk: TdtPcmChunk) {
            if (liveRuns == null) tdtChunks.send(chunk)
            else {
                pendingChunks.addLast(chunk)
                flushResolvedChunks()
            }
        }
        try {
            try {
                detector = SpeechActivityDetector(context, tdtChunking = true)
                current.vadReport?.vadStatus = "available"
                updateSpeechActivity(current, available = true, detected = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: LinkageError) {
                Log.w(TAG, "Speech activity native code unavailable; preserving full PCM for TDT", e)
                current.vadReport?.vadStatus = "unavailable: ${e.javaClass.simpleName}; remaining audio decoded unsplit"
                if (current.historyVadMode == HistoryVadMode.REMOVE_SILENCE || current.liveTdtRemoveSilence) throw IllegalStateException("Silero VAD unavailable", e)
                updateSpeechActivity(current, available = false, detected = false)
            } catch (e: Exception) {
                Log.w(TAG, "Speech activity detector unavailable; preserving full PCM for TDT", e)
                current.vadReport?.vadStatus = "unavailable: ${e.javaClass.simpleName}; remaining audio decoded unsplit"
                if (current.historyVadMode == HistoryVadMode.REMOVE_SILENCE || current.liveTdtRemoveSilence) throw IllegalStateException("Silero VAD unavailable", e)
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
                    current.vadReport?.vadStatus = "stopped near sample ${range.startSample}: ${e.javaClass.simpleName}; remaining audio decoded unsplit"
                    activeDetector.close()
                    detector = null
                    if (current.historyVadMode == HistoryVadMode.REMOVE_SILENCE || current.liveTdtRemoveSilence) throw IllegalStateException("Silero VAD stopped", e)
                    updateSpeechActivity(current, available = false, detected = false)
                    continue
                } catch (e: Exception) {
                    Log.w(TAG, "Speech activity detection stopped; preserving remaining PCM", e)
                    current.vadReport?.vadStatus = "stopped near sample ${range.startSample}: ${e.javaClass.simpleName}; remaining audio decoded unsplit"
                    activeDetector.close()
                    detector = null
                    if (current.historyVadMode == HistoryVadMode.REMOVE_SILENCE || current.liveTdtRemoveSilence) throw IllegalStateException("Silero VAD stopped", e)
                    updateSpeechActivity(current, available = false, detected = false)
                    continue
                }

                for (update in updates) {
                    trailingTrim.observe(update.processedSamples, checkNotNull(update.probability) { "Missing VAD frame probability" })
                    liveRuns?.let {
                        it.observe(update.processedSamples - VAD_FRAME_SAMPLES, update.processedSamples,
                            checkNotNull(update.probability) { "Missing VAD frame probability" })
                        flushResolvedChunks()
                    }
                    if (current.historyVadMode == HistoryVadMode.REMOVE_SILENCE) {
                        val probability = checkNotNull(update.probability) { "Missing VAD frame probability" }
                        negativeRuns.observe(update.processedSamples - VAD_FRAME_SAMPLES, update.processedSamples, probability)
                    }
                    current.vadReport?.let { report ->
                        if (report.intervalCaptureError == null) {
                            try { report.observe(update) } catch (e: Exception) {
                                report.intervalCaptureError = e.javaClass.simpleName
                            }
                        }
                    }
                    lastConfirmedSpeechSamples = update.speechSamples
                    lastPossibleSpeechSamples = update.possibleSpeechSamples
                    if (update.detected != lastDetected) {
                        lastDetected = update.detected
                        updateSpeechActivity(current, available = true, detected = update.detected)
                    }
                    if (!current.cancelled && chunkPolicy.onFrame(
                            update.processedSamples, update.detected, update.speechEnded,
                            update.speechSamples, update.possibleSpeechInFrame
                        )) {
                        check(update.processedSamples in nextChunkStart..expectedInputStart)
                        if (update.processedSamples > nextChunkStart) {
                            emitChunk(TdtPcmChunk(
                                sequence = sequence++,
                                samples = range.samples,
                                startSample = nextChunkStart,
                                endExclusive = update.processedSamples,
                                boundaryReason = chunkPolicy.lastBoundaryReason ?: "VAD boundary",
                                confirmedSpeechSamples = update.speechSamples - chunkConfirmedSpeechStart,
                                possibleSpeechSamples = update.possibleSpeechSamples - chunkPossibleSpeechStart,
                                trailingTrim = trailingTrim.finishChunk(nextChunkStart, update.processedSamples, observationsAvailable = true)
                            ))
                            nextChunkStart = update.processedSamples
                            chunkPossibleSpeechStart = update.possibleSpeechSamples
                            chunkConfirmedSpeechStart = update.speechSamples
                        }
                    }
                }
            }

            liveRuns?.finish(expectedInputStart)
            flushResolvedChunks()
            val finalSamples = recording
            if (!current.cancelled && finalSamples != null && expectedInputStart > nextChunkStart) {
                val activeDetector = detector
                // Tails of at least one second always decode. For shorter tails, retain even an
                // unconfirmed speech candidate; VAD failures must always fail open.
                val tailSamples = expectedInputStart - nextChunkStart
                val skipSpeechlessTail = if (tailSamples < SAMPLE_RATE && activeDetector != null &&
                    activeDetector.possibleSpeechSamples == chunkPossibleSpeechStart) {
                    try {
                        chunkPolicy.skipTail(tailSamples, detectorAvailable = true,
                            possibleSpeech = activeDetector.finalPartialFrameMayContainSpeech())
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: LinkageError) {
                        Log.w(TAG, "Final speech activity check failed; decoding remaining PCM", e)
                        false
                    } catch (e: Exception) {
                        Log.w(TAG, "Final speech activity check failed; decoding remaining PCM", e)
                        false
                    }
                } else {
                    false
                }
                emitChunk(TdtPcmChunk(
                    sequence = sequence,
                    samples = finalSamples,
                    startSample = nextChunkStart,
                    endExclusive = expectedInputStart,
                    skipSpeechlessTail = skipSpeechlessTail,
                    confirmedSpeechSamples = lastConfirmedSpeechSamples - chunkConfirmedSpeechStart,
                    possibleSpeechSamples = lastPossibleSpeechSamples - chunkPossibleSpeechStart,
                    trailingTrim = trailingTrim.finishChunk(nextChunkStart, expectedInputStart, observationsAvailable = activeDetector != null)
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
                initializeEngine(current = current, modelId = current.modelId, asrThreadCount = current.asrThreadCount, deferTranscriptTransforms = true)
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
        onVadInput: (suspend (CapturedPcmRange) -> Unit)? = null,
        onCaptureProgress: ((Int) -> Unit)? = null,
        onCaptureFinished: (() -> Unit)? = null
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
        // Streaming feeds every captured sample directly to its recognizer and does not
        // need the visual-only Silero detector. Keep TDT's existing behavior unchanged.
        if (onVadInput == null && !ModelInfo.getModelById(current.modelId).streaming) {
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
                    onCaptureProgress?.invoke(count)
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
            onCaptureFinished?.invoke()
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

    /** Local advisory work only. Every extra decode runs under the same native lock. */
    private suspend fun collectHistoryDiagnostics(current: Session, samples: FloatArray) {
        val report = current.vadReport ?: return
        withContext(Dispatchers.Default) {
            fun active() = !current.cancelled && prefs.serviceEnabled
            suspend fun advisory(onFailure: (String) -> Unit, block: suspend () -> Unit) {
                try {
                    currentCoroutineContext().ensureActive()
                    if (active()) block()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    onFailure(e.javaClass.simpleName)
                } catch (e: LinkageError) {
                    onFailure(e.javaClass.simpleName)
                }
            }
            fun seconds(sample: Int) = String.format(Locale.ROOT, "%.3f", sample.toDouble() / SAMPLE_RATE)
            advisory({ report.levels = "unavailable: $it" }) {
                val lines = ArrayList<String>()
                // Fine resolution helps compare individual utterances in short recordings;
                // keep long reports bounded to at most 120 intervals.
                val step = SAMPLE_RATE * if (samples.size <= SAMPLE_RATE * 60) 1 else 5
                var start = 0
                while (start < samples.size && active()) {
                    currentCoroutineContext().ensureActive()
                    val end = minOf(start + step, samples.size)
                    var sum = 0.0
                    var peak = 0.0
                    for (i in start until end) {
                        val value = kotlin.math.abs(samples[i].toDouble())
                        sum += value * value
                        peak = maxOf(peak, value)
                    }
                    fun db(value: Double) = if (value == 0.0) "-inf" else
                        String.format(Locale.ROOT, "%.1f", 20.0 * kotlin.math.log10(value))
                    lines += "${seconds(start)}–${seconds(end)} s RMS ${db(kotlin.math.sqrt(sum / (end - start)))}; peak ${db(peak)} dBFS"
                    start = end
                }
                if (active()) report.levels = lines.joinToString("\n")
            }

            advisory({ report.isolated = "unavailable: $it" }) {
                val lastToken = report.lastTokenSeconds
                val unavailable = when {
                    report.pipeline.startsWith("full-audio fallback") -> "primary result used full-audio fallback, not chunk timestamps"
                    current.historyVadMode != HistoryVadMode.STANDARD -> "transformed chunk timestamps are not mapped to original audio"
                    report.vadStatus != "available" || report.intervalCaptureError != null -> "VAD observations incomplete"
                    report.intervalsTruncated -> "VAD interval list truncated"
                    !report.timestampsReliable -> "chunk timestamps invalid or unavailable"
                    lastToken == null -> "no usable chunk token timestamp"
                    else -> null
                }
                if (unavailable != null) {
                    report.isolated = "skipped: $unavailable"
                } else {
                    // Token timestamps are starts, not word ends. A 300 ms guard reduces (but
                    // cannot eliminate) overlap with the last recognized word.
                    val after = ((lastToken!! + 0.3) * SAMPLE_RATE).toInt().coerceAtMost(samples.size)
                    var runStart = -1
                    var runEnd = -1
                    var candidateStart = -1
                    var candidateEnd = -1
                    fun considerRun() {
                        val clippedStart = maxOf(runStart, after)
                        if (runStart >= 0 && runEnd - clippedStart >= SAMPLE_RATE / 4) {
                            candidateStart = clippedStart
                            candidateEnd = runEnd
                        }
                    }
                    for (interval in report.intervals) {
                        if (runEnd != interval.start) {
                            considerRun()
                            runStart = interval.start
                        }
                        runEnd = interval.end
                    }
                    considerRun()
                    if (candidateStart < 0) {
                        report.isolated = "skipped: no continuous VAD-positive interval of at least 250 ms after the last token + 300 ms guard"
                    } else {
                        // Keep the last up-to-7 seconds of the positive region, plus 500 ms
                        // original context on each side; no padding, gain, or normalization.
                        val speechStart = maxOf(candidateStart, candidateEnd - SAMPLE_RATE * 7)
                        val start = maxOf(0, speechStart - SAMPLE_RATE / 2)
                        val end = minOf(samples.size, candidateEnd + SAMPLE_RATE / 2)
                        nativeLock.withLock {
                            if (active()) {
                                initializeEngine(current = current, modelId = current.modelId, deferTranscriptTransforms = true,
                                    asrThreadCount = current.asrThreadCount,
                                    decoderSettings = current.acousticDecoderSettings)
                                if (active()) {
                                    val text = decodeStaticWithRetry(samples.copyOfRange(start, end), samples, start, end, current)
                                    if (active()) report.isolated = "VAD-positive ${seconds(speechStart)}–${seconds(candidateEnd)} s; " +
                                        "original audio ${seconds(start)}–${seconds(end)} s (up to 500 ms margins; max 8 s); " +
                                        "ordinary diagnostic replay; initial dither=${current.initialTdtDither}; an empty result gets dither=${TdtEmptyRetry.DITHER}, 100 ms original context per side (missing context zero-filled), without extra outer silence. Correctness is not guaranteed. Raw ASR: ${text.ifBlank { "<empty>" }}"
                                }
                            }
                        }
                    }
                }
            }

            advisory({ report.baseline = "unavailable: $it" }) {
                if (samples.size > SAMPLE_RATE * 60) {
                    report.baseline = "skipped: recording exceeds 60 s comparison limit"
                } else nativeLock.withLock {
                    if (active()) {
                        // Ordinary History initializes an unbiased decoder regardless of the
                        // currently cached recognizer. Do not reuse the possibly biased VAD one.
                        initializeEngine(current = current, forceUnbiased = true, modelId = current.modelId,
                            asrThreadCount = current.asrThreadCount,
                            deferTranscriptTransforms = true)
                        if (active()) {
                            val endpoint = FinalAudioPolicy.endpointRange(samples, SAMPLE_RATE)
                            val trimmed = if (endpoint.start == 0 && endpoint.endExclusive == samples.size) samples
                                else samples.copyOfRange(endpoint.start, endpoint.endExclusive)
                            val windows = FinalAudioPolicy.reprocessWindows(trimmed, SAMPLE_RATE)
                            val lines = ArrayList<String>()
                            lines += "Unbiased greedy decoder; initial dither=${current.initialTdtDither}, empty-result retry dither=${TdtEmptyRetry.DITHER} with 100 ms original context per side (missing context zero-filled), without extra outer silence; correctness not guaranteed. Exact-zero endpoints ${seconds(endpoint.start)}–${seconds(endpoint.endExclusive)} s (samples ${endpoint.start}–${endpoint.endExclusive}, end exclusive)."
                            for ((index, window) in windows.withIndex()) {
                                currentCoroutineContext().ensureActive()
                                if (!active()) break
                                val start = endpoint.start + window.start
                                val end = endpoint.start + window.endExclusive
                                val text = decodeStaticWithRetry(trimmed.copyOfRange(window.start, window.endExclusive), samples, start, end, current)
                                if (!active()) break
                                lines += "Window ${index + 1}: ${seconds(start)}–${seconds(end)} s (samples $start–$end, end exclusive); raw ASR: ${text.ifBlank { "<empty>" }}"
                            }
                            if (active()) report.baseline = lines.joinToString("\n")
                        }
                    }
                }
            }
        }
    }

    /** Final-only decode; history reprocessing may use contiguous bounded windows. */
    private fun decodeRecording(
        samples: FloatArray,
        current: Session,
        reprocessInWindows: Boolean = false
    ): DecodeResult {
        if (current.cancelled || !prefs.serviceEnabled) return DecodeResult("")
        require(samples.isNotEmpty() && samples.size <= MAX_LIVE_SAMPLES) { "Recording duration exceeded." }
        // One persistent online stream for the whole file: no history windows, VAD
        // segmentation, or endpoint trimming may discard context or cut words.
        if (ModelInfo.getModelById(current.modelId).streaming) {
            return DecodeResult(decodeOnline(samples, current))
        }
        val endpoints = FinalAudioPolicy.endpointRange(samples, SAMPLE_RATE)
        val finalSamples = if (endpoints.start == 0 && endpoints.endExclusive == samples.size) samples
            else samples.copyOfRange(endpoints.start, endpoints.endExclusive)
        if (current.cancelled || !prefs.serviceEnabled) return DecodeResult("")
        Log.i(TAG, "Final PCM endpoints ${endpoints.start}..${endpoints.endExclusive} of ${samples.size}; exact-zero only")
        val result = if (reprocessInWindows) decodeInWindows(finalSamples, samples, endpoints.start, current)
            else DecodeResult(decodeStaticWithRetry(finalSamples, samples, endpoints.start, endpoints.endExclusive, current))
        if (current.cancelled || !prefs.serviceEnabled) return DecodeResult("")
        return result
    }

    private fun decodeInWindows(samples: FloatArray, original: FloatArray, originalOffset: Int, current: Session): DecodeResult {
        val windows = FinalAudioPolicy.reprocessWindows(samples, SAMPLE_RATE)
        Log.i(TAG, "History retry using ${windows.size} decode window(s) for ${samples.size} samples")
        val results = ArrayList<String>(windows.size)
        for (window in windows) {
            if (current.cancelled || !prefs.serviceEnabled) return DecodeResult("")
            val text = decodeStaticWithRetry(samples.copyOfRange(window.start, window.endExclusive), original,
                originalOffset + window.start, originalOffset + window.endExclusive, current).trim()
            if (current.cancelled || !prefs.serviceEnabled) return DecodeResult("")
            if (text.isNotBlank()) results += text
        }
        return DecodeResult(results.joinToString(" "), results.toList())
    }

    /** Static file/window/fallback paths retain their untrimmed original-coordinate source. */
    private fun decodeStaticWithRetry(input: FloatArray, original: FloatArray, start: Int, end: Int, current: Session,
                                      recordInPipeline: Boolean = false): String {
        return TdtEmptyRetry.static(input, original, start, end, MAX_DECODER_SAMPLES,
            !ModelInfo.getModelById(current.modelId).streaming,
            { !current.cancelled && prefs.serviceEnabled }, { decode(it, current) },
            { decodeDitheredRetry(it, current) }) { status ->
            if (status == TdtEmptyRetry.Status.LIMIT) {
                Log.w(TAG, "Empty TDT retry skipped: decoder sample limit")
                if (recordInPipeline) current.vadReport?.pipeline += "; empty full-audio retry skipped: decoder sample limit"
            } else {
                Log.i(TAG, "Retrying empty TDT result: initial dither=${current.initialTdtDither}, retry dither=${TdtEmptyRetry.DITHER}; 100 ms original context per side (missing context zero-filled), without extra outer silence; correctness not guaranteed")
                if (recordInPipeline) current.vadReport?.pipeline += "; empty full-audio result retried with 100 ms original context per side (missing context zero-filled), without extra outer silence; initial dither=${current.initialTdtDither}, retry dither=${TdtEmptyRetry.DITHER}; correctness not guaranteed"
            }
        }
    }

    /** Called under nativeLock. Recreate, never setConfig: JNI may not rebuild feature extraction. */
    private fun decodeDitheredRetry(samples: FloatArray, current: Session): String {
        val original = checkNotNull(recognizer).config
        check(original.featConfig.dither == current.initialTdtDither)
        val key = checkNotNull(loadedConfigKey)
        check(key.dither == current.initialTdtDither)
        fun switch(dither: Float) {
            // Drop the previous model before loading the next to avoid two native model copies.
            releaseNative()
            val config = original.copy(featConfig = original.featConfig.copy(dither = dither))
            recognizer = OfflineRecognizer(null, config)
            loadedConfigKey = key.copy(dither = dither)
        }
        return TdtEmptyRetry.withDitheredRecognizer(current.initialTdtDither, ::switch,
            { decode(samples, current, expectedDither = TdtEmptyRetry.DITHER) }) { error ->
            Log.w(TAG, "Dithered empty TDT retry failed; keeping original empty result", error)
        }
    }

    private fun decode(
        samples: FloatArray,
        current: Session,
        onOfflineResult: ((OfflineRecognizerResult) -> Unit)? = null,
        expectedDither: Float = current.initialTdtDither
    ): String {
        require(samples.isNotEmpty() && samples.size <= MAX_DECODER_SAMPLES) { "Decoder sample limit exceeded." }
        if (ModelInfo.getModelById(current.modelId).streaming) {
            return decodeOnline(samples, current)
        }
        val engine = checkNotNull(recognizer)
        check(engine.config.featConfig.dither == expectedDither) { "Unexpected offline recognizer dither" }
        val stream = engine.createStream()
        return try {
            stream.acceptWaveform(samples, SAMPLE_RATE)
            if (current.cancelled || !prefs.serviceEnabled) return ""
            engine.decode(stream)
            val result = engine.getResult(stream)
            onOfflineResult?.invoke(result)
            result.text.trim()
        } finally { stream.release() }
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
        if (!recording || isAwaitingStop) isChunkProcessing = false
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

    /** Advisory UI only: ordered posts never block microphone capture or alter decoder ownership. */
    private fun postChunkProcessing(current: Session, processing: Boolean) {
        mainHandler.post {
            if (session !== current || current.cancelled || current.stopped || !isRecording || isAwaitingStop) return@post
            if (isChunkProcessing != processing) {
                isChunkProcessing = processing
                onChunkProcessingChanged?.invoke()
            }
        }
    }

    private suspend fun updateSpeechActivity(current: Session, available: Boolean, detected: Boolean) {
        withContext(Dispatchers.Main.immediate) {
            if (session !== current || current.cancelled || current.stopped || current.historyUseVad) return@withContext
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
