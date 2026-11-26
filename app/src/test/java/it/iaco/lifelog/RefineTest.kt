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

class ParagraphPlaceTest {
    private fun seg(id: Long, s: Long, t: String) = Segment(id, s, s + 5_000, t, null)

    @Test fun unCambioDiLuogoApreUnParagrafoNuovo() {
        val segs = listOf(seg(1, 0, "a"), seg(2, 10_000, "b"), seg(3, 20_000, "c"))
        val ps = Exporter.paragraphs(segs, placeOf = { if (it < 15_000) "Casa" else "Università" })
        org.junit.Assert.assertEquals(2, ps.size)
        org.junit.Assert.assertEquals("Università", ps[1].place)
    }
}
