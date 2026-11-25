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

/** Anteprime di Seral e università con dati finti (prefisso s). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "w412dp-h915dp-xxhdpi")
class SeralUniScreenshotTest {
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

    private val seral = SeralUi(
        configured = true,
        nights = listOf(
            NightUi(
                "s-20261206", "Serata al Rosa", "Sabato 6 dicembre, tra 11 giorni",
                listOf(
                    NightCheckUi("locale", "Locale", CheckState.DONE, "Confermato il Rosa, capienza 180.", "Detto mercoledì 25 novembre alle 16:40"),
                    NightCheckUi("dj", "DJ", CheckState.TODO, "Da sentire Marco per il set dalle 23.", "Detto mercoledì 25 novembre alle 16:50"),
                    NightCheckUi("grafica", "Grafica", CheckState.UNKNOWN, "", ""),
                    NightCheckUi("post", "Post", CheckState.DONE, "", "Segnato da te"),
                    NightCheckUi("soldi", "Soldi", CheckState.TODO, "Ingresso 10 euro, chi tiene la cassa è da decidere.", "Detto mercoledì 25 novembre alle 17:02"),
                ),
            ),
        ),
        post = SeralPostUi(
            "Post 48/52", listOf("Serata al Rosa"),
            listOf(
                Entry("18:05", "Aprire il carosello con la foto del Rosa pieno."),
                Entry("21:12", "Mettere l'orario del DJ nella seconda slide."),
            ),
        ),
        ideas = listOf(
            IdeaUi("idea-1", "Serata anni 2000 con dress code obbligatorio.", "Detta mercoledì 25 novembre alle 16:42, tornata un'altra volta"),
            IdeaUi("idea-2", "Biglietti in prevendita con prezzo più basso fino a giovedì.", "Detta martedì 24 novembre alle 22:10, per Serata al Rosa"),
            IdeaUi("idea-3", "Chiedere al Rosa una sala più piccola per il dopo serata.", "Detta lunedì 23 novembre alle 19:30"),
        ),
        archived = listOf(IdeaUi("idea-0", "Flyer stampati in Campo Santa Margherita.", "Detta venerdì 20 novembre alle 18:00", archived = true)),
    )

    private val uni = UniUi(
        configured = true, due = 7, dueLine = "Diritto commerciale e Statistica, circa 3 minuti", totalQuestions = 42,
        exams = listOf(
            ExamUi(
                "e-dc", "Diritto commerciale", "Venerdì 15 gennaio, scritto", "tra 51 giorni", "Oggi: Marchi di forma",
                listOf(Entry("gio 26", "Brevetti"), Entry("ven 27", "Concorrenza sleale"), Entry("sab 28", "Ripasso delle domande")),
                listOf(TopicUi("Imprenditore", true), TopicUi("Marchi di forma", false), TopicUi("Brevetti", false), TopicUi("Concorrenza sleale", false)),
                "18 domande di ripasso, 3 sbagliate negli ultimi 7 giorni", manual = false,
            ),
            ExamUi("e-st", "Statistica", "Martedì 26 gennaio", "tra 62 giorni", null, emptyList(), emptyList(), "6 domande di ripasso", manual = true),
        ),
        subjects = listOf("Diritto commerciale", "Statistica"),
    )

    private val card = ReviewCardUi(
        "Diritto commerciale", "Marchi di forma",
        "Quando una forma non può essere registrata come marchio?",
        "Quando dà valore sostanziale al prodotto o serve per ottenere un risultato tecnico.",
        "Dalla lezione di mercoledì 25 novembre alle 10:12", "3 di 7",
    )

    @Test fun seral() = shot("s1-seral") { SeralScreen(seral, {}, { _, _, _ -> }, { _, _ -> }) }
    @Test fun seral_dark() = shot("s1b-seral-scuro", dark = true) { SeralScreen(seral, {}, { _, _, _ -> }, { _, _ -> }) }
    @Test fun uni() = shot("s2-universita") { UniScreen(uni, {}, {}, { _, _, _ -> }, { _, _ -> }, {}) }
    @Test fun review() = shot("s3-ripasso-domanda") { ReviewScreen(card, false, "", {}, {}, {}, {}) }
    @Test fun review_answer() = shot("s4-ripasso-risposta") { ReviewScreen(card, true, "", {}, {}, {}, {}) }
    @Test fun review_done() = shot("s5-ripasso-finito") { ReviewScreen(null, false, "Sapevi 5 risposte su 7. Le prossime domande tornano quando servono.", {}, {}, {}, {}) }
    @Test fun today_card() = shot("s6-oggi-scheda", tab = Tab.TODAY) {
        TodayScreen(
            TodayUi(
                dateLabel = "Giovedì 26 novembre", ready = true, state = RecState.LISTENING, stalledSince = null,
                level = 0.3f, speaking = false, speechMinutes = 42, pending = 0, pendingMinutes = 0,
                transcribeStatus = "", canTranscribeNow = false,
                lines = listOf("Allora il sei lo facciamo al Rosa.", "Perfetto, sento Marco per il set."),
                lastTime = "09:12", crash = null,
                brief = Brief("Mattina a lezione di diritto commerciale, pomeriggio sopralluogo al Rosa.", listOf(TodoItem("i1", "Mandare a Marco il contatto del fonico", "", false))),
            ),
            {}, {}, {}, {}, {}, {},
            cards = {
                AreeCard(
                    listOf(
                        AreaLine("uni", "7 domande da ripassare", "Circa 3 minuti"),
                        AreaLine("seral", "Serata al Rosa, sabato 6 dicembre", "Da fare: DJ, grafica, soldi"),
                        AreaLine("uni", "Diritto commerciale tra 51 giorni", "Oggi: Marchi di forma"),
                    ),
                    {},
                )
            },
        )
    }
}
