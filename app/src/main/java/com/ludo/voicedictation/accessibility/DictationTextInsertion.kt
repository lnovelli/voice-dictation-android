package com.ludo.voicedictation.accessibility

internal object DictationTextInsertion {
    fun compose(existingText: String?, showingHint: Boolean, transcription: String): String =
        if (showingHint || existingText.isNullOrBlank()) transcription
        else "$existingText $transcription"
}