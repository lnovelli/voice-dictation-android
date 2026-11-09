package com.ludo.voicedictation.accessibility

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.accessibility.AccessibilityNodeInfo
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/** Opt-in, process-local diagnostics. Never store screen contents or arbitrary event strings. */
object OverlayDiagnostics {
    private const val LIMIT = 500
    private const val MAX_DURATION_MS = 10 * 60 * 1000L
    private val lock = Any()
    private val clockFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT)
    private data class Entry(val message: String, val first: String, var last: String, var count: Long = 1,
        val triggers: MutableMap<String, Long>? = null, val firstTriggers: String? = null, var lastTriggers: String? = null)
    private val entries = ArrayDeque<Entry>()
    private var initialSnapshot: Entry? = null
    private val verifiedPackages = LinkedHashSet<String>()
    private var evictedEntries = 0L
    @Volatile private var recording = false
    private var startedAt = 0L
    private var status = "not started"
    @Volatile private var generation = 0L
    private var deadlineToken = 0L
    private var includeUnverifiedPackages = false
    private data class Session(val generation: Long, val includeUnverifiedPackages: Boolean)
    private val evaluatingSession = ThreadLocal<Session>()
    private val handler by lazy { Handler(Looper.getMainLooper()) }

    fun start(includeUnverifiedPackages: Boolean = false) {
        try {
            synchronized(lock) {
                generation++
                val currentDeadline = ++deadlineToken
                entries.clear()
                initialSnapshot = null
                verifiedPackages.clear()
                evictedEntries = 0L
                this.includeUnverifiedPackages = includeUnverifiedPackages
                startedAt = SystemClock.elapsedRealtime()
                recording = true
                status = "recording"
                appendLocked("diagnostics started")
                // One deadline per explicit start; clearing invalidates producers but not the deadline.
                handler.postDelayed({
                    try { synchronized(lock) {
                        if (recording && deadlineToken == currentDeadline) expireLocked()
                    } } catch (_: Exception) { /* Observational only. */ }
                }, MAX_DURATION_MS)
            }
        } catch (_: Exception) {
            synchronized(lock) {
                recording = false
                status = "unavailable"
                generation++
            }
        }
    }

    fun stop() {
        synchronized(lock) {
            expireLocked()
            if (recording) {
                appendLocked("diagnostics stopped by user")
                recording = false
                status = "stopped by user"
                generation++
            }
        }
    }

    fun clear() {
        synchronized(lock) {
            expireLocked()
            generation++ // Discard producers already constructing an entry before the clear.
            entries.clear()
            initialSnapshot = null
            verifiedPackages.clear()
            evictedEntries = 0L
        }
    }

    fun isRecording(): Boolean {
        if (!recording) return false
        return synchronized(lock) { expireLocked(); recording }
    }

    fun report(): String = synchronized(lock) {
        expireLocked()
        buildString {
            append("Floating button diagnostics — ").append(status).append('\n')
            append("Process-local; max 500 entries, recording stops after 10 minutes. Report remains in memory until cleared, replaced, or process exit. Times are local; +ms is monotonic.\n")
            append("Overlay view state is not proof of actual pixel visibility. App/package metadata may be sensitive.\n")
            append("Unverified provider-supplied package names: ")
                .append(if (includeUnverifiedPackages) "enabled (unverified: prefix); syntax does not prove identity or exclude sensitive data" else "disabled")
                .append('\n')
            append("Adjacent identical decisions combine; first and last triggers are shown, but intermediate trigger ordering is not retained. Recheck-from counts may include events before recording began.\n")
            append("Earlier entries evicted: ").append(evictedEntries).append('\n')
            fun render(it: Entry) {
                append(it.first)
                if (it.count > 1) append(" through ").append(it.last).append(" (occurrences=").append(it.count).append(')')
                append('\n')
                it.triggers?.let { triggers ->
                    append("  Triggers: ").append(triggers.entries.joinToString(", ") { t -> "${t.key}: ${t.value}" }).append('\n')
                }
                if (it.count > 1 && it.firstTriggers != null) {
                    append("  First trigger: ").append(it.firstTriggers).append('\n')
                    append("  Last trigger: ").append(it.lastTriggers).append('\n')
                }
                append("  ").append(it.message).append("\n\n")
            }
            initialSnapshot?.let { render(it) }
            if (entries.isEmpty() && initialSnapshot == null) append("No entries.\n") else entries.forEach { render(it) }
        }
    }

    private fun expireLocked() {
        if (recording && SystemClock.elapsedRealtime() - startedAt >= MAX_DURATION_MS) {
            appendLocked("diagnostics stopped: 10-minute limit")
            recording = false
            status = "stopped: 10-minute limit"
            generation++
        }
    }

    private fun appendLocked(message: String, coalesce: Boolean = false, triggers: Map<String, Long>? = null) {
        val timestamp = timestampLocked()
        val last = entries.peekLast()
        if (coalesce && last != null && last.message == message && (last.triggers != null) == (triggers != null)) {
            last.count++
            last.last = timestamp
            if (triggers != null) {
                val totals = last.triggers!!
                triggers.forEach { (key, value) -> totals[key] = (totals[key] ?: 0L) + value }
                last.lastTriggers = formatTriggers(triggers)
            }
            return
        }
        if (entries.size + (if (initialSnapshot == null) 0 else 1) >= LIMIT) {
            entries.removeFirst()
            evictedEntries++
        }
        val labels = triggers?.let(::formatTriggers)
        entries.addLast(Entry(message, timestamp, timestamp, triggers = triggers?.toMutableMap(), firstTriggers = labels, lastTriggers = labels))
    }

    private fun timestampLocked(): String = "${clockFormat.format(Date())} (+${SystemClock.elapsedRealtime() - startedAt}ms)"
    private fun formatTriggers(triggers: Map<String, Long>): String =
        triggers.entries.joinToString(", ") { "${it.key}: ${it.value}" }

    // Capture consent and generation together; an in-flight producer can never inherit a later session's consent.
    private fun session(): Session? = synchronized(lock) {
        expireLocked()
        if (recording) Session(generation, includeUnverifiedPackages) else null
    }

    private inline fun <T> inSession(session: Session, block: () -> T): T {
        val previous = evaluatingSession.get()
        evaluatingSession.set(session)
        try { return block() } finally {
            if (previous == null) evaluatingSession.remove() else evaluatingSession.set(previous)
        }
    }

    /** Keep the bounded starting snapshot even when ordinary timeline entries are evicted. */
    internal fun snapshot(message: () -> String) {
        if (!recording) return
        try {
            val session = session() ?: return
            val body = inSession(session, message)
            synchronized(lock) {
                expireLocked()
                if (recording && generation == session.generation) {
                    val timestamp = timestampLocked()
                    initialSnapshot = Entry(body, timestamp, timestamp)
                    while (entries.size + 1 > LIMIT) { entries.removeFirst(); evictedEntries++ }
                }
            }
        } catch (_: Exception) { /* Observational only. */ }
    }

    /** The lambda is not evaluated while disabled; diagnostics failures cannot affect the app. */
    internal fun record(coalesce: Boolean = false, message: () -> String) {
        if (!recording) return
        try {
            val session = session() ?: return
            add(session.generation, inSession(session, message), coalesce)
        } catch (_: Exception) { /* Observational only. */ }
    }

    /** Merge only adjacent equal states; preserve immediate and delayed trigger counts. */
    internal fun decision(triggers: () -> Map<String, Long>, message: () -> String) {
        if (!recording) return
        try {
            val session = session() ?: return
            val (triggerCounts, body) = inSession(session) { triggers() to message() }
            synchronized(lock) {
                expireLocked()
                if (recording && generation == session.generation) appendLocked(body, true, triggerCounts)
            }
        } catch (_: Exception) { /* Observational only. */ }
    }

    private fun add(current: Long, message: String, coalesce: Boolean) {
        synchronized(lock) {
            expireLocked()
            if (recording && generation == current) appendLocked(message, coalesce)
        }
    }

    /** Verification is preferred; optional provider names are syntax-limited, not trusted as identities. */
    internal fun packageName(context: Context, value: CharSequence?): String {
        if (value == null || value.length !in 1..128) return "unknown"
        val name = value.toString()
        if (name.length !in 1..128 || !name.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '.' || it == '_' }) return "unknown"
        val session = evaluatingSession.get()
        synchronized(lock) {
            expireLocked()
            if (session != null && recording && generation == session.generation && name in verifiedPackages) return name
        }
        val verified = try {
            @Suppress("DEPRECATION")
            context.packageManager.getApplicationInfo(name, 0).packageName == name
        } catch (_: Exception) { false }
        synchronized(lock) {
            expireLocked()
            if (session == null || !recording || generation != session.generation) return "unavailable"
            if (!verified) {
                return if (session.includeUnverifiedPackages && isDottedPackageName(name)) "unverified:$name" else "unavailable"
            }
            if (verifiedPackages.size >= 64) verifiedPackages.remove(verifiedPackages.first())
            verifiedPackages.add(name)
            return name
        }
    }

    private fun isDottedPackageName(name: String): Boolean {
        val parts = name.split('.')
        return parts.size >= 2 && parts.all { part ->
            part.isNotEmpty() && (part[0] in 'a'..'z' || part[0] in 'A'..'Z') &&
                part.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_' }
        }
    }

    /** Class names are provider-controlled; report only fixed categories, never arbitrary strings. */
    private fun classCategory(value: CharSequence?): String = when (value?.toString()) {
        "android.widget.EditText" -> "EditText"
        "android.widget.AutoCompleteTextView" -> "AutoCompleteTextView"
        "android.widget.TextView" -> "TextView"
        "android.webkit.WebView" -> "WebView"
        "android.view.View" -> "View"
        null -> "unknown"
        else -> "custom/other"
    }

    internal fun node(context: Context, node: AccessibilityNodeInfo?): String {
        if (node == null) return "node=unknown"
        fun <T> safe(block: () -> T): String = try { block().toString() } catch (_: Exception) { "unknown" }
        return "nodePackage=${safe { packageName(context, node.packageName) }} class=${safe { classCategory(node.className) }}" +
            " windowId=${safe { node.windowId }} focused=${safe { node.isFocused }} enabled=${safe { node.isEnabled }}" +
            " editable=${safe { node.isEditable }} visibleToUser=${safe { node.isVisibleToUser }}" +
            " setTextAction=${safe { node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT } }}" +
            " passwordFlag=${safe { node.isPassword }} input=${safe { inputCategory(node.inputType) }}"
    }

    internal fun inputCategory(type: Int): String {
        val klass = type and InputType.TYPE_MASK_CLASS
        val variation = type and InputType.TYPE_MASK_VARIATION
        return when {
            klass == InputType.TYPE_CLASS_TEXT && variation in setOf(
                InputType.TYPE_TEXT_VARIATION_PASSWORD, InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD) -> "password"
            klass == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD -> "password"
            klass == InputType.TYPE_CLASS_TEXT -> "text"
            klass == InputType.TYPE_CLASS_NUMBER -> "number"
            klass == InputType.TYPE_CLASS_PHONE -> "phone"
            klass == InputType.TYPE_CLASS_DATETIME -> "datetime"
            klass == InputType.TYPE_NULL -> "none"
            else -> "unknown"
        }
    }
}
