package com.ludo.voicedictation.overlay

import kotlin.math.min
import kotlin.math.roundToInt

/** Pixel geometry shared by the window, its children and the reachable drag bounds. */
internal object OverlayGeometry {
    const val MIN_SCALE = 50
    const val MAX_SCALE = 150
    const val DEFAULT_SCALE = 100

    fun pixels(dp: Int, density: Float, scalePercent: Int): Int =
        (dp * density * scalePercent.coerceIn(MIN_SCALE, MAX_SCALE) / 100f)
            .roundToInt().coerceAtLeast(1)

    /** Geometry for targets in usable screen coordinates. */
    data class Targets(val radius: Int, val distance: Int) {
        fun minimumY(top: Int) = top + distance + radius
        fun maximumY(bottom: Int) = bottom - distance - radius
    }

    /** Shrink cues on constrained screens. The horizontal cue faces inward. */
    fun targets(width: Int, height: Int, window: Int, density: Float, scale: Int): Targets? {
        val gap = (8 * density).roundToInt().coerceAtLeast(1)
        val wantedRadius = maxOf((22 * density).roundToInt(), pixels(22, density, scale))
        val minRadius = (20 * density).roundToInt().coerceAtLeast(1)
        val radius = minOf(wantedRadius, (width - window - 2 * gap) / 4,
            (height - window - 2 * gap) / 4)
        if (radius < minRadius) return null
        return Targets(radius, window / 2 + radius + gap)
    }

    /** Retained for old geometry consumers; the overlay now uses full target bounds. */
    fun clampAxis(position: Int, screenSize: Int, windowSize: Int, buttonSize: Int, density: Float): Int {
        val margin = (windowSize - buttonSize) / 2
        // Retain at least one third per axis so even a corner remains grabbable.
        val visible = min(maxOf((12 * density).roundToInt().coerceAtLeast(1), buttonSize / 3), buttonSize / 2)
        val minimum = visible - margin - buttonSize
        val maximum = (screenSize - visible - margin).coerceAtLeast(minimum)
        return position.coerceIn(minimum, maximum)
    }
}
