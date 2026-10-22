package com.ludo.voicedictation.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.ludo.voicedictation.R
import com.ludo.voicedictation.data.HistoryItem
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoryAdapter(
    private var items: List<HistoryItem>,
    private val onCopyClicked: (HistoryItem) -> Unit,
    private val onDeleteClicked: (HistoryItem) -> Unit,
    private val onPlayAudioClicked: (HistoryItem) -> Unit,
    private val onReprocessAudioClicked: (HistoryItem) -> Unit,
    private val hasAudio: (HistoryItem) -> Boolean
) : RecyclerView.Adapter<HistoryAdapter.ViewHolder>() {

    private val dateFormat = SimpleDateFormat("MMM d, HH:mm:ss", Locale.getDefault())
    private var playingItemId: String? = null
    private var reprocessingItemId: String? = null
    private var reprocessCancelling = false

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvTimestamp: TextView = view.findViewById(R.id.tvHistoryTimestamp)
        val tvText: TextView = view.findViewById(R.id.tvHistoryText)
        val btnCopy: ImageButton = view.findViewById(R.id.btnCopyHistory)
        val btnDelete: ImageButton = view.findViewById(R.id.btnDeleteHistory)
        val btnPlayAudio: MaterialButton = view.findViewById(R.id.btnPlayHistoryAudio)
        val btnReprocessAudio: MaterialButton = view.findViewById(R.id.btnReprocessHistoryAudio)
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

        holder.btnReprocessAudio.isEnabled = available &&
            (reprocessingItemId == null || (reprocessing && !reprocessCancelling))
        holder.btnReprocessAudio.text = when {
            !available -> holder.itemView.context.getString(R.string.history_audio_unavailable)
            reprocessing && reprocessCancelling -> holder.itemView.context.getString(R.string.history_audio_reprocessing)
            reprocessing -> holder.itemView.context.getString(R.string.history_audio_reprocess_cancel)
            else -> holder.itemView.context.getString(R.string.history_audio_reprocess)
        }
        holder.btnReprocessAudio.setOnClickListener {
            if (available && (reprocessingItemId == null || (reprocessing && !reprocessCancelling))) {
                onReprocessAudioClicked(item)
            }
        }
    }

    override fun getItemCount(): Int = items.size

    fun updateItems(newItems: List<HistoryItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    fun setPlayingItemId(id: String?) {
        if (playingItemId == id) return
        playingItemId = id
        notifyDataSetChanged()
    }

    fun setReprocessingItemId(id: String?, cancelling: Boolean = false) {
        if (reprocessingItemId == id && reprocessCancelling == cancelling) return
        reprocessingItemId = id
        reprocessCancelling = cancelling
        notifyDataSetChanged()
    }
}
