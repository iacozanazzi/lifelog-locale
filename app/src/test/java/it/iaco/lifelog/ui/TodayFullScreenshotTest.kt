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

/**
 * Oggi in una giornata piena, con tutte le funzioni attive insieme: è il caso vero
 * di tutti i giorni. "Lungo" mostra tutta la schermata scorrendo fino in fondo.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "w412dp-h915dp-xxhdpi")
class TodayFullScreenshotTest {
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

    private val giulia = PersonNote("Giulia", listOf("Le hai promesso gli appunti di macro (ieri alle 12:40)"))
    private val marco = PersonNote("Marco", listOf("Deve mandarti il preventivo del fonico (ieri alle 17:05)"))

    private val day = TodayUi(
        dateLabel = "Giovedì 26 novembre", ready = true, state = RecState.LISTENING,
        stalledSince = null, level = 0.3f, speaking = false, speechMinutes = 96,
        pending = 4, pendingMinutes = 9, transcribeStatus = "Trascrivo quando metti in carica", canTranscribeNow = false,
        lines = listOf(
            "Il punto è che la domanda dipende dalle aspettative.",
            "Allora il sei lo facciamo al Rosa.",
            "Perfetto, sento Marco per il set.",
        ),
        lastTime = "12:31", crash = null,
        brief = Brief(
            "Lezione di macro, pranzo con Giulia, sopralluogo al Rosa per la serata del 6.",
            listOf(
                TodoItem("i-3", "Mandare a Marco il contatto del fonico", "", done = false, person = "Marco"),
                TodoItem("i-4", "Testo del post 47/52 a Tommaso", "", done = false, person = "Tommaso"),
            ), more = 3,
        ),
        briefing = BriefingUi(
            agenda = listOf(
                AgendaRow("10:00", "Lezione di macroeconomia", "Aula Magna, San Giobbe", emptyList(), past = true),
                AgendaRow("13:00", "Pranzo con Giulia", "Da Lele", listOf(giulia)),
                AgendaRow("18:00", "Sopralluogo al Rosa", "Campo Santa Margherita", listOf(marco)),
            ),
            due = listOf(
                TodoItem("i-1", "Mandare la grafica della serata del 6 al locale", "Scade domani", done = false),
                TodoItem("i-2", "Marco deve: mandare il preventivo del fonico", "Scade oggi", done = false, person = "Marco"),
            ),
            proposals = listOf(
                ProposalRow("e-1", "Consegna tesina di diritto commerciale", "Sabato 12 dicembre", "Detto ieri alle 11:42: «la tesina va consegnata entro il 12»"),
            ),
            drafts = listOf(
                DraftRow("b-1", "A Marco", "Ciao Marco! Ti giro il contatto del fonico di cui ti parlavo. Fammi sapere se lo senti.", "Hai promesso ieri alle 17:05"),
            ),
        ),
        people = listOf(
            PeopleTodayRow("giulia", "Giulia", "Oggi alle 13:00 vedi Giulia.", listOf("Devi: gli appunti di macro")),
            PeopleTodayRow("sara", "Sara", "Non senti Sara da 3 settimane."),
        ),
    )

    private val memory = MemoryTodayUi(
        mementoLabel = "Un mese fa oggi", mementoDay = "2026-10-26",
        mementoText = "Prima riunione di Seral dopo l'estate: deciso di provare il giovedì al Rosa.",
        weekText = "Università 9 h 20, Persone 6 h 05, Seral 4 h 40. Palestra: 2 su 3.",
    )

    private val aree = listOf(
        AreaLine("seral", "Serata al Rosa, sabato 6 dicembre", "Da fare: DJ, grafica, soldi"),
        AreaLine("uni", "7 domande da ripassare", "Circa 3 minuti"),
        AreaLine("uni", "Diritto commerciale tra 51 giorni", "Oggi: Marchi di forma"),
    )

    @Composable
    private fun Full() = TodayScreen(day, {}, {}, {}, {}, {}, {}, memory = memory, cards = { AreeCard(aree, {}) })

    @Test fun full() = shot("o1-oggi-completo") { Full() }

    @Config(qualifiers = "w412dp-h3000dp-xxhdpi")
    @Test fun full_long() = shot("o2-oggi-completo-lungo") { Full() }

    @Test fun full_dark() = shot("o3-oggi-completo-scuro", dark = true) { Full() }
}
