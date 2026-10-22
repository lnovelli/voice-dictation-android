package com.ludo.voicedictation.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.ludo.voicedictation.R
import com.ludo.voicedictation.asr.TranscriptPolisher
import com.ludo.voicedictation.data.AppPreferences
import com.ludo.voicedictation.databinding.TabPolishingBinding
import kotlinx.coroutines.launch

class PolishingFragment : Fragment() {
    private data class FormConfiguration(
        val endpoint: String,
        val model: String,
        val systemPrompt: String
    )

    private var _binding: TabPolishingBinding? = null
    private val binding get() = _binding!!
    private lateinit var prefs: AppPreferences
    private val polisher = TranscriptPolisher()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = TabPolishingBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = AppPreferences(requireContext())
        binding.switchPolishingEnabled.isChecked = prefs.transcriptPolishingEnabled
        binding.etPolishingEndpoint.setText(prefs.transcriptPolishingEndpoint)
        binding.etPolishingModel.setText(prefs.transcriptPolishingModel)
        binding.etPolishingPrompt.setText(
            prefs.customTranscriptPolishingSystemPrompt ?: TranscriptPolisher.SYSTEM_PROMPT
        )

        binding.switchPolishingEnabled.setOnCheckedChangeListener { _, enabled ->
            prefs.transcriptPolishingEnabled = enabled
        }
        binding.btnSavePolishing.setOnClickListener { saveConfiguration() }
        binding.btnTestPolishing.setOnClickListener { testConnection() }
        binding.btnResetPolishingPrompt.setOnClickListener {
            prefs.customTranscriptPolishingSystemPrompt = null
            binding.etPolishingPrompt.setText(TranscriptPolisher.SYSTEM_PROMPT)
            binding.tilPolishingPrompt.error = null
            setStatus(getString(R.string.polishing_prompt_restored), isError = false)
        }
    }

    private fun currentConfiguration(): FormConfiguration? {
        val endpoint = binding.etPolishingEndpoint.text?.toString()?.trim().orEmpty()
        val model = binding.etPolishingModel.text?.toString()?.trim().orEmpty()
        val systemPrompt = binding.etPolishingPrompt.text?.toString().orEmpty()
        binding.tilPolishingEndpoint.error = TranscriptPolisher.validateBaseUrl(endpoint)
        binding.tilPolishingModel.error = TranscriptPolisher.validateModel(model)
        binding.tilPolishingPrompt.error = TranscriptPolisher.validateSystemPrompt(systemPrompt)
        if (binding.tilPolishingEndpoint.error != null || binding.tilPolishingModel.error != null ||
            binding.tilPolishingPrompt.error != null) {
            setStatus(getString(R.string.polishing_invalid_config), isError = true)
            return null
        }
        return FormConfiguration(endpoint, model, systemPrompt)
    }

    private fun saveConfiguration(): FormConfiguration? {
        val configuration = currentConfiguration() ?: return null
        prefs.transcriptPolishingEndpoint = configuration.endpoint
        prefs.transcriptPolishingModel = configuration.model
        prefs.customTranscriptPolishingSystemPrompt = configuration.systemPrompt
            .takeUnless { it == TranscriptPolisher.SYSTEM_PROMPT }
        setStatus(getString(R.string.polishing_saved), isError = false)
        return configuration
    }

    private fun testConnection() {
        val configuration = saveConfiguration() ?: return
        binding.btnTestPolishing.isEnabled = false
        binding.btnSavePolishing.isEnabled = false
        setStatus(getString(R.string.polishing_testing), isError = false)
        viewLifecycleOwner.lifecycleScope.launch {
            val error = polisher.testConnection(configuration.endpoint, configuration.model)
            val currentBinding = _binding ?: return@launch
            currentBinding.btnTestPolishing.isEnabled = true
            currentBinding.btnSavePolishing.isEnabled = true
            if (error == null) {
                setStatus(getString(R.string.polishing_test_success), isError = false)
            } else {
                setStatus(error, isError = true)
            }
        }
    }

    private fun setStatus(message: String, isError: Boolean) {
        val currentBinding = _binding ?: return
        currentBinding.tvPolishingStatus.text = message
        val color = if (isError) R.color.warning else R.color.success
        currentBinding.tvPolishingStatus.setTextColor(ContextCompat.getColor(requireContext(), color))
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
