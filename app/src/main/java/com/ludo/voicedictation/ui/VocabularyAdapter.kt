package com.ludo.voicedictation.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.ludo.voicedictation.R

class VocabularyAdapter(
    private var words: List<String>,
    private val onDeleteClicked: (String) -> Unit
) : RecyclerView.Adapter<VocabularyAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvWord: TextView = view.findViewById(R.id.tvWord)
        val btnDelete: ImageButton = view.findViewById(R.id.btnDeleteWord)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_vocabulary, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val word = words[position]
        holder.tvWord.text = word
        holder.btnDelete.setOnClickListener {
            onDeleteClicked(word)
        }
    }

    override fun getItemCount(): Int = words.size

    fun updateWords(newWords: List<String>) {
        words = newWords
        notifyDataSetChanged()
    }
}
