package it.iaco.lifelog.ui

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Anteprime delle schermate Persone con dati finti (prefisso p). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "w412dp-h915dp-xxhdpi")
class PeopleScreenshotTest {
    @get:Rule val rule = createComposeRule()

    private fun shot(name: String, dark: Boolean = false, content: @Composable () -> Unit) {
        rule.mainClock.autoAdvance = false
        rule.setContent { LifeTheme(dark = dark) { Box(Modifier.fillMaxSize().background(Life.c.bg)) { content() } } }
        rule.mainClock.advanceTimeBy(800)
        rule.onRoot().captureRoboImage("build/screens/$name.png")
    }

    private val people = PeopleUi(
        model = VoiceModelState.READY, meKnown = true,
        unknown = listOf(
            UnknownVoiceRow("v-4", "Voce 4", "14 minuti in 3 giorni, l'ultima volta ieri", "allora il sei lo facciamo al Rosa, il fonico lo porto io"),
            UnknownVoiceRow("v-7", "Voce 7", "2 minuti in 1 giorno, l'ultima volta oggi", "scusa, sai a che ora chiude la biblioteca?"),
        ),
        followed = listOf(PersonRow("giulia-r", "Giulia R.", "Compagna di corso, diritto commerciale", "il 2 novembre")),
        others = listOf(
            PersonRow("marco", "Marco", "Socio di Seral, fa il fonico", "ieri"),
            PersonRow("tommaso", "Tommaso", "Grafico, cura il post settimanale", "3 giorni fa"),
            PersonRow("sara", "Sara", "Voce riconosciuta, 6 minuti", "il 12 novembre"),
        ),
        dataConfigured = true,
    )

    @Test fun list() = shot("p1-persone") { PeopleScreen(people, {}, {}, {}, {}, {}) }

    @Test fun listOff() = shot("p2-persone-da-attivare") {
        PeopleScreen(people.copy(model = VoiceModelState.MISSING, unknown = emptyList()), {}, {}, {}, {}, {})
    }

    private val marco = PersonUi(
        slug = "marco", name = "Marco", who = "Socio di Seral, fa il fonico", last = "Ultima volta ieri",
        met = "Da ottobre 2026, alla serata al Rosa", voice = "Riconosciuta: 2 h 10 min di parlato finora.", followed = false,
        nextMeeting = "Domani alle 20:00: cena al Rosa",
        remember = listOf("Esame di acustica a gennaio (2026-11-20 13:05)"),
        iOwe = listOf("Mandargli il contatto del fonico"),
        theyOwe = listOf("Il preventivo delle luci, entro il 2026-11-30"),
        topics = listOf("Serata 6 dicembre"),
        dates = listOf("Compleanno: 28 novembre, tra 3 giorni (detto il 2026-11-25 alle 16:40)"),
        timeline = listOf("2026-11-25 16:40 deciso insieme il Rosa per il 6 dicembre", "2026-11-20 13:05 ha detto che ha l'esame a gennaio"),
    )

    @Test fun person() = shot("p3-persona") { PersonScreen(marco, {}, {}, {}) }

    @Test fun personDark() = shot("p4-persona-scuro", dark = true) { PersonScreen(marco.copy(followed = true), {}, {}, {}) }

    @Test fun voice() = shot("p5-dai-un-nome") {
        VoiceScreen(
            VoiceUi(
                "v-4", "Voce 4", "14 minuti in 3 giorni. Sentita la prima volta il 22 nov, 18:10.",
                listOf(
                    Entry("22 nov, 18:10", "allora il sei lo facciamo al Rosa, il fonico lo porto io"),
                    Entry("24 nov, 11:02", "ti mando stasera il preventivo delle luci, così vediamo i soldi"),
                    Entry("24 nov, 11:05", "per la grafica sento Tommaso, il post del 48 deve uscire giovedì"),
                ),
                listOf("Giulia R.", "Marco", "Sara", "Tommaso"), meKnown = true,
            ),
            {}, {}, {}, {},
        )
    }

    @Test fun todayCard() = shot("p6-oggi-persone") {
        TodayScreen(
            TodayUi(
                dateLabel = "Mercoledì 25 novembre", ready = true, state = RecState.LISTENING, stalledSince = null,
                level = 0f, speaking = false, speechMinutes = 214, pending = 0, pendingMinutes = 0,
                transcribeStatus = "", canTranscribeNow = false, lines = listOf("Allora ci vediamo stasera al Rosa."), lastTime = "16:25", crash = null,
                people = listOf(
                    PeopleTodayRow("marco", "Marco", "Oggi alle 20:00 vedi Marco: cena al Rosa.", listOf("Ricorda: esame di acustica a gennaio", "Devi: mandargli il contatto del fonico", "Ti deve: il preventivo delle luci")),
                    PeopleTodayRow("giulia-r", "Giulia R.", "Non senti Giulia R. da 3 settimane."),
                ),
            ),
            {}, {}, {}, {}, {}, {},
        )
    }
}
