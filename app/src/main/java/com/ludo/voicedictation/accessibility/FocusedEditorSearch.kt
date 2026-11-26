package com.ludo.voicedictation.accessibility

import android.os.Handler
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import java.util.ArrayDeque
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Bounded asynchronous fallback when a matching input session's primary focus lookup fails. */
internal class FocusedEditorSearch(
    private val service: DictationAccessibilityService,
    private val handler: Handler,
    private val isStillRelevant: (String, Int) -> Boolean,
    private val onResult: (String, Int, AccessibilityNodeInfo?) -> Unit,
) {
    private data class Key(val epoch: Long, val owner: String, val windowId: Int)
    private val epoch = AtomicLong()
    private val worker = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)
    private var key: Key? = null // Main thread only.
    private var early: Runnable? = null
    private var delayed: Runnable? = null
    @Volatile private var closed = false

    fun invalidate() {
        epoch.incrementAndGet()
        early?.let(handler::removeCallbacks)
        delayed?.let(handler::removeCallbacks)
        early = null
        delayed = null
        key = null
    }

    fun request(owner: String, windowId: Int) {
        val next = Key(epoch.get(), owner, windowId)
        if (closed || next == key) return
        early?.let(handler::removeCallbacks)
        delayed?.let(handler::removeCallbacks)
        key = next
        early = Runnable { launch(next) }
        delayed = Runnable { launch(next) }
        handler.postDelayed(early!!, 120L)
        handler.postDelayed(delayed!!, 800L)
    }

    private fun launch(expected: Key) {
        if (closed || key != expected || epoch.get() != expected.epoch ||
            !runCatching { isStillRelevant(expected.owner, expected.windowId) }.getOrDefault(false) ||
            !busy.compareAndSet(false, true)) return
        try {
            worker.execute {
                var result: AccessibilityNodeInfo? = null
                try {
                    result = scan(expected)
                    val candidate = result
                    if (closed || epoch.get() != expected.epoch) return@execute
                    // Transfer ownership only if the callback was actually queued.
                    if (handler.post {
                        try {
                            if (!closed && key == expected && epoch.get() == expected.epoch &&
                                runCatching { isStillRelevant(expected.owner, expected.windowId) }.getOrDefault(false)) {
                                runCatching { onResult(expected.owner, expected.windowId, candidate) }
                            }
                        } finally {
                            @Suppress("DEPRECATION")
                            candidate?.recycle()
                        }
                    }) result = null
                } catch (_: Exception) {
                    // A provider can disappear at any point; keep the normal hidden state.
                } finally {
                    @Suppress("DEPRECATION")
                    result?.recycle()
                    busy.set(false)
                }
            }
        } catch (_: Exception) {
            busy.set(false)
        }
    }

    /** Only a complete, unambiguous scan may return a candidate. Never read field content. */
    @Suppress("DEPRECATION")
    private fun scan(expected: Key): AccessibilityNodeInfo? {
        val started = SystemClock.uptimeMillis()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        var root: AccessibilityNodeInfo? = null
        var candidate: AccessibilityNodeInfo? = null
        var visited = 0
        var complete = true
        try {
            root = service.rootInActiveWindow
            if (root == null || root.windowId != expected.windowId ||
                root.packageName?.toString() != expected.owner) return null
            queue.addLast(root)
            root = null
            while (queue.isNotEmpty()) {
                if (closed || Thread.currentThread().isInterrupted || epoch.get() != expected.epoch ||
                    visited >= 256 || SystemClock.uptimeMillis() - started >= 300L) {
                    complete = false
                    break
                }
                val node = queue.removeFirst()
                try {
                    visited++
                    if (node.windowId == expected.windowId && node.packageName?.toString() == expected.owner &&
                        node.isFocused && node.isEnabled && node.isVisibleToUser && editorEvidence(node)) {
                        if (candidate != null) {
                            complete = false // Ambiguous focus: never guess between editors.
                            break
                        }
                        candidate = AccessibilityNodeInfo.obtain(node)
                    }
                    for (i in 0 until node.childCount) {
                        if (visited + queue.size >= 256 || SystemClock.uptimeMillis() - started >= 300L) {
                            complete = false
                            break
                        }
                        val child = node.getChild(i)
                        if (child == null) complete = false else queue.addLast(child)
                    }
                } finally {
                    node.recycle()
                }
                if (!complete) break
            }
            if (!complete || closed || epoch.get() != expected.epoch) return null
            val found = candidate
            candidate = null
            return found
        } catch (_: Exception) {
            return null
        } finally {
            root?.recycle()
            candidate?.recycle()
            while (queue.isNotEmpty()) queue.removeFirst().recycle()
        }
    }

    private fun editorEvidence(node: AccessibilityNodeInfo): Boolean {
        if (node.isEditable || node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT }) return true
        val name = node.className?.toString() ?: return false
        return name.endsWith("EditText", ignoreCase = true) ||
            name.endsWith("AutoCompleteTextView", ignoreCase = true)
    }

    fun close() {
        closed = true
        invalidate()
        worker.shutdownNow()
    }
}
