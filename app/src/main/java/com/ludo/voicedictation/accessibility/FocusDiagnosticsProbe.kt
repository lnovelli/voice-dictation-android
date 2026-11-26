package com.ludo.voicedictation.accessibility

import android.os.Handler
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import java.util.ArrayDeque
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Opt-in observations only: never chooses a target or changes overlay/focus state. */
internal class FocusDiagnosticsProbe(
    private val service: DictationAccessibilityService,
    private val handler: Handler,
    private val stillActive: (String, Int) -> Boolean,
) {
    private data class Key(val generation: Long, val epoch: Long, val owner: String, val windowId: Int)
    private val executor = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)
    private val epoch = AtomicLong()
    private var key: Key? = null // Main thread only.
    private var first: Runnable? = null
    private var delayed: Runnable? = null
    @Volatile private var closed = false

    /** Invalidate pending input observations on input lifecycle or service changes. */
    fun invalidate() {
        epoch.incrementAndGet()
        first?.let(handler::removeCallbacks)
        delayed?.let(handler::removeCallbacks)
        first = null
        delayed = null
        key = null
    }

    /** Called only when the normal decision rejected an editor in a matching input session. */
    fun request(owner: String, windowId: Int) {
        val generation = OverlayDiagnostics.recordingGeneration() ?: return
        val next = Key(generation, epoch.get(), owner, windowId)
        if (closed || next == key) return
        first?.let(handler::removeCallbacks)
        delayed?.let(handler::removeCallbacks)
        key = next
        first = Runnable { launch(next, "early") }
        delayed = Runnable { launch(next, "delayed") }
        handler.postDelayed(first!!, 150L)
        handler.postDelayed(delayed!!, 1000L)
    }

    private fun launch(expected: Key, phase: String) {
        if (closed || key != expected || epoch.get() != expected.epoch ||
            OverlayDiagnostics.recordingGeneration() != expected.generation) return
        val active = try { stillActive(expected.owner, expected.windowId) } catch (_: Exception) { false }
        if (!active) {
            OverlayDiagnostics.recordForGeneration(expected.generation) {
                "Focus probe: phase=$phase status=skipped; input session or active window changed"
            }
            return
        }
        if (!busy.compareAndSet(false, true)) {
            OverlayDiagnostics.recordForGeneration(expected.generation) {
                "Focus probe: phase=$phase status=skipped; previous probe still running"
            }
            return
        }
        try {
            executor.execute {
                try {
                    OverlayDiagnostics.recordForGeneration(expected.generation) {
                        val result = inspect(expected, phase)
                        if (epoch.get() == expected.epoch) result else null
                    }
                } finally {
                    busy.set(false)
                }
            }
        } catch (_: Exception) {
            busy.set(false)
            OverlayDiagnostics.recordForGeneration(expected.generation) {
                "Focus probe: phase=$phase status=unavailable"
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun inspect(expected: Key, phase: String): String {
        val started = SystemClock.uptimeMillis()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        var root: AccessibilityNodeInfo? = null
        var before: AccessibilityNodeInfo? = null
        var after: AccessibilityNodeInfo? = null
        var visited = 0
        var focusedEditable = 0
        var focusedEnabledEditors = 0
        var focusedEnabledEditable = 0
        var focusedEnabledSetText = 0
        var focusedEnabledClassEditor = 0
        var focusedEnabledPaste = 0
        var editable = 0
        var foreign = 0
        var status = "complete"
        val examples = ArrayList<String>(3)
        try {
            root = service.rootInActiveWindow
            if (root == null || root.windowId != expected.windowId ||
                root.packageName?.toString() != expected.owner) {
                return "Focus probe: phase=$phase status=skipped; active root changed or unavailable"
            }
            before = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            val beforeFacts = OverlayDiagnostics.node(service, before)
            queue.add(root)
            root = null // The queue owns this reference now.
            while (queue.isNotEmpty()) {
                if (Thread.currentThread().isInterrupted || epoch.get() != expected.epoch ||
                    OverlayDiagnostics.recordingGeneration() != expected.generation) {
                    status = "cancelled"
                    break
                }
                if (visited >= 256 || SystemClock.uptimeMillis() - started >= 300L) {
                    status = "truncated"
                    break
                }
                val node = queue.removeFirst()
                try {
                    visited++
                    if (node.windowId != expected.windowId || node.packageName?.toString() != expected.owner) {
                        foreign++
                    } else {
                        val actions = node.actionList
                        val setText = actions.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT }
                        val paste = actions.any { it.id == AccessibilityNodeInfo.ACTION_PASTE }
                        val className = node.className?.toString().orEmpty()
                        val classEditor = className.endsWith("EditText", ignoreCase = true) ||
                            className.endsWith("AutoCompleteTextView", ignoreCase = true)
                        val editorEvidence = node.isEditable || setText || classEditor
                        if (node.isEditable) editable++
                        if (node.isFocused && node.isEditable) focusedEditable++
                        if (node.isFocused && node.isEnabled) {
                            if (editorEvidence) focusedEnabledEditors++
                            if (node.isEditable) focusedEnabledEditable++
                            if (setText) focusedEnabledSetText++
                            if (classEditor) focusedEnabledClassEditor++
                            if (editorEvidence && paste) focusedEnabledPaste++
                            if (editorEvidence && examples.size < 3) {
                                examples.add(OverlayDiagnostics.node(service, node) + " pasteAction=$paste")
                            }
                        }
                    }
                    for (i in 0 until node.childCount) {
                        if (visited + queue.size >= 256 || SystemClock.uptimeMillis() - started >= 300L) {
                            status = "truncated"
                            break
                        }
                        val child = node.getChild(i)
                        if (child == null) {
                            if (status == "complete") status = "partial"
                        } else queue.addLast(child)
                    }
                } finally {
                    node.recycle()
                }
                if (status == "truncated") break
            }
            val currentRoot = service.rootInActiveWindow
            val sameRoot = currentRoot != null && currentRoot.windowId == expected.windowId &&
                currentRoot.packageName?.toString() == expected.owner
            try {
                if (sameRoot) after = currentRoot?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            } finally {
                currentRoot?.recycle()
            }
            val afterFacts = if (sameRoot) OverlayDiagnostics.node(service, after) else "unavailable (root changed)"
            val elapsed = SystemClock.uptimeMillis() - started
            return "Focus probe: phase=$phase status=$status visited=$visited elapsedMs=$elapsed" +
                " rootStillMatches=$sameRoot inputLifecycleUnchanged=${epoch.get() == expected.epoch}\n" +
                "  Tree: editable=$editable focusedEditable=$focusedEditable focusedEnabledEditors=$focusedEnabledEditors" +
                " focusedEnabledEditable=$focusedEnabledEditable" +
                " focusedEnabledSetText=$focusedEnabledSetText focusedEnabledClassEditor=$focusedEnabledClassEditor" +
                " focusedEnabledEditorPaste=$focusedEnabledPaste foreignNodes=$foreign\n" +
                "  Primary before: $beforeFacts\n  Primary after: $afterFacts" +
                if (examples.isEmpty()) "\n  Focused editor examples: none observed" else
                    examples.joinToString(prefix = "\n  Focused editor examples: ", separator = " | ")
        } catch (_: Exception) {
            return "Focus probe: phase=$phase status=error visited=$visited; tree observation incomplete"
        } finally {
            root?.recycle()
            before?.recycle()
            after?.recycle()
            while (queue.isNotEmpty()) queue.removeFirst().recycle()
        }
    }

    fun close() {
        closed = true
        invalidate()
        executor.shutdownNow()
    }
}
