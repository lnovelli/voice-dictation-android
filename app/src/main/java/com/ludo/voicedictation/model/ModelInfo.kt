package com.ludo.voicedictation.model

data class ModelInfo(
    val id: String,
    val displayName: String,
    val description: String,
    val archiveUrl: String,
    val dirName: String,
    val encoderFileName: String = "encoder.int8.onnx",
    val decoderFileName: String = "decoder.int8.onnx",
    val joinerFileName: String = "joiner.int8.onnx",
    val tokensFileName: String = "tokens.txt",
    val streaming: Boolean = false,
    val archiveSha256: String? = null
) {
    companion object {
        val NEMOTRON_STREAMING_560MS = ModelInfo(
            id = "nemotron_3_5_streaming_560ms",
            displayName = "Nemotron 3.5 ASR 0.6B (Multilingual, 560 ms)",
            description = "Multilingual streaming recognition with automatic language detection; 560 ms model chunk size.",
            archiveUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-nemotron-3.5-asr-streaming-0.6b-560ms-int8-2026-06-11.tar.bz2",
            dirName = "sherpa-onnx-nemotron-3.5-asr-streaming-0.6b-560ms-int8-2026-06-11",
            streaming = true,
            archiveSha256 = "c6bf5e0df765f9d5b43bc9e0536d4b4b3e7d40bdf5ecf13e45f134c51c05ae3a"
        )

        // Keep the existing ID and directory so an installed 1120 ms model remains selected.
        val NEMOTRON_STREAMING = ModelInfo(
            id = "nemotron_3_5_streaming",
            displayName = "Nemotron 3.5 ASR 0.6B (Multilingual, 1120 ms)",
            description = "Multilingual streaming recognition with automatic language detection; 1120 ms model chunk size.",
            archiveUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-nemotron-3.5-asr-streaming-0.6b-1120ms-int8-2026-06-11.tar.bz2",
            dirName = "sherpa-onnx-nemotron-3.5-asr-streaming-0.6b-1120ms-int8-2026-06-11",
            streaming = true,
            archiveSha256 = "adbdd5e9fef87300c37cebfcfc4f1ebe56845c860c8a760af0a1dd65ce9beed3"
        )

        fun getModelById(id: String): ModelInfo {
            return when (id) {
                NEMOTRON_STREAMING_560MS.id -> NEMOTRON_STREAMING_560MS
                else -> NEMOTRON_STREAMING
            }
        }
    }
}
