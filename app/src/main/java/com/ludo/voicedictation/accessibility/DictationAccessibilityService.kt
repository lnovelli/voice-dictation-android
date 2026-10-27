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
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.EditorInfo
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.ludo.voicedictation.R
import com.ludo.voicedictation.asr.SpeechEngine
import com.ludo.voicedictation.overlay.OverlayManager
import com.ludo.voicedictation.data.AppPreferences

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
    private var lastFocusedNode: AccessibilityNodeInfo? = null
    private var sessionTargetNode: AccessibilityNodeInfo? = null
    private var systemUiSuppressingOverlay = false
    private var inputEditorActive = false
    private var inputEditorPackage: String? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val reconcileAfterInputFinish = Runnable { refreshServiceState() }
    private var screenReceiverRegistered = false
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    lastFocusedNode = null
                    sessionTargetNode = null
                    inputEditorActive = false
                    inputEditorPackage = null
                    mainHandler.removeCallbacks(reconcileAfterInputFinish)
                    overlayManager.hide() // Explicit safety cancellation, not a focus transition.
                }
                Intent.ACTION_USER_PRESENT -> refreshServiceState()
            }
        }
    }

    private fun isScreenUsable(): Boolean =
        (getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive &&
            !(getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        overlayManager = OverlayManager.getInstance(this)
        overlayManager.onLocalDictationStarted = { captureInsertionTarget() }
        overlayManager.onFinalTranscriptionReady = { text ->
            insertText(text)
        }
        overlayManager.onSessionIdle = {
            sessionTargetNode = null
            refreshServiceState()
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
        refreshServiceState()
        Log.i(TAG, "DictationAccessibilityService connected")
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun onCreateInputMethod(): InputMethod =
        object : InputMethod(this@DictationAccessibilityService) {
            override fun onStartInput(attribute: EditorInfo, restarting: Boolean) {
                inputEditorActive = true
                inputEditorPackage = attribute.packageName?.toString()
                mainHandler.removeCallbacks(reconcileAfterInputFinish)
                if (!prefs.serviceEnabled || !isScreenUsable()) return

                val editorPackage = inputEditorPackage
                if (prefs.avoidAutofillOverlayCollisions && editorPackage != null &&
                    hasCompetingWindow(editorPackage)) {
                    systemUiSuppressingOverlay = true
                    overlayManager.hideTemporarily()
                } else {
                    systemUiSuppressingOverlay = false
                    overlayManager.show()
                }
            }

            override fun onFinishInput() {
                inputEditorActive = false
                inputEditorPackage = null
                mainHandler.removeCallbacks(reconcileAfterInputFinish)
                // A new editor may start immediately after this callback. Re-evaluate once
                // the system has delivered the complete input-focus transition.
                mainHandler.postDelayed(reconcileAfterInputFinish, 120L)
            }
        }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !prefs.serviceEnabled) return
        if (prefs.alwaysShowOverlay) {
            systemUiSuppressingOverlay = false
            return
        }
        if (!prefs.avoidAutofillOverlayCollisions) systemUiSuppressingOverlay = false
        val activeInput = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        val editorPackage = (if (isEditableNode(activeInput)) activeInput?.packageName else null)
            ?.toString() ?: inputEditorPackage ?: lastFocusedNode?.packageName?.toString()

        // Autofill dropdowns and other transient system/app windows can appear while the
        // underlying editor remains focused. Do not re-add our application overlay above them.
        if (prefs.avoidAutofillOverlayCollisions && editorPackage != null &&
            isCompetingWindow(event, editorPackage)) {
            systemUiSuppressingOverlay = true
            overlayManager.hideTemporarily()
            return
        }

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                val source = event.source
                if (isEditableNode(source) && source?.packageName?.toString() == event.packageName?.toString()) {
                    systemUiSuppressingOverlay = false
                    handleFocusEvent(source)
                } else if (systemUiSuppressingOverlay && !hasCompetingWindow(editorPackage)) {
                    systemUiSuppressingOverlay = false
                    handleFocusEvent(activeInput)
                }
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                if (systemUiSuppressingOverlay) {
                    if (hasCompetingWindow(editorPackage)) {
                        overlayManager.hideTemporarily()
                    } else {
                        systemUiSuppressingOverlay = false
                        handleFocusEvent(activeInput)
                    }
                } else {
                    handleFocusEvent(activeInput)
                }
            }
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

    fun refreshServiceState() {
        if (!prefs.avoidAutofillOverlayCollisions) systemUiSuppressingOverlay = false
        if (!prefs.serviceEnabled || !isScreenUsable()) {
            lastFocusedNode = null
            inputEditorActive = false
            inputEditorPackage = null
            mainHandler.removeCallbacks(reconcileAfterInputFinish)
            overlayManager.hide()
        } else if (prefs.alwaysShowOverlay) {
            systemUiSuppressingOverlay = false
            overlayManager.show()
        } else if (prefs.avoidAutofillOverlayCollisions &&
            (rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                ?.takeIf(::isEditableNode)?.packageName?.toString()
                ?: inputEditorPackage ?: lastFocusedNode?.packageName?.toString())
                ?.let(::hasCompetingWindow) == true) {
            systemUiSuppressingOverlay = true
            overlayManager.hideTemporarily()
        } else if (systemUiSuppressingOverlay) {
            val editorPackage = inputEditorPackage ?: lastFocusedNode?.packageName?.toString()
            if (hasCompetingWindow(editorPackage)) overlayManager.hideTemporarily()
            else {
                systemUiSuppressingOverlay = false
                handleFocusEvent(rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT))
            }
        } else if (SpeechEngine.getInstance(this).isExternalSessionActive) {
            // Keep the status indicator visible during an externally owned session, even if
            // the external app's current window has no focused editable node.
            overlayManager.show()
        } else {
            handleFocusEvent(rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT))
        }
    }

    private fun handleFocusEvent(node: AccessibilityNodeInfo?) {
        val speechEngine = SpeechEngine.getInstance(this)
        if (!isScreenUsable()) {
            lastFocusedNode = null
            overlayManager.hide()
            return
        }
        if (isEditableNode(node)) {
            lastFocusedNode = node
            overlayManager.show()
        } else {
            // Check if there's any active input focus in the window
            val activeInput = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (isEditableNode(activeInput)) {
                lastFocusedNode = activeInput
                overlayManager.show()
            } else if (inputEditorActive) {
                // Android 13+ can report an active editor through the input-method
                // callbacks even when its accessibility node is intentionally hidden.
                overlayManager.show()
            } else if (!speechEngine.isLocalSessionActive && !speechEngine.isExternalSessionActive) {
                lastFocusedNode = null
                overlayManager.hide()
            }
            // Expanding notification/quick-settings panels replaces rootInActiveWindow
            // with a non-editable SystemUI window. That is NOT a recording cancel.
            // Keep the active local or external status indicator until completion. Normal idle-focus
            // hiding is re-evaluated after delivery. Explicit hide/lock removes the local overlay;
            // external sessions remain owned and cancellable by their IPC client.
        }
    }

    private fun isEditableNode(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        if (node.isEditable) return true
        val className = node.className?.toString() ?: ""
        return className.contains("EditText", ignoreCase = true) ||
                className.contains("AutoCompleteTextView", ignoreCase = true) ||
                className.contains("SearchView", ignoreCase = true)
    }

    private fun captureInsertionTarget() {
        sessionTargetNode = null
        val active = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        // Only the actual input-focused node is a valid insertion target. A remembered node
        // can be stale after focus moves, so an absent active editor means clipboard delivery.
        val target = active?.takeIf { it.isFocused && isEditableNode(it) }
        sessionTargetNode = target?.let { AccessibilityNodeInfo.obtain(it) }
    }

    fun insertText(text: String) {
        if (!prefs.serviceEnabled || !isScreenUsable()) {
            sessionTargetNode = null
            return
        }
        val targetNode = sessionTargetNode
        if (targetNode == null || !isOriginalTargetFocused(targetNode)) {
            if (copyToClipboard(text)) showCopiedToast()
            sessionTargetNode = null
            return
        }

        try {
            // A retained target is not permission to modify an obscured or previously focused
            // app. Re-check immediately before every editor action; if focus moved, leave the
            // transcript on the clipboard instead of targeting the new field.
            check(isOriginalTargetFocused(targetNode)) {
                "The dictation target is no longer the active focused field."
            }
            val plan = DictationTextInsertion.plan(
                existingText = targetNode.text?.toString(),
                showingHint = targetNode.isShowingHintText,
                hintText = targetNode.hintText?.toString(),
                contentDescription = targetNode.contentDescription?.toString(),
                selectionStart = targetNode.textSelectionStart,
                selectionEnd = targetNode.textSelectionEnd,
                transcription = text
            )

            if (plan.method == DictationTextInsertion.Method.PASTE_AT_CURSOR) {
                // Some custom accessibility providers expose the drawn placeholder as
                // node.text without setting isShowingHintText. Pasting only the transcript
                // lets the editor mutate its real input buffer, without copying that hint.
                if (!isOriginalTargetFocused(targetNode)) {
                    if (copyToClipboard(text)) showCopiedToast()
                    return
                }
                if (!copyToClipboard(plan.text)) return
                if (!isOriginalTargetFocused(targetNode)) {
                    copyToClipboard(text)
                    showCopiedToast()
                    return
                }
                if (!targetNode.performAction(AccessibilityNodeInfo.ACTION_PASTE)) {
                    Log.w(TAG, "Paste action rejected; transcript remains on clipboard")
                }
                return
            }

            val arguments = Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    plan.text
                )
            }

            if (!isOriginalTargetFocused(targetNode)) {
                if (copyToClipboard(text)) showCopiedToast()
                return
            }
            if (!targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)) {
                Log.i(TAG, "Set-text action rejected; attempting clipboard paste fallback")
                if (copyToClipboard(text)) {
                    if (isOriginalTargetFocused(targetNode)) {
                        if (!targetNode.performAction(AccessibilityNodeInfo.ACTION_PASTE)) {
                            Log.w(TAG, "Paste fallback rejected; transcript remains on clipboard")
                        }
                    } else {
                        showCopiedToast()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to insert text: ${e.message}", e)
            copyToClipboard(text)
        } finally {
            sessionTargetNode = null
        }
    }

    private fun isOriginalTargetFocused(target: AccessibilityNodeInfo): Boolean {
        if (!target.refresh() || !target.isFocused || !isEditableNode(target)) return false
        val root = rootInActiveWindow ?: return false
        if (root.windowId != target.windowId) return false
        val active = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        return active.isFocused && isEditableNode(active) && active == target
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
        Log.w(TAG, "DictationAccessibilityService interrupted")
        lastFocusedNode = null
        sessionTargetNode = null
        inputEditorActive = false
        inputEditorPackage = null
        mainHandler.removeCallbacks(reconcileAfterInputFinish)
        if (::overlayManager.isInitialized) overlayManager.hide()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (screenReceiverRegistered) {
            unregisterReceiver(screenReceiver)
            screenReceiverRegistered = false
        }
        mainHandler.removeCallbacks(reconcileAfterInputFinish)
        if (::overlayManager.isInitialized) overlayManager.destroy()
        sessionTargetNode = null
        instance = null
        Log.i(TAG, "DictationAccessibilityService destroyed")
    }
}
