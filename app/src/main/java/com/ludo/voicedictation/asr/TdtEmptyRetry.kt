package com.ludo.voicedictation.asr

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CancellationException

/** Published only after PCM writes; completion means no more original samples can arrive. */
internal class CapturedAudioProgress {
    private data class State(val count: Int, val finished: Boolean)
    private val state = MutableStateFlow(State(0, false))

    @Synchronized fun publish(count: Int) {
        val old = state.value
        require(!old.finished && count >= old.count)
        state.value = State(count, false)
    }

    @Synchronized fun finish() {
        val old = state.value
        state.value = State(old.count, true)
    }

    suspend fun availableThrough(end: Int): Int {
        require(end >= 0)
        return state.first { it.count >= end || it.finished }.count
    }
}

/** Original context around the unchanged decoder input; no extra outer silence. */
internal object TdtEmptyRetry {
    const val CONTEXT = 1600 // 100 ms at 16 kHz
    const val EXTRA_SAMPLES = 2 * CONTEXT
    const val DITHER = 0.001f

    enum class Status { NOT_NEEDED, ATTEMPTED, LIMIT, CANCELLED }
    data class Outcome(val text: String, val status: Status, val available: Int = 0)

    fun prepare(input: FloatArray, start: Int, end: Int, available: Int, capacity: Int,
                suppressRight: Boolean = false,
                original: (Int) -> Float): FloatArray? {
        require(start >= 0 && end > start && available >= end)
        require(capacity >= 0)
        val left = minOf(start, CONTEXT)
        // A trimmed VAD retry must not add zeros or any of the removed negative tail.
        val leftSize = if (suppressRight) left else CONTEXT
        val rightSize = if (suppressRight) 0 else CONTEXT
        if (input.size > capacity - leftSize - rightSize) return null
        val result = FloatArray(input.size + leftSize + rightSize)
        for (i in 0 until left) result[leftSize - left + i] = original(start - left + i)
        input.copyInto(result, leftSize)
        val right = if (suppressRight) 0 else minOf(available - end, CONTEXT)
        for (i in 0 until right) result[leftSize + input.size + i] = original(end + i)
        return result
    }

    fun static(input: FloatArray, original: FloatArray, start: Int, end: Int, capacity: Int,
               offline: Boolean, active: () -> Boolean, decode: (FloatArray) -> String,
               retryDecode: (FloatArray) -> String,
               onRetry: (Status) -> Unit): String {
        val text = decode(input)
        if (text.isNotBlank() || !offline || !active()) return text
        val retry = prepare(input, start, end, original.size, capacity) { original[it] }
        if (retry == null) {
            onRetry(Status.LIMIT)
            return text
        }
        if (!active()) return ""
        onRetry(Status.ATTEMPTED)
        return retryDecode(retry)
    }

    /** A failed retry keeps the original blank result; restoration failure must propagate. */
    fun withDitheredRecognizer(initialDither: Float, switch: (Float) -> Unit, decode: () -> String,
                               onFailure: (Throwable) -> Unit): String {
        require(initialDither == 0f || initialDither == DITHER)
        val needsSwitch = initialDither != DITHER
        try {
            return try {
                if (needsSwitch) switch(DITHER)
                decode()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onFailure(e)
                ""
            } catch (e: LinkageError) {
                onFailure(e)
                ""
            }
        } finally {
            // Even a failed creation or cancellation must restore the original engine.
            if (needsSwitch) switch(initialDither)
        }
    }

    suspend fun live(text: String, input: FloatArray, start: Int, end: Int, capacity: Int,
                     suppressRight: Boolean,
                     progress: CapturedAudioProgress, original: (Int) -> Float,
                     active: () -> Boolean, decode: (FloatArray) -> String): Outcome {
        if (text.isNotBlank()) return Outcome(text, Status.NOT_NEEDED)
        if (!active()) return Outcome(text, Status.CANCELLED)
        val extra = if (suppressRight) minOf(start, CONTEXT) else EXTRA_SAMPLES
        if (input.size > capacity - extra) return Outcome(text, Status.LIMIT)
        val available = progress.availableThrough(if (suppressRight) end else end + CONTEXT)
        if (!active()) return Outcome(text, Status.CANCELLED, available)
        val retry = checkNotNull(prepare(input, start, end, available, capacity, suppressRight, original))
        return Outcome(decode(retry), Status.ATTEMPTED, available)
    }
}
