package com.ludo.voicedictation.asr

internal object TokenizerValidator {
    fun validate(vocabulary: String, tokens: String) {
        val pieces = vocabulary.lineSequence().filter { it.isNotBlank() }.map { line ->
            val columns = line.split('\t')
            require(columns.size == 2 && columns[1].toFloatOrNull()?.isFinite() == true) {
                "Invalid BPE vocabulary entry."
            }
            columns[0]
        }.toList()
        require(pieces.isNotEmpty() && pieces.distinct().size == pieces.size) { "Invalid BPE vocabulary." }
        val symbols = tokens.lineSequence().filter { it.isNotBlank() }.map { line ->
            val split = line.lastIndexOf(' ')
            require(split > 0 && line.substring(split + 1).toIntOrNull() != null) { "Invalid model token table." }
            line.substring(0, split)
        }.toSet()
        require("<blk>" in symbols && pieces.all { it in symbols || it == "<unk>" || it == "<pad>" || it == "<s>" || it == "</s>" }) {
            "BPE vocabulary does not match the selected model. Please download the correct model."
        }
    }
}