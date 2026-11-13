package com.ludo.voicedictation.asr

import com.google.mlkit.genai.common.GenAiException
import java.util.ArrayDeque

/** Process-local ML Kit diagnostics. Never pass exception messages, audio or recognized text. */
object RecognitionDiagnostics {
    private const val MAX_ENTRIES = 40

    // Callers must use these exact tokens. Unknown values are replaced, never stored verbatim.
    private val stages = setOf(
        "status", "download", "initialization", "availability", "prepare", "input",
        "recognition", "completion", "stop", "close", "live", "file"
    )
    private val kinds = setOf(
        "available", "downloadable", "downloading", "unavailable", "unknown_status",
        "failed", "error", "timeout", "not_available", "empty_input", "incomplete",
        "error_response", "download_failed", "other_exception", "started", "ready",
        "final_text", "empty_result", "completed", "success", "cancelled",
        "sdk_reason_unknown", "cause_present", "cause_absent",
        "ERROR_TYPE_UNKNOWN", "ERROR_TYPE_INVALID_REQUEST", "ERROR_TYPE_NO_SPEECH_DETECTED",
        "ERROR_TYPE_NETWORK", "ERROR_TYPE_INSUFFICIENT_PERMISSION", "ERROR_TYPE_MIC_BUSY",
        "ERROR_TYPE_AUDIO_BUFFER_OVERFLOW", "ERROR_TYPE_SODA", "ERROR_TYPE_RECOGNIZER_BUSY",
        "ERROR_TYPE_CANCELLED", "ERROR_TYPE_AICORE_NOT_ENABLED_RUNTIME_LIMITS",
        "ERROR_TYPE_AICORE_APP_QUOTA", "ERROR_TYPE_S3_ERROR", "ERROR_TYPE_AICORE",
        "Exception", "RuntimeException", "IllegalStateException", "IllegalArgumentException",
        "IOException", "SecurityException", "UnsupportedOperationException",
        "MlKitException", "GenAiException", "StatusException", "StatusRuntimeException"
    )
    // Alpha2 constructs these exact fixed messages from its internal speech-error enum.
    // Never store or report the exception message itself, including on an unknown match.
    private val sdkErrorTypes = kinds.filter { it.startsWith("ERROR_TYPE_") }
    private const val SDK_ERROR_PREFIX = "Speech recognition engine is closed due to internal error: "
    private data class Entry(val stage: String, val kind: String, val code: Int?)
    private val entries = ArrayDeque<Entry>(MAX_ENTRIES)

    @Synchronized
    fun record(stage: String, kind: String, code: Int? = null) {
        val safeStage = if (stage in stages) stage else "other"
        val safeKind = if (kind in kinds) kind else "other"
        if (entries.size == MAX_ENTRIES) entries.removeFirst()
        entries.addLast(Entry(safeStage, safeKind, code))
    }

    fun recordFailure(stage: String, error: Throwable) {
        record(stage, error.javaClass.simpleName, (error as? GenAiException)?.errorCode)
    }

    fun recordRecognitionError(error: GenAiException) {
        record("recognition", "error_response", error.errorCode)
        val reason = sdkErrorTypes.firstOrNull { error.message == SDK_ERROR_PREFIX + it }
        record("recognition", reason ?: "sdk_reason_unknown")
        val cause = error.cause
        record("recognition", if (cause == null) "cause_absent" else "cause_present")
        if (cause != null) recordFailure("recognition", cause)
    }

    @Synchronized
    fun report(): String {
        if (entries.isEmpty()) return "No ML Kit diagnostic events recorded."
        return buildString {
            appendLine("ML Kit diagnostics (report format 1)")
            entries.forEach { entry ->
                append("phase=").append(entry.stage).append(" kind=").append(entry.kind)
                entry.code?.let { append(" code=").append(it) }
                appendLine()
            }
        }.trimEnd()
    }

    @Synchronized
    fun clear() {
        entries.clear()
    }
}
