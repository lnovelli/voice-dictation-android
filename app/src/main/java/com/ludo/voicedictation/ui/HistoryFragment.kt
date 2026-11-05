package com.ludo.voicedictation.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ClipDescription
import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Build
import android.os.PersistableBundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.ludo.voicedictation.R
import com.ludo.voicedictation.data.AppPreferences
import com.ludo.voicedictation.data.HistoryAudioStore
import com.ludo.voicedictation.data.HistoryItem
import com.ludo.voicedictation.asr.HistoryVadMode
import com.ludo.voicedictation.asr.SpeechEngine
import com.ludo.voicedictation.asr.TranscriptPolisher
import com.ludo.voicedictation.asr.TranscriptPolishingConfiguration
import com.ludo.voicedictation.databinding.TabHistoryBinding
import com.ludo.voicedictation.model.ModelInfo
import java.lang.ref.WeakReference

class HistoryFragment : Fragment() {

    private var _binding: TabHistoryBinding? = null
    private val binding get() = _binding!!

    private lateinit var prefs: AppPreferences
    private lateinit var adapter: HistoryAdapter
    private var mediaPlayer: MediaPlayer? = null
    private var playingAudioItemId: String? = null
    private val reprocessOwner = Any()
    private var reprocessingItemId: String? = null
    private var reprocessCancelling = false
    private var reprocessVadMode: HistoryVadMode? = null
    // Deliberately fragment-scoped: never persist raw diagnostic text in saved state.
    private val vadDiagnostics = mutableMapOf<String, String>()
    private var fragmentDestroyed = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = TabHistoryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = AppPreferences(requireContext())

        setupRecyclerView()
        setupClearHistory()
        refreshList()
    }

    override fun onResume() {
        super.onResume()
        refreshList()
    }

    private fun setupRecyclerView() {
        adapter = HistoryAdapter(
            items = emptyList(),
            onCopyClicked = { item ->
                val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("Dictated Text", item.text)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(requireContext(), "Copied to clipboard", Toast.LENGTH_SHORT).show()
            },
            onDeleteClicked = { item ->
                if (playingAudioItemId == item.id) stopAudioPlayback()
                if (reprocessingItemId == item.id) cancelAudioReprocess()
                prefs.removeHistoryItem(item.id)
                vadDiagnostics.remove(item.id)
                refreshList()
                Toast.makeText(requireContext(), "Transcription and recording deleted", Toast.LENGTH_SHORT).show()
            },
            onPlayAudioClicked = ::toggleAudioPlayback,
            onReprocessAudioClicked = ::requestAudioReprocess,
            onCopyDiagnosticsClicked = { diagnostics ->
                val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText(getString(R.string.history_vad_diagnostics_title), diagnostics)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    clip.description.extras = PersistableBundle().apply {
                        putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
                    }
                }
                clipboard.setPrimaryClip(clip)
                Toast.makeText(requireContext(), R.string.history_vad_diagnostics_copied, Toast.LENGTH_SHORT).show()
            },
            hasAudio = { item -> HistoryAudioStore.find(requireContext(), item.audioFileName) != null }
        )

        binding.rvHistory.layoutManager = LinearLayoutManager(requireContext())
        binding.rvHistory.adapter = adapter
        adapter.setReprocessingItemId(reprocessingItemId, reprocessCancelling, reprocessVadMode)
    }

    private fun setupClearHistory() {
        binding.btnClearHistory.setOnClickListener {
            val history = prefs.getHistory()
            if (history.isEmpty()) return@setOnClickListener

            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Clear History")
                .setMessage("Delete all ${history.size} saved transcriptions and their recordings?")
                .setPositiveButton("Clear All") { _, _ ->
                    stopAudioPlayback()
                    if (reprocessingItemId != null) cancelAudioReprocess()
                    prefs.clearHistory()
                    vadDiagnostics.clear()
                    refreshList()
                    Toast.makeText(requireContext(), "History and recordings cleared", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun toggleAudioPlayback(item: HistoryItem) {
        if (playingAudioItemId == item.id) {
            stopAudioPlayback()
            return
        }
        stopAudioPlayback()
        val audioFile = HistoryAudioStore.find(requireContext(), item.audioFileName)
        if (audioFile == null) {
            Toast.makeText(requireContext(), R.string.history_audio_unavailable, Toast.LENGTH_SHORT).show()
            refreshList()
            return
        }

        val player = MediaPlayer()
        mediaPlayer = player
        playingAudioItemId = item.id
        adapter.setPlayingItemId(item.id)
        player.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        player.setOnCompletionListener { finishAudioPlayback(player) }
        player.setOnErrorListener { _, _, _ ->
            if (mediaPlayer === player) {
                finishAudioPlayback(player)
                showPlaybackError()
            }
            true
        }
        player.setOnPreparedListener { prepared ->
            if (mediaPlayer !== player || _binding == null) {
                runCatching { prepared.release() }
            } else {
                runCatching { prepared.start() }.onFailure {
                    finishAudioPlayback(player)
                    showPlaybackError()
                }
            }
        }
        try {
            player.setDataSource(audioFile.absolutePath)
            player.prepareAsync()
        } catch (_: Exception) {
            finishAudioPlayback(player)
            showPlaybackError()
        }
    }

    private fun requestAudioReprocess(item: HistoryItem, mode: HistoryVadMode?) {
        if (reprocessingItemId != null) {
            if (reprocessingItemId == item.id && reprocessVadMode == mode) cancelAudioReprocess()
            return
        }
        if (mode != null && !isVadReprocessModelSupported()) {
            Toast.makeText(requireContext(), R.string.history_audio_reprocess_vad_model_required, Toast.LENGTH_LONG).show()
            return
        }
        val polishingConfiguration = if (prefs.transcriptPolishingEnabled) {
            TranscriptPolishingConfiguration(
                baseUrl = prefs.transcriptPolishingEndpoint,
                model = prefs.transcriptPolishingModel,
                vocabulary = prefs.getVocabulary().toSet(),
                systemPrompt = prefs.customTranscriptPolishingSystemPrompt ?: TranscriptPolisher.SYSTEM_PROMPT
            )
        } else null
        val message = if (polishingConfiguration != null) {
            getString(
                R.string.history_audio_reprocess_polish_warning,
                polishingConfiguration.baseUrl,
                polishingConfiguration.model,
                polishingConfiguration.vocabulary.size
            )
        } else getString(R.string.history_audio_reprocess_confirm)
        val action = when (mode) {
            null -> R.string.history_audio_reprocess
            HistoryVadMode.STANDARD -> R.string.history_audio_reprocess_vad
            HistoryVadMode.REMOVE_SILENCE -> R.string.history_audio_reprocess_vad_remove_silence
        }
        val variantExplanation = when (mode) {
            HistoryVadMode.REMOVE_SILENCE -> getString(R.string.history_audio_reprocess_vad_remove_silence_explanation)
            else -> null
        }
        val confirmation = listOfNotNull(
            if (mode != null) getString(R.string.history_audio_reprocess_vad_explanation) else null,
            variantExplanation,
            message
        ).joinToString("\n\n")
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(action)
            .setMessage(confirmation)
            .setPositiveButton(R.string.history_audio_reprocess) { _, _ ->
                beginAudioReprocess(item, polishingConfiguration, mode)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun beginAudioReprocess(
        item: HistoryItem,
        polishingConfiguration: TranscriptPolishingConfiguration?,
        mode: HistoryVadMode?
    ) {
        if (reprocessingItemId != null) return
        if (mode != null && !isVadReprocessModelSupported()) {
            Toast.makeText(requireContext(), R.string.history_audio_reprocess_vad_model_required, Toast.LENGTH_LONG).show()
            return
        }
        val audioFileName = item.audioFileName
        if (audioFileName == null || HistoryAudioStore.find(requireContext(), audioFileName) == null) {
            Toast.makeText(requireContext(), R.string.history_audio_unavailable, Toast.LENGTH_SHORT).show()
            refreshList()
            return
        }
        stopAudioPlayback()
        if (mode != null) {
            vadDiagnostics.remove(item.id)
            refreshList()
        }
        reprocessingItemId = item.id
        reprocessCancelling = false
        reprocessVadMode = mode
        adapter.setReprocessingItemId(item.id, mode = mode)

        val preferences = prefs
        val fragment = WeakReference(this)
        val listener = object : SpeechEngine.ExternalListener {
            override fun onState(state: String) = Unit

            override fun onHistoryVadDiagnostics(diagnostics: String) {
                fragment.get()?.receiveVadDiagnostics(item.id, diagnostics)
            }

            override fun onFinished(text: String?, error: String?) {
                val updated = text?.let { preferences.updateHistoryTranscript(item.id, it) } == true
                fragment.get()?.finishAudioReprocess(item.id, updated, text != null, error)
            }
        }
        val authorizedPolishing = polishingConfiguration?.takeIf { prefs.transcriptPolishingEnabled }
        if (!SpeechEngine.getInstance(requireContext()).startHistoryReprocess(
                reprocessOwner,
                audioFileName,
                listener,
                authorizedPolishing,
                useVad = mode != null,
                vadMode = mode ?: HistoryVadMode.STANDARD
            )) {
            reprocessingItemId = null
            reprocessCancelling = false
            reprocessVadMode = null
            adapter.setReprocessingItemId(null)
            Toast.makeText(requireContext(), R.string.history_audio_reprocess_unavailable, Toast.LENGTH_LONG).show()
        }
    }

    private fun receiveVadDiagnostics(itemId: String, diagnostics: String) {
        // Callbacks run on the main thread. A retry may finish after a row was deleted or trimmed.
        if (fragmentDestroyed || reprocessCancelling || reprocessingItemId != itemId || reprocessVadMode == null ||
            prefs.getHistory().none { it.id == itemId }) return
        vadDiagnostics[itemId] = diagnostics
        if (_binding != null && isAdded) refreshList()
    }

    private fun cancelAudioReprocess() {
        val itemId = reprocessingItemId ?: return
        if (reprocessCancelling) return
        reprocessCancelling = true
        if (reprocessVadMode != null) {
            vadDiagnostics.remove(itemId)
            if (_binding != null && isAdded) refreshList()
        }
        if (::adapter.isInitialized && _binding != null) {
            adapter.setReprocessingItemId(itemId, cancelling = true, mode = reprocessVadMode)
        }
        SpeechEngine.getInstance(requireContext()).cancelHistoryReprocess(reprocessOwner)
    }

    private fun finishAudioReprocess(itemId: String, updated: Boolean, hadText: Boolean, error: String?) {
        if (reprocessingItemId == itemId) {
            reprocessingItemId = null
            reprocessCancelling = false
            reprocessVadMode = null
            if (::adapter.isInitialized && _binding != null) adapter.setReprocessingItemId(null)
        }
        if (_binding != null && isAdded) {
            val message = when {
                updated -> R.string.history_audio_reprocess_done
                hadText -> null // The history entry was deleted before completion.
                error != null -> R.string.history_audio_reprocess_failed
                else -> null // User cancelled; preserve the current transcript.
            }
            message?.let { Toast.makeText(requireContext(), it, Toast.LENGTH_LONG).show() }
            if (::adapter.isInitialized) refreshList()
        }
    }

    private fun isVadReprocessModelSupported(): Boolean {
        val modelId = ModelInfo.getModelById(prefs.selectedModelId).id
        return modelId == AppPreferences.MODEL_PARAKEET_V2 || modelId == AppPreferences.MODEL_PARAKEET_V3
    }

    private fun finishAudioPlayback(player: MediaPlayer) {
        if (mediaPlayer !== player) {
            runCatching { player.release() }
            return
        }
        mediaPlayer = null
        playingAudioItemId = null
        runCatching { player.release() }
        if (::adapter.isInitialized && _binding != null) adapter.setPlayingItemId(null)
    }

    private fun stopAudioPlayback() {
        val player = mediaPlayer
        mediaPlayer = null
        playingAudioItemId = null
        if (player != null) {
            runCatching { if (player.isPlaying) player.stop() }
            runCatching { player.release() }
        }
        if (::adapter.isInitialized && _binding != null) adapter.setPlayingItemId(null)
    }

    private fun showPlaybackError() {
        if (_binding != null && isAdded) {
            Toast.makeText(requireContext(), R.string.history_audio_error, Toast.LENGTH_SHORT).show()
        }
    }

    private fun refreshList() {
        val history = prefs.getHistory()
        val historyIds = history.map { it.id }.toSet()
        vadDiagnostics.keys.retainAll(historyIds)
        if (playingAudioItemId != null && history.none { it.id == playingAudioItemId }) {
            stopAudioPlayback()
        }
        adapter.updateItems(history, vadDiagnostics)
        binding.tvHistoryHeader.text = "Transcription History (${history.size}/${prefs.historyLimit})"

        if (history.isEmpty()) {
            binding.rvHistory.visibility = View.GONE
            binding.tvEmptyHistory.visibility = View.VISIBLE
            binding.btnClearHistory.visibility = View.GONE
        } else {
            binding.rvHistory.visibility = View.VISIBLE
            binding.tvEmptyHistory.visibility = View.GONE
            binding.btnClearHistory.visibility = View.VISIBLE
        }
    }

    override fun onPause() {
        stopAudioPlayback()
        super.onPause()
    }

    override fun onDestroyView() {
        stopAudioPlayback()
        binding.rvHistory.adapter = null
        if (::adapter.isInitialized) adapter.updateItems(emptyList(), emptyMap())
        _binding = null
        super.onDestroyView()
    }

    override fun onDestroy() {
        // The owner token is fragment-scoped; do not leave an uncancellable retry behind.
        fragmentDestroyed = true
        vadDiagnostics.clear()
        if (::adapter.isInitialized) adapter.updateItems(emptyList(), emptyMap())
        if (reprocessingItemId != null) cancelAudioReprocess()
        super.onDestroy()
    }
}
