package com.ludo.voicedictation.asr

import kotlinx.coroutines.channels.Channel
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReferenceArray

/** Provisional independent offline decodes, never the source of the final result.
 * Capture publishes immutable 200 ms blocks without waiting for the recognizer or VAD.
 */
internal class FluidPreview(
    private val blockSamples: Int,
    maxSamples: Int,
    private val windowSamples: Int,
    private val intervalSamples: Int
) {
    private val blocks = AtomicReferenceArray<ShortArray>((maxSamples + blockSamples - 1) / blockSamples)
    private val available = AtomicInteger(0)
    private val pauses = ConcurrentLinkedQueue<Int>()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    // Unknown, silent, speaking, permanently unavailable. Failure can race a VAD frame.
    private val vad = AtomicInteger(0)
    private var start = 0 // preview worker only
    private var lastAttemptEnd = 0 // preview worker only
    private val committed = ArrayList<String>()

    /** Exactly one capture producer; blocks are full-sized except for the final stopped block. */
    fun append(block: ShortArray) {
        val offset = available.get()
        if (offset >= blocks.length() * blockSamples || offset % blockSamples != 0) return
        blocks.set(offset / blockSamples, block)
        available.set(offset + block.size)
        // Cheap and nonblocking. Notifications collapse while inference is busy.
        wake.trySend(Unit)
    }

    /** Sample offset is the end of the actual VAD frame, not the capture queue position. */
    fun vadState(value: Boolean, frameEnd: Int) {
        val next = if (value) 2 else 1
        var previous: Int
        do {
            previous = vad.get()
            if (previous == 3) return
        } while (!vad.compareAndSet(previous, next))
        if (previous == 2 && next == 1) pauses.add(frameEnd)
        wake.trySend(Unit)
    }

    fun vadUnavailable() {
        vad.set(3)
        wake.trySend(Unit)
    }

    fun close() { wake.close() }

    suspend fun run(active: () -> Boolean, decode: suspend (FloatArray) -> String, publish: (String) -> Unit) {
        for (ignored in wake) {
            while (active()) {
                val end = available.get()
                while (pauses.peek()?.let { it <= start } == true) pauses.poll()
                val limit = minOf(end, start + windowSamples)
                val pause = pauses.peek()?.takeIf { it <= limit }
                val silent = vad.get() == 1
                // Short pauses trigger a replacement preview but do not close a segment.
                // Prefer a boundary in the final 40% of the backend's window.
                val eligiblePause = pause != null && pause >= start + windowSamples * 3 / 5
                val commit = eligiblePause ||
                    (end - start >= windowSamples && (!silent || pauses.peek() != null))
                if (pause != null && !commit && pause <= lastAttemptEnd) {
                    pauses.poll()
                    continue
                }
                val target = when {
                    eligiblePause -> checkNotNull(pause)
                    commit -> start + windowSamples
                    pause != null -> pause
                    !silent && end - lastAttemptEnd >= intervalSamples -> end
                    else -> break
                }
                if (target <= start) break
                if (pause != null && pause <= target) pauses.poll()
                // Read only immutable blocks published before the acquire of available.
                val pcm = FloatArray(target - start)
                var source = start
                var destination = 0
                while (source < target) {
                    val block = checkNotNull(blocks.get(source / blockSamples))
                    val inBlock = source % blockSamples
                    val count = minOf(block.size - inBlock, target - source)
                    check(count > 0)
                    for (i in 0 until count) pcm[destination + i] = block[inBlock + i] / 32768f
                    source += count
                    destination += count
                }
                lastAttemptEnd = target
                val text = decode(pcm).trim()
                if (!active()) return
                if (commit) {
                    if (text.isNotEmpty()) committed.add(text)
                    start = target
                    lastAttemptEnd = start
                    publish(committed.joinToString(" "))
                    // A later queued boundary may already need work; otherwise wait for new audio.
                } else {
                    publish((committed + listOfNotNull(text.takeIf { it.isNotEmpty() })).joinToString(" "))
                    break
                }
            }
        }
    }
}
