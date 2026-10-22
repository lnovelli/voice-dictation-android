package com.ludo.voicedictation.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Bundle
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
import com.ludo.voicedictation.asr.SpeechEngine
import com.ludo.voicedictation.asr.TranscriptPolishingConfiguration
import com.ludo.voicedictation.databinding.TabHistoryBinding
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
                refreshList()
                Toast.makeText(requireContext(), "Transcription and recording deleted", Toast.LENGTH_SHORT).show()
            },
            onPlayAudioClicked = ::toggleAudioPlayback,
            onReprocessAudioClicked = ::requestAudioReprocess,
            hasAudio = { item -> HistoryAudioStore.find(requireContext(), item.audioFileName) != null }
        )

        binding.rvHistory.layoutManager = LinearLayoutManager(requireContext())
        binding.rvHistory.adapter = adapter
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

    private fun requestAudioReprocess(item: HistoryItem) {
        if (reprocessingItemId != null) {
            if (reprocessingItemId == item.id) cancelAudioReprocess()
            return
        }
        val polishingConfiguration = if (prefs.transcriptPolishingEnabled) {
            TranscriptPolishingConfiguration(
                baseUrl = prefs.transcriptPolishingEndpoint,
                model = prefs.transcriptPolishingModel,
                vocabulary = prefs.getVocabulary().toSet()
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
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.history_audio_reprocess)
            .setMessage(message)
            .setPositiveButton(R.string.history_audio_reprocess) { _, _ ->
                beginAudioReprocess(item, polishingConfiguration)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun beginAudioReprocess(
        item: HistoryItem,
        polishingConfiguration: TranscriptPolishingConfiguration?
    ) {
        val audioFileName = item.audioFileName
        if (audioFileName == null || HistoryAudioStore.find(requireContext(), audioFileName) == null) {
            Toast.makeText(requireContext(), R.string.history_audio_unavailable, Toast.LENGTH_SHORT).show()
            refreshList()
            return
        }
        stopAudioPlayback()
        reprocessingItemId = item.id
        reprocessCancelling = false
        adapter.setReprocessingItemId(item.id)

        val preferences = prefs
        val fragment = WeakReference(this)
        val listener = object : SpeechEngine.ExternalListener {
            override fun onState(state: String) = Unit

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
                authorizedPolishing
            )) {
            reprocessingItemId = null
            reprocessCancelling = false
            adapter.setReprocessingItemId(null)
            Toast.makeText(requireContext(), R.string.history_audio_reprocess_unavailable, Toast.LENGTH_LONG).show()
        }
    }

    private fun cancelAudioReprocess() {
        val itemId = reprocessingItemId ?: return
        if (reprocessCancelling) return
        reprocessCancelling = true
        adapter.setReprocessingItemId(itemId, cancelling = true)
        SpeechEngine.getInstance(requireContext()).cancelHistoryReprocess(reprocessOwner)
    }

    private fun finishAudioReprocess(itemId: String, updated: Boolean, hadText: Boolean, error: String?) {
        if (reprocessingItemId == itemId) {
            reprocessingItemId = null
            reprocessCancelling = false
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
        if (playingAudioItemId != null && history.none { it.id == playingAudioItemId }) {
            stopAudioPlayback()
        }
        adapter.updateItems(history)
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
        _binding = null
        super.onDestroyView()
    }
}
