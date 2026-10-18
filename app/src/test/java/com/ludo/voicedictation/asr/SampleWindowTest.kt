package com.ludo.voicedictation.asr

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class SampleWindowTest {
    @Test fun wrapsWithoutDuplicatingSamples() {
        val window = SampleWindow(5)
        window.append(floatArrayOf(1f, 2f, 3f, 4f))
        window.discardBefore(3)
        window.append(floatArrayOf(5f, 6f, 7f))
        assertArrayEquals(floatArrayOf(4f, 5f, 6f, 7f), window.snapshot(), 0f)
    }

    @Test fun preservesAbsoluteOffsetsAfterSilence() {
        val window = SampleWindow(5)
        window.append(floatArrayOf(1f, 2f, 3f))
        window.keepLast(1)
        window.append(floatArrayOf(4f, 5f))
        window.discardBefore(4)
        assertArrayEquals(floatArrayOf(5f), window.snapshot(), 0f)
    }

    @Test(expected = IllegalStateException::class) fun rejectsOverflow() {
        SampleWindow(2).append(floatArrayOf(1f, 2f, 3f))
    }
}