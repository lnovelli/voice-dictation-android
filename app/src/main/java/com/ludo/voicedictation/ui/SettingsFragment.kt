package com.ludo.voicedictation.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.ludo.voicedictation.R
import com.ludo.voicedictation.accessibility.DictationAccessibilityService
import com.ludo.voicedictation.asr.SpeechEngine
import com.ludo.voicedictation.data.AppPreferences
import com.ludo.voicedictation.databinding.TabSettingsBinding
import com.ludo.voicedictation.model.ModelInfo
import com.ludo.voicedictation.model.ModelManager
import com.ludo.voicedictation.update.AppUpdateManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsFragment : Fragment() {

    private var _binding: TabSettingsBinding? = null
    private val binding get() = _binding!!

    private lateinit var prefs: AppPreferences
    private lateinit var modelManager: ModelManager
    private lateinit var speechEngine: SpeechEngine

    private var isDownloading = false

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        updatePermissionsStatus()
        if (granted) {
            Toast.makeText(requireContext(), "Microphone permission granted", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(requireContext(), "Microphone permission is required for dictation", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = TabSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val context = requireContext()
        prefs = AppPreferences(context)
        modelManager = ModelManager(context)
        speechEngine = SpeechEngine.getInstance(context)

        setupModelSelection()
        setupPermissions()
        setupSliders()
        setupAppUpdates()
    }

    override fun onResume() {
        super.onResume()
        updatePermissionsStatus()
        updateModelStatus()
    }

    private fun setupModelSelection() {
        if (prefs.selectedModelId == AppPreferences.MODEL_PARAKEET_V3) {
            binding.rbModelV3.isChecked = true
        } else {
            binding.rbModelV2.isChecked = true
        }

        binding.rgModelSelection.setOnCheckedChangeListener { _, checkedId ->
            if (isDownloading) return@setOnCheckedChangeListener
            val selected = if (checkedId == R.id.rbModelV3) {
                AppPreferences.MODEL_PARAKEET_V3
            } else {
                AppPreferences.MODEL_PARAKEET_V2
            }
            prefs.selectedModelId = selected
            updateModelStatus()
        }

        binding.btnDownloadModel.setOnClickListener {
            startModelDownload()
        }

        binding.btnDeleteModel.setOnClickListener {
            val model = ModelInfo.getModelById(prefs.selectedModelId)
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Delete Model")
                .setMessage("Delete ${model.displayName} files to free storage?")
                .setPositiveButton("Delete") { _, _ ->
                    speechEngine.releaseEngine()
                    modelManager.deleteModel(model)
                    updateModelStatus()
                    Toast.makeText(requireContext(), "Model deleted", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        updateModelStatus()
    }

    private fun updateModelStatus() {
        val model = ModelInfo.getModelById(prefs.selectedModelId)
        val downloaded = modelManager.isModelDownloaded(model)

        if (downloaded) {
            binding.tvModelStatus.text = getString(R.string.status_ready)
            binding.tvModelStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.success))
            binding.btnDownloadModel.text = "Model Ready"
            binding.btnDownloadModel.isEnabled = false
            binding.btnDeleteModel.visibility = View.VISIBLE
        } else {
            binding.tvModelStatus.text = getString(R.string.status_not_downloaded)
            binding.tvModelStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.warning))
            binding.btnDownloadModel.text = getString(R.string.btn_download)
            binding.btnDownloadModel.isEnabled = !isDownloading
            binding.btnDeleteModel.visibility = View.GONE
        }
    }

    private fun startModelDownload() {
        val model = ModelInfo.getModelById(prefs.selectedModelId)
        isDownloading = true
        binding.btnDownloadModel.isEnabled = false
        binding.rgModelSelection.isEnabled = false
        binding.rbModelV2.isEnabled = false
        binding.rbModelV3.isEnabled = false
        binding.layoutDownloadProgress.visibility = View.VISIBLE
        binding.progressBarModel.progress = 0
        binding.tvProgressPercent.text = "Starting..."

        lifecycleScope.launch(Dispatchers.IO) {
            val result = modelManager.downloadModel(model) { step, progress ->
                lifecycleScope.launch(Dispatchers.Main) {
                    binding.progressBarModel.progress = progress
                    binding.tvProgressPercent.text = "$step $progress%"
                    binding.tvModelStatus.text = step
                }
            }

            withContext(Dispatchers.Main) {
                isDownloading = false
                binding.layoutDownloadProgress.visibility = View.GONE
                binding.rgModelSelection.isEnabled = true
                binding.rbModelV2.isEnabled = true
                binding.rbModelV3.isEnabled = true

                if (result.isSuccess) {
                    Toast.makeText(requireContext(), "${model.displayName} downloaded successfully!", Toast.LENGTH_LONG).show()
                    speechEngine.initializeEngine()
                } else {
                    val err = result.exceptionOrNull()?.message ?: "Unknown error"
                    Toast.makeText(requireContext(), "Download failed: $err", Toast.LENGTH_LONG).show()
                }
                updateModelStatus()
            }
        }
    }

    private fun setupPermissions() {
        binding.btnGrantAccessibility.setOnClickListener {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent)
        }

        binding.btnGrantOverlay.setOnClickListener {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${requireContext().packageName}")
            ).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent)
        }

        binding.btnGrantRecord.setOnClickListener {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }

        updatePermissionsStatus()
    }

    private fun updatePermissionsStatus() {
        val context = context ?: return

        // Accessibility Service
        val a11yActive = DictationAccessibilityService.isServiceRunning()
        if (a11yActive) {
            binding.btnGrantAccessibility.text = getString(R.string.status_enabled)
            binding.btnGrantAccessibility.isEnabled = false
        } else {
            binding.btnGrantAccessibility.text = getString(R.string.btn_grant)
            binding.btnGrantAccessibility.isEnabled = true
        }

        // Overlay Permission
        val overlayActive = Settings.canDrawOverlays(context)
        if (overlayActive) {
            binding.btnGrantOverlay.text = getString(R.string.status_enabled)
            binding.btnGrantOverlay.isEnabled = false
        } else {
            binding.btnGrantOverlay.text = getString(R.string.btn_grant)
            binding.btnGrantOverlay.isEnabled = true
        }

        // Microphone Permission
        val micGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (micGranted) {
            binding.btnGrantRecord.text = getString(R.string.status_enabled)
            binding.btnGrantRecord.isEnabled = false
        } else {
            binding.btnGrantRecord.text = getString(R.string.btn_grant)
            binding.btnGrantRecord.isEnabled = true
        }
    }

    private fun setupSliders() {
        // Retention limit
        binding.sliderHistoryLimit.value = prefs.historyLimit.toFloat()
        binding.tvHistoryLimitLabel.text = "History retention limit: ${prefs.historyLimit} transcriptions"
        binding.sliderHistoryLimit.addOnChangeListener { _, value, _ ->
            val limit = value.toInt()
            prefs.historyLimit = limit
            binding.tvHistoryLimitLabel.text = "History retention limit: $limit transcriptions"
        }

        // Overlay opacity
        binding.sliderOverlayOpacity.value = prefs.overlayOpacity.toFloat()
        binding.tvOverlayOpacityLabel.text = "Overlay opacity: ${prefs.overlayOpacity}%"
        binding.sliderOverlayOpacity.addOnChangeListener { _, value, _ ->
            val opacity = value.toInt()
            prefs.overlayOpacity = opacity
            binding.tvOverlayOpacityLabel.text = "Overlay opacity: $opacity%"
        }

        // Hotwords score
        binding.sliderHotwordsScore.value = prefs.hotwordsScore
        binding.tvHotwordsScoreLabel.text = "Context biasing score: ${String.format("%.1f", prefs.hotwordsScore)}"
        binding.sliderHotwordsScore.addOnChangeListener { _, value, _ ->
            prefs.hotwordsScore = value
            binding.tvHotwordsScoreLabel.text = "Context biasing score: ${String.format("%.1f", value)}"
            speechEngine.reloadVocabulary()
        }
    }

    private fun setupAppUpdates() {
        val currentVersion = AppUpdateManager.getCurrentVersionName(requireContext())
        val currentCode = AppUpdateManager.getCurrentVersionCode(requireContext())
        binding.tvAppVersion.text = "Installed Version: v$currentVersion (Build $currentCode)"

        binding.btnCheckUpdates.setOnClickListener {
            binding.btnCheckUpdates.isEnabled = false
            Toast.makeText(requireContext(), "Checking for updates...", Toast.LENGTH_SHORT).show()

            AppUpdateManager.checkForUpdates(requireContext()) { hasUpdate, remoteCode, remoteName, downloadUrl, notes, error ->
                binding.btnCheckUpdates.isEnabled = true
                if (error != null) {
                    Toast.makeText(requireContext(), error, Toast.LENGTH_LONG).show()
                    return@checkForUpdates
                }

                if (hasUpdate) {
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle("Update Available")
                        .setMessage("A new build (v$remoteName, Build $remoteCode) is available.\n\nRelease notes:\n$notes")
                        .setPositiveButton("Download & Install") { _, _ ->
                            Toast.makeText(requireContext(), "Downloading update...", Toast.LENGTH_SHORT).show()
                            AppUpdateManager.downloadAndPromptInstall(
                                context = requireContext(),
                                downloadUrl = downloadUrl,
                                onError = { msg ->
                                    Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
                                }
                            )
                        }
                        .setNegativeButton("Later", null)
                        .show()
                } else {
                    Toast.makeText(requireContext(), "You are on the latest version!", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
