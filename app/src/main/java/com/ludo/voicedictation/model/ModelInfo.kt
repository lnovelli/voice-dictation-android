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
    val tokensFileName: String = "tokens.txt"
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

        fun getModelById(id: String): ModelInfo {
            return if (id == PARAKEET_V3.id) PARAKEET_V3 else PARAKEET_V2
        }
    }
}
