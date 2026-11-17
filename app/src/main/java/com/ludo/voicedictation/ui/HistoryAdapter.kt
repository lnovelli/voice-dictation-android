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
import java.text.NumberFormat
import java.util.Date
import java.util.Locale

internal class HistoryAdapter(
    private var items: List<HistoryItem>,
    private val onCopyClicked: (HistoryItem) -> Unit,
    private val onDeleteClicked: (HistoryItem) -> Unit,
    private val onPlayAudioClicked: (HistoryItem) -> Unit,
    private val onPlaybackSpeedClicked: (Float) -> Unit,
    private val onReprocessAudioClicked: (HistoryItem) -> Unit,
    private val hasAudio: (HistoryItem) -> Boolean
) : RecyclerView.Adapter<HistoryAdapter.ViewHolder>() {

    private val dateFormat = SimpleDateFormat("MMM d, HH:mm:ss", Locale.getDefault())
    private var playingItemId: String? = null
    private var playbackSpeed = 1f
    private var playbackReady = false
    private var reprocessingItemId: String? = null
    private var reprocessCancelling = false
    private var durations: Map<String, Double?> = emptyMap()
    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvTimestamp: TextView = view.findViewById(R.id.tvHistoryTimestamp)
        val tvDuration: TextView = view.findViewById(R.id.tvHistoryDuration)
        val tvText: TextView = view.findViewById(R.id.tvHistoryText)
        val btnCopy: ImageButton = view.findViewById(R.id.btnCopyHistory)
        val btnDelete: ImageButton = view.findViewById(R.id.btnDeleteHistory)
        val btnPlayAudio: MaterialButton = view.findViewById(R.id.btnPlayHistoryAudio)
        val btnSpeed1: MaterialButton = view.findViewById(R.id.btnHistorySpeed1)
        val btnSpeed15: MaterialButton = view.findViewById(R.id.btnHistorySpeed15)
        val btnSpeed2: MaterialButton = view.findViewById(R.id.btnHistorySpeed2)
        val btnReprocessAudio: MaterialButton = view.findViewById(R.id.btnReprocessHistoryAudio)
        val tvReprocessStatus: TextView = view.findViewById(R.id.tvHistoryReprocessStatus)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_history, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.tvTimestamp.text = dateFormat.format(Date(item.timestamp))
        val duration = durations[item.id]
        holder.tvDuration.text = when {
            !durations.containsKey(item.id) -> holder.itemView.context.getString(R.string.history_audio_duration_loading)
            duration == null -> holder.itemView.context.getString(R.string.history_audio_duration_unavailable)
            else -> holder.itemView.context.getString(R.string.history_audio_duration, NumberFormat.getNumberInstance().apply {
                maximumFractionDigits = if (duration < 0.01) 5 else 2
            }.format(duration))
        }
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

        fun bindSpeedButton(button: MaterialButton, speed: Float, label: Int) {
            val playing = playingItemId == item.id
            button.visibility = if (playing) View.VISIBLE else View.GONE
            button.isEnabled = playing && playbackReady
            val speedLabel = holder.itemView.context.getString(label)
            val selected = playbackSpeed == speed
            button.text = if (selected) holder.itemView.context.getString(R.string.history_audio_speed_selected, speedLabel) else speedLabel
            button.contentDescription = holder.itemView.context.getString(
                if (selected) R.string.history_audio_speed_selected_accessibility else R.string.history_audio_speed_accessibility,
                speedLabel
            )
            button.setOnClickListener { if (playing && playbackReady) onPlaybackSpeedClicked(speed) }
        }
        bindSpeedButton(holder.btnSpeed1, 1f, R.string.history_audio_speed_1)
        bindSpeedButton(holder.btnSpeed15, 1.5f, R.string.history_audio_speed_15)
        bindSpeedButton(holder.btnSpeed2, 2f, R.string.history_audio_speed_2)

        fun bindReprocessButton(button: MaterialButton, label: Int) {
            val active = reprocessing
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
                    onReprocessAudioClicked(item)
                }
            }
        }
        bindReprocessButton(holder.btnReprocessAudio, R.string.history_audio_reprocess)
        holder.tvReprocessStatus.visibility = if (reprocessing) View.VISIBLE else View.GONE
        if (reprocessing) {
            holder.tvReprocessStatus.text = holder.itemView.context.getString(
                if (reprocessCancelling) R.string.history_audio_reprocess_cancelling_accessibility
                else R.string.history_audio_reprocess_progress_variant,
                holder.itemView.context.getString(R.string.history_audio_reprocess)
            )
        }
    }

    override fun getItemCount(): Int = items.size

    fun updateItems(newItems: List<HistoryItem>) {
        items = newItems
        durations = emptyMap()
        notifyDataSetChanged()
    }

    fun updateDurations(values: Map<String, Double?>) {
        durations = values
        notifyDataSetChanged()
    }

    fun setPlayingItemId(id: String?) {
        if (playingItemId == id) return
        playingItemId = id
        playbackSpeed = 1f
        playbackReady = false
        notifyDataSetChanged()
    }

    fun setPlaybackState(speed: Float, ready: Boolean) {
        if (playbackSpeed == speed && playbackReady == ready) return
        playbackSpeed = speed
        playbackReady = ready
        notifyDataSetChanged()
    }

    fun setReprocessingItemId(id: String?, cancelling: Boolean = false) {
        if (reprocessingItemId == id && reprocessCancelling == cancelling) return
        reprocessingItemId = id
        reprocessCancelling = cancelling
        notifyDataSetChanged()
    }
}
