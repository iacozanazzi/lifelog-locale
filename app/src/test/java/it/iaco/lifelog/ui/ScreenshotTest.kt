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
 * Non è un vero test: genera le anteprime delle schermate con dati finti,
 * così si vede l'aspetto dell'app senza un telefono. Le immagini vanno nella Release.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "w412dp-h915dp-xxhdpi")
class ScreenshotTest {
    @get:Rule val rule = createComposeRule()

    private fun shot(name: String, dark: Boolean = false, tab: Tab? = Tab.TODAY, content: @Composable () -> Unit) {
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

    private val spans = listOf(
        Span(452f, 470f, Span.Kind.TRANSCRIBED), Span(480f, 486f, Span.Kind.TRANSCRIBED),
        Span(540f, 600f, Span.Kind.TRANSCRIBED), Span(612f, 640f, Span.Kind.GAP),
        Span(700f, 712f, Span.Kind.TRANSCRIBED), Span(745f, 790f, Span.Kind.TRANSCRIBED),
        Span(840f, 870f, Span.Kind.TRANSCRIBED),
        Span(930f, 960f, Span.Kind.PENDING), Span(975f, 985f, Span.Kind.PENDING),
    )

    private val today = TodayUi(
        dateLabel = "Mercoledì 25 novembre", ready = true, state = RecState.LISTENING,
        stalledSince = null, level = 0.4f, speaking = false, speechMinutes = 214,
        pending = 37, pendingMinutes = 42,
        transcribeStatus = "Trascrivo quando metti in carica", canTranscribeNow = false,
        lines = listOf(
            "E poi passare in banca prima delle cinque.",
            "Il punto è che la domanda dipende dalle aspettative.",
            "Il modello gira tutto sul telefono, senza mandare niente fuori.",
            "Allora ci vediamo giovedì in studio.",
            "Porto io le bozze del progetto e ne parliamo con calma.",
        ),
        lastTime = "16:25", crash = null,
    )

    @Test fun today() = shot("1-oggi") { TodayScreen(today, {}, {}, {}, {}, {}, {}) }

    @Test fun today_dark() = shot("6-oggi-scuro", dark = true) { TodayScreen(today, {}, {}, {}, {}, {}, {}) }

    @Test fun today_empty() = shot("5-oggi-vuoto") {
        TodayScreen(today.copy(state = RecState.OFF, lines = emptyList(), pending = 0, speechMinutes = 0, lastTime = null, transcribeStatus = ""), {}, {}, {}, {}, {}, {})
    }

    @Test fun days() = shot("2-giorni", tab = Tab.DAYS) {
        DaysScreen(
            listOf(
                DayRow("2026-11-25", "Oggi", "3 h 34 min", spans),
                DayRow("2026-11-24", "Ieri", "5 h 2 min", spans.map { it.copy(fromMin = it.fromMin - 60, toMin = it.toMin - 50, kind = if (it.kind == Span.Kind.PENDING) Span.Kind.TRANSCRIBED else it.kind) }),
                DayRow("2026-11-23", "Lunedì 23", "2 h 8 min", spans.filter { it.kind == Span.Kind.TRANSCRIBED }.take(4)),
                DayRow("2026-11-22", "Domenica 22", "48 min", spans.take(2).map { it.copy(fromMin = it.fromMin + 200, toMin = it.toMin + 230) }),
            ), {}
        )
    }

    @Test fun search() = shot("7-cerca", tab = Tab.SEARCH) {
        SearchScreen(
            "giulia",
            listOf(
                SearchHit("2026-11-24", "Ieri, 12:25", "Pranzo con Giulia: aggiungere all'app un riassunto della settimana ogni domenica sera."),
                SearchHit("2026-11-20", "Venerdì 20, 18:40", "Devo ridare a Giulia il libro di macroeconomia prima dell'esame."),
            ), {}, {}
        )
    }

    @Test fun day() = shot("3-giorno", tab = null) {
        DayScreen(
            DayUi(
                title = "Ieri", subtitle = "24 novembre 2026. 5 h 2 min di voce, 26.120 parole", spans = spans, pending = 0,
                sections = listOf(
                    "Mattina" to listOf(
                        Moment("07:32–07:50", "Buongiorno. Oggi devo ricordarmi di chiamare Marco per il contratto e poi passare in banca."),
                        Moment("09:00–10:00", "Il punto chiave è che la domanda aggregata dipende dalle aspettative, non solo dal reddito corrente."),
                        Moment("10:12–10:40", "non registrato (28 min). Il sistema ha chiuso l'app.", gap = true),
                    ),
                    "Pomeriggio" to listOf(
                        Moment("12:25–13:10", "Pranzo con Giulia: aggiungere all'app un riassunto della settimana ogni domenica sera."),
                    ),
                )
            ),
            onBack = {}, onShare = {}
        )
    }

    @Test fun settings() = shot("4-impostazioni", tab = Tab.SETTINGS) {
        SettingsScreen(
            SettingsUi(true, true, 37.5f, "Ora: batteria al 64%, 31,2 °C, non in carica.", true, "Salvato alle 16:20 (184 KB)", "0.1.10",
                engines = listOf(
                    EngineRow("PARAKEET", "Parakeet", "Veloce. Riconosce la lingua da solo.", "Pronto", selected = false, ready = true),
                    EngineRow("WHISPER", "Whisper turbo", "Più preciso in italiano, circa 3 volte più lento. Scarica 564 MB.", "Whisper: download al 41%", selected = true, ready = false),
                    EngineRow("GOOGLE", "Google sul telefono", "Il riconoscimento vocale di Google, offline.", "Se manca il pacchetto italiano offline, lo scarica Google da solo al primo uso.", selected = false, ready = true),
                )),
            {}, {}, {}, {}, {}, {}, {}, {}
        )
    }

    @Test fun compare() = shot("8-confronta", tab = null) {
        CompareScreen(
            CompareUi(false, "Fatto.", listOf(
                CompareRow("Martedì 25, 16:42", listOf(
                    "Parakeet, 1,2 s" to "allora ci vediamo giovedi pomeriggio in studio porto io le bozze",
                    "Whisper turbo, 3,8 s" to "Allora ci vediamo giovedì pomeriggio in studio, porto io le bozze del progetto.",
                    "Google sul telefono, 2,1 s" to "allora ci vediamo giovedì pomeriggio in studio porto io le bozze del progetto",
                )),
            )),
            {}, {}
        )
    }

    @Test fun setup() = shot("0-configurazione", tab = null) {
        SetupScreen(
            SetupUi(mic = true, notif = true, battery = false, download = DownloadState.RUNNING, downloadPct = 62, downloadStage = "Download al 62%", downloadError = ""),
            {}, {}, {}, {}, {}, {}, {}
        )
    }
}
