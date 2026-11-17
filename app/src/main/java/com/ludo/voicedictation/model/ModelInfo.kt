package com.ludo.voicedictation.model

data class ModelFile(val name: String, val size: Long, val sha256: String)

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
    val archiveSha256: String? = null,
    val directFiles: List<ModelFile> = emptyList()
) {
    companion object {
        // The split sherpa-onnx int8 graphs refer to the adjacent .onnx.data files by name.
        // vocab.txt is a Git blob; its SHA-256 is computed from the pinned revision's bytes.
        private const val FLUID_REVISION = "fa588e9f87f1b9ad0ca2e2d8f7eaf84dc3803581"
        val FLUID_SPEECH_PRO = ModelInfo(
            id = "fluid_speech_pro",
            displayName = "Fluid Speech Pro",
            description = "Offline recognition after stop; automatic detection of 25 European languages.",
            archiveUrl = "https://huggingface.co/FluidVoice-Labs/fluid-speech-pro-onnx/resolve/$FLUID_REVISION/",
            dirName = "fluid-speech-pro-onnx-$FLUID_REVISION",
            encoderFileName = "encoder-model.int8.onnx",
            tokensFileName = "vocab.txt",
            streaming = false,
            directFiles = listOf(
                ModelFile("encoder-model.int8.onnx", 955592L, "07ad77459bd3bb165c2c53e978ed1dfd64c6e129b18af2ec5ee38343be1d4451"),
                ModelFile("encoder-model.int8.onnx.data", 745971712L, "d2f8bfbb1fa399f207cde85aa3fabee0d67f6730cb31cbf31832c10de69f9c3c"),
                ModelFile("decoder.int8.onnx", 4797L, "0bd3e225124a8a90373e500f62516270fd85fdf42237aa569489b232d21cac08"),
                ModelFile("decoder.int8.onnx.data", 47251456L, "632c3cbf59d3e52d2f7f4dda3bf8e28c045f7a3bb3dbef0f44529ba63926efaa"),
                ModelFile("joiner.int8.onnx", 2754L, "c0e995b659ed5e15fc0c38408ee56cd9a03e6d3329f304c32b9d5017c8cdd381"),
                ModelFile("joiner.int8.onnx.data", 6564352L, "7473427ab99417fae8cf7025ba11507cb5dcc223194bae2f79064dde0936d0af"),
                ModelFile("vocab.txt", 93939L, "d58544679ea4bc6ac563d1f545eb7d474bd6cfa467f0a6e2c1dc1c7d37e3c35d")
            )
        )

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
                FLUID_SPEECH_PRO.id -> FLUID_SPEECH_PRO
                else -> NEMOTRON_STREAMING
            }
        }
    }
}
