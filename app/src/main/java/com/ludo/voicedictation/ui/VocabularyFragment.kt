package com.ludo.voicedictation.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.ludo.voicedictation.R
import com.ludo.voicedictation.asr.SpeechEngine
import com.ludo.voicedictation.data.AppPreferences
import com.ludo.voicedictation.databinding.TabVocabularyBinding
import java.util.Locale

class VocabularyFragment : Fragment() {

    private var _binding: TabVocabularyBinding? = null
    private val binding get() = _binding!!

    private lateinit var prefs: AppPreferences
    private lateinit var adapter: VocabularyAdapter
    private lateinit var speechEngine: SpeechEngine

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = TabVocabularyBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val context = requireContext()
        prefs = AppPreferences(context)
        speechEngine = SpeechEngine.getInstance(context)

        setupRecyclerView()
        setupAddWord()
        setupClearAll()
        setupAcousticControls()
        refreshList()
    }

    private fun setupAcousticControls() {
        binding.switchAcousticVocabulary.isChecked = prefs.acousticVocabularyEnabled
        binding.sliderMaxActivePaths.value = prefs.acousticMaxActivePaths.toFloat()
        binding.sliderHotwordsScore.value = prefs.acousticHotwordsScore

        fun updateLabels() {
            binding.tvMaxActivePaths.text = getString(
                R.string.max_active_paths_value, prefs.acousticMaxActivePaths
            )
            binding.tvHotwordsScore.text = getString(
                R.string.hotwords_score_value,
                String.format(Locale.ROOT, "%.1f", prefs.acousticHotwordsScore)
            )

        }
        fun updateSliderEnabled(enabled: Boolean) {
            binding.sliderMaxActivePaths.isEnabled = enabled
            binding.sliderHotwordsScore.isEnabled = enabled

        }

        updateLabels()
        updateSliderEnabled(prefs.acousticVocabularyEnabled)
        binding.switchAcousticVocabulary.setOnCheckedChangeListener { _, enabled ->
            prefs.acousticVocabularyEnabled = enabled
            updateSliderEnabled(enabled)
        }
        binding.sliderMaxActivePaths.addOnChangeListener { _, value, fromUser ->
            if (fromUser) prefs.acousticMaxActivePaths = value.toInt()
            updateLabels()
        }
        binding.sliderHotwordsScore.addOnChangeListener { _, value, fromUser ->
            if (fromUser) prefs.acousticHotwordsScore = value
            updateLabels()
        }

    }

    private fun setupRecyclerView() {
        adapter = VocabularyAdapter(emptyList()) { word ->
            prefs.removeWord(word)
            speechEngine.reloadVocabulary()
            refreshList()
            Toast.makeText(requireContext(), "Removed '$word'", Toast.LENGTH_SHORT).show()
        }
        binding.rvVocabulary.layoutManager = LinearLayoutManager(requireContext())
        binding.rvVocabulary.adapter = adapter
    }

    private fun setupAddWord() {
        val addAction = {
            val text = binding.etNewWord.text?.toString()?.trim() ?: ""
            if (text.isNotEmpty()) {
                prefs.addWord(text)
                binding.etNewWord.text?.clear()
                speechEngine.reloadVocabulary()
                refreshList()
                Toast.makeText(requireContext(), "Added '$text' to vocabulary", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnAddWord.setOnClickListener {
            addAction()
        }

        binding.etNewWord.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                addAction()
                true
            } else false
        }
    }

    private fun setupClearAll() {
        binding.tvClearVocabulary.setOnClickListener {
            val vocab = prefs.getVocabulary()
            if (vocab.isEmpty()) return@setOnClickListener

            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Clear Vocabulary")
                .setMessage("Remove all ${vocab.size} custom terms?")
                .setPositiveButton("Clear All") { _, _ ->
                    prefs.clearVocabulary()
                    speechEngine.reloadVocabulary()
                    refreshList()
                    Toast.makeText(requireContext(), "Custom vocabulary cleared", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun refreshList() {
        val words = prefs.getVocabulary().sorted()
        adapter.updateWords(words)
        binding.tvVocabularyCount.text = "Custom Terms (${words.size})"

        if (words.isEmpty()) {
            binding.rvVocabulary.visibility = View.GONE
            binding.tvEmptyVocabulary.visibility = View.VISIBLE
            binding.tvClearVocabulary.visibility = View.GONE
        } else {
            binding.rvVocabulary.visibility = View.VISIBLE
            binding.tvEmptyVocabulary.visibility = View.GONE
            binding.tvClearVocabulary.visibility = View.VISIBLE
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
