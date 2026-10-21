package com.ludo.voicedictation.accessibility

internal object DictationTextInsertion {
    enum class Method { SET_TEXT, PASTE_AT_CURSOR }

    data class Plan(val method: Method, val text: String)

    /**
     * Prefer replacement for ordinary fields. Some accessibility providers expose a
     * visible placeholder as node.text instead of hintText; when that is ambiguous,
     * paste into the real edit buffer rather than copying the exposed placeholder.
     */
    fun plan(
        existingText: String?,
        showingHint: Boolean,
        hintText: String?,
        contentDescription: String?,
        selectionStart: Int,
        selectionEnd: Int,
        transcription: String
    ): Plan {
        val current = existingText.orEmpty()
        if (showingHint || current.isBlank()) return Plan(Method.SET_TEXT, transcription)

        val matchesHint = !hintText.isNullOrBlank() && current == hintText
        val matchesDescription = !contentDescription.isNullOrBlank() && current == contentDescription
        val possibleHint = matchesHint || matchesDescription
        val caretAtStart = selectionStart == 0 && selectionEnd == 0
        val caretAtEnd = selectionStart >= 0 && selectionStart == selectionEnd &&
            selectionEnd == current.length
        if (possibleHint || caretAtStart || caretAtEnd) {
            // A provider may expose the drawn placeholder as node.text and report the caret
            // after it. Never add a separator based on that potentially virtual text.
            val separator = !possibleHint && caretAtEnd && current.isNotEmpty() &&
                !current.last().isWhitespace()
            return Plan(Method.PASTE_AT_CURSOR, (if (separator) " " else "") + transcription)
        }

        return Plan(Method.SET_TEXT, compose(current, false, transcription))
    }

    fun compose(existingText: String?, showingHint: Boolean, transcription: String): String =
        if (showingHint || existingText.isNullOrBlank()) transcription
        else "$existingText $transcription"
}
