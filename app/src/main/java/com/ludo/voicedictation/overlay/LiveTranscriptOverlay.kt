package com.ludo.voicedictation.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.TextView
import kotlin.math.abs

/** Ephemeral transcript window; only its independent position is persisted. Main thread only. */
internal class LiveTranscriptOverlay(private val context: Context) {
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val positions = context.getSharedPreferences("live_transcript_position", Context.MODE_PRIVATE)
    private var view: TextView? = null
    private var params: WindowManager.LayoutParams? = null
    private var landscape = isLandscape()
    private var dirty = false
    private var cancelDrag: (() -> Unit)? = null

    private fun isLandscape() = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density + 0.5f).toInt()

    /** Screen-relative usable area, including navigation bars and display cutouts. */
    private fun safeBounds(): Rect {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = wm.currentWindowMetrics
            val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars() or WindowInsets.Type.displayCutout()
            )
            val bounds = metrics.bounds
            return Rect(insets.left, insets.top, bounds.width() - insets.right, bounds.height() - insets.bottom)
        }
        @Suppress("DEPRECATION") val metrics = android.util.DisplayMetrics()
        @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(metrics)
        fun dimension(name: String): Int {
            val id = context.resources.getIdentifier(name, "dimen", "android")
            return if (id == 0) 0 else context.resources.getDimensionPixelSize(id)
        }
        return Rect(0, dimension("status_bar_height"), metrics.widthPixels,
            metrics.heightPixels - dimension("navigation_bar_height"))
    }

    private fun key(axis: String) = "${axis}_${if (landscape) "landscape" else "portrait"}"

    private fun persistPosition() {
        val lp = params ?: return
        if (!dirty) return
        if (positions.edit().putInt(key("x"), lp.x).putInt(key("y"), lp.y).commit()) dirty = false
    }

    private fun clamp(lp: WindowManager.LayoutParams, bounds: Rect) {
        lp.x = lp.x.coerceIn(bounds.left, maxOf(bounds.left, bounds.right - lp.width))
        lp.y = lp.y.coerceIn(bounds.top, maxOf(bounds.top, bounds.bottom - lp.height))
    }

    /** Recalculate orientation and sizing from the current display without retaining prior text. */
    fun refresh() {
        val root = view ?: return
        showText(root.text.toString())
    }

    @SuppressLint("ClickableViewAccessibility")
    fun showText(text: String) {
        if (text.isBlank()) { clear(); return }
        val bounds = safeBounds()
        if (bounds.width() <= 0 || bounds.height() <= 0) { clear(); return }
        val oldOrientation = landscape
        val nextOrientation = isLandscape()
        if (oldOrientation != nextOrientation) {
            cancelDrag?.invoke()
            persistPosition()
            landscape = nextOrientation
        }
        val width = minOf(dp(480), maxOf(1, bounds.width() - dp(16)))
        val root = view ?: TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 16f
            maxLines = 2
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = GradientDrawable().apply {
                setColor(0xe6282d35.toInt())
                cornerRadius = dp(12).toFloat()
            }
            // Avoid automatically announcing sensitive text or allowing text selection/copy.
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            setTextIsSelectable(false)
        }
        // Bound memory and keep the latest complete rendered lines rather than ellipsizing the end.
        var tail = text.takeLast(4096)
        if (tail.isNotEmpty() && Character.isLowSurrogate(tail[0])) tail = tail.substring(1)
        val layout = StaticLayout.Builder.obtain(tail, 0, tail.length, root.paint,
            maxOf(1, width - root.paddingLeft - root.paddingRight))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(root.includeFontPadding).build()
        if (layout.lineCount > 2) tail = tail.substring(layout.getLineStart(layout.lineCount - 2))
        root.text = tail
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(bounds.height(), View.MeasureSpec.AT_MOST))
        val height = maxOf(1, root.measuredHeight)
        val existing = params
        val lp = existing ?: WindowManager.LayoutParams(
            width, height, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_SECURE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.LEFT }
        lp.width = width
        lp.height = height
        if (existing == null || oldOrientation != nextOrientation) {
            lp.x = if (positions.contains(key("x")) && positions.contains(key("y"))) positions.getInt(key("x"), bounds.left)
                else bounds.left + (bounds.width() - width) / 2
            lp.y = if (positions.contains(key("x")) && positions.contains(key("y"))) positions.getInt(key("y"), bounds.top)
                else bounds.bottom - height - dp(16)
        }
        clamp(lp, bounds)
        if (existing == null) {
            params = lp
            setupDrag(root, lp)
            try {
                wm.addView(root, lp)
                view = root
            } catch (_: RuntimeException) {
                root.text = ""
                params = null
                cancelDrag = null
            }
        } else {
            try { wm.updateViewLayout(root, lp) } catch (_: RuntimeException) { clear() }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupDrag(root: TextView, lp: WindowManager.LayoutParams) {
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        var active = false
        var dragging = false
        cancelDrag = {
            if (dragging) persistPosition()
            active = false
            dragging = false
        }
        root.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = lp.x; startY = lp.y
                    touchX = event.rawX; touchY = event.rawY
                    active = true; dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                    if (active) {
                        val dx = event.rawX - touchX
                        val dy = event.rawY - touchY
                        if (!dragging && (abs(dx) > slop || abs(dy) > slop)) dragging = true
                        if (dragging) {
                            val oldX = lp.x; val oldY = lp.y
                            lp.x = startX + dx.toInt(); lp.y = startY + dy.toInt()
                            clamp(lp, safeBounds())
                            if (lp.x != oldX || lp.y != oldY) {
                                try { wm.updateViewLayout(root, lp); dirty = true }
                                catch (_: RuntimeException) { lp.x = oldX; lp.y = oldY }
                            }
                        }
                    }
                    if (event.actionMasked == MotionEvent.ACTION_UP) cancelDrag?.invoke()
                    true
                }
                MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> {
                    cancelDrag?.invoke(); true
                }
                else -> true
            }
        }
    }

    fun clear() {
        cancelDrag?.invoke()
        persistPosition()
        cancelDrag = null
        val root = view
        view = null
        params = null
        dirty = false
        root?.text = ""
        root?.setOnTouchListener(null)
        if (root != null) {
            try { wm.removeViewImmediate(root) } catch (_: RuntimeException) { root.visibility = View.GONE }
        }
    }
}
