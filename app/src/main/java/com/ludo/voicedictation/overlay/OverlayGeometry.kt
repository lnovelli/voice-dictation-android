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

    /** Allow the circle off either edge, but retain a usable sliver of the actual button. */
    fun clampAxis(position: Int, screenSize: Int, windowSize: Int, buttonSize: Int, density: Float): Int {
        val margin = (windowSize - buttonSize) / 2
        // Retain at least one third per axis so even a corner remains grabbable.
        val visible = min(maxOf((12 * density).roundToInt().coerceAtLeast(1), buttonSize / 3), buttonSize / 2)
        val minimum = visible - margin - buttonSize
        val maximum = (screenSize - visible - margin).coerceAtLeast(minimum)
        return position.coerceIn(minimum, maximum)
    }
}
