package it.iaco.lifelog.ui

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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

/** Anteprime del briefing in Oggi, con dati finti. Non è un vero test. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "w412dp-h915dp-xxhdpi")
class BriefingScreenshotTest {
    @get:Rule val rule = createComposeRule()

    private fun shot(name: String, dark: Boolean = false, content: @Composable () -> Unit) {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            LifeTheme(dark = dark) {
                Box(Modifier.fillMaxSize().background(Life.c.bg)) {
                    Column {
                        Box(Modifier.weight(1f)) { content() }
                        BottomBar(Tab.TODAY) {}
                    }
                }
            }
        }
        rule.mainClock.advanceTimeBy(800)
        rule.onRoot().captureRoboImage("build/screens/$name.png")
    }

    private val base = TodayUi(
        dateLabel = "Giovedì 26 novembre", ready = true, state = RecState.LISTENING,
        stalledSince = null, level = 0.3f, speaking = false, speechMinutes = 48,
        pending = 0, pendingMinutes = 0, transcribeStatus = "", canTranscribeNow = false,
        lines = listOf("Allora ci vediamo alle sei davanti al Rosa.", "Porto io le casse piccole."),
        lastTime = "09:12", crash = null,
    )

    private val marco = PersonNote("Marco", listOf("Deve mandarti il preventivo del fonico (ieri alle 17:05)", "Esame di diritto a gennaio (il 20 novembre alle 13:05)"))
    private val giulia = PersonNote("Giulia", listOf("Le hai promesso gli appunti di macro (ieri alle 12:40)"))

    private val morning = BriefingUi(
        agenda = listOf(
            AgendaRow("10:00", "Lezione di macroeconomia", "Aula Magna, San Giobbe", emptyList()),
            AgendaRow("13:00", "Pranzo con Giulia", "", listOf(giulia)),
            AgendaRow("18:00", "Sopralluogo al Rosa", "Campo Santa Margherita", listOf(marco)),
        ),
        due = listOf(
            TodoItem("i-1", "Mandare la grafica della serata del 6 al locale", "Scade domani", done = false),
            TodoItem("i-2", "Marco deve: mandare il preventivo del fonico", "Scade oggi", done = false, person = "Marco"),
        ),
    )

    private val evening = BriefingUi(
        tomorrow = listOf(
            PrepCard(
                "Domani alle 11:00", "Caffè con Tommaso", listOf(PersonNote("Tommaso", listOf("Aspetta il testo del post 47/52 (ieri alle 16:20)"))),
                listOf("Avevate lasciato in sospeso il DJ per il 13 dicembre (ieri alle 16:31)"),
            ),
        ),
        proposals = listOf(
            ProposalRow("e-1", "Consegna tesina di diritto commerciale", "Sabato 12 dicembre, tutto il giorno", "Detto ieri alle 11:42: «la tesina va consegnata entro il 12»"),
        ),
        drafts = listOf(
            DraftRow("b-1", "A Marco", "Ciao Marco! Ti giro il contatto del fonico di cui ti parlavo: [contatto]. Fammi sapere se lo senti.", "Hai promesso ieri alle 17:05: Mandare a Marco il contatto del fonico"),
        ),
    )

    private val brief = Brief(
        "Lezione di macro, pranzo con Giulia, sopralluogo al Rosa per la serata del 6.",
        listOf(TodoItem("1", "Mandare a Marco il contatto del fonico", "", done = false)),
    )

    @Test fun morning() = shot("b1-briefing-mattina") {
        TodayScreen(base.copy(brief = brief, briefing = morning), {}, {}, {}, {}, {}, {})
    }

    @Test fun evening() = shot("b2-briefing-sera") {
        TodayScreen(base.copy(briefing = evening), {}, {}, {}, {}, {}, {})
    }

    @Test fun evening_dark() = shot("b3-briefing-sera-scuro", dark = true) {
        TodayScreen(base.copy(briefing = evening), {}, {}, {}, {}, {}, {})
    }

    @Test fun noCalendar() = shot("b4-briefing-senza-calendario") {
        TodayScreen(base.copy(brief = brief, briefing = BriefingUi(calendarMissing = true)), {}, {}, {}, {}, {}, {})
    }
}
