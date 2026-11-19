package com.ludo.voicedictation.model

data class ModelFile(val name: String, val size: Long, val sha256: String)

enum class ModelArchiveFormat { TAR_BZ2, ZIP }

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
    val directFiles: List<ModelFile> = emptyList(),
    val archiveFormat: ModelArchiveFormat = ModelArchiveFormat.TAR_BZ2,
    val archiveSize: Long? = null
) {
    // ZIP models must have a complete immutable catalog before download or readiness.
    val hasCompleteZipMetadata: Boolean
        get() {
            val required = setOf(encoderFileName, decoderFileName, joinerFileName, tokensFileName)
            val names = directFiles.map { it.name }
            return archiveFormat == ModelArchiveFormat.ZIP && archiveUrl.startsWith("https://") &&
                archiveSize != null && archiveSize > 0 &&
                archiveSha256?.matches(Regex("[0-9a-f]{64}")) == true &&
                required.size == 4 && names.size == 4 && names.toSet() == required &&
                directFiles.all { it.name.isNotEmpty() && '/' !in it.name && '\\' !in it.name &&
                    it.name != "." && it.name != ".." && it.size > 0 &&
                    it.sha256.matches(Regex("[0-9a-f]{64}")) }
        }

    companion object {
        // SM8750 QNN context binaries generated together and pinned as one ZIP.
        val FLUID_SPEECH_PRO_QNN = ModelInfo(
            id = "fluid_speech_pro_qnn_sm8750",
            displayName = "Fluid Speech Pro NPU 10s (SM8750)",
            description = "Experimental 10-second Galaxy S25 Snapdragon 8 Elite NPU model.",
            archiveUrl = "https://secure.ipv64.net/download/fluid-speech-pro-qnn-sm8750-10s-f76d03ca.zip",
            dirName = "fluid-speech-pro-qnn-sm8750",
            encoderFileName = "encoder.bin",
            decoderFileName = "decoder.bin",
            joinerFileName = "joiner.bin",
            tokensFileName = "tokens.txt",
            archiveFormat = ModelArchiveFormat.ZIP,
            archiveSize = 642588808L,
            archiveSha256 = "f76d03ca188744dfb576890aee4a81b6987dc89a1e71b44ebabcad6817de4ced",
            directFiles = listOf(
                ModelFile("encoder.bin", 612241408L, "c964659bc74dfeb00f67e7142af8e9abbda8bea68c26df3408f5dda45bed7323"),
                ModelFile("decoder.bin", 23715840L, "e292ef57f04425d6d083c0a87cd3f59ff547ebd207ec7653650ab3d76707aa1b"),
                ModelFile("joiner.bin", 6537216L, "3b27d31ccdeb8ecc02d5b0b048fb05c96ce4815c8eb4aa17a85b1bb10f87e404"),
                ModelFile("tokens.txt", 93934L, "06c76ebf093e64fbcc1699b42a174022f3e6c8c8073090e8de782403f5c8905b")
            )
        )
        val FLUID_SPEECH_PRO_QNN_15S = ModelInfo(
            id = "fluid_speech_pro_qnn_sm8750_15s",
            displayName = "Fluid Speech Pro NPU 15s (SM8750)",
            description = "Experimental, untested 15-second Galaxy S25 Snapdragon 8 Elite NPU model.",
            archiveUrl = "https://secure.ipv64.net/download/fluid-speech-pro-qnn-sm8750-15s-87183120a63049f49b0db1dfd43f5870f86e95e2467e44556792cb8c81058cec.zip",
            dirName = "fluid-speech-pro-qnn-sm8750-15s",
            encoderFileName = "encoder.bin",
            decoderFileName = "decoder.bin",
            joinerFileName = "joiner.bin",
            tokensFileName = "tokens.txt",
            archiveFormat = ModelArchiveFormat.ZIP,
            archiveSize = 458310578L,
            archiveSha256 = "87183120a63049f49b0db1dfd43f5870f86e95e2467e44556792cb8c81058cec",
            directFiles = listOf(
                ModelFile("encoder.bin", 618266624L, "6f4c19fe991d165f14a18ecc5749d0bbeec01a788f3e42c96d87bd5427a326c9"),
                ModelFile("decoder.bin", 23715840L, "e292ef57f04425d6d083c0a87cd3f59ff547ebd207ec7653650ab3d76707aa1b"),
                ModelFile("joiner.bin", 6537216L, "dbc0d93428cb6ebb1150b914eb39adaa18ac832e1075892906632842632e4035"),
                ModelFile("tokens.txt", 93934L, "06c76ebf093e64fbcc1699b42a174022f3e6c8c8073090e8de782403f5c8905b")
            )
        )
        val FLUID_SPEECH_PRO_QNN_30S = ModelInfo(
            id = "fluid_speech_pro_qnn_sm8750_30s",
            displayName = "Fluid Speech Pro NPU 30s (SM8750)",
            description = "Experimental 30-second Galaxy S25 Snapdragon 8 Elite NPU model.",
            archiveUrl = "https://secure.ipv64.net/download/fluid-speech-pro-qnn-sm8750-30s-f5402009.zip",
            dirName = "fluid-speech-pro-qnn-sm8750-30s",
            encoderFileName = "encoder.bin",
            decoderFileName = "decoder.bin",
            joinerFileName = "joiner.bin",
            tokensFileName = "tokens.txt",
            archiveFormat = ModelArchiveFormat.ZIP,
            archiveSize = 463425217L,
            archiveSha256 = "f5402009bff3b9dbb32ecaf302a79c59644e233a63daa5072581902b8f997a02",
            directFiles = listOf(
                ModelFile("encoder.bin", 633352192L, "d980fbbcbd71fbb2fbb90b91f3914cd3be72f28588effdf6f97c568b35f66443"),
                ModelFile("decoder.bin", 23715840L, "e292ef57f04425d6d083c0a87cd3f59ff547ebd207ec7653650ab3d76707aa1b"),
                ModelFile("joiner.bin", 6537216L, "03a1544841488768129220fdaf6912d7370e242c756f0ec4ec67240d66b6f556"),
                ModelFile("tokens.txt", 93934L, "06c76ebf093e64fbcc1699b42a174022f3e6c8c8073090e8de782403f5c8905b")
            )
        )
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
                FLUID_SPEECH_PRO_QNN.id -> FLUID_SPEECH_PRO_QNN
                FLUID_SPEECH_PRO_QNN_15S.id -> FLUID_SPEECH_PRO_QNN_15S
                FLUID_SPEECH_PRO_QNN_30S.id -> FLUID_SPEECH_PRO_QNN_30S
                else -> NEMOTRON_STREAMING
            }
        }
    }
}
