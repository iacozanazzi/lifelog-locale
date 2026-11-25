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

/** Anteprime della memoria (settimana, abitudini, salute, luoghi, domande) con dati finti. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "w412dp-h915dp-xxhdpi")
class MemoryScreenshotTest {
    @get:Rule val rule = createComposeRule()

    private fun shot(name: String, dark: Boolean = false, tab: Tab? = null, content: @Composable () -> Unit) {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            LifeTheme(dark = dark) {
                Box(Modifier.fillMaxSize().background(Life.c.bg)) {
                    Column {
                        Box(Modifier.weight(1f)) { content() }
                        if (tab != null) BottomBar(tab) {}
                    }
                }
            }
        }
        rule.mainClock.advanceTimeBy(800)
        rule.onRoot().captureRoboImage("build/screens/$name.png")
    }

    private val today = TodayUi(
        dateLabel = "Mercoledì 2 dicembre", ready = true, state = RecState.LISTENING,
        stalledSince = null, level = 0.3f, speaking = false, speechMinutes = 96,
        pending = 0, pendingMinutes = 0, transcribeStatus = "", canTranscribeNow = false,
        lines = listOf("Allora ci sentiamo domani per la grafica.", "Il sei siamo al Rosa, confermato."),
        lastTime = "18:02", crash = null,
        brief = Brief("Lezione di diritto commerciale, pranzo con Giulia, prove audio al Rosa.", listOf(TodoItem("i1", "Mandare a Marco il contatto del fonico", "", false))),
    )

    private val memory = MemoryTodayUi(
        mementoLabel = "Un mese fa oggi", mementoDay = "2026-11-02",
        mementoText = "Prima riunione di Seral dopo l'estate: deciso di provare il giovedì al Rosa.",
        weekText = "Università 9 h 20, Persone 6 h 05, Seral 4 h 40. Palestra: 2 su 3.",
    )

    @Test fun today() = shot("m1-oggi-memoria", tab = Tab.TODAY) {
        TodayScreen(today, {}, {}, {}, {}, {}, {}, memory = memory)
    }

    private val letters = listOf("L", "M", "M", "G", "V", "S", "D")
    private fun days(done: Set<Int>, skipped: Set<Int> = emptySet(), todayIdx: Int = 9) = (0 until 14).map { i ->
        HabitDay("d$i", letters[i % 7], if (i in done) 1 else if (i in skipped) 2 else 0, future = i > todayIdx)
    }

    private val week = WeekUi(
        title = "Da lunedì 30 novembre",
        areas = listOf(AreaBar("Seral", 280, 210), AreaBar("Università", 560, 610), AreaBar("Persone", 365, 300)),
        areasNote = "Contati 2 giorni; oggi entra domani mattina.",
        habits = listOf(
            HabitRow(
                "a1", "Palestra", "2 su 3 questa settimana",
                "Detto 25 novembre alle 16:40: \"da lunedì vado in palestra tre volte a settimana\"",
                days(setOf(0, 2, 4, 7, 9), skipped = setOf(5)),
                "Ultima prova: mercoledì 2 alle 19:10, \"sono appena tornato dalla palestra\".",
            ),
            HabitRow(
                "a2", "Niente telefono a letto", "Una volta questa settimana",
                "Detto 28 novembre alle 23:50: \"da stasera il telefono resta in cucina\"",
                days(setOf(6, 8)), "",
            ),
        ),
        health = HealthState.CONNECTED,
        healthDays = listOf(
            HealthDayRow("1", "Oggi", "7 h 10 di sonno", "6.204 passi"),
            HealthDayRow("2", "Ieri", "6 h 25 di sonno", "11.873 passi"),
            HealthDayRow("3", "Lunedì 30", "7 h 50 di sonno", "8.412 passi"),
            HealthDayRow("4", "Domenica 29", "sonno senza dati", "4.015 passi"),
        ),
        healthNote = "Il sonno di stanotte compare quando Samsung Health lo sincronizza.",
        places = PlacesState.ON,
        topPlaces = listOf("Casa" to "61 h", "Ca' Foscari" to "11 h 30", "Rosa" to "3 h 15"),
    )

    @Test fun week() = shot("m2-settimana") { WeekScreen(week, {}, { _, _, _ -> }, {}, {}, {}, {}) }

    @Test fun week_empty() = shot("m3-settimana-vuota", dark = true) {
        WeekScreen(
            WeekUi(
                "Da lunedì 30 novembre", listOf(AreaBar("Seral", 0, 0), AreaBar("Università", 0, 0), AreaBar("Persone", 0, 0)),
                "Nessun giorno analizzato da lunedì. Oggi conta da domani mattina.", emptyList(),
                HealthState.NOT_CONNECTED, emptyList(), "", PlacesState.OFF, emptyList(),
            ),
            {}, { _, _, _ -> }, {}, {}, {}, {}
        )
    }

    @Test fun day() = shot("m4-giorno-luoghi") {
        DayScreen(
            DayUi(
                title = "Ieri", subtitle = "1 dicembre 2026. 5 h 12 min di voce, 31.402 parole",
                spans = emptyList(), sections = emptyList(), pending = 0,
                story = YesterdayUi(
                    dateLabel = "Martedì 1 dicembre", state = AnState.READY, note = "312 minuti di parlato, analisi delle 03:14",
                    giornata = "Lezione di diritto commerciale, pranzo con Giulia, prove audio al Rosa.",
                    sections = listOf("Seral" to listOf(Entry("18:40", "Il fonico porta il suo mixer.", "Decisione"))),
                    tomorrow = emptyList(), verify = emptyList(),
                ),
                people = listOf("Giulia", "Marco"),
            ),
            {}, {},
            memory = DayMemoryUi(
                health = "Dormito 6 h 25, dalle 1:05 alle 7:40. 11.873 passi.",
                stays = listOf(
                    StayRow("9:05–12:50", "Ca' Foscari", emptyList(), 0.0, 0.0),
                    StayRow("13:10–14:20", null, listOf("Giulia"), 0.0, 0.0),
                    StayRow("18:15–20:40", "Rosa", listOf("Marco"), 0.0, 0.0),
                ),
            ),
        )
    }

    @Test fun questions() = shot("m5-domande") {
        QuestionsScreen(
            QuestionsUi(
                listOf(
                    QuestionRow("Preparami per {persona}", listOf("persona"), false),
                    QuestionRow("Cosa ho promesso questa settimana", emptyList(), false),
                    QuestionRow("Cosa mi devono gli altri", emptyList(), false),
                    QuestionRow("Cosa abbiamo deciso per Seral", emptyList(), false),
                    QuestionRow("Quando ho parlato di {argomento}", listOf("argomento"), false),
                    QuestionRow("Quali DJ abbiamo sentito questo mese", emptyList(), true),
                ),
                listOf("Marco", "Giulia", "Tommaso"),
            ),
            {}, { _, _ -> }, {}, {}
        )
    }
}
