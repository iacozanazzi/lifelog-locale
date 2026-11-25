package it.iaco.lifelog.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.work.WorkInfo
import androidx.work.WorkManager
import it.iaco.lifelog.AsrFactory
import it.iaco.lifelog.data.Assistant
import it.iaco.lifelog.data.GitHub
import it.iaco.lifelog.data.Assistant.fetchStatus
import it.iaco.lifelog.data.Assistant.uploadStatus
import it.iaco.lifelog.CrashLog
import it.iaco.lifelog.Engine
import it.iaco.lifelog.Pcm
import it.iaco.lifelog.Recent
import it.iaco.lifelog.Db
import it.iaco.lifelog.DriveSync
import it.iaco.lifelog.Exporter
import it.iaco.lifelog.Gap
import it.iaco.lifelog.Live
import it.iaco.lifelog.ModelSetupWorker
import it.iaco.lifelog.Models
import it.iaco.lifelog.Notifications
import it.iaco.lifelog.Prefs
import it.iaco.lifelog.RecorderService
import it.iaco.lifelog.Segment
import it.iaco.lifelog.Throttle
import it.iaco.lifelog.TranscribeWorker
import it.iaco.lifelog.Watchdog
import it.iaco.lifelog.people.Speakers
import it.iaco.lifelog.people.Voice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private sealed interface Route {
    data object Main : Route
    data object Setup : Route
    data class Day(val day: String) : Route
    data object Compare : Route
    data object Settings : Route
    data object People : Route
    data class Person(val name: String) : Route
    data class Voice(val id: String) : Route
}

@Composable
fun App(resumeTick: Int) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    var route by remember { mutableStateOf<Route>(if (prefs.onboardingDone) Route.Main else Route.Setup) }
    var tab by remember { mutableStateOf(Tab.TODAY) }
    var searchQuery by remember { mutableStateOf("") }
    val openPerson: (String) -> Unit = { name -> route = Route.Person(name) }
    val searchFor: (String) -> Unit = { name -> searchQuery = name; tab = Tab.SEARCH; route = Route.Main }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(2000); tick++
        }
    }
    LaunchedEffect(resumeTick) {
        tick++
        Assistant.fetchSoon(ctx) // controlla se è arrivata l'analisi di stanotte
    }
    LaunchedEffect(Unit) { Assistant.schedule(ctx) }
    BackHandler(enabled = route != Route.Main || tab != Tab.TODAY) {
        route = when (route) {
            Route.Compare -> Route.Settings
            is Route.Voice -> Route.People
            Route.Main -> { tab = Tab.TODAY; Route.Main }
            else -> Route.Main
        }
    }

    LifeTheme {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            Column(Modifier.safeDrawingPadding()) {
                AnimatedContent(
                    targetState = route,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    modifier = Modifier.weight(1f),
                    label = "route"
                ) { r ->
                    when (r) {
                        Route.Setup -> SetupRoute(tick) {
                            prefs.onboardingDone = true
                            route = Route.Main
                        }
                        is Route.Day -> DayRoute(r.day, tick, onBack = { route = Route.Main }, onPerson = openPerson)
                        Route.Compare -> CompareRoute { route = Route.Settings }
                        Route.People -> PeopleRoute(tick, onBack = { route = Route.Main }, onPerson = openPerson, onVoice = { route = Route.Voice(it) })
                        is Route.Person -> PersonRoute(r.name, tick, onBack = { route = Route.People }, onSearch = searchFor)
                        is Route.Voice -> VoiceRoute(r.id, onBack = { route = Route.People })
                        Route.Settings -> SettingsRoute(tick, onSetup = { route = Route.Setup }, onCompare = { route = Route.Compare }, onBack = { route = Route.Main })
                        Route.Main -> when (tab) {
                            Tab.TODAY -> TodayRoute(tick, onSetup = { route = Route.Setup }, onDay = { route = Route.Day(it) }, onSettings = { route = Route.Settings }, onPerson = openPerson)
                            Tab.DIARY -> DaysRoute(tick, onDay = { route = Route.Day(it) })
                            Tab.TODO -> TodoRoute(tick, onSetup = { route = Route.Settings })
                            Tab.SEARCH -> SearchRoute(searchQuery, onQuery = { searchQuery = it }, onDay = { route = Route.Day(it) }, onPerson = openPerson, onPeople = { route = Route.People })
                        }
                    }
                }
                if (route == Route.Main) BottomBar(tab) { tab = it }
            }
        }
    }
}

// =============================================================== Dati → interfaccia

private val zone get() = ZoneId.systemDefault()
private val ITA = Locale.ITALIAN

private fun minuteOfDay(ms: Long): Float {
    val t = Instant.ofEpochMilli(ms).atZone(zone).toLocalTime()
    return t.hour * 60f + t.minute + t.second / 60f
}

private fun spansOf(segs: List<Segment>, gaps: List<Gap>): List<Span> =
    segs.map {
        Span(minuteOfDay(it.startMs), minuteOfDay(it.endMs).coerceAtLeast(minuteOfDay(it.startMs)),
            if (it.text == null) Span.Kind.PENDING else Span.Kind.TRANSCRIBED)
    } + gaps.map {
        val from = minuteOfDay(it.startMs)
        val sameDay = Db.dayOf(it.startMs) == Db.dayOf(it.endMs)
        Span(from, if (sameDay) minuteOfDay(it.endMs) else 1440f, Span.Kind.GAP)
    }

private fun wordsOf(segs: List<Segment>) = segs.sumOf { s ->
    s.text?.trim()?.takeIf { it.isNotEmpty() }?.split(Regex("\\s+"))?.size ?: 0
}

private fun dayTitle(day: String): String {
    val d = LocalDate.parse(day)
    val today = LocalDate.now()
    return when (d) {
        today -> "Oggi"
        today.minusDays(1) -> "Ieri"
        else -> d.format(DateTimeFormatter.ofPattern("EEEE d", ITA)).replaceFirstChar { c -> c.uppercase() }
    }
}

private fun daySubtitle(day: String): String =
    LocalDate.parse(day).format(DateTimeFormatter.ofPattern("d MMMM yyyy", ITA))

private fun momentsOf(segs: List<Segment>, gaps: List<Gap>, voices: Map<String, Voice> = emptyMap()): List<Pair<Long, Moment>> =
    Exporter.paragraphs(segs, byVoice = voices.isNotEmpty()).map { p ->
        val who = if (voices.isEmpty()) "" else Speakers.label(p.voice, voices).takeIf { w -> w != "?" }?.let { w -> ", $w" }.orEmpty()
        p.startMs to Moment("${Notifications.hhmm(p.startMs)}–${Notifications.hhmm(p.endMs)}$who", p.text)
    } + gaps.map {
        it.startMs to Moment(
            "${Notifications.hhmm(it.startMs)}–${Notifications.hhmm(it.endMs)}",
            "non registrato (${(it.endMs - it.startMs) / 60_000} min): ${gapReason(it.reason)}.",
            gap = true
        )
    }

private fun gapReason(r: String) = when (r) {
    "chiamata" -> "eri al telefono"
    "app aggiornata" -> "l'app si stava aggiornando"
    "telefono riavviato" -> "il telefono si è riavviato"
    else -> "Android ha chiuso l'app"
}

// =============================================================== Oggi

/** Divide i paragrafi in frasi, per mostrarle una per riga come un testo che scorre. */
private fun sentences(text: String): List<String> =
    text.split(Regex("(?<=[.!?…])\\s+")).map { it.trim() }.filter { it.length > 1 }

@Composable
private fun TodayRoute(tick: Int, onSetup: () -> Unit, onDay: (String) -> Unit, onSettings: () -> Unit, onPerson: (String) -> Unit = {}) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    val db = remember { Db.get(ctx) }
    val level by Live.level.collectAsState()
    val speaking by Live.speaking.collectAsState()
    var crash by remember { mutableStateOf(CrashLog.read(ctx)) }
    var base by remember { mutableStateOf<TodayUi?>(null) }

    // La parte pesante (le frasi di oggi) si ricalcola solo quando il database cambia.
    val dbVersion = remember(tick) { db.version }
    var heavy by remember { mutableStateOf<Triple<List<String>, String?, Long>?>(null) }
    LaunchedEffect(dbVersion, Db.today()) {
        heavy = withContext(Dispatchers.IO) {
            val segs = db.segmentsOfDay(Db.today())
            val paras = Exporter.paragraphs(segs)
            Triple(
                paras.flatMap { sentences(it.text) }.takeLast(5),
                paras.lastOrNull()?.let { Notifications.hhmm(it.endMs) },
                segs.sumOf { it.endMs - it.startMs } / 60_000,
            )
        }
    }
    // Promemoria persone: si ricalcolano quando cambia il database o ogni minuto circa (analisi appena scaricata).
    var peopleRows by remember { mutableStateOf(emptyList<PeopleTodayRow>()) }
    LaunchedEffect(tick / 30) { peopleRows = withContext(Dispatchers.IO) { peopleToday(ctx) } }
    LaunchedEffect(tick, heavy) {
        val h = heavy ?: return@LaunchedEffect
        base = withContext(Dispatchers.IO) {
            val wanted = prefs.recordingWanted
            val stalled = Watchdog.isStalled(ctx)
            val alive = RecorderService.isAlive(ctx)
            val pending = db.pendingCount()
            val brief = briefOf(ctx)
            TodayUi(
                brief = brief,
                dateLabel = LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE d MMMM", ITA)).replaceFirstChar { it.uppercase() },
                ready = Models.vadReady(ctx) && hasMic(ctx),
                state = when {
                    stalled -> RecState.STALLED
                    wanted && alive -> RecState.LISTENING
                    else -> RecState.OFF
                },
                stalledSince = if (stalled) Notifications.hhmm(prefs.lastHeartbeat) else null,
                level = 0f, speaking = false,
                speechMinutes = h.third,
                pending = pending,
                pendingMinutes = db.pendingSeconds() / 60,
                transcribeStatus = prefs.transcribeStatus,
                canTranscribeNow = pending > 0 && Models.parakeetReady(ctx),
                lines = h.first,
                lastTime = h.second,
                crash = null,
            )
        }
    }
    // Briefing (BriefingRoutes.kt): calendario e file di Claude, ricalcolati ogni minuto o dopo una scelta.
    var briefingBump by remember { mutableIntStateOf(0) }
    var briefing by remember { mutableStateOf<BriefingUi?>(null) }
    LaunchedEffect(briefingBump, tick / 30) { briefing = withContext(Dispatchers.IO) { runCatching { briefingOf(ctx) }.getOrNull() } }
    val briefingActions = rememberBriefingActions(onPerson) { briefingBump++ }
    val s = base ?: return
    TodayScreen(
        s.copy(level = level, speaking = speaking, crash = crash, briefing = briefing, people = peopleRows),
        briefingActions = briefingActions,
        onToggle = {
            try {
                if (s.state == RecState.LISTENING) RecorderService.stop(ctx) else RecorderService.start(ctx)
            } catch (e: Exception) {
                Toast.makeText(ctx, "Non riesco ad avviare: ${e.message}", Toast.LENGTH_LONG).show()
            }
        },
        onSetup = onSetup,
        onOpenToday = { onDay(Db.today()) },
        onTranscribeNow = {
            TranscribeWorker.runNow(ctx)
            Toast.makeText(ctx, "Trascrizione avviata. Si ferma se la batteria si scalda.", Toast.LENGTH_SHORT).show()
        },
        onShareCrash = { shareText(ctx, crash.orEmpty(), "Condividi l'errore") },
        onDismissCrash = { CrashLog.clear(ctx); crash = null },
        onSettings = onSettings,
        onToggleTodo = { id, done ->
            Assistant.setDone(ctx, id, done)
            base = base?.let { b -> b.copy(brief = b.brief?.let { br -> br.copy(todos = br.todos.map { if (it.id == id) it.copy(done = done) else it }) }) }
        },
        onOpenYesterday = { onDay(Assistant.yesterday()) },
        onPerson = onPerson,
    )
}

/** Riassunto di ieri per la cima di Oggi: frase di Claude e i primi impegni aperti. */
private fun briefOf(ctx: Context): Brief? {
    if (!Assistant.isConfigured(ctx)) return null
    val a = Assistant.analysis(ctx) ?: return null
    if (a.vuoto || a.giornata.isBlank()) return null
    val y = Assistant.yesterday()
    val open = Assistant.commitments(ctx).filter { it.chi != "altro" && it.stato != "fatto" }
        .sortedWith(compareBy({ it.fonteGiorno != y }, { it.fonteGiorno }))
    return Brief(a.giornata, open.take(3).map { TodoItem(it.id, it.testo, "", done = false, person = it.persona) }, more = (open.size - 3).coerceAtLeast(0))
}

private fun shareText(ctx: Context, text: String, title: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    ctx.startActivity(Intent.createChooser(send, title))
}

private fun durationLabel(ms: Long): String {
    val m = ms / 60_000
    return if (m < 60) "$m min" else "${m / 60} h ${m % 60} min"
}

// =============================================================== Giorni

@Composable
private fun DaysRoute(tick: Int, onDay: (String) -> Unit) {
    val ctx = LocalContext.current
    val db = remember { Db.get(ctx) }
    var days by remember { mutableStateOf(emptyList<DayRow>()) }
    val dbVersion = remember(tick) { db.version }
    LaunchedEffect(dbVersion) {
        days = withContext(Dispatchers.IO) {
            val cms = Assistant.commitments(ctx)
            db.days().take(120).map { d ->
                val segs = db.segmentsOfDay(d.day)
                val a = Assistant.analysis(ctx, d.day)
                val decisions = a?.sezioni?.sumOf { s -> s.voci.count { it.tipo == "decisione" } } ?: 0
                val todos = cms.count { it.fonteGiorno == d.day }
                DayRow(
                    day = d.day, title = dayTitle(d.day),
                    meta = durationLabel(d.speechMs),
                    spans = spansOf(segs, db.gapsOfDay(d.day)),
                    summary = when {
                        a != null && !a.vuoto && a.giornata.isNotBlank() -> a.giornata
                        d.day == Db.today() -> "In corso." + (Exporter.paragraphs(segs).lastOrNull()?.let { " Ultima frase alle ${Notifications.hhmm(it.endMs)}." } ?: "")
                        else -> Exporter.paragraphs(segs).firstOrNull()?.text?.let { sentences(it).firstOrNull() ?: it }?.take(140).orEmpty()
                    },
                    badge = listOfNotNull(
                        decisions.takeIf { it > 0 }?.let { if (it == 1) "1 decisione" else "$it decisioni" },
                        todos.takeIf { it > 0 }?.let { if (it == 1) "1 impegno" else "$it impegni" },
                    ).joinToString(", "),
                )
            }
        }
    }
    DaysScreen(days, onDay)
}

@Composable
private fun SearchRoute(query: String, onQuery: (String) -> Unit, onDay: (String) -> Unit, onPerson: (String) -> Unit = onQuery, onPeople: () -> Unit = {}) {
    val ctx = LocalContext.current
    val db = remember { Db.get(ctx) }
    var hits by remember { mutableStateOf(emptyList<SearchHit>()) }
    var people by remember { mutableStateOf(emptyList<String>()) }
    LaunchedEffect(Unit) { people = withContext(Dispatchers.IO) { peopleOf(ctx) } }
    LaunchedEffect(query) {
        val q = query.trim()
        if (q.length >= 2) delay(250)
        hits = if (q.length < 2) emptyList() else withContext(Dispatchers.IO) {
            db.search(q).map {
                val day = Db.dayOf(it.startMs)
                SearchHit(day, "${dayTitle(day)}, ${Notifications.hhmm(it.startMs)}", it.text.orEmpty())
            }
        }
    }
    SearchScreen(query, hits, onQuery = onQuery, onDay = onDay, people = people, onPerson = onPerson, onPeople = onPeople)
}

/** Persone nominate negli impegni, dalle più frequenti. */
private fun peopleOf(ctx: Context): List<String> =
    Assistant.commitments(ctx).map { it.persona.trim() }.filter { it.isNotBlank() }
        .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key }.take(20)

// =============================================================== Ieri e Impegni

/** Racconto di Claude per un giorno qualsiasi, o null se non c'è. */
private fun storyOf(ctx: Context, day: String): YesterdayUi? {
    val a = Assistant.analysis(ctx, day) ?: return null
    if (a.vuoto) return null
    return yesterdayUi(ctx, day)
}

private fun yesterdayUi(ctx: Context, day: String = Assistant.yesterday()): YesterdayUi {
    return run {
        val label = LocalDate.parse(day).format(DateTimeFormatter.ofPattern("EEEE d MMMM", ITA)).replaceFirstChar { it.uppercase() }
        val a = Assistant.analysis(ctx, day)
        when {
            !Assistant.isConfigured(ctx) -> YesterdayUi(label, AnState.NOT_CONFIGURED, "")
            a == null -> YesterdayUi(label, AnState.MISSING, ctx.fetchStatus)
            a.vuoto -> YesterdayUi(label, AnState.EMPTY, "")
            else -> YesterdayUi(
                dateLabel = label, state = AnState.READY,
                note = "${a.parlatoMin} minuti di parlato" + generatedAt(a.generato),
                giornata = a.giornata,
                sections = a.sezioni.map { sec -> sec.titolo to sec.voci.map { Entry(it.ora, it.testo, kindLabel(it.tipo)) } },
                tomorrow = a.domani,
                verify = a.daVerificare.map { Entry(it.ora, it.testo) },
            )
        }
    }
}

private fun kindLabel(t: String) = when (t.lowercase()) {
    "decisione" -> "Decisione"
    "impegno" -> "Impegno"
    "idea" -> "Idea"
    "domanda" -> "Domanda aperta"
    "fatto" -> "Fatto"
    "scadenza" -> "Scadenza"
    else -> ""
}

private fun generatedAt(iso: String) = try {
    ", analisi delle " + java.time.OffsetDateTime.parse(iso).atZoneSameInstant(zone).format(DateTimeFormatter.ofPattern("HH:mm"))
} catch (_: Exception) { "" }

@Composable
private fun TodoRoute(tick: Int, onSetup: () -> Unit) {
    val ctx = LocalContext.current
    var version by remember { mutableIntStateOf(0) }
    val ui = remember(tick / 5, version) {
        val today = LocalDate.now(zone)
        val list = Assistant.commitments(ctx)
        fun item(cm: Assistant.Commitment): TodoItem {
            val src = runCatching { LocalDate.parse(cm.fonteGiorno) }.getOrNull()
            val ageDays = src?.let { java.time.temporal.ChronoUnit.DAYS.between(it, today) } ?: 0
            val meta = buildList {
                if (cm.persona.isNotBlank()) add(cm.persona)
                if (src != null) add("detto ${dayTitle(cm.fonteGiorno).lowercase(ITA)}" + (if (cm.fonteOra.isNotBlank()) " alle ${cm.fonteOra}" else ""))
                cm.scadenza?.let { add("entro ${runCatching { dayTitle(it).lowercase(ITA) }.getOrDefault(it)}") }
            }.joinToString(", ")
            val age = when {
                cm.stato == "fermo" -> "Fermo da più di due settimane"
                ageDays > 7 -> "Aperto da $ageDays giorni"
                else -> ""
            }
            return TodoItem(cm.id, cm.testo, meta, done = cm.stato == "fatto", age = age, person = cm.persona.trim())
        }
        val sorted = list.sortedWith(compareBy<Assistant.Commitment>({ it.stato == "fatto" }, { it.fonteGiorno }))
        TodoUi(
            configured = Assistant.isConfigured(ctx),
            mine = sorted.filter { it.chi != "altro" }.map(::item),
            theirs = sorted.filter { it.chi == "altro" }.map(::item),
            note = if (list.isEmpty() && Assistant.isConfigured(ctx)) "Ancora nessun impegno: arrivano dopo la prima analisi notturna." else "",
            people = list.filter { it.stato != "fatto" }.map { it.persona.trim() }.filter { it.isNotBlank() }.distinct(),
        )
    }
    TodoScreen(ui, onToggle = { id, done -> Assistant.setDone(ctx, id, done); version++ }, onSetup = onSetup)
}

// =============================================================== Giorno

private fun partOfDay(ms: Long): String {
    val h = Instant.ofEpochMilli(ms).atZone(zone).hour
    return when (h) {
        in 5..11 -> "Mattina"
        in 12..17 -> "Pomeriggio"
        in 18..22 -> "Sera"
        else -> "Notte"
    }
}

@Composable
private fun DayRoute(day: String, tick: Int, onBack: () -> Unit, onPerson: (String) -> Unit) {
    val ctx = LocalContext.current
    val db = remember { Db.get(ctx) }
    var ui by remember { mutableStateOf<DayUi?>(null) }
    var plain by remember { mutableStateOf("") }
    val dbVersion = remember(tick) { db.version }
    LaunchedEffect(day, dbVersion, tick / 15) {
        withContext(Dispatchers.IO) {
            val segs = db.segmentsOfDay(day)
            val gaps = db.gapsOfDay(day)
            val words = wordsOf(segs)
            val all = momentsOf(segs, gaps, Speakers.voiceMap(ctx)).sortedBy { it.first }
            val sections = all.groupBy { partOfDay(it.first) }.map { (k, v) -> k to v.map { it.second } }
            ui = DayUi(
                title = dayTitle(day),
                subtitle = "${daySubtitle(day)}. ${durationLabel(segs.sumOf { it.endMs - it.startMs })} di voce, ${"%,d".format(ITA, words)} parole",
                spans = spansOf(segs, gaps),
                sections = sections,
                pending = segs.count { it.text == null },
                story = storyOf(ctx, day),
                people = Assistant.commitments(ctx).filter { it.fonteGiorno == day }.map { it.persona.trim() }.filter { it.isNotBlank() }.distinct(),
                todos = Assistant.commitments(ctx).filter { it.fonteGiorno == day }.map { TodoItem(it.id, it.testo, "", done = it.stato == "fatto", person = it.persona) },
            )
            plain = "Lifelog, ${daySubtitle(day)}\n\n" + all.filter { !it.second.gap }.joinToString("\n\n") { "${it.second.time}\n${it.second.text}" }
        }
    }
    ui?.let { u ->
        DayScreen(
            u, onBack, onShare = { shareText(ctx, plain, "Condividi la giornata") }, onPerson = onPerson,
            onToggleTodo = { id, done ->
                Assistant.setDone(ctx, id, done)
                ui = u.copy(todos = u.todos.map { if (it.id == id) it.copy(done = done) else it })
            },
        )
    }
}

// =============================================================== Impostazioni

@Composable
private fun SettingsRoute(tick: Int, onSetup: () -> Unit, onCompare: () -> Unit, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    val scope = rememberCoroutineScope()
    var version by remember { mutableIntStateOf(0) } // forza la rilettura dopo un cambio
    val whisperWork by WorkManager.getInstance(ctx)
        .getWorkInfosForUniqueWorkFlow(ModelSetupWorker.NAME_WHISPER)
        .collectAsState(initial = emptyList())
    val wInfo = whisperWork.firstOrNull()
    var dataMsg by remember { mutableStateOf("") }
    val ui = remember(tick, version, wInfo?.state, wInfo?.progress, dataMsg) {
        SettingsUi(
            onlyCharging = prefs.onlyWhileCharging,
            waitCharge = prefs.minChargePercent > 0,
            maxTemp = prefs.maxBatteryTemp,
            battery = Throttle.battery(ctx).let { b ->
                "Ora: batteria al %d%%, %.1f °C, %s.".format(b.percent, b.tempC, if (b.plugged) "in carica" else "non in carica")
            },
            driveConfigured = DriveSync.isConfigured(ctx),
            driveStatus = prefs.driveStatus,
            version = try {
                ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: ""
            } catch (_: Exception) { "" },
            dataConfigured = Assistant.isConfigured(ctx),
            dataRepo = Assistant.repo(ctx),
            dataStatus = listOf(ctx.uploadStatus, ctx.fetchStatus).filter { it.isNotBlank() }.joinToString("\n").ifEmpty { dataMsg },
            engines = Engine.entries.map { e ->
                val ready = AsrFactory.ready(ctx, e)
                val state = when {
                    e == Engine.WHISPER && !ready -> when (wInfo?.state) {
                        WorkInfo.State.RUNNING -> wInfo.progress.getString(ModelSetupWorker.KEY_STAGE) ?: "Download in corso…"
                        WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> "In attesa di connessione. Tocca per scaricarlo subito con i dati mobili."
                        WorkInfo.State.FAILED -> "Download non riuscito. Tocca per riprovare."
                        else -> "Tocca per scaricarlo (564 MB, va bene anche con i dati mobili)"
                    }
                    e == Engine.AUTO && ready && !Models.whisperReady(ctx) -> "Per ora usa solo Parakeet. Tocca Whisper qui sotto per scaricarlo: poi lo uso da solo per le voci lontane."
                    e == Engine.AUTO && ready -> "Pronto"
                    e == Engine.GOOGLE && !ready -> "Serve Android 13 o più recente"
                    e == Engine.GOOGLE -> "Se manca il pacchetto italiano offline, lo scarica Google da solo al primo uso."
                    !ready -> "Non ancora scaricato"
                    else -> "Pronto"
                }
                EngineRow(e.name, e.label, e.detail, state, selected = prefs.engine == e, ready = ready)
            },
        )
    }
    val pickDrive = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
        if (uri != null) {
            DriveSync.setTarget(ctx, uri)
            version++
            Toast.makeText(ctx, "Collegato. Primo salvataggio in corso…", Toast.LENGTH_SHORT).show()
        }
    }
    val exportMd = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
        if (uri != null) scope.launch(Dispatchers.IO) { export(ctx, uri, md = true) }
    }
    val exportJson = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch(Dispatchers.IO) { export(ctx, uri, md = false) }
    }
    SettingsScreen(
        ui,
        onOnlyCharging = { prefs.onlyWhileCharging = it; version++ },
        onWaitCharge = { prefs.minChargePercent = if (it) 80 else 0; version++ },
        onConnectDrive = { pickDrive.launch("Lifelog.md") },
        onSyncDrive = {
            scope.launch(Dispatchers.IO) {
                val msg = DriveSync.syncBlocking(ctx)
                withContext(Dispatchers.Main) {
                    version++
                    Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
                }
            }
        },
        onDisconnectDrive = { DriveSync.disconnect(ctx); version++ },
        onExportMd = { exportMd.launch("lifelog-${LocalDate.now()}.md") },
        onExportJson = { exportJson.launch("lifelog-${LocalDate.now()}.json") },
        onSetup = onSetup,
        onEngine = { key ->
            val e = Engine.valueOf(key)
            if (e == Engine.WHISPER && !Models.whisperReady(ctx)) {
                // Scarico e basta: in Automatico verrà usato da solo per le voci lontane.
                if (wInfo?.state != WorkInfo.State.RUNNING) ModelSetupWorker.startWhisper(ctx, wifiOnly = false)
                Toast.makeText(ctx, "Scarico Whisper (564 MB, anche con i dati mobili). In Automatico lo uso per le voci lontane.", Toast.LENGTH_LONG).show()
            } else if (e == Engine.GOOGLE && !AsrFactory.ready(ctx, e)) {
                Toast.makeText(ctx, "Google sul telefono richiede Android 13", Toast.LENGTH_LONG).show()
            } else {
                prefs.engine = e
            }
            version++
        },
        onCompare = onCompare,
        onDataSave = { token, repo ->
            dataMsg = "Provo la connessione…"
            scope.launch(Dispatchers.IO) {
                val msg = try {
                    val probe = GitHub(token.trim(), repo.trim().ifEmpty { Assistant.DEFAULT_REPO })
                    probe.check().also {
                        Assistant.configure(ctx, token, repo)
                        Assistant.uploadSoon(ctx)
                        Assistant.fetchSoon(ctx)
                    } + ". Invio i giorni in corso."
                } catch (e: Exception) { "Non funziona: ${e.message}" }
                withContext(Dispatchers.Main) { dataMsg = msg; version++ }
            }
        },
        onDataSend = {
            Assistant.uploadSoon(ctx)
            Toast.makeText(ctx, "Invio in corso…", Toast.LENGTH_SHORT).show()
        },
        onDataTest = {
            scope.launch(Dispatchers.IO) {
                val msg = try { Assistant.check(ctx) } catch (e: Exception) { "Non funziona: ${e.message}" }
                withContext(Dispatchers.Main) { Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show() }
            }
        },
        onDataDisconnect = { Assistant.disconnect(ctx); dataMsg = ""; version++ },
        onBack = onBack,
    )
}

// =============================================================== Confronta motori

@Composable
private fun CompareRoute(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var ui by remember { mutableStateOf(CompareUi(running = false, status = "", rows = emptyList())) }
    BackHandler(onBack = onBack)
    val fmt = remember { DateTimeFormatter.ofPattern("EEEE d, HH:mm", ITA) }
    CompareScreen(ui, onBack = onBack, onRun = {
        scope.launch(Dispatchers.Default) {
            val clips = Recent.list(ctx, 4)
            if (clips.isEmpty()) {
                ui = CompareUi(false, "Non ci sono ancora frasi trascritte da confrontare. Registra un po' e riprova.", emptyList())
                return@launch
            }
            val audio = clips.map { (start, f) -> start to Pcm.read(f) }
            val results = audio.map { mutableListOf<Pair<String, String>>() }
            val engines = Engine.entries.filter { it != Engine.AUTO && AsrFactory.ready(ctx, it) }
            for (e in engines) {
                ui = ui.copy(running = true, status = "Trascrivo con ${e.label}…")
                val asr = try { AsrFactory.create(ctx, e, threads = 4) } catch (ex: Throwable) {
                    results.forEach { it += e.label to "Errore: ${ex.message}" }; continue
                }
                try {
                    audio.forEachIndexed { i, (_, a) ->
                        val t0 = System.currentTimeMillis()
                        val text = try { asr.transcribe(a) } catch (ex: Throwable) { "Errore: ${ex.message}" }
                        val secs = (System.currentTimeMillis() - t0) / 1000.0
                        results[i] += "${e.label}, %.1f s".format(ITA, secs) to text
                        ui = ui.copy(rows = audio.mapIndexed { j, (st, _) ->
                            CompareRow(Instant.ofEpochMilli(st).atZone(zone).format(fmt).replaceFirstChar { it.uppercase() }, results[j].toList())
                        })
                    }
                } finally {
                    asr.release()
                }
            }
            val missing = Engine.entries.filter { it != Engine.AUTO && it !in engines }.joinToString { it.label }
            ui = ui.copy(running = false, status = if (missing.isEmpty()) "Fatto." else "Fatto. Non pronti: $missing.")
        }
    })
}

private suspend fun export(ctx: Context, uri: Uri, md: Boolean) {
    val msg = try {
        val db = Db.get(ctx)
        Exporter.write(ctx, uri, if (md) Exporter.markdown(db) else Exporter.json(db))
        "Esportato"
    } catch (e: Exception) {
        "Errore: ${e.message}"
    }
    withContext(Dispatchers.Main) { Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show() }
}

// =============================================================== Configurazione

private fun hasMic(ctx: Context) =
    ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

private fun hasNotif(ctx: Context) =
    ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

private fun ignoringBattery(ctx: Context) =
    ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)

private fun tryStart(ctx: Context, vararg intents: Intent): Boolean {
    for (i in intents) {
        try {
            ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return true
        } catch (_: ActivityNotFoundException) {
        } catch (_: SecurityException) {
        }
    }
    return false
}

private fun openSamsungBattery(ctx: Context) {
    val ok = tryStart(
        ctx,
        Intent().setComponent(ComponentName("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity")),
        Intent().setComponent(ComponentName("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity")),
        Intent(Intent.ACTION_POWER_USAGE_SUMMARY),
        Intent(Settings.ACTION_SETTINGS),
    )
    if (!ok) Toast.makeText(ctx, "Apri Impostazioni → Batteria a mano", Toast.LENGTH_LONG).show()
}

@Composable
private fun SetupRoute(tick: Int, onDone: () -> Unit) {
    val ctx = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh++ }
    val work by WorkManager.getInstance(ctx)
        .getWorkInfosForUniqueWorkFlow(ModelSetupWorker.NAME)
        .collectAsState(initial = emptyList())
    val info = work.firstOrNull()
    val ready = remember(tick, info?.state) { Models.allReady(ctx) }
    val ui = SetupUi(
        mic = remember(tick, refresh) { hasMic(ctx) },
        notif = remember(tick, refresh) { hasNotif(ctx) },
        battery = remember(tick, refresh) { ignoringBattery(ctx) },
        download = when {
            ready -> DownloadState.DONE
            info?.state == WorkInfo.State.RUNNING -> DownloadState.RUNNING
            info?.state == WorkInfo.State.ENQUEUED || info?.state == WorkInfo.State.BLOCKED -> DownloadState.WAITING_WIFI
            info?.state == WorkInfo.State.FAILED -> DownloadState.FAILED
            else -> DownloadState.IDLE
        },
        downloadPct = info?.progress?.getInt(ModelSetupWorker.KEY_PCT, 0) ?: 0,
        downloadStage = info?.progress?.getString(ModelSetupWorker.KEY_STAGE) ?: "Avvio…",
        downloadError = info?.outputData?.getString(ModelSetupWorker.KEY_ERROR) ?: "",
    )
    SetupScreen(
        ui,
        onPermissions = { permLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)) },
        onDownload = { ModelSetupWorker.start(ctx, wifiOnly = true) },
        onDownloadMobile = { ModelSetupWorker.start(ctx, wifiOnly = false) },
        onBattery = {
            tryStart(
                ctx,
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")),
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            )
        },
        onAppInfo = { tryStart(ctx, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))) },
        onSamsungBattery = { openSamsungBattery(ctx) },
        onDone = onDone,
    )
}
