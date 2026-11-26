package it.iaco.lifelog.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime

class NightTimesTest {
    private fun next(s: String) = Assistant.nextAt(Assistant.UPLOAD_TIMES, LocalDateTime.parse(s)).toString()

    @Test fun sceglieLaProssimaSveglia() {
        assertEquals("2026-11-25T23:00", next("2026-11-25T20:59"))
        assertEquals("2026-11-26T02:45", next("2026-11-25T23:00"))
        assertEquals("2026-11-26T02:45", next("2026-11-26T00:30"))
        assertEquals("2026-11-26T23:00", next("2026-11-26T02:45"))
    }
}
