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
        val PARAKEET_V2 = ModelInfo(
            id = "parakeet_v2",
            displayName = "Parakeet TDT 0.6B v2 (English)",
            description = "Trained on 36k hours of English speech. Excellent accuracy, punctuation, and capitalization.",
            archiveUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8.tar.bz2",
            dirName = "sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8"
        )

        val PARAKEET_V3 = ModelInfo(
            id = "parakeet_v3",
            displayName = "Parakeet TDT 0.6B v3 (Multilingual)",
            description = "Supports 25 European languages with punctuation and capitalization.",
            archiveUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8.tar.bz2",
            dirName = "sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8"
        )

        val NEMOTRON_STREAMING = ModelInfo(
            id = "nemotron_3_5_streaming",
            displayName = "Nemotron 3.5 ASR 0.6B (Multilingual, Streaming)",
            description = "Multilingual streaming recognition with automatic language detection; 1120 ms model chunk size.",
            archiveUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-nemotron-3.5-asr-streaming-0.6b-1120ms-int8-2026-06-11.tar.bz2",
            dirName = "sherpa-onnx-nemotron-3.5-asr-streaming-0.6b-1120ms-int8-2026-06-11",
            streaming = true,
            archiveSha256 = "adbdd5e9fef87300c37cebfcfc4f1ebe56845c860c8a760af0a1dd65ce9beed3"
        )

        fun getModelById(id: String): ModelInfo {
            return when (id) {
                PARAKEET_V3.id -> PARAKEET_V3
                NEMOTRON_STREAMING.id -> NEMOTRON_STREAMING
                else -> PARAKEET_V2
            }
        }
    }
}
