package com.ludo.voicedictation.overlay

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.ComponentCallbacks
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Build
import android.app.KeyguardManager
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.Toast
import com.ludo.voicedictation.R
import com.ludo.voicedictation.accessibility.OverlayDiagnostics
import com.ludo.voicedictation.asr.SpeechEngine
import com.ludo.voicedictation.asr.MicrophoneDevices
import com.ludo.voicedictation.data.AppPreferences
import kotlin.math.abs

class OverlayManager(private val context: Context) {

    companion object {
        private const val TAG = "OverlayManager"
        private const val MICROPHONE_HOLD_MS = 1500L

        @Volatile
        private var instance: OverlayManager? = null

        fun getInstance(context: Context): OverlayManager {
            return instance ?: synchronized(this) {
                instance ?: OverlayManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val prefs = AppPreferences(context)
    private val speechEngine = SpeechEngine.getInstance(context)
    private val transcriptPreview = LiveTranscriptOverlay(context)
    private val preferenceStore = context.getSharedPreferences("voice_dictation_prefs", Context.MODE_PRIVATE)
    private var previewSessionActive = false
    private var previewGeneration = 0L
    private val previewPreferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        if (previewSessionActive && (!prefs.serviceEnabled || !prefs.streamingPreviewEnabled)) stopPreview()
    }

    private var overlayView: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var isAttached = false
    private var diagnosticRequested = "unknown"
    private var diagnosticOutcome = "unknown"

    /** App-owned view state only; it cannot establish whether Android actually displays pixels. */
    internal fun diagnosticState(): String =
        "overlayRequested=$diagnosticRequested overlayOutcome=$diagnosticOutcome attached=$isAttached markedVisible=${overlayView?.visibility?.let { it == View.VISIBLE } ?: "unknown"} pixels=unknown"

    private var hasUncommittedPosition = false
    private var positionIsLandscape = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    private var pulseAnimator: ObjectAnimator? = null
    private var cancelGesture: (() -> Unit)? = null
    private var microphoneDialog: AlertDialog? = null
    private val configurationCallbacks = object : ComponentCallbacks {
        override fun onConfigurationChanged(newConfig: Configuration) {
            overlayView?.post {
                refreshAppearance()
                if (canDisplayPreview()) transcriptPreview.refresh() else transcriptPreview.clear()
            }
        }
        override fun onLowMemory() = Unit
    }

    var onFinalTranscriptionReady: ((String) -> Unit)? = null
    var onLocalDictationStarted: (() -> Unit)? = null
    var onSessionIdle: (() -> Unit)? = null

    init {
        speechEngine.onStateChanged = { recording, processing ->
            if (previewSessionActive && (!speechEngine.isLocalSessionActive ||
                    (!recording && speechEngine.currentStatus != "starting") ||
                    speechEngine.currentStatus == "recording_limit")) stopPreview()
            OverlayDiagnostics.record { "dictation state recording=$recording processing=$processing external=${speechEngine.isExternalSessionActive}" }
            if (recording || processing) microphoneDialog?.dismiss()
            if (speechEngine.isExternalSessionActive && canShowExternalStatus()) showExternalStatus("dictation-state")
            else updateOverlayVisuals(recording, processing)
            if (!recording && !processing) {
                // Run after final insertion, not between state cleanup and delivery.
                overlayView?.post { onSessionIdle?.invoke() }
            }
        }

        speechEngine.onRecordingLimit = {
            val message = "10-minute limit reached. Tap Stop to transcribe, or hold to discard."
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            overlayView?.announceForAccessibility(message)
            com.ludo.voicedictation.util.AutomaticStopCue.play(context, overlayView)
        }
        speechEngine.onFinalResult = { text ->
            onFinalTranscriptionReady?.invoke(text)
        }
        speechEngine.onError = { message ->
            OverlayDiagnostics.record { "dictation error (details excluded)" }
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    fun hasOverlayPermission(): Boolean {
        return Settings.canDrawOverlays(context)
    }

    private fun canShowExternalStatus(): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager ?: return false
        return powerManager.isInteractive && !keyguardManager.isKeyguardLocked
    }

    private fun canDisplayPreview(): Boolean = previewSessionActive && prefs.serviceEnabled &&
        prefs.streamingPreviewEnabled && hasOverlayPermission() && canShowExternalStatus() &&
        speechEngine.isLocalSessionActive && !speechEngine.isExternalSessionActive &&
        speechEngine.isRecording && !speechEngine.isProcessing && speechEngine.currentStatus == "recording" &&
        overlayView?.visibility == View.VISIBLE && isAttached

    /** Subscribe only to the local session started by this overlay, never IPC or History. */
    private fun startPreview() {
        if (!prefs.streamingPreviewEnabled || !speechEngine.isLocalSessionActive) return
        previewSessionActive = true
        val generation = ++previewGeneration
        preferenceStore.registerOnSharedPreferenceChangeListener(previewPreferenceListener)
        speechEngine.onPreviewTextChanged = { text ->
            if (generation == previewGeneration) {
                if (canDisplayPreview()) transcriptPreview.showText(text) else transcriptPreview.clear()
            }
        }
    }

    private fun stopPreview() {
        previewSessionActive = false
        previewGeneration++
        speechEngine.onPreviewTextChanged = null
        preferenceStore.unregisterOnSharedPreferenceChangeListener(previewPreferenceListener)
        transcriptPreview.clear()
    }

    private fun showExternalStatus(trigger: String) {
        val before = try { if (OverlayDiagnostics.isRecording()) diagnosticState() else null } catch (_: Exception) { null }
        show()
        try {
            if (before != null && (before != diagnosticState() || diagnosticOutcome.startsWith("blocked-") || diagnosticOutcome.endsWith("-exception"))) {
                OverlayDiagnostics.decision({ mapOf(trigger to 1L) }) {
                    "Decision: show — external session active\n  Overlay: ${diagnosticState()}"
                }
            }
        } catch (_: Exception) { /* Observational only. */ }
    }

    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        diagnosticRequested = "show"
        diagnosticOutcome = "show-requested"
        if (!prefs.serviceEnabled) {
            stopPreview()
            diagnosticOutcome = "blocked-disabled"
            return
        }
        if (!hasOverlayPermission()) {
            stopPreview()
            diagnosticOutcome = "blocked-permission"
            Log.w(TAG, "Cannot show overlay: SYSTEM_ALERT_WINDOW permission missing")
            return
        }

        if (overlayView == null) {
            try {
                initOverlayView()
            } catch (e: Exception) {
                diagnosticOutcome = "initialize-exception"
                OverlayDiagnostics.record(coalesce = true) { "overlay initialize=exception" }
                throw e
            }
        }

        val orientationChanged = updatePositionForOrientation()
        if (!isAttached && overlayView != null && params != null) {
            applyOverlaySize()
            clampPosition()
            try {
                windowManager.addView(overlayView, params)
                isAttached = true
            } catch (e: Exception) {
                diagnosticOutcome = "attach-exception"
                Log.e(TAG, "Failed to attach overlay window: ${e.message}", e)
                return
            }
        } else if (isAttached && orientationChanged) {
            applyOverlaySize()
            clampPosition()
            try {
                windowManager.updateViewLayout(overlayView, params)
            } catch (e: Exception) {
                diagnosticOutcome = "reposition-exception"
                Log.w(TAG, "Failed to reposition overlay after orientation change", e)
            }
        }

        overlayView?.visibility = View.VISIBLE
        if (diagnosticOutcome == "show-requested") diagnosticOutcome = "show-completed"
        updateOverlayVisuals(speechEngine.isRecording, speechEngine.isProcessing)
    }

    fun hide() {
        diagnosticRequested = "hide"
        stopPreview()
        speechEngine.cancelRecording()
        hideTemporarily()
    }

    /** Hides only the floating view while another app/system surface is active; capture ownership is unchanged. */
    fun hideTemporarily() {
        diagnosticRequested = "hide"
        transcriptPreview.clear()
        cancelGesture?.invoke()
        microphoneDialog?.dismiss()
        pulseAnimator?.cancel()
        overlayView?.keepScreenOn = false
        overlayView?.visibility = View.GONE
        diagnosticOutcome = "hide-completed"
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun initOverlayView() {
        val themeContext = ContextThemeWrapper(context, R.style.Theme_VoiceDictation)
        val inflater = LayoutInflater.from(themeContext)
        overlayView = inflater.inflate(R.layout.overlay_dictation, null)

        positionIsLandscape = currentIsLandscape()
        val savedPosition = prefs.savedOverlayPosition(positionIsLandscape)
        val (initialX, initialY) = savedPosition ?: defaultOverlayPosition()

        val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = initialX
            y = initialY

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        applyOverlaySize()
        context.registerComponentCallbacks(configurationCallbacks)
        setupTouchAndDrag()
    }

    /** Resize layout bounds, not just rendering, to keep the touch region aligned. */
    private fun applyOverlaySize() {
        val root = overlayView ?: return
        val lp = params ?: return
        val density = context.resources.displayMetrics.density
        fun px(dp: Int) = OverlayGeometry.pixels(dp, density, prefs.overlayScalePercent)
        lp.width = px(80)
        lp.height = px(80)
        root.setPadding(0, 0, 0, 0)
        for ((id, dp) in listOf(
            R.id.btnOverlayCircle to 52, R.id.ivOverlayIcon to 26,
            R.id.pbOverlayProcessing to 28, R.id.pulseRing to 56
        )) {
            val child = root.findViewById<View>(id)
            child.layoutParams = child.layoutParams.apply { width = px(dp); height = px(dp) }
        }
    }

    private fun currentIsLandscape(): Boolean =
        context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    /** Default to the right edge and vertically center the visible button, not its top edge. */
    private fun defaultOverlayPosition(): Pair<Int, Int> {
        val dm = context.resources.displayMetrics
        val bounds = displayBounds()
        val windowSize = OverlayGeometry.pixels(80, dm.density, prefs.overlayScalePercent)
        val buttonSize = OverlayGeometry.pixels(52, dm.density, prefs.overlayScalePercent)
        val buttonLeftInset = (windowSize - buttonSize) / 2
        val rightMargin = OverlayGeometry.pixels(4, dm.density, OverlayGeometry.DEFAULT_SCALE)
        val x = bounds.width() - buttonLeftInset - buttonSize - rightMargin
        val y = (bounds.height() - windowSize) / 2
        return x to y
    }

    private fun displayBounds(): android.graphics.Rect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        windowManager.currentWindowMetrics.bounds
    } else {
        @Suppress("DEPRECATION")
        android.graphics.Rect().also { rect ->
            val metrics = android.util.DisplayMetrics()
            windowManager.defaultDisplay.getRealMetrics(metrics)
            rect.set(0, 0, metrics.widthPixels, metrics.heightPixels)
        }
    }

    /** Load the other orientation's saved coordinates, or its centered right-edge default. */
    private fun updatePositionForOrientation(): Boolean {
        val landscape = currentIsLandscape()
        if (landscape == positionIsLandscape) return false
        persistOverlayPositionIfNeeded()
        positionIsLandscape = landscape
        val lp = params ?: return true
        val (x, y) = prefs.savedOverlayPosition(landscape) ?: defaultOverlayPosition()
        lp.x = x
        lp.y = y
        hasUncommittedPosition = false
        return true
    }

    private fun clampPosition() {
        val lp = params ?: return
        val dm = context.resources.displayMetrics
        val bounds = displayBounds()
        val buttonSize = OverlayGeometry.pixels(52, dm.density, prefs.overlayScalePercent)
        lp.x = OverlayGeometry.clampAxis(lp.x, bounds.width(), lp.width, buttonSize, dm.density)
        lp.y = OverlayGeometry.clampAxis(lp.y, bounds.height(), lp.height, buttonSize, dm.density)
    }

    /** Synchronously commit a completed/interrupted drag before its owner can be restarted. */
    private fun persistOverlayPositionIfNeeded() {
        if (!hasUncommittedPosition) return
        val lp = params ?: return
        if (prefs.persistOverlayPosition(lp.x, lp.y, positionIsLandscape)) {
            hasUncommittedPosition = false
        } else {
            Log.w(TAG, "Could not persist floating-button position")
        }
    }

    fun refreshAppearance() {
        if (overlayView == null) return
        updatePositionForOrientation()
        applyOverlaySize()
        clampPosition()
        if (isAttached) {
            try {
                windowManager.updateViewLayout(overlayView, params)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to update overlay appearance", e)
            }
        }
        updateOverlayVisuals(speechEngine.isRecording, speechEngine.isProcessing)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouchAndDrag() {
        val root = overlayView ?: return
        val lp = params ?: return
        val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false
        var touchActive = false
        var consumeRelease = false
        var holdWasDiscard = false
        fun updateDraggedPosition(event: MotionEvent) {
            val oldX = lp.x
            val oldY = lp.y
            // Use raw-coordinate deltas with the original window offset; no status-bar
            // offset belongs in a delta, and the window is laid out in the screen.
            lp.x = initialX + (event.rawX - initialTouchX).toInt()
            lp.y = initialY + (event.rawY - initialTouchY).toInt()
            clampPosition()
            if (lp.x == oldX && lp.y == oldY) return
            try {
                windowManager.updateViewLayout(root, lp)
                hasUncommittedPosition = true
            } catch (e: Exception) {
                lp.x = oldX
                lp.y = oldY
                Log.w(TAG, "Failed to update floating-button position", e)
            }
        }
        val hold = Runnable {
            if (touchActive && !isDragging && !consumeRelease) {
                // Never reinterpret a hold if ownership changed while the finger was down.
                consumeRelease = true
                val discardNow = speechEngine.isLocalSessionActive &&
                    (speechEngine.isRecording || speechEngine.isProcessing) &&
                    !speechEngine.isExternalSessionActive
                if ((holdWasDiscard && discardNow) || (!holdWasDiscard && !speechEngine.isBusy)) {
                    root.performLongClick()
                }
            }
        }
        cancelGesture = {
            root.removeCallbacks(hold)
            persistOverlayPositionIfNeeded()
            touchActive = false
            consumeRelease = true
        }
        root.isFocusable = true
        root.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        root.findViewById<View>(R.id.btnOverlayCircle).importantForAccessibility =
            View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        root.setOnClickListener { onOverlayClicked() }
        root.setOnLongClickListener {
            if (speechEngine.isLocalSessionActive &&
                (speechEngine.isRecording || speechEngine.isProcessing) &&
                !speechEngine.isExternalSessionActive) {
                OverlayDiagnostics.record { "dictation discard requested" }
                stopPreview()
                speechEngine.cancelRecording()
                root.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                root.announceForAccessibility("Dictation discarded")
                Toast.makeText(context, "Dictation discarded", Toast.LENGTH_SHORT).show()
                true
            } else if (!speechEngine.isBusy) {
                root.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                showMicrophoneChooser()
                true
            } else false
        }
        root.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    root.removeCallbacks(hold)
                    initialX = lp.x
                    initialY = lp.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    touchActive = true
                    consumeRelease = false
                    holdWasDiscard = speechEngine.isLocalSessionActive &&
                        (speechEngine.isRecording || speechEngine.isProcessing) &&
                        !speechEngine.isExternalSessionActive
                    if (holdWasDiscard) {
                        root.postDelayed(hold, ViewConfiguration.getLongPressTimeout().toLong())
                    } else if (!speechEngine.isBusy) {
                        root.postDelayed(hold, MICROPHONE_HOLD_MS)
                    }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (touchActive && !consumeRelease) {
                        val dx = event.rawX - initialTouchX
                        val dy = event.rawY - initialTouchY
                        if (!isDragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                            isDragging = true
                            root.removeCallbacks(hold)
                        }
                        if (isDragging) updateDraggedPosition(event)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    root.removeCallbacks(hold)
                    if (touchActive && !consumeRelease) {
                        if (isDragging) {
                            updateDraggedPosition(event)
                            persistOverlayPositionIfNeeded()
                        } else root.performClick()
                    }
                    touchActive = false
                    consumeRelease = true
                    true
                }
                MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> {
                    // Cancel the gesture only, never stop/decode/cancel a recording on touch cancellation.
                    root.removeCallbacks(hold)
                    if (isDragging) persistOverlayPositionIfNeeded()
                    touchActive = false
                    consumeRelease = true
                    true
                }
                else -> true
            }
        }
    }

    /** A separate non-focusable overlay keeps the underlying editor's input focus intact. */
    private fun showMicrophoneChooser() {
        if (!isAttached || !hasOverlayPermission() || speechEngine.isBusy || microphoneDialog != null) return
        val choices = try {
            MicrophoneDevices.available(context)
        } catch (_: SecurityException) {
            Toast.makeText(context, "Android denied microphone device discovery; System default remains available.", Toast.LENGTH_LONG).show()
            emptyList()
        } catch (e: RuntimeException) {
            Log.w(TAG, "Microphone device discovery failed", e)
            Toast.makeText(context, "Microphone discovery failed; System default remains available.", Toast.LENGTH_LONG).show()
            emptyList()
        }
        val selected = prefs.preferredMicrophone
        val selectedIndex = if (selected == null) 0 else choices.indexOfFirst { it.unique && it.key == selected }
            .let { if (it < 0) -1 else it + 1 }
        val labels = arrayOf("System default (automatic)") + choices.map {
            it.label + if (it.unique) "" else " (indistinguishable; unavailable)"
        }
        val title = "Microphone\nAndroid controls routing; device names may not uniquely identify replacements." +
            if (selected != null && selectedIndex < 0) "\nSaved microphone is unavailable." else ""
        val themed = ContextThemeWrapper(context, android.R.style.Theme_Material_Dialog_Alert)
        val dialog = AlertDialog.Builder(themed)
            .setTitle(title)
            .setSingleChoiceItems(labels, selectedIndex) { dialogInterface, which ->
                if (speechEngine.isBusy) {
                    dialogInterface.dismiss()
                } else if (which == 0) {
                    val saved = prefs.persistPreferredMicrophone(null)
                    dialogInterface.dismiss()
                    Toast.makeText(context, if (saved) "Microphone: System default (automatic)"
                        else "Could not save microphone preference for future sessions.", Toast.LENGTH_LONG).show()
                } else {
                    val choice = choices[which - 1]
                    val stillAvailable = try {
                        choice.unique && MicrophoneDevices.resolve(context, choice.key).id == choice.device.id
                    } catch (_: Exception) { false }
                    if (!stillAvailable) {
                        dialogInterface.dismiss()
                        Toast.makeText(context, "Microphone unavailable or indistinguishable. Open the list again.", Toast.LENGTH_LONG).show()
                    } else {
                        val saved = prefs.persistPreferredMicrophone(choice.key)
                        dialogInterface.dismiss()
                        Toast.makeText(context, if (saved) "Microphone preference saved; Android controls routing."
                            else "Could not save microphone preference for future sessions.", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .create()
        dialog.setOnDismissListener { if (microphoneDialog === dialog) microphoneDialog = null }
        dialog.window?.apply {
            setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
            addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
        }
        microphoneDialog = dialog
        try {
            dialog.show()
            dialog.setCanceledOnTouchOutside(true)
        } catch (e: RuntimeException) {
            microphoneDialog = null
            Log.w(TAG, "Could not display microphone chooser", e)
            Toast.makeText(context, "Could not open microphone chooser.", Toast.LENGTH_LONG).show()
        }
    }

    private fun onOverlayClicked() {
        if (!prefs.serviceEnabled || speechEngine.isExternalSessionActive) return
        if (speechEngine.currentStatus == "starting") {
            OverlayDiagnostics.record { "dictation stop requested while starting" }
            stopPreview()
            speechEngine.stopRecording()
            overlayView?.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
            overlayView?.announceForAccessibility("Recording stopped. Transcribing.")
            return
        }
        if (speechEngine.isProcessing) return
        if (speechEngine.isRecording) {
            OverlayDiagnostics.record { "dictation stop requested" }
            stopPreview()
            speechEngine.stopRecording()
            overlayView?.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
            overlayView?.announceForAccessibility("Recording stopped. Transcribing.")
        } else {
            // Retire the prior generation before even attempting a replacement session.
            stopPreview()
            if (speechEngine.startRecording()) {
                startPreview()
                onLocalDictationStarted?.invoke()
                overlayView?.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                overlayView?.announceForAccessibility("Starting recording. Tap to stop and transcribe. Hold to discard.")
            } else {
                OverlayDiagnostics.record { "dictation start rejected" }
            }
        }
    }

    private fun updateOverlayVisuals(isRecording: Boolean, isProcessing: Boolean) {
        val root = overlayView ?: return
        val externalSession = speechEngine.isExternalSessionActive
        root.contentDescription = when {
            externalSession && speechEngine.currentStatus == "polishing" -> "Polishing Hermes dictation"
            externalSession && speechEngine.currentStatus == "loading" -> "Loading model for Hermes dictation"
            externalSession && speechEngine.currentStatus == "processing" -> "Processing Hermes dictation"
            externalSession && speechEngine.currentStatus == "starting" -> "Starting Hermes dictation"
            externalSession && speechEngine.isAwaitingStop -> "Recording limit reached; stop or cancel in Hermes"
            externalSession && isRecording -> "Recording controlled by Hermes"
            externalSession -> "Dictation controlled by Hermes"
            speechEngine.currentStatus == "polishing" -> "Polishing dictated text"
            speechEngine.currentStatus == "starting" -> "Starting recording. Tap to stop and transcribe. Hold to discard."
            speechEngine.currentStatus == "loading" -> "Loading recognition model"
            isProcessing -> "Transcribing recording"
            speechEngine.isAwaitingStop -> "Recording limit reached. Tap to transcribe. Hold to discard."
            isRecording -> "Recording. Tap to stop and transcribe. Hold to discard."
            else -> "Ready to record. Hold for microphone selection."
        }

        root.keepScreenOn = (isRecording || isProcessing) && !speechEngine.isExternalSessionActive &&
            root.visibility == View.VISIBLE
        val btnCircle = root.findViewById<FrameLayout>(R.id.btnOverlayCircle) ?: return
        val ivIcon = root.findViewById<ImageView>(R.id.ivOverlayIcon) ?: return
        val pbProcessing = root.findViewById<ProgressBar>(R.id.pbOverlayProcessing) ?: return
        val pulseRing = root.findViewById<View>(R.id.pulseRing) ?: return
        ivIcon.imageTintList = context.getColorStateList(android.R.color.white)
        pbProcessing.indeterminateTintList = context.getColorStateList(
            if (speechEngine.currentStatus == "polishing") R.color.success else R.color.accent
        )

        val alphaVal = prefs.overlayOpacity / 100f

        if (isProcessing) {
            pulseAnimator?.cancel()
            pulseRing.visibility = View.GONE
            ivIcon.visibility = View.GONE
            pbProcessing.visibility = View.VISIBLE
            btnCircle.setBackgroundResource(R.drawable.bg_overlay_idle)
            root.alpha = 1.0f
        } else if (isRecording) {
            pbProcessing.visibility = View.GONE
            ivIcon.visibility = View.VISIBLE
            ivIcon.setImageResource(if (externalSession) R.drawable.ic_mic else R.drawable.ic_stop)
            val recordingBackground = R.drawable.bg_overlay_recording
            btnCircle.setBackgroundResource(recordingBackground)
            pulseRing.setBackgroundResource(recordingBackground)
            root.alpha = 1.0f

            if (speechEngine.isAwaitingStop) {
                pulseAnimator?.cancel()
                pulseRing.visibility = View.GONE
            } else {
                pulseRing.visibility = View.VISIBLE
                startPulseAnimation(pulseRing)
            }
        } else {
            pulseAnimator?.cancel()
            pulseRing.visibility = View.GONE
            pbProcessing.visibility = View.GONE
            ivIcon.visibility = View.VISIBLE
            ivIcon.setImageResource(R.drawable.ic_mic)
            btnCircle.setBackgroundResource(R.drawable.bg_overlay_idle)
            root.alpha = alphaVal
        }
    }

    private fun startPulseAnimation(view: View) {
        if (pulseAnimator?.isRunning == true) return
        pulseAnimator?.cancel()
        val scaleX = PropertyValuesHolder.ofFloat(View.SCALE_X, 1.0f, 1.4f)
        val scaleY = PropertyValuesHolder.ofFloat(View.SCALE_Y, 1.0f, 1.4f)
        val alpha = PropertyValuesHolder.ofFloat(View.ALPHA, 0.8f, 0.0f)

        pulseAnimator = ObjectAnimator.ofPropertyValuesHolder(view, scaleX, scaleY, alpha).apply {
            duration = 1000
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            start()
        }
    }

    fun destroy() {
        diagnosticRequested = "destroy"
        diagnosticOutcome = "destroy-requested"
        OverlayDiagnostics.record { "overlay destroy requested" }
        stopPreview()
        cancelGesture?.invoke()
        microphoneDialog?.dismiss()
        persistOverlayPositionIfNeeded()
        cancelGesture = null
        if (overlayView != null) context.unregisterComponentCallbacks(configurationCallbacks)
        speechEngine.cancelRecording()
        onFinalTranscriptionReady = null
        onLocalDictationStarted = null
        onSessionIdle = null
        overlayView?.keepScreenOn = false
        pulseAnimator?.cancel()
        if (isAttached && overlayView != null) {
            try {
                windowManager.removeView(overlayView)
                OverlayDiagnostics.record { "overlay detach=accepted" }
            } catch (_: Exception) {
                diagnosticOutcome = "detach-exception"
                OverlayDiagnostics.record { "overlay detach=exception" }
            }
            isAttached = false
        }
        overlayView = null
        if (diagnosticOutcome == "destroy-requested") diagnosticOutcome = "destroy-completed"
    }
}
