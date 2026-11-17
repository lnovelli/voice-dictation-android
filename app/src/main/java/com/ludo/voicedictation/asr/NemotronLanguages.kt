package com.ludo.voicedictation.asr

/** NVIDIA's transcription-ready and broad-coverage locales for the bundled Nemotron 3.5 model. */
object NemotronLanguages {
    const val AUTO = "auto"
    val broadCoverage = setOf(
        "bg-BG", "cs-CZ", "da-DK", "et-EE", "fi-FI", "hr-HR", "hu-HU",
        "nb-NO", "pl-PL", "ro-RO", "sk-SK", "sv-SE", "zh-CN"
    )
    val locales = listOf(
        "ar-AR", "bg-BG", "cs-CZ", "da-DK", "de-DE", "en-GB", "en-US",
        "es-ES", "es-US", "et-EE", "fi-FI", "fr-CA", "fr-FR", "hi-IN",
        "hr-HR", "hu-HU", "it-IT", "ja-JP", "ko-KR", "nb-NO", "nl-NL",
        "pl-PL", "pt-BR", "pt-PT", "ro-RO", "ru-RU", "sk-SK", "sv-SE",
        "tr-TR", "uk-UA", "vi-VN", "zh-CN"
    )
    fun isValid(value: String): Boolean = value == AUTO || value in locales
}
