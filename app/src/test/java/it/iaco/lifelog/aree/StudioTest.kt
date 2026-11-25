package it.iaco.lifelog.aree

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class StudioTest {
    private val oggi = LocalDate.of(2026, 11, 25)

    @Test fun giustaAllungaSbagliataAzzera() {
        var p = Studio.dopo(null, true, oggi)
        assertEquals(oggi.plusDays(1), p.prossima)
        p = Studio.dopo(p, true, p.prossima)
        assertEquals(1, p.scatola)
        assertEquals(oggi.plusDays(1 + 3), p.prossima)
        val sbagliata = Studio.dopo(p, false, p.prossima)
        assertEquals(0, sbagliata.scatola)
        assertEquals(p.prossima.plusDays(1), sbagliata.prossima)
        assertEquals(1, sbagliata.sbagliate.size)
        assertEquals(oggi, sbagliata.prima)
    }

    @Test fun maiDopoLEsame() {
        val p = Studio.Progresso(5, oggi, oggi.minusDays(90), emptyList())
        val esame = oggi.plusDays(10)
        val n = Studio.dopo(p, true, oggi, esame)
        assertEquals(oggi.plusDays(9), n.prossima)
    }

    @Test fun codaPrimaScaduteePoiNuoveConLimite() {
        val domande = (1..20).map { "q$it" }
        val prog = mapOf(
            "q1" to Studio.Progresso(2, oggi.minusDays(3), oggi.minusDays(20), emptyList()),
            "q2" to Studio.Progresso(2, oggi.minusDays(1), oggi.minusDays(20), emptyList()),
            "q3" to Studio.Progresso(2, oggi.plusDays(4), oggi.minusDays(20), emptyList()),
        )
        val coda = Studio.codaDiOggi(domande, { it }, { "m" }, prog, oggi, emptyMap())
        assertEquals(listOf("q1", "q2"), coda.take(2))
        assertEquals(2 + Studio.NUOVE_AL_GIORNO, coda.size)
        assertTrue("q3" !in coda)
    }

    @Test fun pianoDivideGliArgomentiETieneIlRipasso() {
        val args = listOf("a", "b", "c", "d", "e", "f", "g")
        val piano = Studio.piano(args, oggi, oggi.plusDays(10))
        assertEquals(10, piano.size)
        assertEquals(args, piano.flatMap { it.argomenti })
        assertTrue(piano.last().ripasso)
        assertEquals(oggi, piano.first().data)
        assertTrue(piano.take(9).none { it.ripasso })
    }

    @Test fun pianoAllUltimoGiorno() {
        val piano = Studio.piano(listOf("a", "b"), oggi, oggi.plusDays(1))
        assertEquals(1, piano.size)
        assertTrue(piano[0].ripasso)
        assertTrue(Studio.piano(listOf("a"), oggi, oggi).isEmpty())
    }
}
