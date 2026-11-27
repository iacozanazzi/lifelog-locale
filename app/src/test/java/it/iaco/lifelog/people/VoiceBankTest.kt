package it.iaco.lifelog.people

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import kotlin.random.Random

/** Prove della logica delle voci e dei promemoria, senza telefono. */
class VoiceBankTest {
    private val rnd = Random(7)
    private fun base() = Vec.norm(FloatArray(192) { rnd.nextFloat() * 2 - 1 })
    /** Stessa persona: la stessa direzione più un po' di rumore. */
    private fun near(v: FloatArray, noise: Float) = Vec.norm(FloatArray(v.size) { v[it] + (rnd.nextFloat() * 2 - 1) * noise })

    @Test fun samePersonStaysTheSameVoice() {
        val bank = VoiceBank(emptyList())
        val marco = base()
        val first = bank.assign(near(marco, 0.02f), 5.0, 1000)
        assertNotNull(first.voice)
        repeat(10) { i -> assertEquals(first.voice, bank.assign(near(marco, 0.03f), 4.0, 2000L + i).voice) }
        assertEquals(1, bank.all.size)
    }

    @Test fun differentPeopleGetDifferentVoices() {
        val bank = VoiceBank(emptyList())
        val a = bank.assign(base(), 5.0, 1).voice
        val b = bank.assign(base(), 5.0, 2).voice
        assertNotNull(a); assertNotNull(b); assertNotEquals(a, b)
    }

    @Test fun shortPiecesNeverCreateVoices() {
        val bank = VoiceBank(emptyList())
        assertNull(bank.assign(base(), 1.2, 1).voice)
        assertTrue(bank.all.isEmpty())
    }

    @Test fun newVoiceIsHiddenUntilThirtySeconds() {
        val bank = VoiceBank(emptyList())
        val g = base()
        val id = bank.assign(g, 5.0, 1).voice!!
        assertEquals("?", Speakers.label(id, bank.all.associateBy { it.id }))
        repeat(6) { bank.assign(near(g, 0.02f), 5.0, 2) }
        assertEquals("Voce 1", Speakers.label(id, bank.all.associateBy { it.id }))
        assertEquals("?", Speakers.label(null, emptyMap()))
    }

    @Test fun mergeKeepsNameAndSeconds() {
        val g = base()
        val a = Voice("v-1", 1, "Giulia", "giulia", g, listOf(g), 100.0, 1, 5, 5)
        val b = Voice("v-2", 2, null, null, near(g, 0.01f), emptyList(), 40.0, 0, 9, null)
        val bank = VoiceBank(listOf(a, b))
        assertEquals(listOf("v-1" to "v-2"), bank.mergeCandidates())
        val m = bank.merge("v-1", "v-2")!!
        assertEquals("Giulia", m.name); assertEquals(140.0, m.seconds, 0.01); assertEquals(0L, m.firstMs)
        assertEquals(1, bank.all.size)
    }

    @Test fun bytesRoundTrip() {
        val v = base()
        assertTrue(v.contentEquals(Vec.fromBytes(Vec.toBytes(v))))
        val l = Vec.listFromBytes(Vec.listToBytes(listOf(v, base())))
        assertEquals(2, l.size); assertTrue(l[0].contentEquals(v))
        assertTrue(Vec.listFromBytes(Vec.listToBytes(emptyList())).isEmpty())
    }

    @Test fun slugLikeTheDataRepo() {
        assertEquals("nicolo", Speakers.slug("Nicolò"))
        assertEquals("giulia-r", Speakers.slug("Giulia R."))
    }

    @Test fun birthdaysAndSilence() {
        val today = LocalDate.of(2026, 11, 25)
        assertEquals(LocalDate.of(2026, 11, 28), People.nextOccurrence("11-28", today))
        assertEquals(LocalDate.of(2027, 3, 14), People.nextOccurrence("03-14", today))
        assertNull(People.nextOccurrence("2026-01-01", today))
        val remote = People.Remote(
            "Marco", "marco", "v-3", "socio di Seral", "2026-10", "2026-11-01", listOf("serata-6-dicembre"),
            listOf("esame a gennaio (2026-11-20 13:05)"),
            listOf(People.Date("11-28", "compleanno", null)),
            People.Meeting("2026-11-25", "20:00", "cena al Rosa", null),
        )
        val p = People.Person("Marco", "marco", remote, null, LocalDate.of(2026, 11, 1), 0, followed = true, iOwe = emptyList(), theyOwe = emptyList())
        val r = People.reminders(listOf(p), today).map { it.text }
        assertEquals(listOf("Oggi alle 20:00 vedi Marco: cena al Rosa.", "Tra 3 giorni è il compleanno di Marco.", "Non senti Marco da 3 settimane."), r)
        // Chi non è seguito non genera mai "non senti".
        assertTrue(People.reminders(listOf(p.copy(followed = false, remote = remote.copy(dates = emptyList(), nextMeeting = null))), today).isEmpty())
    }
}

/** 27/11: due persone diverse registrate dallo stesso telefono hanno un coseno intorno a 0,55-0,65. Non devono diventare la stessa voce. */
class VoiceBankChannelTest {
    private val rnd = Random(11)
    private fun rand() = Vec.norm(FloatArray(192) { rnd.nextFloat() * 2 - 1 })
    /** Vettore con coseno circa [c] rispetto a [v]. */
    private fun withCos(v: FloatArray, c: Float): FloatArray {
        val r = rand(); val d = Vec.cos(v, r)
        val o = Vec.norm(FloatArray(v.size) { r[it] - d * v[it] })
        val s = kotlin.math.sqrt(1 - c * c)
        return Vec.norm(FloatArray(v.size) { c * v[it] + s * o[it] })
    }

    @Test fun similarButDifferentPeopleDoNotCollapse() {
        val bank = VoiceBank(emptyList())
        val iaco = rand()
        val a = bank.assign(iaco, 5.0, 1).voice
        val other = bank.assign(withCos(iaco, 0.6f), 5.0, 2)
        assertNotEquals(a, other.voice)
    }

    @Test fun weakMatchesDoNotMoveTheVoice() {
        val bank = VoiceBank(emptyList())
        val iaco = rand()
        bank.assign(iaco, 5.0, 1)
        repeat(50) { bank.assign(withCos(iaco, 0.72f), 5.0, 2L + it) }
        assertTrue(Vec.cos(bank.all.first().centroid, iaco) > 0.99f)
    }
}
