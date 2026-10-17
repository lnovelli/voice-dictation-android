package com.ludo.voicedictation.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.ludo.voicedictation.R
import com.ludo.voicedictation.data.HistoryItem
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoryAdapter(
    private var items: List<HistoryItem>,
    private val onCopyClicked: (HistoryItem) -> Unit,
    private val onDeleteClicked: (HistoryItem) -> Unit
) : RecyclerView.Adapter<HistoryAdapter.ViewHolder>() {

    private val dateFormat = SimpleDateFormat("MMM d, HH:mm:ss", Locale.getDefault())

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvTimestamp: TextView = view.findViewById(R.id.tvHistoryTimestamp)
        val tvText: TextView = view.findViewById(R.id.tvHistoryText)
        val btnCopy: ImageButton = view.findViewById(R.id.btnCopyHistory)
        val btnDelete: ImageButton = view.findViewById(R.id.btnDeleteHistory)
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
    }

    override fun getItemCount(): Int = items.size

    fun updateItems(newItems: List<HistoryItem>) {
        items = newItems
        notifyDataSetChanged()
    }
}
