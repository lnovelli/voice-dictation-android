package com.ludo.voicedictation.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.ludo.voicedictation.overlay.OverlayManager

class DictationAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "DictationA11yService"

        @Volatile
        var instance: DictationAccessibilityService? = null
            private set

        fun isServiceRunning(): Boolean = instance != null
    }

    private lateinit var overlayManager: OverlayManager
    private var lastFocusedNode: AccessibilityNodeInfo? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        overlayManager = OverlayManager.getInstance(this)
        overlayManager.onFinalTranscriptionReady = { text ->
            insertText(text)
        }
        Log.i(TAG, "DictationAccessibilityService connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                handleFocusEvent(event.source)
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                val inputNode = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                handleFocusEvent(inputNode)
            }
        }
    }

    private fun handleFocusEvent(node: AccessibilityNodeInfo?) {
        if (isEditableNode(node)) {
            lastFocusedNode = node
            overlayManager.show()
        } else {
            // Check if there's any active input focus in the window
            val activeInput = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (isEditableNode(activeInput)) {
                lastFocusedNode = activeInput
                overlayManager.show()
            } else {
                overlayManager.hide()
            }
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

    fun insertText(text: String) {
        val targetNode = lastFocusedNode ?: rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (targetNode == null) {
            Log.w(TAG, "No focused text field available to insert text: $text")
            copyToClipboard(text)
            return
        }

        try {
            val existingText = targetNode.text?.toString() ?: ""
            val textToInsert = if (existingText.isBlank()) {
                text
            } else {
                "$existingText $text"
            }

            val arguments = Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    textToInsert
                )
            }

            val success = targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
            if (!success) {
                Log.i(TAG, "ACTION_SET_TEXT returned false, attempting clipboard paste fallback")
                copyToClipboard(text)
                targetNode.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to insert text: ${e.message}", e)
            copyToClipboard(text)
        }
    }

    private fun copyToClipboard(text: String) {
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Dictated Text", text)
            clipboard.setPrimaryClip(clip)
        } catch (e: Exception) {
            Log.e(TAG, "Clipboard copy error: ${e.message}")
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "DictationAccessibilityService interrupted")
        overlayManager.hide()
    }

    override fun onDestroy() {
        super.onDestroy()
        overlayManager.destroy()
        instance = null
        Log.i(TAG, "DictationAccessibilityService destroyed")
    }
}
