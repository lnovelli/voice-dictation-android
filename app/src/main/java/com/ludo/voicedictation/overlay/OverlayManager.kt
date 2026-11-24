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
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.Bundle
import android.view.WindowInsets
import android.view.accessibility.AccessibilityNodeInfo
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
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
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
    private var microphonePanelSession: Any? = null
    private var microphonePanelStatus: TextView? = null
    private var microphonePanelList: LinearLayout? = null
    private var panelGeneration = 0L
    private var chooserGeneration = 0L
    private var chooserPending = false
    private var cueView: CueView? = null
    private var cuesEnabled = false
    private var reportedSmallDisplay = false
    private var stateGeneration = 0L
    private var lastUsableBounds: Rect? = null
    private val actionMicrophone = R.id.overlay_action_microphone
    private val actionHide = R.id.overlay_action_hide
    private val actionDiscard = R.id.overlay_action_discard
    private val actionMoveUp = R.id.overlay_action_move_up
    private val actionMoveDown = R.id.overlay_action_move_down
    private val actionMoveLeft = R.id.overlay_action_move_left
    private val actionMoveRight = R.id.overlay_action_move_right
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
    /** Requests field-scoped suppression; the accessibility service owns the token. */
    var onHideForCurrentEditor: (() -> Unit)? = null

    init {
        speechEngine.onStateChanged = { recording, processing ->
            stateGeneration++
            cancelGesture?.invoke()
            if (previewSessionActive && (!speechEngine.isLocalSessionActive ||
                    (!recording && speechEngine.currentStatus != "starting") ||
                    speechEngine.currentStatus == "recording_limit")) stopPreview()
            OverlayDiagnostics.record { "dictation state recording=$recording processing=$processing external=${speechEngine.isExternalSessionActive}" }
            refreshMicrophonePanel()
            if (speechEngine.isExternalSessionActive && canShowExternalStatus()) showExternalStatus("dictation-state")
            else updateOverlayVisuals(recording, processing)
            if (!recording && !processing) {
                // Run after final insertion, not between state cleanup and delivery.
                overlayView?.post { onSessionIdle?.invoke() }
            }
        }

        // Read the current state when notified so an older queued notification cannot
        // restore a speaking indicator after capture has stopped.
        speechEngine.onVadStateChanged = {
            overlayView?.let { updateIconTint(it) }
        }

        speechEngine.onActiveLocalMicrophoneDeviceChanged = { refreshMicrophonePanel() }

        speechEngine.onRecordingLimit = {
            val message = if (targetGeometry() == null)
                "10-minute limit reached. Tap to transcribe, or use the discard accessibility action."
            else "10-minute limit reached. Tap to transcribe, or swipe down or ${horizontalDirectionName()} to discard."
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

        if (currentIsLandscape() != positionIsLandscape) cancelGesture?.invoke()
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
        chooserGeneration++
        chooserPending = false
        panelGeneration++
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
        overlayView?.setOnApplyWindowInsetsListener { view, insets ->
            // Recompute when system bar or display cutout insets change.
            view.post {
                val bounds = usableBounds()
                if (isAttached && lastUsableBounds != bounds) refreshAppearance()
            }
            insets
        }
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

    /** Default toward the right edge. */
    private fun defaultOverlayPosition(): Pair<Int, Int> {
        val dm = context.resources.displayMetrics
        val bounds = usableBounds()
        val windowSize = OverlayGeometry.pixels(80, dm.density, prefs.overlayScalePercent)
        return (bounds.right - windowSize) to (bounds.centerY() - windowSize / 2)
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

    private fun usableBounds(): Rect {
        val bounds = displayBounds()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val windowInsets = windowManager.currentWindowMetrics.windowInsets
            val insets = windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            // The overlay is deliberately allowed on top of the keyboard.
            bounds.left += insets.left
            bounds.top += insets.top
            bounds.right -= insets.right
            bounds.bottom -= insets.bottom
        } else {
            // Legacy real metrics include system bars. Reserve their framework dimensions.
            fun systemSize(name: String): Int {
                val id = context.resources.getIdentifier(name, "dimen", "android")
                return if (id == 0) 0 else context.resources.getDimensionPixelSize(id)
            }
            val windowInsets = overlayView?.rootWindowInsets
            val cutout = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) windowInsets?.displayCutout else null
            @Suppress("DEPRECATION")
            val legacyLeft = windowInsets?.stableInsetLeft ?: 0
            @Suppress("DEPRECATION")
            val legacyTop = windowInsets?.stableInsetTop ?: 0
            @Suppress("DEPRECATION")
            val legacyRight = windowInsets?.stableInsetRight ?: 0
            @Suppress("DEPRECATION")
            val legacyBottom = windowInsets?.stableInsetBottom ?: 0
            bounds.left += maxOf(legacyLeft, cutout?.safeInsetLeft ?: 0)
            bounds.top += maxOf(systemSize("status_bar_height"), legacyTop, cutout?.safeInsetTop ?: 0)
            bounds.right -= maxOf(legacyRight, cutout?.safeInsetRight ?: 0)
            bounds.bottom -= maxOf(systemSize("navigation_bar_height"), legacyBottom, cutout?.safeInsetBottom ?: 0)
        }
        return bounds
    }

    private fun targetGeometry(): OverlayGeometry.Targets? {
        val lp = params ?: return null
        val bounds = usableBounds()
        return OverlayGeometry.targets(bounds.width(), bounds.height(), lp.width,
            context.resources.displayMetrics.density, prefs.overlayScalePercent)
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
        val bounds = usableBounds()
        lastUsableBounds = Rect(bounds)
        val half = lp.width / 2
        val targets = targetGeometry()
        if (targets == null) {
            // Keep the button visible; gesture actions are explicitly unavailable until resized.
            lp.x = lp.x.coerceIn(bounds.left, (bounds.right - lp.width).coerceAtLeast(bounds.left))
            lp.y = lp.y.coerceIn(bounds.top, (bounds.bottom - lp.height).coerceAtLeast(bounds.top))
            if (!reportedSmallDisplay) {
                reportedSmallDisplay = true
                Toast.makeText(context, "Screen too small for swipe targets. Reduce button size or use accessibility actions.", Toast.LENGTH_LONG).show()
            }
        } else {
            reportedSmallDisplay = false
            val buttonHalf = OverlayGeometry.pixels(52, context.resources.displayMetrics.density,
                prefs.overlayScalePercent) / 2
            val edgeInset = maxOf(buttonHalf, targets.radius)
            val cx = (lp.x + half).coerceIn(bounds.left + edgeInset, bounds.right - edgeInset)
            val cy = (lp.y + half).coerceIn(targets.minimumY(bounds.top), targets.maximumY(bounds.bottom))
            lp.x = cx - half
            lp.y = cy - half
        }
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
        cancelGesture?.invoke()
        chooserGeneration++
        chooserPending = false
        panelGeneration++
        microphoneDialog?.dismiss()
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

    private enum class Owner { IDLE, LOCAL, EXTERNAL, OTHER }
    private enum class Direction { UP, DOWN, LEFT, RIGHT }
    private enum class GestureMode { FINISHED, PENDING, SWIPING, MOVING }
    private data class GestureSnapshot(val owner: Owner, val session: Any?, val tap: Boolean, val generation: Long)

    private fun snapshot(): GestureSnapshot {
        val session = speechEngine.localSessionIdentity
        val owner = when {
            speechEngine.isExternalSessionActive -> Owner.EXTERNAL
            session != null -> Owner.LOCAL
            speechEngine.isBusy -> Owner.OTHER
            else -> Owner.IDLE
        }
        val tap = owner == Owner.IDLE || (owner == Owner.LOCAL &&
            (speechEngine.currentStatus == "starting" || (!speechEngine.isProcessing &&
                (speechEngine.isRecording || speechEngine.isAwaitingStop))))
        return GestureSnapshot(owner, session, tap, stateGeneration)
    }

    private fun stillValid(s: GestureSnapshot): Boolean {
        if (!prefs.serviceEnabled || !isAttached || overlayView?.visibility != View.VISIBLE ||
            !hasOverlayPermission() || !canShowExternalStatus()) return false
        val now = snapshot()
        return now.owner == s.owner && now.generation == s.generation &&
            (s.owner != Owner.LOCAL || (s.session != null && now.session === s.session))
    }

    private fun horizontalDirection(): Direction {
        val bounds = usableBounds()
        val lp = params ?: return Direction.LEFT
        return if (lp.x + lp.width / 2 < bounds.centerX()) Direction.RIGHT else Direction.LEFT
    }

    private fun horizontalDirectionName() = if (horizontalDirection() == Direction.RIGHT) "right" else "left"

    private fun directions(s: GestureSnapshot): Set<Direction> = when (s.owner) {
        Owner.IDLE -> if (onHideForCurrentEditor != null) setOf(Direction.UP, Direction.DOWN) else setOf(Direction.UP)
        Owner.LOCAL -> setOf(Direction.UP, Direction.DOWN, horizontalDirection())
        else -> emptySet()
    }

    private fun dispatchDirection(s: GestureSnapshot, direction: Direction) {
        if (!stillValid(s) || direction !in directions(s)) return
        when (direction) {
            Direction.UP -> showMicrophoneChooser(s)
            Direction.DOWN -> if (s.owner == Owner.IDLE) onHideForCurrentEditor?.invoke() else discardLocal()
            Direction.LEFT, Direction.RIGHT -> discardLocal()
        }
    }

    private fun discardLocal() {
        if (!speechEngine.isLocalSessionActive || speechEngine.isExternalSessionActive) return
        OverlayDiagnostics.record { "dictation discard requested" }
        stopPreview()
        speechEngine.cancelRecording()
        overlayView?.announceForAccessibility("Dictation discarded")
        Toast.makeText(context, "Dictation discarded", Toast.LENGTH_SHORT).show()
    }

    /** The cue window never accepts input; raw screen coordinates remain the hit-test space. */
    private inner class CueView(
        val centerX: Int, val centerY: Int, val geometry: OverlayGeometry.Targets,
        val available: Set<Direction>, val owner: Owner, val originX: Int, val originY: Int
    ) : View(context) {
        var selected: Direction? = null
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private fun center(direction: Direction): Pair<Int, Int> = when (direction) {
            Direction.UP -> centerX to centerY - geometry.distance
            Direction.DOWN -> centerX to centerY + geometry.distance
            Direction.LEFT -> centerX - geometry.distance to centerY
            Direction.RIGHT -> centerX + geometry.distance to centerY
        }
        fun hit(x: Float, y: Float, downX: Float, downY: Float): Direction? {
            val dx = x - downX
            val dy = y - downY
            return available.firstOrNull { direction ->
                val strict = when (direction) {
                    Direction.UP -> dy < 0 && -dy > abs(dx)
                    Direction.DOWN -> dy > 0 && dy > abs(dx)
                    Direction.LEFT -> dx < 0 && -dx > abs(dy)
                    Direction.RIGHT -> dx > 0 && dx > abs(dy)
                }
                val (cx, cy) = center(direction)
                val hitRadius = geometry.radius * 1.5f
                strict && (x - cx) * (x - cx) + (y - cy) * (y - cy) <= hitRadius * hitRadius
            }
        }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            for (direction in available) {
                val (screenX, screenY) = center(direction)
                val x = (screenX - originX).toFloat()
                val y = (screenY - originY).toFloat()
                val r = geometry.radius.toFloat()
                paint.style = Paint.Style.FILL
                paint.color = if (selected == direction) Color.rgb(0, 160, 220) else Color.rgb(30, 30, 30)
                canvas.drawCircle(x, y, r, paint)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = ((if (selected == direction) 4 else 2) * resources.displayMetrics.density).coerceAtLeast(1f)
                paint.color = Color.WHITE
                canvas.drawCircle(x, y, r - paint.strokeWidth / 2, paint)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = (r * 0.10f).coerceAtLeast(1f)
                paint.strokeCap = Paint.Cap.ROUND
                val iconY = y - r * 0.22f
                if (direction == Direction.UP) {
                    context.getDrawable(R.drawable.ic_mic)?.mutate()?.let { icon ->
                        val size = (r * 0.94f).toInt()
                        icon.setBounds((x - size / 2).toInt(), (iconY - size / 2).toInt(),
                            (x + size / 2).toInt(), (iconY + size / 2).toInt())
                        icon.draw(canvas)
                    }
                } else if (direction == Direction.DOWN && owner == Owner.IDLE) {
                    canvas.drawOval(x - r * 0.48f, iconY - r * 0.25f,
                        x + r * 0.48f, iconY + r * 0.25f, paint)
                    canvas.drawCircle(x, iconY, r * 0.10f, paint)
                    canvas.drawLine(x - r * 0.45f, iconY + r * 0.40f,
                        x + r * 0.45f, iconY - r * 0.40f, paint)
                } else {
                    canvas.drawLine(x - r * 0.35f, iconY - r * 0.35f,
                        x + r * 0.35f, iconY + r * 0.35f, paint)
                    canvas.drawLine(x - r * 0.35f, iconY + r * 0.35f,
                        x + r * 0.35f, iconY - r * 0.35f, paint)
                }
                paint.style = Paint.Style.FILL
                paint.textAlign = Paint.Align.CENTER
                paint.typeface = android.graphics.Typeface.DEFAULT_BOLD
                paint.textSize = r * 0.38f
                val label = when (direction) {
                    Direction.UP -> "MIC"
                    Direction.DOWN -> if (owner == Owner.IDLE) "HIDE" else "DISCARD"
                    Direction.LEFT, Direction.RIGHT -> "DISCARD"
                }
                canvas.drawText(label, x, y + r * 0.52f, paint)
            }
        }
    }

    private fun removeCues() {
        cuesEnabled = false
        val view = cueView ?: return
        try {
            windowManager.removeViewImmediate(view)
            cueView = null
        } catch (e: Exception) {
            if (!view.isAttachedToWindow) cueView = null else view.visibility = View.GONE
            Log.w(TAG, "Could not remove gesture targets", e)
        }
    }

    private fun showCues(s: GestureSnapshot) {
        removeCues()
        if (cueView != null) return
        val lp = params ?: return
        val geometry = targetGeometry() ?: return
        val actions = directions(s)
        if (actions.isEmpty()) return
        val cx = lp.x + lp.width / 2
        val cy = lp.y + lp.height / 2
        val left = if (Direction.RIGHT in actions) cx - geometry.radius
            else cx - geometry.distance - geometry.radius
        val top = cy - geometry.distance - geometry.radius
        val view = CueView(cx, cy, geometry, actions, s.owner, left, top)
        view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        val cueParams = WindowManager.LayoutParams(
            geometry.distance + 2 * geometry.radius, 2 * (geometry.distance + geometry.radius),
            lp.type, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = left
            y = top
            alpha = 0.8f
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        try {
            windowManager.addView(view, cueParams)
            cueView = view
            cuesEnabled = true
        } catch (e: Exception) {
            Log.w(TAG, "Could not show gesture targets", e)
            Toast.makeText(context, "Swipe targets unavailable; use accessibility actions.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun movePosition(dx: Int, dy: Int): Boolean {
        val root = overlayView ?: return false
        val lp = params ?: return false
        val oldX = lp.x
        val oldY = lp.y
        lp.x += dx
        lp.y += dy
        clampPosition()
        if (lp.x == oldX && lp.y == oldY) return false
        return try {
            windowManager.updateViewLayout(root, lp)
            hasUncommittedPosition = true
            true
        } catch (e: Exception) {
            lp.x = oldX
            lp.y = oldY
            Log.w(TAG, "Failed to update floating-button position", e)
            false
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouchAndDrag() {
        val root = overlayView ?: return
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        var mode = GestureMode.FINISHED
        var downX = 0f
        var downY = 0f
        var lastX = 0f
        var lastY = 0f
        var moveTouchX = 0f
        var moveTouchY = 0f
        var moveWindowX = 0
        var moveWindowY = 0
        fun moveTo(rawX: Float, rawY: Float) {
            val lp = params ?: return
            movePosition(moveWindowX + (rawX - moveTouchX).toInt() - lp.x,
                moveWindowY + (rawY - moveTouchY).toInt() - lp.y)
        }
        var gesture = snapshot()
        val hold = Runnable {
            if (mode == GestureMode.PENDING && stillValid(gesture)) {
                removeCues()
                mode = GestureMode.MOVING
                moveTouchX = lastX
                moveTouchY = lastY
                moveWindowX = params?.x ?: 0
                moveWindowY = params?.y ?: 0
                // lastX/Y were updated even for sub-slop movement: no jump at unlock.
                root.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                root.announceForAccessibility("Move button, then release")
            }
        }
        cancelGesture = {
            root.removeCallbacks(hold)
            removeCues()
            persistOverlayPositionIfNeeded()
            mode = GestureMode.FINISHED
        }
        root.isFocusable = true
        root.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        root.findViewById<View>(R.id.btnOverlayCircle).importantForAccessibility =
            View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        root.setOnClickListener {
            val s = snapshot()
            if (s.tap && stillValid(s)) onOverlayClicked()
        }
        root.setAccessibilityDelegate(object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                val s = snapshot()
                if (!s.tap) {
                    info.isClickable = false
                    info.removeAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK)
                }
                if (Direction.UP in directions(s)) info.addAction(AccessibilityNodeInfo.AccessibilityAction(actionMicrophone,
                    if (s.owner == Owner.IDLE) "Choose microphone" else "Show current microphone status"))
                if (s.owner == Owner.IDLE && Direction.DOWN in directions(s))
                    info.addAction(AccessibilityNodeInfo.AccessibilityAction(actionHide, "Hide for this editor"))
                if (s.owner == Owner.LOCAL)
                    info.addAction(AccessibilityNodeInfo.AccessibilityAction(actionDiscard, "Cancel and discard dictation"))
                info.addAction(AccessibilityNodeInfo.AccessibilityAction(actionMoveUp, "Move button up"))
                info.addAction(AccessibilityNodeInfo.AccessibilityAction(actionMoveDown, "Move button down"))
                info.addAction(AccessibilityNodeInfo.AccessibilityAction(actionMoveLeft, "Move button left"))
                info.addAction(AccessibilityNodeInfo.AccessibilityAction(actionMoveRight, "Move button right"))
            }
            override fun performAccessibilityAction(host: View, action: Int, args: Bundle?): Boolean {
                val s = snapshot()
                if (!stillValid(s)) return false
                when (action) {
                    actionMicrophone -> if (Direction.UP in directions(s)) dispatchDirection(s, Direction.UP) else return false
                    actionHide -> if (s.owner == Owner.IDLE && Direction.DOWN in directions(s)) dispatchDirection(s, Direction.DOWN) else return false
                    actionDiscard -> if (s.owner == Owner.LOCAL) dispatchDirection(s, horizontalDirection()) else return false
                    actionMoveUp, actionMoveDown, actionMoveLeft, actionMoveRight -> {
                        val step = (48 * context.resources.displayMetrics.density).toInt().coerceAtLeast(1)
                        val dx = when (action) { actionMoveLeft -> -step; actionMoveRight -> step; else -> 0 }
                        val dy = when (action) { actionMoveUp -> -step; actionMoveDown -> step; else -> 0 }
                        val moved = movePosition(dx, dy)
                        persistOverlayPositionIfNeeded()
                        if (moved) updateOverlayVisuals(speechEngine.isRecording, speechEngine.isProcessing)
                        host.announceForAccessibility(if (moved) "Button moved" else "Button at movement limit")
                    }
                    else -> return super.performAccessibilityAction(host, action, args)
                }
                return true
            }
        })
        root.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    cancelGesture?.invoke()
                    gesture = snapshot()
                    downX = event.rawX
                    downY = event.rawY
                    lastX = downX
                    lastY = downY
                    mode = GestureMode.PENDING
                    showCues(gesture)
                    root.postDelayed(hold, maxOf(1000L, ViewConfiguration.getLongPressTimeout().toLong() * 2))
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (mode == GestureMode.PENDING && (abs(event.rawX - downX) > slop || abs(event.rawY - downY) > slop)) {
                        root.removeCallbacks(hold)
                        mode = GestureMode.SWIPING
                    }
                    if (mode == GestureMode.MOVING) {
                        moveTo(event.rawX, event.rawY)
                    } else if (mode == GestureMode.SWIPING && cuesEnabled) {
                        cueView?.let { cue ->
                            val selected = cue.hit(event.rawX, event.rawY, downX, downY)
                            if (selected != cue.selected) {
                                cue.selected = selected
                                cue.invalidate()
                                if (selected != null) {
                                    root.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                                }
                            }
                        }
                    }
                    lastX = event.rawX
                    lastY = event.rawY
                    true
                }
                MotionEvent.ACTION_UP -> {
                    root.removeCallbacks(hold)
                    if (mode == GestureMode.PENDING &&
                        (abs(event.rawX - downX) > slop || abs(event.rawY - downY) > slop)) {
                        mode = GestureMode.SWIPING
                    }
                    val selected = if (mode == GestureMode.SWIPING && cuesEnabled)
                        cueView?.hit(event.rawX, event.rawY, downX, downY) else null
                    val oldMode = mode
                    mode = GestureMode.FINISHED
                    removeCues()
                    if (oldMode == GestureMode.MOVING) {
                        moveTo(event.rawX, event.rawY)
                        persistOverlayPositionIfNeeded()
                        updateOverlayVisuals(speechEngine.isRecording, speechEngine.isProcessing)
                    } else if (oldMode == GestureMode.PENDING && gesture.tap && stillValid(gesture) && snapshot().tap) {
                        root.performClick()
                    } else if (oldMode == GestureMode.SWIPING && selected != null) {
                        dispatchDirection(gesture, selected)
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> {
                    cancelGesture?.invoke()
                    true
                }
                else -> true
            }
        }
    }

    /** A separate non-focusable overlay keeps the underlying editor's input focus intact. */
    private fun showMicrophoneChooser(s: GestureSnapshot) {
        if (!stillValid(s) || microphoneDialog != null) return
        if (s.owner == Owner.LOCAL) {
            showReadOnlyMicrophonePanel(s)
            return
        }
        if (s.owner != Owner.IDLE || speechEngine.isBusy || chooserPending) return
        val root = overlayView ?: return
        chooserPending = true
        val generation = ++chooserGeneration
        Thread {
            val result = runCatching { MicrophoneDevices.available(context) }
            root.post {
                if (generation != chooserGeneration) return@post
                chooserPending = false
                if (!stillValid(s) || microphoneDialog != null || speechEngine.isBusy) return@post
                if (result.isFailure) {
                    Toast.makeText(context, "Microphone discovery failed; System default remains available.", Toast.LENGTH_LONG).show()
                }
                showIdleMicrophoneChooser(s, result.getOrDefault(emptyList()))
            }
        }.start()
    }

    private fun showIdleMicrophoneChooser(s: GestureSnapshot, choices: List<MicrophoneDevices.Choice>) {
        if (!stillValid(s) || speechEngine.isBusy || microphoneDialog != null) return
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
                if (!stillValid(s) || speechEngine.isBusy) {
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
                    } else if (!stillValid(s) || speechEngine.isBusy) {
                        dialogInterface.dismiss()
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

    private fun refreshMicrophonePanel() {
        val dialog = microphoneDialog ?: return
        val session = microphonePanelSession
        val generation = ++panelGeneration
        if (session == null) {
            if (speechEngine.isBusy) dialog.dismiss()
            return
        }
        if (speechEngine.localSessionIdentity !== session || speechEngine.isExternalSessionActive ||
            !isAttached || overlayView?.visibility != View.VISIBLE) {
            dialog.dismiss()
            return
        }
        val status = microphonePanelStatus ?: return
        val list = microphonePanelList ?: return
        val capturing = speechEngine.isRecording && !speechEngine.isProcessing &&
            !speechEngine.isAwaitingStop && speechEngine.currentStatus == "recording"
        val id = if (capturing) speechEngine.activeLocalMicrophoneDeviceId else null
        status.text = when {
            speechEngine.currentStatus == "starting" -> "Starting dictation; no microphone route verified yet."
            !capturing -> "No microphone currently active."
            id == null -> "Active microphone unknown or unavailable."
            else -> "Checking active microphone route…"
        } + "\nSelection is locked until dictation finishes."
        list.removeAllViews()
        list.addView(TextView(status.context).apply { text = "Loading microphones…" })
        val root = overlayView ?: return
        Thread {
            val choices = try { MicrophoneDevices.available(context) } catch (_: Exception) { emptyList() }
            val choice = if (id == null) null else choices.filter { it.device.id == id }.singleOrNull()?.takeIf { it.unique }
            root.post {
                if (generation != panelGeneration || microphoneDialog !== dialog ||
                    microphonePanelSession !== session || speechEngine.localSessionIdentity !== session ||
                    (capturing && speechEngine.activeLocalMicrophoneDeviceId != id)) return@post
                if (capturing && id != null) status.text = if (choice == null)
                    "Active microphone unknown or unavailable. The route could not be uniquely identified.\nSelection is locked until dictation finishes."
                else "Active microphone: ${choice.label}\nAndroid controls routing. Selection is locked until dictation finishes."
                list.removeAllViews()
                val pad = (12 * context.resources.displayMetrics.density).toInt()
                fun addRow(label: String, active: Boolean) {
                    list.addView(TextView(status.context).apply {
                        text = (if (active) "● " else "○ ") + label + if (active) "  (active)" else ""
                        setPadding(0, pad, 0, pad)
                        if (active) {
                            setTypeface(typeface, android.graphics.Typeface.BOLD)
                            setTextColor(Color.rgb(0, 100, 145))
                        }
                    })
                }
                addRow("System default (automatic)", false)
                choices.forEach { addRow(it.label + if (it.unique) "" else " (indistinguishable)", it === choice) }
            }
        }.start()
    }

    private fun showReadOnlyMicrophonePanel(s: GestureSnapshot) {
        if (!stillValid(s) || s.session == null) return
        val themed = ContextThemeWrapper(context, android.R.style.Theme_Material_Dialog_Alert)
        val pad = (24 * context.resources.displayMetrics.density).toInt()
        val content = LinearLayout(themed).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, 0, pad, 0)
        }
        val status = TextView(themed)
        val list = LinearLayout(themed).apply { orientation = LinearLayout.VERTICAL }
        content.addView(status)
        content.addView(ScrollView(themed).apply { addView(list) })
        val dialog = AlertDialog.Builder(themed)
            .setTitle("Current microphone")
            .setView(content)
            .setPositiveButton("Close", null)
            .create()
        dialog.setOnDismissListener {
            if (microphoneDialog === dialog) {
                microphoneDialog = null
                microphonePanelSession = null
                microphonePanelStatus = null
                microphonePanelList = null
            }
        }
        dialog.window?.apply {
            setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
            addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
        }
        microphonePanelSession = s.session
        microphonePanelStatus = status
        microphonePanelList = list
        microphoneDialog = dialog
        try {
            dialog.show()
            dialog.setCanceledOnTouchOutside(true)
            refreshMicrophonePanel()
        } catch (e: RuntimeException) {
            microphoneDialog = null
            microphonePanelSession = null
            microphonePanelStatus = null
            microphonePanelList = null
            Log.w(TAG, "Could not display microphone status", e)
            Toast.makeText(context, "Could not open microphone status.", Toast.LENGTH_LONG).show()
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
                overlayView?.announceForAccessibility(if (targetGeometry() == null)
                    "Starting recording. Tap to stop and transcribe. Use the discard accessibility action to cancel."
                    else "Starting recording. Tap to stop and transcribe. Swipe down or ${horizontalDirectionName()} to discard.")
            } else {
                OverlayDiagnostics.record { "dictation start rejected" }
            }
        }
    }

    private fun updateIconTint(root: View) {
        val icon = root.findViewById<ImageView>(R.id.ivOverlayIcon) ?: return
        val color = if (speechEngine.isRecording && !speechEngine.isProcessing &&
            !speechEngine.isExternalSessionActive) {
            when (speechEngine.currentVadSpeaking) {
                true -> R.color.recording_red
                false -> R.color.overlay_no_speech
                null -> android.R.color.white
            }
        } else android.R.color.white
        icon.imageTintList = context.getColorStateList(color)
        if (speechEngine.isRecording && !speechEngine.isProcessing && !speechEngine.isExternalSessionActive) {
            // Preserve the usual red recording button when VAD is inactive or unavailable.
            root.findViewById<FrameLayout>(R.id.btnOverlayCircle)?.setBackgroundResource(
                if (speechEngine.currentVadSpeaking == null) R.drawable.bg_overlay_recording else R.drawable.bg_overlay_idle
            )
        }
    }

    private fun updateOverlayVisuals(isRecording: Boolean, isProcessing: Boolean) {
        val root = overlayView ?: return
        val externalSession = speechEngine.isExternalSessionActive
        val swipes = targetGeometry() != null
        val localHints = if (swipes) "Swipe down or ${horizontalDirectionName()} to discard, up for microphone status."
            else "Use accessibility actions for discard and microphone status; swipe targets do not fit."
        val idleHints = if (!swipes && onHideForCurrentEditor != null)
            "Use accessibility actions for microphone and hide; swipe targets do not fit."
            else if (!swipes) "Use the microphone accessibility action; swipe targets do not fit."
            else if (onHideForCurrentEditor != null) "Swipe up for microphone, down to hide."
            else "Swipe up for microphone."
        val moveHint = " Hold and move to reposition, or use movement actions."
        root.contentDescription = when {
            externalSession && speechEngine.currentStatus == "polishing" -> "Polishing Hermes dictation.$moveHint"
            externalSession && speechEngine.currentStatus == "loading" -> "Loading model for Hermes dictation.$moveHint"
            externalSession && speechEngine.currentStatus == "processing" -> "Processing Hermes dictation.$moveHint"
            externalSession && speechEngine.currentStatus == "starting" -> "Starting Hermes dictation.$moveHint"
            externalSession && speechEngine.isAwaitingStop -> "Recording limit reached; stop or cancel in Hermes.$moveHint"
            externalSession && isRecording -> "Recording controlled by Hermes.$moveHint"
            externalSession -> "Dictation controlled by Hermes.$moveHint"
            !speechEngine.isLocalSessionActive && speechEngine.isBusy -> "Dictation finishing or controlled elsewhere.$moveHint"
            speechEngine.currentStatus == "polishing" -> "Polishing dictated text. $localHints$moveHint"
            speechEngine.currentStatus == "starting" -> "Starting recording. Tap to stop and transcribe. $localHints$moveHint"
            speechEngine.currentStatus == "loading" -> "Loading recognition model. $localHints$moveHint"
            isProcessing -> "Transcribing recording. $localHints$moveHint"
            speechEngine.isAwaitingStop -> "Recording limit reached. Tap to transcribe. $localHints$moveHint"
            isRecording -> "Recording. Tap to stop and transcribe. $localHints$moveHint"
            else -> "Ready to record. Tap to start. $idleHints$moveHint"
        }

        root.keepScreenOn = (isRecording || isProcessing) && !speechEngine.isExternalSessionActive &&
            root.visibility == View.VISIBLE
        val btnCircle = root.findViewById<FrameLayout>(R.id.btnOverlayCircle) ?: return
        val ivIcon = root.findViewById<ImageView>(R.id.ivOverlayIcon) ?: return
        val pbProcessing = root.findViewById<ProgressBar>(R.id.pbOverlayProcessing) ?: return
        val pulseRing = root.findViewById<View>(R.id.pulseRing) ?: return
        updateIconTint(root)
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
            // The dark button keeps VAD colors visible; otherwise retain the usual red.
            btnCircle.setBackgroundResource(
                if (!externalSession && speechEngine.currentVadSpeaking != null) R.drawable.bg_overlay_idle
                else recordingBackground
            )
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
        chooserGeneration++
        chooserPending = false
        panelGeneration++
        cancelGesture?.invoke()
        microphoneDialog?.dismiss()
        persistOverlayPositionIfNeeded()
        cancelGesture = null
        if (overlayView != null) context.unregisterComponentCallbacks(configurationCallbacks)
        speechEngine.cancelRecording()
        onFinalTranscriptionReady = null
        onLocalDictationStarted = null
        onSessionIdle = null
        onHideForCurrentEditor = null
        lastUsableBounds = null
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
