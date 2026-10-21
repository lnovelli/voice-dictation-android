package com.ludo.voicedictation.overlay

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.ComponentCallbacks
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
import com.ludo.voicedictation.asr.SpeechEngine
import com.ludo.voicedictation.data.AppPreferences
import kotlin.math.abs

class OverlayManager(private val context: Context) {

    companion object {
        private const val TAG = "OverlayManager"

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

    private var overlayView: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var isAttached = false
    private var hasUncommittedPosition = false
    private var pulseAnimator: ObjectAnimator? = null
    private var cancelGesture: (() -> Unit)? = null
    private val configurationCallbacks = object : ComponentCallbacks {
        override fun onConfigurationChanged(newConfig: Configuration) {
            overlayView?.post { refreshAppearance() }
        }
        override fun onLowMemory() = Unit
    }

    var onFinalTranscriptionReady: ((String) -> Unit)? = null
    var onSessionIdle: (() -> Unit)? = null

    init {
        speechEngine.onStateChanged = { recording, processing ->
            if (speechEngine.isExternalSessionActive && canShowExternalStatus()) show()
            else updateOverlayVisuals(recording, processing)
            if (!recording && !processing) {
                // Run after final insertion, not between state cleanup and delivery.
                overlayView?.post { onSessionIdle?.invoke() }
            }
        }
        speechEngine.onRecordingLimit = {
            val message = "120-second limit reached. Tap Stop to transcribe, or hold to discard."
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            overlayView?.announceForAccessibility(message)
            overlayView?.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        }
        speechEngine.onFinalResult = { text ->
            onFinalTranscriptionReady?.invoke(text)
        }
        speechEngine.onError = { message ->
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

    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        if (!prefs.serviceEnabled) return
        if (!hasOverlayPermission()) {
            Log.w(TAG, "Cannot show overlay: SYSTEM_ALERT_WINDOW permission missing")
            return
        }

        if (overlayView == null) {
            initOverlayView()
        }

        if (!isAttached && overlayView != null && params != null) {
            applyOverlaySize()
            clampPosition()
            try {
                windowManager.addView(overlayView, params)
                isAttached = true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to attach overlay window: ${e.message}", e)
                return
            }
        }

        overlayView?.visibility = View.VISIBLE
        updateOverlayVisuals(speechEngine.isRecording, speechEngine.isProcessing)
    }

    fun hide() {
        cancelGesture?.invoke()
        speechEngine.cancelRecording()
        pulseAnimator?.cancel()
        overlayView?.keepScreenOn = false
        overlayView?.visibility = View.GONE
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun initOverlayView() {
        val themeContext = ContextThemeWrapper(context, R.style.Theme_VoiceDictation)
        val inflater = LayoutInflater.from(themeContext)
        overlayView = inflater.inflate(R.layout.overlay_dictation, null)

        val dm = context.resources.displayMetrics
        val defaultX = dm.widthPixels - (70 * dm.density).toInt()
        val defaultY = dm.heightPixels / 2

        val savedPosition = prefs.savedOverlayPosition()
        val initialX = savedPosition?.first ?: defaultX
        val initialY = savedPosition?.second ?: defaultY

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

    private fun clampPosition() {
        val lp = params ?: return
        val dm = context.resources.displayMetrics
        val bounds = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowManager.currentWindowMetrics.bounds
        } else {
            @Suppress("DEPRECATION")
            android.graphics.Rect().also { rect ->
                val metrics = android.util.DisplayMetrics()
                windowManager.defaultDisplay.getRealMetrics(metrics)
                rect.set(0, 0, metrics.widthPixels, metrics.heightPixels)
            }
        }
        val buttonSize = OverlayGeometry.pixels(52, dm.density, prefs.overlayScalePercent)
        lp.x = OverlayGeometry.clampAxis(lp.x, bounds.width(), lp.width, buttonSize, dm.density)
        lp.y = OverlayGeometry.clampAxis(lp.y, bounds.height(), lp.height, buttonSize, dm.density)
    }

    /** Synchronously commit a completed/interrupted drag before its owner can be restarted. */
    private fun persistOverlayPositionIfNeeded() {
        if (!hasUncommittedPosition) return
        val lp = params ?: return
        if (prefs.persistOverlayPosition(lp.x, lp.y)) {
            hasUncommittedPosition = false
        } else {
            Log.w(TAG, "Could not persist floating-button position")
        }
    }

    fun refreshAppearance() {
        if (overlayView == null) return
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
        val hold = Runnable {
            if (touchActive && !isDragging && !consumeRelease) {
                // Consume UP even though cancellation may immediately make the engine idle.
                consumeRelease = true
                root.performLongClick()
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
            if (speechEngine.isLocalSessionActive && speechEngine.isRecording && !speechEngine.isExternalSessionActive) {
                speechEngine.cancelRecording()
                root.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                root.announceForAccessibility("Recording discarded")
                Toast.makeText(context, "Recording discarded", Toast.LENGTH_SHORT).show()
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
                    if (speechEngine.isLocalSessionActive && speechEngine.isRecording && !speechEngine.isExternalSessionActive) {
                        root.postDelayed(hold, ViewConfiguration.getLongPressTimeout().toLong())
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
                        if (isDragging) {
                            val oldX = lp.x
                            val oldY = lp.y
                            lp.x = initialX + dx.toInt()
                            lp.y = initialY + dy.toInt()
                            clampPosition()
                            if (lp.x != oldX || lp.y != oldY) hasUncommittedPosition = true
                            try { windowManager.updateViewLayout(overlayView, lp) } catch (_: Exception) { }
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    root.removeCallbacks(hold)
                    if (touchActive && !consumeRelease) {
                        if (isDragging) {
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

    private fun onOverlayClicked() {
        if (!prefs.serviceEnabled || speechEngine.isExternalSessionActive || speechEngine.isProcessing) return
        if (speechEngine.isRecording) {
            speechEngine.stopRecording()
            overlayView?.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
            overlayView?.announceForAccessibility("Recording stopped. Transcribing.")
        } else if (speechEngine.startRecording()) {
            overlayView?.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
            overlayView?.announceForAccessibility("Recording. Tap to stop and transcribe. Hold to discard.")
        }
    }

    private fun updateOverlayVisuals(isRecording: Boolean, isProcessing: Boolean) {
        val root = overlayView ?: return
        val externalSession = speechEngine.isExternalSessionActive
        root.contentDescription = when {
            externalSession && speechEngine.currentStatus == "loading" -> "Loading model for Hermes dictation"
            externalSession && speechEngine.currentStatus == "processing" -> "Processing Hermes dictation"
            externalSession && speechEngine.isAwaitingStop -> "Recording limit reached; stop or cancel in Hermes"
            externalSession && isRecording -> "Recording controlled by Hermes"
            externalSession -> "Dictation controlled by Hermes"
            speechEngine.currentStatus == "loading" -> "Loading recognition model"
            isProcessing -> "Transcribing recording"
            speechEngine.isAwaitingStop -> "Recording limit reached. Tap to transcribe. Hold to discard."
            isRecording -> "Recording. Tap to stop and transcribe. Hold to discard."
            else -> "Ready to record"
        }
        root.keepScreenOn = (isRecording || isProcessing) && !speechEngine.isExternalSessionActive &&
            root.visibility == View.VISIBLE
        val btnCircle = root.findViewById<FrameLayout>(R.id.btnOverlayCircle) ?: return
        val ivIcon = root.findViewById<ImageView>(R.id.ivOverlayIcon) ?: return
        val pbProcessing = root.findViewById<ProgressBar>(R.id.pbOverlayProcessing) ?: return
        val pulseRing = root.findViewById<View>(R.id.pulseRing) ?: return

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
            btnCircle.setBackgroundResource(R.drawable.bg_overlay_recording)
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
        cancelGesture?.invoke()
        persistOverlayPositionIfNeeded()
        cancelGesture = null
        if (overlayView != null) context.unregisterComponentCallbacks(configurationCallbacks)
        speechEngine.cancelRecording()
        onFinalTranscriptionReady = null
        onSessionIdle = null
        overlayView?.keepScreenOn = false
        pulseAnimator?.cancel()
        if (isAttached && overlayView != null) {
            try {
                windowManager.removeView(overlayView)
            } catch (_: Exception) {
            }
            isAttached = false
        }
        overlayView = null
    }
}
