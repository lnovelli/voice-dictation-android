package com.ludo.voicedictation.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.ludo.voicedictation.R
import com.ludo.voicedictation.asr.SpeechEngine
import com.ludo.voicedictation.data.AppPreferences
import com.ludo.voicedictation.data.StringReplacementRule
import com.ludo.voicedictation.data.StringReplacementRules
import com.ludo.voicedictation.databinding.TabVocabularyBinding
import java.util.Locale

class VocabularyFragment : Fragment() {

    private var _binding: TabVocabularyBinding? = null
    private val binding get() = _binding!!

    private lateinit var prefs: AppPreferences
    private lateinit var adapter: VocabularyAdapter
    private lateinit var replacementAdapter: StringReplacementAdapter
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
        setupStringReplacements()
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

    private fun setupStringReplacements() {
        replacementAdapter = StringReplacementAdapter(
            emptyList(),
            onEditClicked = { index -> showStringReplacementDialog(index) },
            onDeleteClicked = { index -> removeStringReplacement(index) }
        )
        binding.rvStringReplacements.layoutManager = LinearLayoutManager(requireContext())
        binding.rvStringReplacements.adapter = replacementAdapter
        binding.btnAddStringReplacement.setOnClickListener { showStringReplacementDialog() }
        binding.tvClearStringReplacements.setOnClickListener {
            val rules = prefs.getStringReplacementRules()
            if (rules.isEmpty()) return@setOnClickListener
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.string_replacement_clear_title)
                .setMessage(getString(R.string.string_replacement_clear_message, rules.size))
                .setPositiveButton(R.string.string_replacement_clear_action) { _, _ ->
                    prefs.setStringReplacementRules(emptyList())
                    refreshStringReplacements()
                    Toast.makeText(requireContext(), R.string.string_replacement_cleared, Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
        refreshStringReplacements()
    }

    private fun showStringReplacementDialog(editIndex: Int? = null) {
        val currentRules = prefs.getStringReplacementRules()
        val existing = editIndex?.let(currentRules::getOrNull)
        if (editIndex != null && existing == null) return
        if (editIndex == null && currentRules.size >= StringReplacementRules.MAX_RULES) {
            Toast.makeText(requireContext(), R.string.string_replacement_limit_error, Toast.LENGTH_LONG).show()
            return
        }

        val dialogView = layoutInflater.inflate(R.layout.dialog_string_replacement, null)
        val inputsLayout = dialogView.findViewById<TextInputLayout>(R.id.tilStringReplacementInputs)
        val outputLayout = dialogView.findViewById<TextInputLayout>(R.id.tilStringReplacementOutput)
        val inputsField = dialogView.findViewById<TextInputEditText>(R.id.etStringReplacementInputs)
        val outputField = dialogView.findViewById<TextInputEditText>(R.id.etStringReplacementOutput)
        inputsField.setText(existing?.inputs?.joinToString("\n").orEmpty())
        outputField.setText(existing?.output.orEmpty())

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(if (editIndex == null) R.string.string_replacement_add_dialog else R.string.string_replacement_edit_dialog)
            .setView(dialogView)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.string_replacement_save, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                inputsLayout.error = null
                outputLayout.error = null
                val rawInputs = inputsField.text?.toString().orEmpty().lines().filter { it.isNotBlank() }
                val inputs = rawInputs.map(StringReplacementRules::normalizeInput)
                if (inputs.isEmpty() || inputs.size > StringReplacementRules.MAX_INPUTS_PER_RULE ||
                    inputs.any { it == null }) {
                    inputsLayout.error = getString(R.string.string_replacement_inputs_error)
                    return@setOnClickListener
                }
                val normalizedInputs = inputs.filterNotNull()
                val output = StringReplacementRules.normalizeOutput(outputField.text?.toString().orEmpty())
                if (output == null) {
                    outputLayout.error = getString(R.string.string_replacement_output_error)
                    return@setOnClickListener
                }

                val inputKeys = normalizedInputs.map { it.lowercase(Locale.ROOT) }
                if (inputKeys.distinct().size != inputKeys.size) {
                    inputsLayout.error = getString(R.string.string_replacement_duplicate_error)
                    return@setOnClickListener
                }
                val otherRules = currentRules.filterIndexed { index, _ -> index != editIndex }
                val existingKeys = otherRules.flatMap { it.inputs }
                    .mapTo(mutableSetOf()) { it.lowercase(Locale.ROOT) }
                if (inputKeys.any { it in existingKeys }) {
                    inputsLayout.error = getString(R.string.string_replacement_duplicate_error)
                    return@setOnClickListener
                }
                val totalInputs = otherRules.sumOf { it.inputs.size } + normalizedInputs.size
                if (otherRules.size + 1 > StringReplacementRules.MAX_RULES ||
                    totalInputs > StringReplacementRules.MAX_TOTAL_INPUTS) {
                    inputsLayout.error = getString(R.string.string_replacement_limit_error)
                    return@setOnClickListener
                }

                val updated = otherRules.toMutableList()
                val rule = StringReplacementRule(normalizedInputs, output)
                if (editIndex == null) updated.add(rule) else updated.add(editIndex, rule)
                prefs.setStringReplacementRules(updated)
                refreshStringReplacements()
                Toast.makeText(requireContext(), R.string.string_replacement_saved, Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun removeStringReplacement(index: Int) {
        val rules = prefs.getStringReplacementRules().toMutableList()
        if (index !in rules.indices) return
        rules.removeAt(index)
        prefs.setStringReplacementRules(rules)
        refreshStringReplacements()
        Toast.makeText(requireContext(), R.string.string_replacement_deleted, Toast.LENGTH_SHORT).show()
    }

    private fun refreshStringReplacements() {
        val rules = prefs.getStringReplacementRules()
        replacementAdapter.updateRules(rules)
        binding.tvStringReplacementCount.text = getString(R.string.string_replacement_count, rules.size)
        val hasRules = rules.isNotEmpty()
        binding.rvStringReplacements.visibility = if (hasRules) View.VISIBLE else View.GONE
        binding.tvEmptyStringReplacements.visibility = if (hasRules) View.GONE else View.VISIBLE
        binding.tvClearStringReplacements.visibility = if (hasRules) View.VISIBLE else View.GONE
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
