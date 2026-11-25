package it.iaco.lifelog.ui

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import it.iaco.lifelog.aree.Aree
import it.iaco.lifelog.aree.Fonte
import it.iaco.lifelog.aree.Seral
import it.iaco.lifelog.aree.Studio
import it.iaco.lifelog.aree.Uni
import it.iaco.lifelog.data.Assistant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

// Collegamento tra i dati di Seral e università (pacchetto aree) e le schermate pure.

private val IT = Locale.ITALIAN

/** "a, b e c" */
private fun andList(l: List<String>) = if (l.size <= 1) l.joinToString("") else l.dropLast(1).joinToString(", ") + " e " + l.last()

private fun cap(s: String) = s.replaceFirstChar { it.uppercase() }

/** "sabato 6 dicembre" */
private fun dayName(d: LocalDate) = d.format(DateTimeFormatter.ofPattern("EEEE d MMMM", IT))

/** "tra 11 giorni", "domani", "oggi" */
private fun countdown(today: LocalDate, d: LocalDate): String = when (val n = Studio.giorni(today, d)) {
    0L -> "oggi"
    1L -> "domani"
    else -> "tra $n giorni"
}

/** "detto mercoledì 25 novembre alle 16:42" */
private fun said(f: Fonte?, verb: String = "detto"): String {
    if (f == null) return ""
    val d = runCatching { LocalDate.parse(f.giorno) }.getOrNull() ?: return ""
    return "$verb ${d.format(DateTimeFormatter.ofPattern("EEEE d MMMM", IT))}" + if (f.ora.isNotBlank()) " alle ${f.ora}" else ""
}

private val VOCE_LABEL = mapOf("locale" to "Locale", "dj" to "DJ", "grafica" to "Grafica", "post" to "Post", "soldi" to "Soldi")

// =============================================================== Seral

internal fun seralUi(ctx: Context, today: LocalDate = Aree.today()): SeralUi {
    val nights = Seral.upcoming(ctx, today)
    val titles = Seral.nights(ctx).associate { it.id to it.titolo }
    val ideas = Seral.ideas(ctx)
    fun idea(i: Seral.Idea) = IdeaUi(
        id = i.id, text = i.testo, archived = i.stato != "nuova",
        meta = listOfNotNull(
            cap(said(i.fonte, "detta")).takeIf { it.isNotBlank() },
            when (i.ritorni) { 0 -> null; 1 -> "tornata un'altra volta"; else -> "tornata altre ${i.ritorni} volte" },
            i.serata?.let { titles[it] }?.let { "per $it" },
        ).joinToString(", "),
    )
    val post = Seral.post(ctx)
    return SeralUi(
        configured = Assistant.isConfigured(ctx),
        nights = nights.map { n ->
            NightUi(
                id = n.id, title = n.titolo,
                whenText = n.data?.let { cap(dayName(it)) + ", " + countdown(today, it) } ?: "Data non ancora detta",
                checks = n.checklist.map { v ->
                    NightCheckUi(
                        key = v.voce, label = VOCE_LABEL[v.voce] ?: cap(v.voce),
                        state = when (v.stato) { "fatto" -> CheckState.DONE; "da_fare" -> CheckState.TODO; else -> CheckState.UNKNOWN },
                        note = v.nota.orEmpty(),
                        source = if (v.aMano) "Segnato da te" else cap(said(v.fonte)),
                    )
                },
            )
        },
        post = post?.let { p ->
            SeralPostUi(
                title = if (p.numero != null) "Post ${p.numero}" else "Post della settimana",
                nights = p.serate.mapNotNull { titles[it] },
                ideas = p.idee.map { Entry(it.fonte?.ora.orEmpty(), it.testo) },
            )
        },
        ideas = ideas.filter { it.stato == "nuova" }.sortedByDescending { it.fonte?.giorno.orEmpty() + it.fonte?.ora.orEmpty() }.map(::idea),
        archived = ideas.filter { it.stato != "nuova" }.map(::idea),
    )
}

@Composable
internal fun SeralRoute(tick: Int, onBack: () -> Unit, onSetup: () -> Unit) {
    BackHandler(onBack = onBack)
    val ctx = LocalContext.current
    var version by remember { mutableIntStateOf(0) }
    var ui by remember { mutableStateOf<SeralUi?>(null) }
    LaunchedEffect(tick / 15, version) { ui = withContext(Dispatchers.IO) { seralUi(ctx) } }
    ui?.let { s ->
        SeralScreen(
            s, onBack = onBack, onSetup = onSetup,
            onCheck = { night, key, done -> Seral.setCheck(ctx, night, key, done); version++ },
            onIdea = { id, stato ->
                Seral.setIdea(ctx, id, stato); version++
                if (stato == "scartata") Toast.makeText(ctx, "Idea archiviata", Toast.LENGTH_SHORT).show()
            },
        )
    }
}

// =============================================================== Università

internal fun uniUi(ctx: Context, today: LocalDate = Aree.today()): UniUi {
    val queue = Uni.queue(ctx, today)
    val summary = Uni.reviewSummary(ctx, today).associateBy { it.materia }
    val short = DateTimeFormatter.ofPattern("EEE d", IT)
    val exams = Uni.exams(ctx, today).map { e ->
        val done = Uni.doneTopics(ctx, e.id)
        val plan = Studio.piano(e.argomenti.filter { it !in done }, today, e.data)
        fun what(g: Studio.GiornoDiStudio) = when {
            g.argomenti.isNotEmpty() -> g.argomenti.joinToString(", ")
            g.ripasso -> "Ripasso generale"
            else -> "Ripasso delle domande"
        }
        val todayPlan = plan.firstOrNull()?.takeIf { e.argomenti.isNotEmpty() }
        val sum = summary[e.materia]
        ExamUi(
            id = e.id, subject = e.materia,
            whenText = cap(dayName(e.data)) + listOfNotNull(e.ora?.let { " alle $it" }, e.tipo?.let { ", $it" }).joinToString(""),
            countdown = countdown(today, e.data),
            today = todayPlan?.let { "Oggi: " + what(it) } ?: if (e.argomenti.isNotEmpty() && done.containsAll(e.argomenti)) "Programma finito: ripassa le domande." else null,
            nextDays = if (e.argomenti.isEmpty()) emptyList() else plan.drop(1).take(6).map { Entry(it.data.format(short), what(it)) },
            topics = e.argomenti.map { TopicUi(it, it in done) },
            reviewLine = sum?.let {
                (if (it.domande == 1) "1 domanda di ripasso" else "${it.domande} domande di ripasso") +
                    if (it.sbagliate7 > 0) ", ${it.sbagliate7} sbagliate negli ultimi 7 giorni" else ""
            }.orEmpty(),
            manual = e.aMano,
        )
    }
    val subjects = queue.map { it.materia }.distinct()
    return UniUi(
        configured = Assistant.isConfigured(ctx),
        due = queue.size,
        dueLine = andList(subjects) + ", circa ${((queue.size * 20 + 59) / 60).coerceAtLeast(1)} minuti",
        totalQuestions = Uni.questions(ctx).size,
        exams = exams,
        subjects = Uni.subjects(ctx),
    )
}

@Composable
internal fun UniRoute(tick: Int, onBack: () -> Unit, onReview: () -> Unit, onSetup: () -> Unit) {
    BackHandler(onBack = onBack)
    val ctx = LocalContext.current
    var version by remember { mutableIntStateOf(0) }
    var ui by remember { mutableStateOf<UniUi?>(null) }
    LaunchedEffect(tick / 15, version) { ui = withContext(Dispatchers.IO) { uniUi(ctx) } }
    ui?.let { s ->
        UniScreen(
            s, onBack = onBack, onReview = onReview, onSetup = onSetup,
            onTopic = { exam, topic, done -> Uni.setTopic(ctx, exam, topic, done); version++ },
            onAddExam = { subject, date ->
                Uni.addExam(ctx, subject, LocalDate.parse(date)); version++
                Toast.makeText(ctx, "Esame aggiunto. Stanotte Claude ci collega il programma dalle lezioni.", Toast.LENGTH_LONG).show()
            },
            onRemoveExam = { Uni.removeExam(ctx, it); version++ },
        )
    }
}

@Composable
internal fun ReviewRoute(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val ctx = LocalContext.current
    // La coda si fissa all'apertura; le sbagliate tornano una volta in fondo, come in Anki.
    var queue by remember { mutableStateOf<List<Uni.Domanda>?>(null) }
    var index by remember { mutableIntStateOf(0) }
    var revealed by remember { mutableStateOf(false) }
    var knew by remember { mutableIntStateOf(0) }
    var retried by remember { mutableStateOf(setOf<String>()) }
    LaunchedEffect(Unit) { queue = withContext(Dispatchers.IO) { Uni.queue(ctx) } }
    val q = queue ?: return
    val cur = q.getOrNull(index)
    ReviewScreen(
        card = cur?.let {
            ReviewCardUi(
                subject = it.materia, topic = it.argomento, question = it.domanda, answer = it.risposta,
                source = cap(said(it.fonte, "dalla lezione di")), position = "${index + 1} di ${q.size}",
            )
        },
        revealed = revealed,
        finishedText = when {
            q.isEmpty() -> "Niente da ripassare adesso."
            else -> "Sapevi $knew risposte su ${q.size}. Le prossime domande tornano quando servono."
        },
        onReveal = { revealed = true },
        onAnswer = { ok ->
            if (cur != null) {
                if (cur.id !in retried) {
                    // Solo il primo tentativo conta per gli intervalli; una sbagliata torna una volta in fondo.
                    Uni.answer(ctx, cur, ok)
                    if (ok) knew++ else { queue = q + cur; retried = retried + cur.id }
                }
                index++; revealed = false
            }
        },
        onDiscard = {
            if (cur != null) {
                Uni.discard(ctx, cur.id)
                queue = q.filterIndexed { i, x -> i < index || x.id != cur.id }
                revealed = false
            }
        },
        onBack = onBack,
    )
}

// =============================================================== Scheda in Oggi

internal fun areeLines(ctx: Context, today: LocalDate = Aree.today()): List<AreaLine> {
    if (!Assistant.isConfigured(ctx)) return emptyList()
    val due = Uni.queue(ctx, today).size
    val exam = Uni.exams(ctx, today).firstOrNull()
    val night = Seral.upcoming(ctx, today).firstOrNull()
    val newIdeas = Seral.ideas(ctx).count { it.stato == "nuova" }
    val lines = mutableListOf<Pair<Int, AreaLine>>() // priorità più bassa = più urgente
    if (exam != null) {
        val n = Studio.giorni(today, exam.data)
        val done = Uni.doneTopics(ctx, exam.id)
        val todayTopics = Studio.piano(exam.argomenti.filter { it !in done }, today, exam.data).firstOrNull()?.argomenti.orEmpty()
        lines += (if (n <= 7) 0 else 3) to AreaLine(
            "uni", "${exam.materia} ${countdown(today, exam.data)}",
            if (todayTopics.isNotEmpty()) "Oggi: " + todayTopics.joinToString(", ") else cap(dayName(exam.data)),
        )
    }
    if (due > 0) lines += 1 to AreaLine("uni", if (due == 1) "Una domanda da ripassare" else "$due domande da ripassare", "Circa ${((due * 20 + 59) / 60).coerceAtLeast(1)} minuti")
    if (night != null) {
        val missing = night.checklist.filter { it.stato != "fatto" }.map { VOCE_LABEL[it.voce] ?: it.voce }
        val soon = night.data?.let { Studio.giorni(today, it) <= 7 } == true
        lines += (if (soon) 0 else 2) to AreaLine(
            "seral", night.titolo + (night.data?.let { ", ${dayName(it)}" } ?: ""),
            if (missing.isEmpty()) "Tutto pronto" else "Da fare: " + missing.joinToString(", ").lowercase(IT).replace("dj", "DJ"),
        )
    }
    if (night == null && newIdeas > 0) lines += 4 to AreaLine("seral", "Seral", if (newIdeas == 1) "Una idea nuova" else "$newIdeas idee nuove")
    if (lines.none { it.second.area == "seral" }) lines += 9 to AreaLine("seral", "Seral", "Idee e serate in preparazione")
    if (lines.none { it.second.area == "uni" }) lines += 9 to AreaLine("uni", "Università", "Ripasso ed esami")
    return lines.sortedBy { it.first }.map { it.second }
}

/** Da mettere in Oggi: legge i dati da sola e apre l'area toccata. */
@Composable
fun AreeTodayCard(tick: Int, onOpen: (String) -> Unit) {
    val ctx = LocalContext.current
    var lines by remember { mutableStateOf(emptyList<AreaLine>()) }
    LaunchedEffect(tick / 15) { lines = withContext(Dispatchers.IO) { areeLines(ctx) } }
    AreeCard(lines, onOpen)
}

/** Titolo di una sezione del Racconto → area da aprire, o null. */
fun areaOfSection(title: String): String? = when (title.trim().lowercase(IT)) {
    "seral" -> "seral"
    "università", "universita", "uni" -> "uni"
    else -> null
}
