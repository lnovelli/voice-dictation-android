package com.ludo.voicedictation.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.provider.Settings
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import android.widget.ScrollView
import android.widget.TextView
import android.widget.LinearLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.ludo.voicedictation.R
import com.ludo.voicedictation.accessibility.DictationAccessibilityService
import com.ludo.voicedictation.accessibility.OverlayDiagnostics
import com.ludo.voicedictation.asr.SpeechEngine
import com.ludo.voicedictation.asr.AdvancedSpeechModel
import com.ludo.voicedictation.asr.RecognitionDiagnostics
import com.ludo.voicedictation.data.AppPreferences
import com.ludo.voicedictation.databinding.TabSettingsBinding
import com.ludo.voicedictation.model.ModelInfo
import com.ludo.voicedictation.model.ModelManager
import com.ludo.voicedictation.overlay.OverlayManager
import com.ludo.voicedictation.update.AppUpdateManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Locale

class SettingsFragment : Fragment() {

    private var _binding: TabSettingsBinding? = null
    private val binding get() = _binding!!

    private lateinit var prefs: AppPreferences
    private lateinit var modelManager: ModelManager
    private lateinit var speechEngine: SpeechEngine

    private var isDownloading = false
    private var mlkitStatus: Int? = null
    private var mlkitMessage = R.string.mlkit_checking
    private var mlkitProgress: String? = null
    private var mlkitDownloading = false
    private var mlkitCheckJob: Job? = null
    private var mlkitCheckGeneration = 0
    private var updatingPreviewToggle = false
    private val mlkitLocaleTags = listOf(
        "ar-SA", "cmn-Hans-CN", "cmn-Hant-TW", "da-DK", "de-DE", "en-US", "es-ES",
        "fr-FR", "hi-IN", "id-ID", "it-IT", "ja-JP", "ko-KR", "nl-NL", "pl-PL",
        "pt-PT", "ru-RU", "sv-SE", "th-TH", "tr-TR", "vi-VN"
    )
    private val diagnosticsRefreshHandler = Handler(Looper.getMainLooper())
    private val diagnosticsRefresh = object : Runnable {
        override fun run() {
            if (_binding == null) return
            updateOverlayDiagnostics()
            diagnosticsRefreshHandler.postDelayed(this, 5_000L)
        }
    }

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

    private val exportSettingsLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) exportSettings(uri)
    }

    private val importSettingsLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null && isAdded) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.settings_backup_import_title)
                .setMessage(R.string.settings_backup_import_warning)
                .setPositiveButton(R.string.settings_backup_import_confirm) { _, _ -> importSettings(uri) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
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

        binding.switchServiceEnabled.isChecked = prefs.serviceEnabled
        binding.switchServiceEnabled.setOnCheckedChangeListener { _, enabled ->
            prefs.serviceEnabled = enabled
            DictationAccessibilityService.instance?.refreshServiceState()
            if (!enabled) speechEngine.releaseEngine()
        }
        setupModelSelection()
        setupPermissions()
        setupSliders()
        setupFocusAndOverlayBehavior()
        setupOverlayDiagnostics()
        setupSettingsBackup()
        setupAppUpdates()
    }

    private fun setupSettingsBackup() {
        binding.btnExportSettings.setOnClickListener {
            exportSettingsLauncher.launch("voice-dictation-settings.json")
        }
        binding.btnImportSettings.setOnClickListener {
            importSettingsLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
        }
    }

    private fun exportSettings(uri: Uri) {
        val context = context ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    val json = AppPreferences(context).exportSettingsJson()
                    val output = context.contentResolver.openOutputStream(uri, "wt")
                        ?: throw IllegalStateException("No output stream")
                    output.use { it.write(json.toByteArray(StandardCharsets.UTF_8)) }
                    true
                } catch (_: Exception) {
                    false
                }
            }
            if (isAdded) Toast.makeText(context,
                if (ok) R.string.settings_backup_export_success else R.string.settings_backup_export_failed,
                Toast.LENGTH_LONG).show()
        }
    }

    private fun importSettings(uri: Uri) {
        val context = context ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) {
                try {
                    val input = context.contentResolver.openInputStream(uri)
                        ?: throw IllegalStateException("No input stream")
                    val bytes = input.use { stream ->
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = stream.read(buffer)
                            if (count < 0) break
                            if (output.size() + count > 2 * 1024 * 1024) {
                                throw IllegalArgumentException("Settings file is too large")
                            }
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray()
                    }
                    StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes)).toString()
                } catch (_: Exception) {
                    null
                }
            }
            if (!isAdded) return@launch
            // Commit and refresh together while this view is active; a cancelled file read cannot
            // silently apply settings after its screen has gone away.
            val result = if (text == null) -1 else try {
                if (AppPreferences(context).importSettingsJson(text)) 1 else 0
            } catch (_: Exception) {
                -1
            }
            if (result < 0) {
                Toast.makeText(context, R.string.settings_backup_import_failed, Toast.LENGTH_LONG).show()
                return@launch
            }
            // A disk error can leave changes in memory or in one of the preference stores.
            if (!prefs.serviceEnabled) speechEngine.releaseEngine()
            DictationAccessibilityService.instance?.refreshServiceState()
            OverlayManager.getInstance(context).refreshAppearance()
            Toast.makeText(context, if (result == 1) R.string.settings_backup_import_success
                else R.string.settings_backup_import_failed, Toast.LENGTH_LONG).show()
            // Start a fresh activity rather than letting saved view state overwrite imported preferences.
            val activity = requireActivity()
            activity.startActivity(Intent(activity, MainActivity::class.java))
            activity.finish()
        }
    }

    override fun onResume() {
        super.onResume()
        updatePermissionsStatus()
        updateModelStatus()
        checkMlkitStatus()
        diagnosticsRefreshHandler.removeCallbacks(diagnosticsRefresh)
        diagnosticsRefresh.run()
    }

    override fun onPause() {
        diagnosticsRefreshHandler.removeCallbacks(diagnosticsRefresh)
        super.onPause()
    }

    private fun setupModelSelection() {
        binding.switchStreamingPreview.isChecked = prefs.streamingPreviewEnabled
        binding.switchStreamingPreview.setOnCheckedChangeListener { _, checked ->
            if (updatingPreviewToggle) return@setOnCheckedChangeListener
            if (checked && !supportsStreamingPreview(prefs.selectedModelId)) {
                binding.switchStreamingPreview.isChecked = false
                binding.tvStreamingPreviewError.visibility = View.VISIBLE
            } else {
                prefs.streamingPreviewEnabled = checked
            }
        }
        val labels = mlkitLocaleTags.map { tag ->
            val locale = Locale.forLanguageTag(tag)
            val beta = tag in setOf("ar-SA", "da-DK", "hi-IN", "id-ID", "nl-NL", "pl-PL", "sv-SE", "tr-TR", "vi-VN")
            "${locale.getDisplayName(Locale.getDefault())} ($tag${if (beta) ", beta" else ""})"
        }
        binding.spinnerMlkitLocale.adapter = ArrayAdapter(
            requireContext(), android.R.layout.simple_spinner_item, labels
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        val localeIndex = mlkitLocaleTags.indexOf(prefs.mlKitLocaleTag).let { if (it >= 0) it else mlkitLocaleTags.indexOf("en-US") }
        binding.spinnerMlkitLocale.setSelection(localeIndex)
        binding.spinnerMlkitLocale.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val tag = mlkitLocaleTags[position]
                if (prefs.mlKitLocaleTag != tag) {
                    prefs.mlKitLocaleTag = tag
                    checkMlkitStatus()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        binding.modelVariants.check(when (prefs.selectedModelId) {
            AppPreferences.MODEL_NEMOTRON_STREAMING_560MS -> R.id.model560ms
            AppPreferences.MODEL_MLKIT_ADVANCED -> R.id.modelMlkitAdvanced
            else -> R.id.model1120ms
        })
        binding.modelVariants.setOnCheckedChangeListener { _, checkedId ->
            val selected = when (checkedId) {
                R.id.model560ms -> AppPreferences.MODEL_NEMOTRON_STREAMING_560MS
                R.id.model1120ms -> AppPreferences.MODEL_NEMOTRON_STREAMING
                R.id.modelMlkitAdvanced -> AppPreferences.MODEL_MLKIT_ADVANCED
                else -> return@setOnCheckedChangeListener
            }
            prefs.selectedModelId = selected
            updateModelStatus()
        }
        binding.btnDownloadModel.setOnClickListener {
            if (prefs.selectedModelId == AppPreferences.MODEL_MLKIT_ADVANCED) startMlkitDownload()
            else startModelDownload()
        }
        binding.btnCheckMlkit.setOnClickListener { checkMlkitStatus() }
        setupMlkitDiagnostics()

        binding.btnDeleteModel.setOnClickListener {
            val model = ModelInfo.getModelById(prefs.selectedModelId)
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Delete Model")
                .setMessage("Delete ${model.displayName} files to free storage?")
                .setPositiveButton("Delete") { _, _ ->
                    viewLifecycleOwner.lifecycleScope.launch {
                        speechEngine.releaseEngine().join()
                        val deleted = withContext(Dispatchers.IO) { modelManager.deleteModel(model) }
                        updateModelStatus()
                        Toast.makeText(requireContext(), if (deleted) "Model deleted" else "Model deletion failed", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        updateModelStatus()
    }

    private fun mlkitDiagnosticsReport(): String {
        val context = requireContext()
        val version = try {
            @Suppress("DEPRECATION")
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            val name = info.versionName?.takeIf { it.matches(Regex("[A-Za-z0-9._+-]{1,40}")) } ?: "unknown"
            @Suppress("DEPRECATION")
            val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()
            "$name ($code)"
        } catch (_: Exception) {
            "unknown"
        }
        return "App version: $version\n\n${RecognitionDiagnostics.report()}"
    }

    private fun setupMlkitDiagnostics() {
        binding.btnMlkitDiagnosticsView.setOnClickListener {
            val context = requireContext()
            val padding = (24 * resources.displayMetrics.density).toInt()
            val text = TextView(context).apply {
                this.text = mlkitDiagnosticsReport()
                setTextIsSelectable(true)
                setPadding(padding, padding / 2, padding, padding / 2)
            }
            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.mlkit_diagnostics_title)
                .setView(ScrollView(context).apply { addView(text) })
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
        binding.btnMlkitDiagnosticsCopy.setOnClickListener {
            val context = requireContext()
            val clip = ClipData.newPlainText(getString(R.string.mlkit_diagnostics_title), mlkitDiagnosticsReport())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                clip.description.extras = PersistableBundle().apply {
                    putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
                }
            }
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(clip)
            Toast.makeText(context, R.string.mlkit_diagnostics_copied, Toast.LENGTH_SHORT).show()
        }
        binding.btnMlkitDiagnosticsClear.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.mlkit_diagnostics_clear_title)
                .setMessage(R.string.mlkit_diagnostics_clear_message)
                .setPositiveButton(R.string.mlkit_diagnostics_clear) { _, _ -> RecognitionDiagnostics.clear() }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun updateModelStatus() {
        if (_binding == null) return
        val supportsPreview = supportsStreamingPreview(prefs.selectedModelId)
        updatingPreviewToggle = true
        binding.switchStreamingPreview.isChecked = supportsPreview && prefs.streamingPreviewEnabled
        updatingPreviewToggle = false
        binding.switchStreamingPreview.isEnabled = supportsPreview
        binding.tvStreamingPreviewError.visibility = if (supportsPreview) View.GONE else View.VISIBLE
        val isMlkit = prefs.selectedModelId == AppPreferences.MODEL_MLKIT_ADVANCED
        binding.sliderAsrThreads.isEnabled = !isMlkit
        binding.tvAsrThreadsLabel.isEnabled = !isMlkit
        binding.tvMlkitLocaleLabel.visibility = if (isMlkit) View.VISIBLE else View.GONE
        binding.spinnerMlkitLocale.visibility = if (isMlkit) View.VISIBLE else View.GONE
        binding.spinnerMlkitLocale.isEnabled = !mlkitDownloading
        binding.modelMlkitAdvanced.isEnabled = !isDownloading && when (mlkitStatus) {
            FeatureStatus.AVAILABLE, FeatureStatus.DOWNLOADABLE, FeatureStatus.DOWNLOADING -> true
            else -> false
        }
        binding.btnCheckMlkit.isEnabled = !mlkitDownloading
        binding.tvMlkitAvailability.text = mlkitProgress ?: getString(mlkitMessage)
        if (isMlkit) {
            binding.tvModelStatus.text = mlkitProgress ?: getString(mlkitMessage)
            binding.tvModelStatus.setTextColor(ContextCompat.getColor(requireContext(),
                if (mlkitStatus == FeatureStatus.AVAILABLE) R.color.success else R.color.warning))
            binding.btnDownloadModel.text = if (mlkitStatus == FeatureStatus.AVAILABLE)
                getString(R.string.status_ready) else getString(R.string.btn_download)
            binding.btnDownloadModel.isEnabled = mlkitStatus == FeatureStatus.DOWNLOADABLE && !mlkitDownloading
            binding.btnDeleteModel.visibility = View.GONE
            return
        }
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

    private fun supportsStreamingPreview(modelId: String): Boolean = when (modelId) {
        AppPreferences.MODEL_NEMOTRON_STREAMING,
        AppPreferences.MODEL_NEMOTRON_STREAMING_560MS -> true
        else -> false
    }

    // Checks update only status, not the current choice. Generations reject superseded results.
    private fun checkMlkitStatus() {
        if (_binding == null || mlkitDownloading) return
        val generation = ++mlkitCheckGeneration
        val localeTag = prefs.mlKitLocaleTag
        mlkitCheckJob?.cancel()
        mlkitStatus = null
        mlkitProgress = null
        mlkitMessage = R.string.mlkit_checking
        updateModelStatus()
        mlkitCheckJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val client = AdvancedSpeechModel(Locale.forLanguageTag(localeTag))
                val status = try { client.checkStatus() } finally { client.close() }
                if (generation != mlkitCheckGeneration || _binding == null) return@launch
                setMlkitStatus(status)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                RecognitionDiagnostics.recordFailure("initialization", e)
                if (generation != mlkitCheckGeneration || _binding == null) return@launch
                mlkitStatus = null
                mlkitMessage = R.string.mlkit_check_failed
                updateModelStatus()
            }
        }
    }

    private fun setMlkitStatus(status: Int) {
        mlkitStatus = status
        mlkitProgress = null
        mlkitMessage = when (status) {
            FeatureStatus.AVAILABLE -> R.string.mlkit_available
            FeatureStatus.DOWNLOADABLE -> R.string.mlkit_downloadable
            FeatureStatus.DOWNLOADING -> R.string.mlkit_downloading
            FeatureStatus.UNAVAILABLE -> R.string.mlkit_unavailable
            else -> R.string.mlkit_check_failed
        }
        updateModelStatus()
    }

    private fun startMlkitDownload() {
        if (mlkitDownloading || mlkitStatus != FeatureStatus.DOWNLOADABLE) return
        val generation = ++mlkitCheckGeneration
        val localeTag = prefs.mlKitLocaleTag
        mlkitCheckJob?.cancel()
        mlkitDownloading = true
        mlkitStatus = FeatureStatus.DOWNLOADING
        mlkitMessage = R.string.mlkit_downloading
        updateModelStatus()
        viewLifecycleOwner.lifecycleScope.launch {
            var failed = false
            try {
                val client = AdvancedSpeechModel(Locale.forLanguageTag(localeTag))
                try {
                    // Revalidate at the side-effect boundary; never download from a stale check.
                    val status = client.checkStatus()
                    if (generation == mlkitCheckGeneration && status == FeatureStatus.DOWNLOADABLE &&
                        prefs.mlKitLocaleTag == localeTag &&
                        prefs.selectedModelId == AppPreferences.MODEL_MLKIT_ADVANCED) {
                        client.download().collect { event ->
                            if (generation == mlkitCheckGeneration && _binding != null) {
                                when (event) {
                                    is DownloadStatus.DownloadProgress -> {
                                        mlkitProgress = getString(R.string.mlkit_download_progress,
                                            Formatter.formatShortFileSize(requireContext(), event.totalBytesDownloaded))
                                        updateModelStatus()
                                    }
                                    is DownloadStatus.DownloadFailed -> {
                                        RecognitionDiagnostics.record("download", "download_failed")
                                        failed = true
                                    }
                                    else -> Unit
                                }
                            }
                        }
                    }
                } finally {
                    client.close()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                RecognitionDiagnostics.recordFailure("download", e)
                failed = true
            } finally {
                if (generation == mlkitCheckGeneration) mlkitDownloading = false
            }
            if (generation != mlkitCheckGeneration || _binding == null) return@launch
            if (failed) {
                mlkitStatus = null
                mlkitProgress = null
                mlkitMessage = R.string.mlkit_download_failed
                updateModelStatus()
            } else {
                checkMlkitStatus()
            }
        }
    }

    private fun startModelDownload() {
        val model = ModelInfo.getModelById(prefs.selectedModelId)
        isDownloading = true
        binding.model560ms.isEnabled = false
        binding.model1120ms.isEnabled = false
        binding.modelMlkitAdvanced.isEnabled = false
        binding.btnDownloadModel.isEnabled = false
        binding.layoutDownloadProgress.visibility = View.VISIBLE
        binding.progressBarModel.progress = 0
        binding.tvProgressPercent.text = "Starting..."

        val downloadScope = viewLifecycleOwner.lifecycleScope
        downloadScope.launch(Dispatchers.IO) {
            val result = modelManager.downloadModel(model) { step, progress ->
                downloadScope.launch(Dispatchers.Main) {
                    binding.progressBarModel.progress = progress
                    binding.tvProgressPercent.text = "$step $progress%"
                    binding.tvModelStatus.text = step
                }
            }

            withContext(Dispatchers.Main) {
                isDownloading = false
                binding.model560ms.isEnabled = true
                binding.model1120ms.isEnabled = true
                binding.layoutDownloadProgress.visibility = View.GONE

                if (result.isSuccess) {
                    Toast.makeText(requireContext(), "${model.displayName} downloaded successfully!", Toast.LENGTH_LONG).show()
                    speechEngine.reloadVocabulary()
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
        if (_binding == null) return

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
        binding.sliderAsrThreads.value = prefs.asrThreadCount.toFloat()
        fun updateAsrThreadsLabel(count: Int) {
            val label = getString(R.string.asr_threads_value, count)
            binding.tvAsrThreadsLabel.text = label
            binding.sliderAsrThreads.contentDescription = label
        }
        updateAsrThreadsLabel(prefs.asrThreadCount)
        binding.sliderAsrThreads.setLabelFormatter { it.toInt().toString() }
        binding.sliderAsrThreads.addOnChangeListener { _, value, fromUser ->
            val count = value.toInt()
            if (fromUser) prefs.asrThreadCount = count
            updateAsrThreadsLabel(count)
        }

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
            OverlayManager.getInstance(requireContext()).refreshAppearance()
        }

        binding.sliderOverlayScale.value = prefs.overlayScalePercent.toFloat()
        binding.tvOverlayScaleLabel.text = getString(R.string.overlay_scale_label, prefs.overlayScalePercent)
        binding.sliderOverlayScale.setLabelFormatter { "${it.toInt()}%" }
        binding.sliderOverlayScale.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                prefs.overlayScalePercent = value.toInt()
                binding.tvOverlayScaleLabel.text = getString(R.string.overlay_scale_label, value.toInt())
                OverlayManager.getInstance(requireContext()).refreshAppearance()
            }
        }
    }

    private fun setupFocusAndOverlayBehavior() {
        binding.switchAlwaysShowOverlay.isChecked = prefs.alwaysShowOverlay
        binding.switchAlwaysShowOverlay.setOnCheckedChangeListener { _, enabled ->
            prefs.alwaysShowOverlay = enabled
            DictationAccessibilityService.instance?.refreshServiceState()
        }
        binding.switchAvoidAutofillCollision.isChecked = prefs.avoidAutofillOverlayCollisions
        binding.switchAvoidAutofillCollision.setOnCheckedChangeListener { _, enabled ->
            prefs.avoidAutofillOverlayCollisions = enabled
            DictationAccessibilityService.instance?.refreshServiceState()
        }
    }

    private fun setupOverlayDiagnostics() {
        binding.btnOverlayDiagnosticsStart.setOnClickListener {
            // The backend deliberately exposes no has-report query. Always warn before replacing it.
            val context = requireContext()
            val padding = (24 * resources.displayMetrics.density).toInt()
            val checkbox = MaterialCheckBox(context).apply {
                setText(R.string.overlay_diagnostics_unverified_opt_in)
                isChecked = false
            }
            val content = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(padding, 0, padding, 0)
                addView(TextView(context).apply { setText(R.string.overlay_diagnostics_start_warning) })
                addView(checkbox)
            }
            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.overlay_diagnostics_start_title)
                .setView(ScrollView(context).apply { addView(content) })
                .setPositiveButton(R.string.overlay_diagnostics_start) { _, _ ->
                    OverlayDiagnostics.start(includeUnverifiedPackages = checkbox.isChecked)
                    captureOverlayDiagnosticsSnapshot()
                    updateOverlayDiagnostics()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
        binding.btnOverlayDiagnosticsStop.setOnClickListener {
            OverlayDiagnostics.stop()
            updateOverlayDiagnostics()
        }
        binding.btnOverlayDiagnosticsClear.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.overlay_diagnostics_clear_title)
                .setMessage(R.string.overlay_diagnostics_clear_warning)
                .setPositiveButton(R.string.overlay_diagnostics_clear) { _, _ ->
                    OverlayDiagnostics.clear()
                    if (OverlayDiagnostics.isRecording()) captureOverlayDiagnosticsSnapshot()
                    updateOverlayDiagnostics()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
        binding.btnOverlayDiagnosticsView.setOnClickListener {
            val context = requireContext()
            val padding = (24 * resources.displayMetrics.density).toInt()
            val text = TextView(context).apply {
                this.text = OverlayDiagnostics.report()
                setTextIsSelectable(true)
                setPadding(padding, padding / 2, padding, padding / 2)
            }
            val scroll = ScrollView(context).apply { addView(text) }
            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.overlay_diagnostics_timeline_title)
                .setView(scroll)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            updateOverlayDiagnostics()
        }
        binding.btnOverlayDiagnosticsCopy.setOnClickListener {
            val context = requireContext()
            val clip = ClipData.newPlainText(
                getString(R.string.overlay_diagnostics_title), OverlayDiagnostics.report()
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                clip.description.extras = PersistableBundle().apply {
                    putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
                }
            }
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(clip)
            Toast.makeText(context, R.string.overlay_diagnostics_copied, Toast.LENGTH_SHORT).show()
            updateOverlayDiagnostics()
        }
        updateOverlayDiagnostics()
    }

    private fun captureOverlayDiagnosticsSnapshot() {
        val service = DictationAccessibilityService.instance
        if (service != null) service.recordInitialDiagnosticsSnapshot()
        else OverlayDiagnostics.snapshot { "initial snapshot: service unavailable" }
    }

    private fun updateOverlayDiagnostics() {
        if (_binding == null) return
        val recording = OverlayDiagnostics.isRecording()
        binding.tvOverlayDiagnosticsStatus.setText(
            if (recording) R.string.overlay_diagnostics_recording else R.string.overlay_diagnostics_not_recording
        )
        binding.btnOverlayDiagnosticsStop.isEnabled = recording
    }

    private fun setupAppUpdates() {
        val currentVersion = AppUpdateManager.getCurrentVersionName(requireContext())
        val currentCode = AppUpdateManager.getCurrentVersionCode(requireContext())
        binding.tvAppVersion.text = "Installed Version: v$currentVersion (Build $currentCode)"

        binding.btnCheckUpdates.setOnClickListener {
            binding.btnCheckUpdates.isEnabled = false
            Toast.makeText(requireContext(), "Checking for updates...", Toast.LENGTH_SHORT).show()

            AppUpdateManager.checkForUpdates(requireContext()) { hasUpdate, remoteCode, remoteName, downloadUrl, notes, sha256, error ->
                if (_binding == null || !isAdded) return@checkForUpdates
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
                                expectedSha256 = sha256,
                                expectedVersionCode = remoteCode,
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
        ++mlkitCheckGeneration
        mlkitCheckJob?.cancel()
        mlkitCheckJob = null
        mlkitStatus = null
        mlkitMessage = R.string.mlkit_checking
        mlkitProgress = null
        mlkitDownloading = false
        isDownloading = false
        super.onDestroyView()
        _binding = null
    }
}
