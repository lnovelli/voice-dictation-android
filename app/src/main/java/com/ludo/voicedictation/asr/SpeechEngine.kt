package com.ludo.voicedictation.asr

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.TenVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import com.ludo.voicedictation.data.AppPreferences
import com.ludo.voicedictation.model.ModelInfo
import com.ludo.voicedictation.model.ModelManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class SpeechEngine(private val context: Context) {

    companion object {
        private const val TAG = "SpeechEngine"
        private const val SAMPLE_RATE = 16000
        private const val WINDOW_SIZE = 512

        @Volatile
        private var instance: SpeechEngine? = null

        fun getInstance(context: Context): SpeechEngine {
            return instance ?: synchronized(this) {
                instance ?: SpeechEngine(context.applicationContext).also { instance = it }
            }
        }
    }

    private val prefs = AppPreferences(context)
    private val modelManager = ModelManager(context)

    private var recognizer: OfflineRecognizer? = null
    private var vad: Vad? = null
    private var loadedModelId: String? = null

    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default)

    var isRecording: Boolean = false
        private set

    var isProcessing: Boolean = false
        private set

    var onStateChanged: ((isRecording: Boolean, isProcessing: Boolean) -> Unit)? = null
    var onPartialResult: ((text: String) -> Unit)? = null
    var onFinalResult: ((text: String) -> Unit)? = null
    var onError: ((error: String) -> Unit)? = null

    fun isModelReady(): Boolean {
        val model = ModelInfo.getModelById(prefs.selectedModelId)
        return modelManager.isModelDownloaded(model)
    }

    @Synchronized
    fun initializeEngine(): Boolean {
        val model = ModelInfo.getModelById(prefs.selectedModelId)
        if (!modelManager.isModelDownloaded(model)) {
            Log.w(TAG, "Selected model ${model.displayName} is not downloaded")
            return false
        }

        // Check if already initialized with current settings
        if (recognizer != null && vad != null && loadedModelId == model.id) {
            return true
        }

        releaseEngine()

        try {
            Log.i(TAG, "Initializing VAD...")
            val vadConfig = VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(
                    model = modelManager.vadFile.absolutePath,
                    threshold = 0.5f,
                    minSilenceDuration = 0.25f,
                    minSpeechDuration = 0.25f,
                    windowSize = WINDOW_SIZE,
                    maxSpeechDuration = 20.0f
                ),
                tenVadModelConfig = TenVadModelConfig(),
                sampleRate = SAMPLE_RATE,
                numThreads = 1,
                provider = "cpu",
                debug = false
            )
            vad = Vad(null, vadConfig)

            Log.i(TAG, "Initializing Parakeet Recognizer (${model.displayName})...")
            val modelDir = modelManager.getModelDir(model)
            val encPath = File(modelDir, model.encoderFileName).absolutePath
            val decPath = File(modelDir, model.decoderFileName).absolutePath
            val joinerPath = File(modelDir, model.joinerFileName).absolutePath
            val tokensPath = File(modelDir, model.tokensFileName).absolutePath

            val transducerConfig = OfflineTransducerModelConfig(
                encoder = encPath,
                decoder = decPath,
                joiner = joinerPath
            )

            val offlineModelConfig = OfflineModelConfig(
                transducer = transducerConfig,
                tokens = tokensPath,
                numThreads = 2,
                debug = false,
                provider = "cpu",
                modelType = "nemo_transducer"
            )

            // Setup Custom Vocabulary / Hotwords
            val vocab = prefs.getVocabulary()
            val hotwordsFile = File(context.filesDir, "hotwords.txt")
            val hasHotwords = vocab.isNotEmpty()

            val decodingMethod: String
            val hotwordsFilePath: String
            val hotwordsScore: Float
            val maxActivePaths: Int

            if (hasHotwords) {
                hotwordsFile.printWriter().use { out ->
                    for (word in vocab) {
                        out.println(word.trim().uppercase())
                    }
                }
                decodingMethod = "modified_beam_search"
                hotwordsFilePath = hotwordsFile.absolutePath
                hotwordsScore = prefs.hotwordsScore
                maxActivePaths = 4
                Log.i(TAG, "Context biasing enabled with ${vocab.size} terms, score=$hotwordsScore")
            } else {
                decodingMethod = "greedy_search"
                hotwordsFilePath = ""
                hotwordsScore = 1.5f
                maxActivePaths = 4
            }

            val recognizerConfig = OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                modelConfig = offlineModelConfig,
                decodingMethod = decodingMethod,
                hotwordsFile = hotwordsFilePath,
                hotwordsScore = hotwordsScore,
                maxActivePaths = maxActivePaths
            )

            recognizer = OfflineRecognizer(null, recognizerConfig)
            loadedModelId = model.id
            Log.i(TAG, "SpeechEngine initialized successfully with ${model.displayName}")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize SpeechEngine: ${e.message}", e)
            releaseEngine()
            onError?.invoke("Engine init failed: ${e.message}")
            return false
        }
    }

    @SuppressLint("MissingPermission")
    fun startRecording(): Boolean {
        if (isRecording) return true

        if (!initializeEngine()) {
            onError?.invoke("Model is not ready. Please download in Settings.")
            return false
        }

        val minBufSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufSize = (minBufSize * 2).coerceAtLeast(SAMPLE_RATE / 2)

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufSize
            )
            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                onError?.invoke("Failed to initialize AudioRecord")
                return false
            }

            audioRecord?.startRecording()
            isRecording = true
            isProcessing = false
            notifyStateChanged()

            recordingJob = scope.launch(Dispatchers.IO) {
                processAudioLoop()
            }
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error starting AudioRecord: ${e.message}", e)
            onError?.invoke("Microphone error: ${e.message}")
            stopRecording()
            return false
        }
    }

    private suspend fun processAudioLoop() {
        val vadInstance = vad ?: return
        val recognizerInstance = recognizer ?: return
        vadInstance.reset()

        val shortBuffer = ShortArray(WINDOW_SIZE)
        val windowFloats = FloatArray(WINDOW_SIZE)
        val accumulatedSpeech = ArrayList<Float>()
        var speechDetected = false
        var lastDecodeTime = System.currentTimeMillis()
        var lastRecognizedText = ""

        try {
            while (isRecording && audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                val readShorts = audioRecord?.read(shortBuffer, 0, WINDOW_SIZE) ?: -1
                if (readShorts < WINDOW_SIZE) continue

                // Convert 16-bit PCM to normalized float [-1.0f, 1.0f]
                for (i in 0 until WINDOW_SIZE) {
                    windowFloats[i] = shortBuffer[i] / 32768.0f
                }

                vadInstance.acceptWaveform(windowFloats)
                val isSpeechNow = vadInstance.isSpeechDetected()

                if (isSpeechNow) {
                    speechDetected = true
                    for (sample in windowFloats) {
                        accumulatedSpeech.add(sample)
                    }
                } else if (speechDetected) {
                    // Small trailing silence
                    for (sample in windowFloats) {
                        accumulatedSpeech.add(sample)
                    }
                }

                // Incremental simulated streaming: every 250ms during active speech, decode accumulated buffer
                val now = System.currentTimeMillis()
                if (accumulatedSpeech.size >= SAMPLE_RATE / 2 && (now - lastDecodeTime) > 250) {
                    lastDecodeTime = now
                    try {
                        val samples = accumulatedSpeech.toFloatArray()
                        val stream = recognizerInstance.createStream()
                        stream.acceptWaveform(samples, SAMPLE_RATE)
                        recognizerInstance.decode(stream)
                        val partialResult = recognizerInstance.getResult(stream)
                        val text = partialResult.text.trim()
                        stream.release()

                        if (text.isNotEmpty() && text != lastRecognizedText) {
                            lastRecognizedText = text
                            withContext(Dispatchers.Main) {
                                onPartialResult?.invoke(text)
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Partial decode error: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Audio loop error: ${e.message}", e)
        } finally {
            // Finalize transcription when recording stops
            isProcessing = true
            notifyStateChanged()

            var finalText = lastRecognizedText
            try {
                vadInstance.flush()
                while (!vadInstance.empty()) {
                    val seg = vadInstance.front()
                    val segSamples = seg.samples
                    for (s in segSamples) {
                        accumulatedSpeech.add(s)
                    }
                    vadInstance.pop()
                }

                if (accumulatedSpeech.isNotEmpty()) {
                    val finalSamples = accumulatedSpeech.toFloatArray()
                    val stream = recognizerInstance.createStream()
                    stream.acceptWaveform(finalSamples, SAMPLE_RATE)
                    recognizerInstance.decode(stream)
                    val result = recognizerInstance.getResult(stream)
                    val decoded = result.text.trim()
                    stream.release()
                    if (decoded.isNotEmpty()) {
                        finalText = decoded
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Final decode error: ${e.message}", e)
            }

            accumulatedSpeech.clear()
            vadInstance.clear()
            isProcessing = false
            notifyStateChanged()

            if (finalText.isNotEmpty()) {
                withContext(Dispatchers.Main) {
                    prefs.addHistoryItem(finalText)
                    onFinalResult?.invoke(finalText)
                }
            }
        }
    }

    fun stopRecording() {
        if (!isRecording) return
        isRecording = false
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) {
        }
        audioRecord = null
        notifyStateChanged()
    }

    fun reloadVocabulary() {
        // Trigger re-initialization on next run or now
        scope.launch(Dispatchers.IO) {
            loadedModelId = null
            initializeEngine()
        }
    }

    @Synchronized
    fun releaseEngine() {
        stopRecording()
        try {
            recognizer?.release()
        } catch (_: Exception) {
        }
        recognizer = null

        try {
            vad?.release()
        } catch (_: Exception) {
        }
        vad = null
        loadedModelId = null
    }

    private fun notifyStateChanged() {
        scope.launch(Dispatchers.Main) {
            onStateChanged?.invoke(isRecording, isProcessing)
        }
    }
}
