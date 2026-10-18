package com.ludo.voicedictation.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayGeometryTest {
    @Test fun scaleControlsEveryDimensionAndClampsInvalidValues() {
        assertEquals(26, OverlayGeometry.pixels(52, 1f, 50))
        assertEquals(52, OverlayGeometry.pixels(52, 1f, 100))
        assertEquals(78, OverlayGeometry.pixels(52, 1f, 150))
        assertEquals(26, OverlayGeometry.pixels(52, 1f, -100))
        assertEquals(78, OverlayGeometry.pixels(52, 1f, 999))
        assertEquals(156, OverlayGeometry.pixels(52, 3f, 100))
    }

    @Test fun negativeSavedCoordinatesRemainValid() {
        assertEquals(-20, OverlayGeometry.clampAxis(-20, 1080, 80, 52, 1f))
    }

    @Test fun allEdgesAllowPartialHidingButRetainActualCircle() {
        for (density in listOf(1f, 2.625f, 3f, 4f)) {
            for (scale in 50..150 step 5) {
                val window = OverlayGeometry.pixels(80, density, scale)
                val button = OverlayGeometry.pixels(52, density, scale)
                val margin = (window - button) / 2
                for (screen in listOf(720, 1080, 2400)) {
                    val left = OverlayGeometry.clampAxis(-10000, screen, window, button, density)
                    val right = OverlayGeometry.clampAxis(10000, screen, window, button, density)
                    assertTrue(left + margin < 0)
                    assertTrue(left + margin + button > 0)
                    assertTrue(right + margin < screen)
                    assertTrue(right + margin + button > screen)
                    assertEquals(left + margin + button, screen - right - margin)
                    // The screen corner must intersect the actual circular button,
                    // not merely the transparent square surrounding it.
                    val radius = button / 2f
                    val center = left + margin + radius
                    assertTrue(2f * center * center < radius * radius)
                    assertEquals(left, OverlayGeometry.clampAxis(left, screen, window, button, density))
                    assertEquals(right, OverlayGeometry.clampAxis(right, screen, window, button, density))
                }
            }
        }
    }

    @Test fun shrinkingAndRotationRecoverPreviouslyHiddenPositions() {
        val smallWindow = OverlayGeometry.pixels(80, 3f, 50)
        val smallButton = OverlayGeometry.pixels(52, 3f, 50)
        val recovered = OverlayGeometry.clampAxis(-240, 1080, smallWindow, smallButton, 3f)
        assertTrue(recovered > -240)
        val rotated = OverlayGeometry.clampAxis(2200, 1080, smallWindow, smallButton, 3f)
        assertTrue(rotated < 1080)
    }
}
