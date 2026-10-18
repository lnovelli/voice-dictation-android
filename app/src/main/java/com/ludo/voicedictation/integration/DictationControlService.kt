package com.ludo.voicedictation.integration

import android.Manifest
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import android.util.Log
import androidx.core.content.ContextCompat
import com.ludo.voicedictation.asr.SpeechEngine
import com.ludo.voicedictation.data.AppPreferences
import java.util.UUID

/** Signature-gated, explicit-bind-only IPC. No broadcasts, callback intents or text injection. */
class DictationControlService : Service() {
    companion object {
        const val PROTOCOL_VERSION = 1
        const val START = 1
        const val STOP = 2
        const val CANCEL = 3
        const val STATE = 101
        const val PARTIAL = 102
        const val RESULT = 103
        const val ERROR = 104
        const val CONTROL_PERMISSION = "com.ludo.voicedictation.permission.CONTROL_DICTATION"
        private const val CLIENT_PACKAGE = "com.hermes.assistant"
        private const val MAX_SESSION_MS = 5 * 60 * 1000L
        private const val TAG = "DictationControl"
    }

    private class Owner(val uid: Int, val id: String, val callback: Messenger) {
        val token = Any()
        lateinit var death: IBinder.DeathRecipient
        lateinit var timeout: Runnable
        var cancelled = false
        var linked = false
    }

    private val engine by lazy { SpeechEngine.getInstance(this) }
    private val prefs by lazy { AppPreferences(this) }
    private val handler = Handler(Looper.getMainLooper()) { message ->
        receive(message)
        true
    }
    private val messenger = Messenger(handler)
    private var owner: Owner? = null
    private var bound = false
    private var destroyed = false

    override fun onBind(intent: Intent): IBinder {
        bound = true
        return messenger.binder
    }

    override fun onUnbind(intent: Intent): Boolean {
        bound = false
        owner?.let { abort(it) }
        // A later bind calls onBind again, rather than reusing a stale bound flag.
        return false
    }

    override fun onDestroy() {
        destroyed = true
        bound = false
        owner?.let { abort(it) }
        super.onDestroy()
    }

    private fun trusted(uid: Int): Boolean {
        // Message.sendingUid is populated by Messenger's Binder transport, unlike data supplied
        // in a Bundle. Reject shared UIDs: only the exact Hermes package may issue commands.
        val packages = packageManager.getPackagesForUid(uid) ?: return false
        return packages.size == 1 && packages[0] == CLIENT_PACKAGE &&
            packageManager.checkSignatures(uid, applicationInfo.uid) == PackageManager.SIGNATURE_MATCH &&
            checkPermission(CONTROL_PERMISSION, -1, uid) == PackageManager.PERMISSION_GRANTED
    }

    private fun receive(message: Message) {
        if (destroyed || !bound || !trusted(message.sendingUid)) return
        val callback = message.replyTo ?: return
        // Bad parcelables/types must not crash the service or affect a live session.
        var id = ""
        try {
            val data = message.data
            id = data.getString("session_id") ?: ""
            val validId = id.length == 36 && runCatching {
                UUID.fromString(id).toString().equals(id, ignoreCase = true)
            }.getOrDefault(false)
            if (!validId || data.getInt("protocol_version", -1) != PROTOCOL_VERSION) {
                send(callback, id, ERROR, "error", "Expected protocol_version 1 and a UUID session_id.")
                return
            }
            when (message.what) {
                START -> start(message.sendingUid, id, callback)
                STOP, CANCEL -> {
                    val current = owner
                    if (current == null || current.cancelled || current.uid != message.sendingUid ||
                        current.id != id || current.callback.binder != callback.binder) {
                        send(callback, id, ERROR, "error", "No matching owned dictation session.")
                    } else if (message.what == STOP) {
                        engine.stopExternal(current.token)
                    } else {
                        // CANCEL discards all output; no RESULT or acknowledgement is emitted.
                        abort(current)
                    }
                }
                else -> send(callback, id, ERROR, "error", "Unknown dictation command.")
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "Invalid dictation command", e)
            send(callback, id, ERROR, "error", "Malformed dictation command.")
        }
    }

    private fun start(uid: Int, id: String, callback: Messenger) {
        val error = when {
            owner != null || engine.isBusy -> "Dictation is busy."
            !prefs.serviceEnabled -> "Enable dictation service in Voice Dictation Settings."
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ->
                "Grant microphone permission to Voice Dictation."
            !engine.isModelReady() -> "Download the selected model in Voice Dictation Settings."
            else -> null
        }
        if (error != null) {
            send(callback, id, ERROR, "error", error)
            return
        }
        val current = Owner(uid, id, callback)
        current.death = IBinder.DeathRecipient { handler.post { abort(current) } }
        current.timeout = Runnable {
            if (owner === current && !current.cancelled) {
                emit(current, ERROR, "error", "Dictation exceeded the five-minute session limit.")
                abort(current)
            }
        }
        try {
            callback.binder.linkToDeath(current.death, 0)
            current.linked = true
        } catch (_: RemoteException) {
            return
        }
        owner = current
        handler.postDelayed(current.timeout, MAX_SESSION_MS)
        val started = engine.startExternal(current.token, object : SpeechEngine.ExternalListener {
            override fun onState(state: String) { emit(current, STATE, "state", state) }
            override fun onPartial(text: String) { emit(current, PARTIAL, "text", text) }
            override fun onFinished(text: String?, error: String?) {
                if (owner !== current) return
                // Engine ownership and all async decode/capture work have already finished.
                // Clear service ownership before sending the terminal event: unbind is now safe.
                val deliver = !current.cancelled && bound && !destroyed
                clear(current)
                owner = null
                if (deliver) {
                    if (text != null) send(callback, id, RESULT, "text", text)
                    else send(callback, id, ERROR, "error", error ?: "Dictation was cancelled.")
                }
            }
        })
        if (!started) {
            clear(current)
            owner = null
            send(callback, id, ERROR, "error", "Dictation could not start; check permissions and try again.")
        }
    }

    private fun emit(current: Owner, event: Int, key: String, value: String) {
        if (owner !== current || current.cancelled || !bound || destroyed) return
        if (!send(current.callback, current.id, event, key, value)) abort(current)
    }

    private fun send(callback: Messenger, id: String, event: Int, key: String, value: String): Boolean {
        return try {
            callback.send(Message.obtain(null, event).apply {
                data = Bundle().apply {
                    putString("session_id", id)
                    putInt("protocol_version", PROTOCOL_VERSION)
                    putString(key, value)
                }
            })
            true
        } catch (_: RemoteException) {
            false
        }
    }

    private fun abort(current: Owner) {
        if (owner !== current || current.cancelled) return
        current.cancelled = true
        clear(current)
        engine.cancelExternal(current.token)
        // Retain ownership until the engine's onFinished; a native decode is not interruptible.
    }

    private fun clear(current: Owner) {
        handler.removeCallbacks(current.timeout)
        if (current.linked) {
            current.callback.binder.unlinkToDeath(current.death, 0)
            current.linked = false
        }
    }
}
