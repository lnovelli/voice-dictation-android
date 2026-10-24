package com.ludo.voicedictation.asr

import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriptPolisherTest {
    @Test fun stripsThroughClosingTagEvenWithoutOpeningTag() {
        assertEquals("Final transcript", TranscriptPolisher.stripThinkingPrefix("reasoning text</think>\nFinal transcript"))
    }

    @Test fun leavesResponsesWithoutClosingTagUnchanged() {
        assertEquals("Ordinary polished text", TranscriptPolisher.stripThinkingPrefix("Ordinary polished text"))
    }

    @Test fun ignoresClosingTagCaseAndReturnsEmptyForThinkOnlyResponse() {
        assertEquals("Answer", TranscriptPolisher.stripThinkingPrefix("reasoning</THINK> Answer"))
        assertEquals("", TranscriptPolisher.stripThinkingPrefix("reasoning</think>"))
    }
}
