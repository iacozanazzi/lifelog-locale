package it.iaco.lifelog.ui

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
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
 * Non è un vero test: genera le immagini di anteprima delle schermate con dati finti,
 * così si vede l'aspetto dell'app senza un telefono. Le immagini vengono pubblicate nella Release.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "w412dp-h915dp-xxhdpi")
class ScreenshotTest {
    @get:Rule val rule = createComposeRule()

    private fun shot(name: String, dark: Boolean = true, tab: Tab? = Tab.TODAY, content: @Composable () -> Unit) {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            LifeTheme(dark = dark) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    Column {
                        Box(Modifier.weight(1f)) { content() }
                        if (tab != null) BottomBar(tab) {}
                    }
                }
            }
        }
        rule.mainClock.advanceTimeBy(1300)
        rule.onRoot().captureRoboImage("build/screens/$name.png")
    }

    private val spans = listOf(
        Span(0f, 400f, Span.Kind.GAP),
        Span(452f, 470f, Span.Kind.TRANSCRIBED), Span(480f, 486f, Span.Kind.TRANSCRIBED),
        Span(540f, 600f, Span.Kind.TRANSCRIBED), Span(612f, 640f, Span.Kind.TRANSCRIBED),
        Span(700f, 712f, Span.Kind.TRANSCRIBED), Span(745f, 790f, Span.Kind.TRANSCRIBED),
        Span(612f + 260, 640f + 260, Span.Kind.GAP),
        Span(930f, 960f, Span.Kind.PENDING), Span(975f, 985f, Span.Kind.PENDING),
    )

    private val today = TodayUi(
        dateLabel = "mercoledì 25 novembre", ready = true, mode = OrbMode.LISTENING, wanted = true,
        stalledSince = null, level = 0.55f, speaking = true, speechMinutes = 214, words = 18430,
        pending = 37, pendingMinutes = 42, batteryLine = "Batteria 64% · 31,2 °C · non in carica",
        transcribeStatus = "In attesa: trascrivo quando metti in carica", canTranscribeNow = true,
        spans = spans, nowMin = 990f,
        latest = listOf(
            Moment("16:15–16:25", "Allora ci vediamo giovedì in studio, porto io le bozze del progetto e ne parliamo con calma.", pending = false),
            Moment("14:32–15:00", "Non registrato per 28 minuti (servizio fermato dal sistema)", gap = true),
            Moment("12:25–13:10", "La cosa interessante è che il modello gira tutto sul telefono, senza mandare niente fuori."),
        ),
        crash = null,
    )

    @Test fun today_dark() = shot("1-oggi") {
        TodayScreen(today, {}, {}, {}, {}, {}, {}, {})
    }

    @Test fun today_light() = shot("6-oggi-chiaro", dark = false) {
        TodayScreen(today.copy(speaking = false, level = 0.1f), {}, {}, {}, {}, {}, {}, {})
    }

    @Test fun today_stalled() = shot("5-fermo") {
        TodayScreen(today.copy(mode = OrbMode.STALLED, stalledSince = "14:32", speaking = false, level = 0f), {}, {}, {}, {}, {}, {}, {})
    }

    @Test fun archive() = shot("2-archivio", tab = Tab.ARCHIVE) {
        val rows = listOf(
            DayRow("2026-11-25", "Oggi", "25 novembre 2026", 214, 18430, 37, spans),
            DayRow("2026-11-24", "Ieri", "24 novembre 2026", 302, 26120, 0, spans.map { it.copy(fromMin = (it.fromMin + 90) % 1400, toMin = (it.toMin + 90) % 1440, kind = if (it.kind == Span.Kind.PENDING) Span.Kind.TRANSCRIBED else it.kind) }),
            DayRow("2026-11-23", "Lunedì 23", "23 novembre 2026", 128, 9800, 0, spans.filter { it.kind != Span.Kind.GAP }.map { it.copy(kind = Span.Kind.TRANSCRIBED) }),
        )
        ArchiveScreen(rows, "", emptyList(), {}, {})
    }

    @Test fun day() = shot("3-giorno", tab = null) {
        DayScreen(
            DayUi(
                title = "Ieri", subtitle = "24 novembre 2026", spans = spans, pending = 0,
                moments = listOf(
                    Moment("07:32–07:50", "Buongiorno. Oggi devo ricordarmi di chiamare Marco per la questione del contratto e poi passare in banca."),
                    Moment("09:00–10:00", "Lezione: il punto chiave è che la domanda aggregata dipende dalle aspettative, non solo dal reddito corrente. Questo spiega perché le politiche annunciate hanno effetto prima di essere applicate."),
                    Moment("10:12–10:40", "Non registrato per 28 minuti (servizio fermato dal sistema)", gap = true),
                    Moment("12:25–13:10", "Pranzo con Giulia: idea per l'app, aggiungere un riassunto della settimana ogni domenica sera."),
                )
            ),
            onBack = {}
        )
    }

    @Test fun settings() = shot("4-impostazioni", tab = Tab.SETTINGS) {
        SettingsScreen(
            SettingsUi(true, true, 37.5f, true, "Salvato alle 16:20 (184 KB)", "0.1.8"),
            {}, {}, {}, {}, {}, {}, {}, {}
        )
    }

    @Test fun setup() = shot("0-configurazione", tab = null) {
        SetupScreen(
            SetupUi(mic = true, notif = true, battery = false, download = DownloadState.RUNNING, downloadPct = 62, downloadStage = "Download 62%", downloadError = ""),
            {}, {}, {}, {}, {}, {}, {}
        )
    }
}
