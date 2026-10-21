package com.ludo.voicedictation.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.ludo.voicedictation.R
import com.ludo.voicedictation.data.StringReplacementRule

class StringReplacementAdapter(
    private var rules: List<StringReplacementRule>,
    private val onEditClicked: (Int) -> Unit,
    private val onDeleteClicked: (Int) -> Unit
) : RecyclerView.Adapter<StringReplacementAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val summary: TextView = view.findViewById(R.id.tvStringReplacementSummary)
        val edit: TextView = view.findViewById(R.id.tvEditStringReplacement)
        val delete: ImageButton = view.findViewById(R.id.btnDeleteStringReplacement)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_string_replacement, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val rule = rules[position]
        holder.summary.text = "${rule.inputs.joinToString(" · ")}\n→ ${rule.output}"
        holder.edit.setOnClickListener {
            val current = holder.bindingAdapterPosition
            if (current != RecyclerView.NO_POSITION) onEditClicked(current)
        }
        holder.delete.setOnClickListener {
            val current = holder.bindingAdapterPosition
            if (current != RecyclerView.NO_POSITION) onDeleteClicked(current)
        }
    }

    override fun getItemCount(): Int = rules.size

    fun updateRules(newRules: List<StringReplacementRule>) {
        rules = newRules
        notifyDataSetChanged()
    }
}
