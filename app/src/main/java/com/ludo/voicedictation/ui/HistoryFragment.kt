package com.ludo.voicedictation.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.ludo.voicedictation.data.AppPreferences
import com.ludo.voicedictation.databinding.TabHistoryBinding

class HistoryFragment : Fragment() {

    private var _binding: TabHistoryBinding? = null
    private val binding get() = _binding!!

    private lateinit var prefs: AppPreferences
    private lateinit var adapter: HistoryAdapter

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
                prefs.removeHistoryItem(item.id)
                refreshList()
                Toast.makeText(requireContext(), "Deleted history item", Toast.LENGTH_SHORT).show()
            }
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
                .setMessage("Delete all ${history.size} saved transcriptions?")
                .setPositiveButton("Clear All") { _, _ ->
                    prefs.clearHistory()
                    refreshList()
                    Toast.makeText(requireContext(), "History cleared", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun refreshList() {
        val history = prefs.getHistory()
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

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
