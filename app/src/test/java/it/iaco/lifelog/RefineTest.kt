package it.iaco.lifelog

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RefineTest {
    @Test fun scartaLeAllucinazioniDiWhisper() {
        assertFalse(Refine.accept(""))
        assertFalse(Refine.accept("  ...  "))
        assertFalse(Refine.accept("Sottotitoli creati dalla comunità Amara.org"))
        assertFalse(Refine.accept("Grazie per la visione!"))
    }

    @Test fun tieneIlParlatoVero() {
        assertTrue(Refine.accept("Allora ci vediamo sabato al Rosa per le prove."))
    }
}
