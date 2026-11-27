package it.iaco.lifelog

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HallucinationTest {
    @Test fun stockPhrasesAreFake() {
        assertTrue(Hallucination.isFake("Grazie a tutti."))
        assertTrue(Hallucination.isFake(" grazie a tutti! "))
        assertTrue(Hallucination.isFake("Grazie a tutti. Grazie a tutti."))
        assertTrue(Hallucination.isFake("Sottotitoli creati dalla comunità Amara.org"))
    }

    @Test fun realSpeechStays() {
        assertFalse(Hallucination.isFake("Grazie a tutti per essere venuti alla serata di Seral"))
        assertFalse(Hallucination.isFake("Grazie"))
        assertFalse(Hallucination.isFake(""))
    }
}
