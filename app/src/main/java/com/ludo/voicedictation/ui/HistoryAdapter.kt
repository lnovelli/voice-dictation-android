package com.ludo.voicedictation.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.ludo.voicedictation.R
import com.ludo.voicedictation.asr.HistoryVadMode
import com.ludo.voicedictation.data.HistoryItem
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal class HistoryAdapter(
    private var items: List<HistoryItem>,
    private val onCopyClicked: (HistoryItem) -> Unit,
    private val onDeleteClicked: (HistoryItem) -> Unit,
    private val onPlayAudioClicked: (HistoryItem) -> Unit,
    private val onReprocessAudioClicked: (HistoryItem, HistoryVadMode?) -> Unit,
    private val onCopyDiagnosticsClicked: (String) -> Unit,
    private val hasAudio: (HistoryItem) -> Boolean
) : RecyclerView.Adapter<HistoryAdapter.ViewHolder>() {

    private val dateFormat = SimpleDateFormat("MMM d, HH:mm:ss", Locale.getDefault())
    private var playingItemId: String? = null
    private var reprocessingItemId: String? = null
    private var reprocessCancelling = false
    private var reprocessVadMode: HistoryVadMode? = null
    private var diagnosticsByItemId: Map<String, String> = emptyMap()
    private val expandedDiagnosticsIds = mutableSetOf<String>()

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvTimestamp: TextView = view.findViewById(R.id.tvHistoryTimestamp)
        val tvText: TextView = view.findViewById(R.id.tvHistoryText)
        val btnCopy: ImageButton = view.findViewById(R.id.btnCopyHistory)
        val btnDelete: ImageButton = view.findViewById(R.id.btnDeleteHistory)
        val btnPlayAudio: MaterialButton = view.findViewById(R.id.btnPlayHistoryAudio)
        val btnReprocessAudio: MaterialButton = view.findViewById(R.id.btnReprocessHistoryAudio)
        val btnReprocessVad: MaterialButton = view.findViewById(R.id.btnReprocessHistoryAudioVad)
        val btnReprocessVadRemoveSilence: MaterialButton = view.findViewById(R.id.btnReprocessHistoryAudioVadRemoveSilence)
        val tvReprocessStatus: TextView = view.findViewById(R.id.tvHistoryReprocessStatus)
        val diagnosticsSection: View = view.findViewById(R.id.historyVadDiagnosticsSection)
        val tvDiagnostics: TextView = view.findViewById(R.id.tvHistoryVadDiagnostics)
        val btnCopyDiagnostics: MaterialButton = view.findViewById(R.id.btnCopyHistoryVadDiagnostics)
        val btnToggleDiagnostics: MaterialButton = view.findViewById(R.id.btnToggleHistoryVadDiagnostics)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_history, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.tvTimestamp.text = dateFormat.format(Date(item.timestamp))
        holder.tvText.text = item.text
        holder.btnCopy.setOnClickListener { onCopyClicked(item) }
        holder.btnDelete.setOnClickListener { onDeleteClicked(item) }
        val available = hasAudio(item)
        val reprocessing = reprocessingItemId == item.id
        holder.btnPlayAudio.isEnabled = available && reprocessingItemId == null
        holder.btnPlayAudio.text = when {
            !available -> holder.itemView.context.getString(R.string.history_audio_unavailable)
            playingItemId == item.id -> holder.itemView.context.getString(R.string.history_audio_stop)
            else -> holder.itemView.context.getString(R.string.history_audio_play)
        }
        holder.btnPlayAudio.setOnClickListener { if (available && reprocessingItemId == null) onPlayAudioClicked(item) }

        fun bindReprocessButton(button: MaterialButton, mode: HistoryVadMode?, label: Int) {
            val active = reprocessing && reprocessVadMode == mode
            button.isEnabled = if (active) !reprocessCancelling else available && reprocessingItemId == null
            button.text = holder.itemView.context.getString(when {
                active && reprocessCancelling -> R.string.history_audio_reprocessing
                active -> R.string.history_audio_reprocess_cancel
                else -> label
            })
            button.contentDescription = if (active) {
                holder.itemView.context.getString(
                    if (reprocessCancelling) R.string.history_audio_reprocess_cancelling_accessibility
                    else R.string.history_audio_reprocess_cancel_accessibility,
                    holder.itemView.context.getString(label)
                )
            } else null
            button.setOnClickListener {
                if ((active && !reprocessCancelling) || (available && reprocessingItemId == null)) {
                    onReprocessAudioClicked(item, mode)
                }
            }
        }
        bindReprocessButton(holder.btnReprocessAudio, null, R.string.history_audio_reprocess)
        bindReprocessButton(holder.btnReprocessVad, HistoryVadMode.STANDARD, R.string.history_audio_reprocess_vad)
        bindReprocessButton(holder.btnReprocessVadRemoveSilence, HistoryVadMode.REMOVE_SILENCE, R.string.history_audio_reprocess_vad_remove_silence)

        holder.tvReprocessStatus.visibility = if (reprocessing) View.VISIBLE else View.GONE
        if (reprocessing) {
            val label = when (reprocessVadMode) {
                null -> R.string.history_audio_reprocess
                HistoryVadMode.STANDARD -> R.string.history_audio_reprocess_vad
                HistoryVadMode.REMOVE_SILENCE -> R.string.history_audio_reprocess_vad_remove_silence
            }
            holder.tvReprocessStatus.text = holder.itemView.context.getString(
                if (reprocessCancelling) R.string.history_audio_reprocess_cancelling_accessibility
                else R.string.history_audio_reprocess_progress_variant,
                holder.itemView.context.getString(label)
            )
        }
        val diagnostics = diagnosticsByItemId[item.id]
        val expanded = diagnostics != null && item.id in expandedDiagnosticsIds
        holder.diagnosticsSection.visibility = if (diagnostics != null) View.VISIBLE else View.GONE
        holder.tvDiagnostics.visibility = if (expanded) View.VISIBLE else View.GONE
        holder.tvDiagnostics.text = if (expanded) diagnostics else ""
        holder.btnCopyDiagnostics.setOnClickListener {
            diagnosticsByItemId[item.id]?.let(onCopyDiagnosticsClicked)
        }
        holder.btnToggleDiagnostics.setText(
            if (expanded) R.string.history_vad_diagnostics_hide else R.string.history_vad_diagnostics_show
        )
        holder.btnToggleDiagnostics.setOnClickListener {
            if (diagnosticsByItemId[item.id] != null) {
                if (!expandedDiagnosticsIds.add(item.id)) expandedDiagnosticsIds.remove(item.id)
                val currentPosition = holder.bindingAdapterPosition
                if (currentPosition != RecyclerView.NO_POSITION) notifyItemChanged(currentPosition)
            }
        }
    }

    override fun getItemCount(): Int = items.size

    fun updateItems(newItems: List<HistoryItem>, diagnostics: Map<String, String>) {
        items = newItems
        diagnosticsByItemId = diagnostics.toMap()
        expandedDiagnosticsIds.retainAll(diagnosticsByItemId.keys)
        notifyDataSetChanged()
    }

    fun setPlayingItemId(id: String?) {
        if (playingItemId == id) return
        playingItemId = id
        notifyDataSetChanged()
    }

    fun setReprocessingItemId(id: String?, cancelling: Boolean = false, mode: HistoryVadMode? = null) {
        if (reprocessingItemId == id && reprocessCancelling == cancelling && reprocessVadMode == mode) return
        reprocessingItemId = id
        reprocessCancelling = cancelling
        reprocessVadMode = mode
        notifyDataSetChanged()
    }
}
