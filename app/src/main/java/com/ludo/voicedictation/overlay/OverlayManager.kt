package com.ludo.voicedictation.overlay

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
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
    private var pulseAnimator: ObjectAnimator? = null

    var onFinalTranscriptionReady: ((String) -> Unit)? = null

    init {
        speechEngine.onStateChanged = { recording, processing ->
            updateOverlayVisuals(recording, processing)
        }
        speechEngine.onFinalResult = { text ->
            onFinalTranscriptionReady?.invoke(text)
        }
    }

    fun hasOverlayPermission(): Boolean {
        return Settings.canDrawOverlays(context)
    }

    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        if (!hasOverlayPermission()) {
            Log.w(TAG, "Cannot show overlay: SYSTEM_ALERT_WINDOW permission missing")
            return
        }

        if (overlayView == null) {
            initOverlayView()
        }

        if (!isAttached && overlayView != null && params != null) {
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
        if (speechEngine.isRecording) {
            speechEngine.stopRecording()
        }
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

        val initialX = if (prefs.overlayX >= 0) prefs.overlayX else defaultX
        val initialY = if (prefs.overlayY >= 0) prefs.overlayY else defaultY

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
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
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

        setupTouchAndDrag()
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

        root.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = lp.x
                    initialY = lp.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (!isDragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        isDragging = true
                    }
                    if (isDragging) {
                        lp.x = initialX + dx
                        lp.y = initialY + dy
                        try {
                            windowManager.updateViewLayout(overlayView, lp)
                        } catch (_: Exception) {
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        // Click event!
                        onOverlayClicked()
                    } else {
                        // Save new position
                        prefs.overlayX = lp.x
                        prefs.overlayY = lp.y
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun onOverlayClicked() {
        if (speechEngine.isRecording) {
            speechEngine.stopRecording()
        } else {
            speechEngine.startRecording()
        }
    }

    private fun updateOverlayVisuals(isRecording: Boolean, isProcessing: Boolean) {
        val root = overlayView ?: return
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
            ivIcon.setImageResource(R.drawable.ic_stop)
            btnCircle.setBackgroundResource(R.drawable.bg_overlay_recording)
            root.alpha = 1.0f

            pulseRing.visibility = View.VISIBLE
            startPulseAnimation(pulseRing)
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
