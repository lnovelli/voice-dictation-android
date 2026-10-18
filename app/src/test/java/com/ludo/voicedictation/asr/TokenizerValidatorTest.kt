package com.ludo.voicedictation.asr

import org.junit.Test

class TokenizerValidatorTest {
    @Test fun acceptsMatchingCaseSensitiveVocabulary() {
        TokenizerValidator.validate("<unk>\t0\nLudo\t-1\n", "<unk> 0\nLudo 1\n<blk> 2\n")
    }

    @Test(expected = IllegalArgumentException::class) fun rejectsMismatchedVocabulary() {
        TokenizerValidator.validate("Ludo\t-1\n", "LUDO 0\n<blk> 1\n")
    }

    @Test(expected = IllegalArgumentException::class) fun rejectsInvalidScores() {
        TokenizerValidator.validate("Ludo\tNaN\n", "Ludo 0\n<blk> 1\n")
    }
}