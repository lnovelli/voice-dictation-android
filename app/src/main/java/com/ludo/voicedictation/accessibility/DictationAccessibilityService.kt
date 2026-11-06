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
    private val reconcileAfterInputFinish = Runnable { refreshServiceState() }
    private val reconcileAfterAccessibilityEvent = Runnable { refreshServiceState() }
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
                refreshServiceState()
                // Input callbacks and the accessibility tree can arrive in either order.
                mainHandler.postDelayed(reconcileAfterInputFinish, 120L)
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
        val editorPackage = (if (isFocusedEditableNode(activeInput)) activeInput?.packageName else null)
            ?.toString() ?: inputEditorPackage

        // Autofill dropdowns and other transient system/app windows can appear while the
        // underlying editor remains focused. Do not re-add our application overlay above them.
        if (prefs.avoidAutofillOverlayCollisions && editorPackage != null &&
            isCompetingWindow(event, editorPackage)) {
            systemUiSuppressingOverlay = true
            mainHandler.removeCallbacks(reconcileAfterAccessibilityEvent)
            overlayManager.hideTemporarily()
            return
        }

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                // A clicked or accessibility-focused source is not proof of keyboard focus.
                if (systemUiSuppressingOverlay && !hasCompetingWindow(editorPackage)) {
                    systemUiSuppressingOverlay = false
                }
                if (!systemUiSuppressingOverlay) handleFocusEvent(activeInput)
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
        // A click is only a reason to re-read focus, never evidence of an editor.
        // Some providers update their tree just after the event was delivered.
        if (!systemUiSuppressingOverlay) {
            mainHandler.removeCallbacks(reconcileAfterAccessibilityEvent)
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

    fun refreshServiceState() {
        if (!prefs.avoidAutofillOverlayCollisions) systemUiSuppressingOverlay = false
        if (!prefs.serviceEnabled || !isScreenUsable()) {
            inputEditorActive = false
            inputEditorPackage = null
            mainHandler.removeCallbacks(reconcileAfterInputFinish)
            mainHandler.removeCallbacks(reconcileAfterAccessibilityEvent)
            overlayManager.hide()
        } else if (prefs.alwaysShowOverlay) {
            systemUiSuppressingOverlay = false
            overlayManager.show()
        } else if (prefs.avoidAutofillOverlayCollisions &&
            (rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                ?.takeIf(::isFocusedEditableNode)?.packageName?.toString()
                ?: inputEditorPackage)
                ?.let(::hasCompetingWindow) == true) {
            systemUiSuppressingOverlay = true
            overlayManager.hideTemporarily()
        } else if (systemUiSuppressingOverlay) {
            val editorPackage = inputEditorPackage
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
            overlayManager.hide()
            return
        }
        val root = rootInActiveWindow
        val window = root?.let { active -> windows.firstOrNull { it.id == active.windowId } }
        // Never mistake an IME suggestion/search control or system overlay for the app editor.
        // Some providers omit the window from the interactive windows list.
        val applicationWindow = root != null &&
            (window == null || window.type == AccessibilityWindowInfo.TYPE_APPLICATION)
        val eligibleEditor = if (!applicationWindow || (node != null && node.windowId != root?.windowId)) {
            false
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Trust an explicitly focused editable node even when the input-session
            // callback is missing. For less explicit custom-editor metadata, require the
            // matching live session; a focused unrelated button must not inherit it.
            isFocusedEditableNode(node) ||
                (inputEditorActive && root != null && inputEditorPackage != null &&
                    root.packageName?.toString() == inputEditorPackage &&
                    (node == null || (node.packageName?.toString() == inputEditorPackage &&
                        isFocusedInputSessionEditor(node))))
        } else {
            isFocusedEditableNode(node)
        }
        if (eligibleEditor) {
            overlayManager.show()
        } else if (!speechEngine.isLocalSessionActive && !speechEngine.isExternalSessionActive) {
            overlayManager.hide()
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
            // Let the editor perform a real paste at its current cursor or selection.
            // Rebuilding node.text would lose editor state, mishandle placeholders, and
            // could append to the wrong position. No automatic whitespace is added.
            if (!copyToClipboard(text)) return
            // The retained node is not permission to modify a previously focused field.
            // Re-check immediately before paste; leave the transcript on the clipboard
            // if focus moved or the editor refuses the paste action.
            if (!isOriginalTargetFocused(targetNode) ||
                !targetNode.performAction(AccessibilityNodeInfo.ACTION_PASTE)) {
                Log.w(TAG, "Paste unavailable; transcript remains on clipboard")
                showCopiedToast()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to insert text: ${e.message}", e)
            if (copyToClipboard(text)) showCopiedToast()
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
        Log.w(TAG, "DictationAccessibilityService interrupted")
        sessionTargetNode = null
        inputEditorActive = false
        inputEditorPackage = null
        mainHandler.removeCallbacks(reconcileAfterInputFinish)
        mainHandler.removeCallbacks(reconcileAfterAccessibilityEvent)
        if (::overlayManager.isInitialized) overlayManager.hide()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (screenReceiverRegistered) {
            unregisterReceiver(screenReceiver)
            screenReceiverRegistered = false
        }
        mainHandler.removeCallbacks(reconcileAfterInputFinish)
        mainHandler.removeCallbacks(reconcileAfterAccessibilityEvent)
        if (::overlayManager.isInitialized) overlayManager.destroy()
        sessionTargetNode = null
        instance = null
        Log.i(TAG, "DictationAccessibilityService destroyed")
    }
}
