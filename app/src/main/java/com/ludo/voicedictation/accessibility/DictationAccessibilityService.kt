package com.ludo.voicedictation.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.InputMethod
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import android.widget.Toast
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.EditorInfo
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.ludo.voicedictation.R
import com.ludo.voicedictation.asr.SpeechEngine
import com.ludo.voicedictation.overlay.OverlayManager
import com.ludo.voicedictation.data.AppPreferences

// Visibility evidence only, never an insertion target. A null/unfocused provider result
// can stand in for unavailable input focus while the active app's input session matches.
private fun unavailableFocusMatchesSession(
    sessionActive: Boolean,
    sessionPackage: String?,
    rootPackage: String?,
    rootWindowId: Int,
    activeApplicationWindow: Boolean,
    nodePresent: Boolean,
    nodePackage: String?,
    nodeWindowId: Int?,
    nodeFocused: Boolean,
    nodeEnabled: Boolean,
): Boolean = sessionActive && !sessionPackage.isNullOrBlank() &&
    rootPackage == sessionPackage && activeApplicationWindow &&
    (!nodePresent || (nodePackage == sessionPackage && nodeWindowId == rootWindowId && !nodeFocused && nodeEnabled))

class DictationAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "DictationA11yService"

        @Volatile
        var instance: DictationAccessibilityService? = null
            private set

        fun isServiceRunning(): Boolean = instance != null
    }

    private lateinit var overlayManager: OverlayManager
    private val prefs by lazy { AppPreferences(this) }
    private var sessionTargetNode: AccessibilityNodeInfo? = null
    private var systemUiSuppressingOverlay = false
    private var inputEditorActive = false
    private var inputEditorPackage: String? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    // Fixed-size counters retain every event folded into the existing debounce.
    private val pendingInput = LongArray(3)
    private val pendingEvents = LongArray(5)
    private val eventNames = arrayOf("view-focused", "view-clicked", "window-state", "windows-changed", "other-event")
    private fun eventIndex(type: Int): Int = when (type) {
        AccessibilityEvent.TYPE_VIEW_FOCUSED -> 0
        AccessibilityEvent.TYPE_VIEW_CLICKED -> 1
        AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> 2
        AccessibilityEvent.TYPE_WINDOWS_CHANGED -> 3
        else -> 4
    }
    private fun delayedTriggers(kind: String, counts: LongArray, names: Array<String>): Map<String, Long> =
        linkedMapOf("$kind-recheck" to 1L).apply {
            counts.forEachIndexed { index, count -> if (count != 0L) put("$kind-from-${names[index]}", count) }
        }
    private val reconcileAfterInputFinish = Runnable {
        val counts = pendingInput.copyOf()
        pendingInput.fill(0)
        refreshServiceState { delayedTriggers("input", counts, arrayOf("start", "restart", "finish")) }
    }
    private val reconcileAfterAccessibilityEvent = Runnable {
        val counts = pendingEvents.copyOf()
        pendingEvents.fill(0)
        refreshServiceState { delayedTriggers("accessibility", counts, eventNames) }
    }
    private var screenReceiverRegistered = false
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    sessionTargetNode = null
                    inputEditorActive = false
                    inputEditorPackage = null
                    mainHandler.removeCallbacks(reconcileAfterInputFinish)
                    mainHandler.removeCallbacks(reconcileAfterAccessibilityEvent)
                    pendingInput.fill(0)
                    pendingEvents.fill(0)
                    overlayManager.hide() // Explicit safety cancellation, not a focus transition.
                    recordDecision({ mapOf("screen-off" to 1L) }, "hide", "screen off")
                }
                Intent.ACTION_USER_PRESENT -> {
                    refreshServiceState { mapOf("user-present" to 1L) }
                }
            }
        }
    }

    private fun isScreenUsable(): Boolean =
        (getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive &&
            !(getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        OverlayDiagnostics.record { "service connected" }
        overlayManager = OverlayManager.getInstance(this)
        overlayManager.onLocalDictationStarted = { captureInsertionTarget() }
        overlayManager.onFinalTranscriptionReady = { text ->
            insertText(text)
        }
        overlayManager.onSessionIdle = {
            sessionTargetNode = null
            refreshServiceState { mapOf("session-idle" to 1L) }
        }
        ContextCompat.registerReceiver(this, screenReceiver,
            IntentFilter(Intent.ACTION_SCREEN_OFF).apply { addAction(Intent.ACTION_USER_PRESENT) },
            ContextCompat.RECEIVER_NOT_EXPORTED)
        screenReceiverRegistered = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val info = serviceInfo
            info.flags = info.flags or AccessibilityServiceInfo.FLAG_INPUT_METHOD_EDITOR
            setServiceInfo(info)
        }
        refreshServiceState { mapOf("service-connected" to 1L) }
        Log.i(TAG, "DictationAccessibilityService connected")
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun onCreateInputMethod(): InputMethod =
        object : InputMethod(this@DictationAccessibilityService) {
            override fun onStartInput(attribute: EditorInfo, restarting: Boolean) {
                inputEditorActive = true
                inputEditorPackage = attribute.packageName?.toString()
                mainHandler.removeCallbacks(reconcileAfterInputFinish)
                refreshServiceState { mapOf((if (restarting) "input-restart" else "input-start") to 1L) }
                // Input callbacks and the accessibility tree can arrive in either order.
                pendingInput[if (restarting) 1 else 0]++
                mainHandler.postDelayed(reconcileAfterInputFinish, 120L)
            }

            override fun onFinishInput() {
                inputEditorActive = false
                inputEditorPackage = null
                mainHandler.removeCallbacks(reconcileAfterInputFinish)
                // A new editor may start immediately after this callback. Re-evaluate once
                // the system has delivered the complete input-focus transition.
                pendingInput[2]++
                mainHandler.postDelayed(reconcileAfterInputFinish, 120L)
            }
        }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !prefs.serviceEnabled) {
            return
        }
        val index = eventIndex(event.eventType)
        val trigger = { mapOf("event-${eventNames[index]}" to 1L) }
        if (prefs.alwaysShowOverlay) {
            systemUiSuppressingOverlay = false
            return
        }
        if (!prefs.avoidAutofillOverlayCollisions) systemUiSuppressingOverlay = false
        val activeInput = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        val editorPackage = (if (isFocusedEditableNode(activeInput)) activeInput?.packageName else null)
            ?.toString() ?: inputEditorPackage

        // Autofill dropdowns and other transient system/app windows can appear while the
        // underlying editor remains focused. Do not re-add our application overlay above them.
        if (prefs.avoidAutofillOverlayCollisions && editorPackage != null &&
            isCompetingWindow(event, editorPackage)) {
            systemUiSuppressingOverlay = true
            mainHandler.removeCallbacks(reconcileAfterAccessibilityEvent)
            pendingEvents.fill(0)
            overlayManager.hideTemporarily()
            recordDecision(trigger, "hide temporarily", "competing-window") { diagnosticEventWindow(event) }
            return
        }

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                // A clicked or accessibility-focused source is not proof of keyboard focus.
                if (systemUiSuppressingOverlay && !hasCompetingWindow(editorPackage)) {
                    systemUiSuppressingOverlay = false
                }
                if (!systemUiSuppressingOverlay) handleFocusEvent(activeInput, trigger)
                else recordDecision(trigger, "keep current", "competing-window") { diagnosticEventWindow(event) }
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                if (systemUiSuppressingOverlay) {
                    if (hasCompetingWindow(editorPackage)) {
                        overlayManager.hideTemporarily()
                        recordDecision(trigger, "hide temporarily", "competing-window") { diagnosticEventWindow(event) }
                    } else {
                        systemUiSuppressingOverlay = false
                        handleFocusEvent(activeInput, trigger)
                    }
                } else {
                    handleFocusEvent(activeInput, trigger)
                }
            }
        }
        // A click is only a reason to re-read focus, never evidence of an editor.
        // Some providers update their tree just after the event was delivered.
        if (!systemUiSuppressingOverlay) {
            mainHandler.removeCallbacks(reconcileAfterAccessibilityEvent)
            pendingEvents[index]++
            mainHandler.postDelayed(reconcileAfterAccessibilityEvent, 120L)
        }
    }

    /** True when a non-IME window from another package has taken focus or changed window state. */
    private fun isCompetingWindow(event: AccessibilityEvent, editorPackage: String): Boolean {
        val eventPackage = event.packageName?.toString()
        val eventWindow = windows.firstOrNull { it.id == event.windowId }
        if (eventWindow?.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD) return false
        if (eventPackage != null && eventPackage != editorPackage && eventPackage != packageName) {
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return true
            if (event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED && eventWindow != null) return true
        }
        return hasCompetingWindow(editorPackage)
    }

    private fun hasCompetingWindow(editorPackage: String?): Boolean {
        if (editorPackage == null) return false
        return windows.any { window ->
            if (!window.isFocused || window.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD) return@any false
            val owner = runCatching { window.root?.packageName?.toString() }.getOrNull()
            owner != null && owner != editorPackage && owner != packageName
        }
    }

    /** Read-only: never refreshes the service, changes the overlay, or reads field contents. */
    fun recordInitialDiagnosticsSnapshot() {
        OverlayDiagnostics.snapshot {
            if (!::overlayManager.isInitialized) "initial snapshot: service unavailable"
            else "initial snapshot: ${try { diagnosticCurrentFacts() } catch (_: Exception) { "state unavailable" }}"
        }
    }

    private fun diagnosticCurrentFacts(): String {
        val root = try { rootInActiveWindow } catch (_: Exception) { null }
        val node = try { root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) } catch (_: Exception) { null }
        val window = try { root?.let { active -> windows.firstOrNull { it.id == active.windowId } } } catch (_: Exception) { null }
        return diagnosticFacts(root, node, window)
    }

    private fun diagnosticFacts(root: AccessibilityNodeInfo?, node: AccessibilityNodeInfo?, window: AccessibilityWindowInfo?): String {
        fun safe(block: () -> Any?): String = try { block()?.toString() ?: "unknown" } catch (_: Exception) { "unknown" }
        val engine = SpeechEngine.getInstance(this)
        return "Settings: enabled=${safe { prefs.serviceEnabled }} screenUsable=${safe { isScreenUsable() }}" +
            " alwaysVisible=${safe { prefs.alwaysShowOverlay }} collisionFilter=${safe { prefs.avoidAutofillOverlayCollisions }}" +
            " collisionSuppressed=$systemUiSuppressingOverlay\n" +
            "  Session: local=${safe { engine.isLocalSessionActive }}" +
            " externalSession=${safe { engine.isExternalSessionActive }} inputSession=$inputEditorActive" +
            " inputPackage=${safe { OverlayDiagnostics.packageName(this, inputEditorPackage) }}\n" +
            "  Window: rootPackage=${safe { OverlayDiagnostics.packageName(this, root?.packageName) }}" +
            " rootWindowId=${safe { root?.windowId }} windowType=${safe { window?.type }}" +
            " rootMatchesInput=${safe { root?.packageName?.toString()?.let { it == inputEditorPackage } }}" +
            " nodeMatchesInput=${safe { node?.packageName?.toString()?.let { it == inputEditorPackage } }}\n" +
            "  Field: ${safe { OverlayDiagnostics.node(this, node) }}\n" +
            "  Overlay: ${safe { overlayManager.diagnosticState() }}"
    }

    private fun diagnosticEventWindow(event: AccessibilityEvent): String {
        val windowType = try { windows.firstOrNull { it.id == event.windowId }?.type?.toString() ?: "unknown" }
            catch (_: Exception) { "unknown" }
        return "\n  Trigger window: id=${event.windowId} type=$windowType package=${OverlayDiagnostics.packageName(this, event.packageName)}"
    }

    private fun recordDecision(trigger: () -> Map<String, Long>, decision: String, reason: String, extra: () -> String = { "" }) {
        OverlayDiagnostics.decision(trigger) {
            "Decision: $decision — $reason\n  ${diagnosticCurrentFacts()}${extra()}"
        }
    }

    private fun recordFocusDecision(trigger: () -> Map<String, Long>, decision: String, reason: String,
        root: AccessibilityNodeInfo?, node: AccessibilityNodeInfo?, window: AccessibilityWindowInfo?) {
        OverlayDiagnostics.decision(trigger) {
            val generic = "session mismatch or no eligible editor"
            val detail = if (!reason.contains(generic)) reason else {
                val why = try {
                    when {
                        !inputEditorActive -> "no focused editable field and no active input session"
                        inputEditorPackage == null -> "no focused editable field and input-session package unavailable"
                        root?.packageName?.toString() != inputEditorPackage -> "no focused editable field and input session does not match root package"
                        node == null -> "focused editor unavailable"
                        node.packageName?.toString() != inputEditorPackage -> "focused node does not match input-session package"
                        !node.isFocused -> "node is not input-focused"
                        !node.isEnabled -> "focused node is disabled"
                        else -> "focused node is not an eligible editor"
                    }
                } catch (_: Exception) { generic }
                reason.replace(generic, why)
            }
            "Decision: $decision — $detail\n  ${diagnosticFacts(root, node, window)}"
        }
    }

    fun refreshServiceState(trigger: () -> Map<String, Long> = { mapOf("refresh-requested" to 1L) }) {
        if (!prefs.avoidAutofillOverlayCollisions) systemUiSuppressingOverlay = false
        val serviceEnabled = prefs.serviceEnabled
        if (!serviceEnabled || !isScreenUsable()) {
            val reason = if (!serviceEnabled) "disabled" else "locked or screen off"
            inputEditorActive = false
            inputEditorPackage = null
            mainHandler.removeCallbacks(reconcileAfterInputFinish)
            mainHandler.removeCallbacks(reconcileAfterAccessibilityEvent)
            pendingInput.fill(0)
            pendingEvents.fill(0)
            overlayManager.hide()
            recordDecision(trigger, "hide", reason)
        } else if (prefs.alwaysShowOverlay) {
            systemUiSuppressingOverlay = false
            overlayManager.show()
            recordDecision(trigger, "show", "always visible")
        } else if (prefs.avoidAutofillOverlayCollisions &&
            (rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                ?.takeIf(::isFocusedEditableNode)?.packageName?.toString()
                ?: inputEditorPackage)
                ?.let(::hasCompetingWindow) == true) {
            systemUiSuppressingOverlay = true
            overlayManager.hideTemporarily()
            recordDecision(trigger, "hide temporarily", "competing-window")
        } else if (systemUiSuppressingOverlay) {
            val editorPackage = inputEditorPackage
            if (hasCompetingWindow(editorPackage)) {
                overlayManager.hideTemporarily()
                recordDecision(trigger, "hide temporarily", "competing-window")
            } else {
                systemUiSuppressingOverlay = false
                handleFocusEvent(rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT), trigger)
            }
        } else if (SpeechEngine.getInstance(this).isExternalSessionActive) {
            // Keep the status indicator visible during an externally owned session, even if
            // the external app's current window has no focused editable node.
            overlayManager.show()
            recordDecision(trigger, "show", "external session active")
        } else {
            handleFocusEvent(rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT), trigger)
        }
    }

    private fun handleFocusEvent(node: AccessibilityNodeInfo?, trigger: () -> Map<String, Long>) {
        val speechEngine = SpeechEngine.getInstance(this)
        if (!isScreenUsable()) {
            overlayManager.hide()
            recordDecision(trigger, "hide", "locked or screen off")
            return
        }
        val root = rootInActiveWindow
        val currentWindows = windows
        val window = root?.let { active -> currentWindows.firstOrNull { it.id == active.windowId } }
        // Never mistake an IME suggestion/search control or system overlay for the app editor.
        // Some providers omit the window from the interactive windows list.
        val applicationWindow = root != null &&
            (window == null || window.type == AccessibilityWindowInfo.TYPE_APPLICATION)
        var reason = "unknown"
        val eligibleEditor = if (!applicationWindow || (node != null && node.windowId != root?.windowId)) {
            reason = if (root == null) "no root" else "wrong window"
            false
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Preserve short-circuit evaluation and use only already-required focus queries.
            if (isFocusedEditableNode(node)) {
                reason = "primary editable"
                true
            } else if (root != null && unavailableFocusMatchesSession(
                    sessionActive = inputEditorActive,
                    sessionPackage = inputEditorPackage,
                    rootPackage = root.packageName?.toString(),
                    rootWindowId = root.windowId,
                    // rootInActiveWindow supplies the active root. If window metadata is
                    // present, also require input focus; reject a known competing focused
                    // non-IME window even when this root is absent from the window list.
                    activeApplicationWindow = applicationWindow &&
                        (window == null || (window.isActive && window.isFocused)) &&
                        currentWindows.none { it.id != root.windowId && it.isFocused &&
                            it.type != AccessibilityWindowInfo.TYPE_INPUT_METHOD },
                    nodePresent = node != null,
                    nodePackage = node?.packageName?.toString(),
                    nodeWindowId = node?.windowId,
                    nodeFocused = node?.isFocused == true,
                    nodeEnabled = node?.isEnabled == true,
                )) {
                reason = if (node == null) "matched session null node" else "matched session unfocused node"
                true
            } else if (inputEditorActive && root != null && inputEditorPackage != null &&
                root.packageName?.toString() == inputEditorPackage &&
                node != null && node.packageName?.toString() == inputEditorPackage &&
                isFocusedInputSessionEditor(node)) {
                reason = "matched session custom editor"
                true
            } else {
                reason = "session mismatch or no eligible editor"
                false
            }
        } else {
            val primary = isFocusedEditableNode(node)
            reason = if (primary) "primary editable" else "no eligible editor"
            primary
        }
        if (eligibleEditor) {
            overlayManager.show()
            recordFocusDecision(trigger, "show", reason, root, node, window)
        } else if (!speechEngine.isLocalSessionActive && !speechEngine.isExternalSessionActive) {
            overlayManager.hide()
            recordFocusDecision(trigger, "hide", reason, root, node, window)
        } else {
            recordFocusDecision(trigger, "keep current", "session active; focus=$reason", root, node, window)
        }
        // A transient shade or focus change must not cancel an active recording. Normal
        // idle-focus hiding is re-evaluated after delivery; explicit hide/lock remains separate.
    }

    private fun isFocusedEditableNode(node: AccessibilityNodeInfo?): Boolean =
        node != null && node.isFocused && node.isEnabled && node.isEditable

    /** Fallback is only used with a matching, live Android 13+ input session. */
    private fun isFocusedInputSessionEditor(node: AccessibilityNodeInfo): Boolean {
        if (!node.isFocused || !node.isEnabled) return false
        if (node.isEditable) return true
        if (node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT }) return true
        val className = node.className?.toString() ?: return false
        return className.endsWith("EditText", ignoreCase = true) ||
            className.endsWith("AutoCompleteTextView", ignoreCase = true)
    }

    private fun isEditableNode(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        if (node.isEditable) return true
        val className = node.className?.toString() ?: ""
        return className.contains("EditText", ignoreCase = true) ||
                className.contains("AutoCompleteTextView", ignoreCase = true) ||
                className.contains("SearchView", ignoreCase = true)
    }

    private fun isInsertionEditor(node: AccessibilityNodeInfo?): Boolean {
        if (node == null || !node.isFocused || !node.isEnabled) return false
        if (isEditableNode(node)) return true
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            inputEditorActive && inputEditorPackage != null &&
            node.packageName?.toString() == inputEditorPackage &&
            isFocusedInputSessionEditor(node)
    }

    private fun captureInsertionTarget() {
        sessionTargetNode = null
        val active = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        // Only the actual input-focused node is a valid insertion target. A remembered node
        // can be stale after focus moves, so an absent active editor means clipboard delivery.
        val target = active?.takeIf(::isInsertionEditor)
        sessionTargetNode = target?.let { AccessibilityNodeInfo.obtain(it) }
        OverlayDiagnostics.record { "insertion target=${if (target == null) "unavailable" else "captured"} ${OverlayDiagnostics.node(this, active)}" }
    }

    fun insertText(text: String) {
        if (!prefs.serviceEnabled || !isScreenUsable()) {
            OverlayDiagnostics.record { "delivery skipped reason=disabled or locked" }
            sessionTargetNode = null
            return
        }
        val targetNode = sessionTargetNode
        if (targetNode == null || !isOriginalTargetFocused(targetNode)) {
            val copied = copyToClipboard(text)
            OverlayDiagnostics.record { "Delivery: clipboard fallback — ${if (targetNode == null) "no captured target" else "original target no longer focused"}; copy=${if (copied) "accepted" else "failed"}" }
            if (copied) showCopiedToast()
            sessionTargetNode = null
            return
        }

        try {
            // Let the editor perform a real paste at its current cursor or selection.
            // Rebuilding node.text would lose editor state, mishandle placeholders, and
            // could append to the wrong position. No automatic whitespace is added.
            if (!copyToClipboard(text)) {
                OverlayDiagnostics.record { "paste skipped reason=clipboard failure" }
                return
            }
            // The retained node is not permission to modify a previously focused field.
            // Re-check immediately before paste; leave the transcript on the clipboard
            // if focus moved or the editor refuses the paste action.
            if (!isOriginalTargetFocused(targetNode)) {
                OverlayDiagnostics.record { "paste skipped reason=original target no longer focused; clipboard retained" }
                Log.w(TAG, "Paste unavailable; transcript remains on clipboard")
                showCopiedToast()
            } else if (!targetNode.performAction(AccessibilityNodeInfo.ACTION_PASTE)) {
                OverlayDiagnostics.record { "paste action=rejected; clipboard retained" }
                Log.w(TAG, "Paste unavailable; transcript remains on clipboard")
                showCopiedToast()
            } else {
                OverlayDiagnostics.record { "paste action=accepted by target (delivery not independently verified)" }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to insert text: ${e.message}", e)
            val copied = copyToClipboard(text)
            OverlayDiagnostics.record { "Delivery: clipboard fallback — paste exception; copy=${if (copied) "accepted" else "failed"}" }
            if (copied) showCopiedToast()
        } finally {
            sessionTargetNode = null
        }
    }

    private fun isOriginalTargetFocused(target: AccessibilityNodeInfo): Boolean {
        if (!target.refresh() || !isInsertionEditor(target)) return false
        val root = rootInActiveWindow ?: return false
        if (root.windowId != target.windowId) return false
        val active = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        return isInsertionEditor(active) && active == target
    }

    private fun copyToClipboard(text: String): Boolean {
        return try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Dictated Text", text)
            clipboard.setPrimaryClip(clip)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Clipboard copy error: ${e.message}")
            false
        }
    }

    private fun showCopiedToast() {
        Toast.makeText(this, R.string.dictation_copied_to_clipboard, Toast.LENGTH_SHORT).show()
    }

    override fun onInterrupt() {
        OverlayDiagnostics.record { "service interrupted" }
        Log.w(TAG, "DictationAccessibilityService interrupted")
        sessionTargetNode = null
        inputEditorActive = false
        inputEditorPackage = null
        mainHandler.removeCallbacks(reconcileAfterInputFinish)
        mainHandler.removeCallbacks(reconcileAfterAccessibilityEvent)
        pendingInput.fill(0)
        pendingEvents.fill(0)
        if (::overlayManager.isInitialized) {
            overlayManager.hide()
            recordDecision({ mapOf("service-interrupted" to 1L) }, "hide", "service interrupted")
        }
    }

    override fun onDestroy() {
        OverlayDiagnostics.record { "service destroyed" }
        super.onDestroy()
        if (screenReceiverRegistered) {
            unregisterReceiver(screenReceiver)
            screenReceiverRegistered = false
        }
        mainHandler.removeCallbacks(reconcileAfterInputFinish)
        mainHandler.removeCallbacks(reconcileAfterAccessibilityEvent)
        if (::overlayManager.isInitialized) {
            overlayManager.destroy()
            recordDecision({ mapOf("service-destroyed" to 1L) }, "destroy", "service destroyed")
        }
        sessionTargetNode = null
        instance = null
        Log.i(TAG, "DictationAccessibilityService destroyed")
    }
}
