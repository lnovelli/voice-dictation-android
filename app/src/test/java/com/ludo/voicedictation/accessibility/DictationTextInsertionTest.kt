package com.ludo.voicedictation.accessibility

import org.junit.Assert.assertEquals
import org.junit.Test

class DictationTextInsertionTest {
    @Test fun ignoresDisplayedPlaceholder() {
        assertEquals("Hello", DictationTextInsertion.compose("Ask anything", true, "Hello"))
    }

    @Test fun preservesRealTextEvenWhenItMatchesAPlaceholder() {
        assertEquals("Ask anything Hello", DictationTextInsertion.compose("Ask anything", false, "Hello"))
    }

    @Test fun handlesEmptyFields() {
        for (value in listOf(null, "", "   ")) {
            assertEquals("Hello", DictationTextInsertion.compose(value, false, "Hello"))
        }
    }
}