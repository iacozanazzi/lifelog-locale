package it.iaco.lifelog.ui

import android.Manifest
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.health.connect.client.PermissionController
import it.iaco.lifelog.data.Assistant
import it.iaco.lifelog.memory.Health
import it.iaco.lifelog.memory.Memory
import it.iaco.lifelog.memory.Places
import it.iaco.lifelog.memory.Places.placesOn
import it.iaco.lifelog.memory.Questions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val ITA_M = Locale.ITALIAN
private val memZone get() = ZoneId.systemDefault()

private fun areaLabel(a: String) = when (a) {
    "seral" -> "Seral"
    "uni" -> "Università"
    "persone" -> "Persone"
    else -> a.replaceFirstChar { it.uppercase() }
}

private fun hm(m: Int) = when {
    m < 60 -> "$m min"
    m % 60 == 0 -> "${m / 60} h"
    else -> "${m / 60} h ${"%02d".format(m % 60)}"
}

private fun steps(n: Long) = "%,d".format(ITA_M, n)

private fun dayLabel(d: LocalDate) = d.format(DateTimeFormatter.ofPattern("EEEE d", ITA_M)).replaceFirstChar { it.uppercase() }

private fun healthLine(h: Health.DayHealth?): String? {
    h ?: return null
    val parts = listOfNotNull(
        h.sleepMin?.let { m -> "Dormito ${hm(m)}" + if (h.bed != null && h.wake != null) ", dalle ${h.bed} alle ${h.wake}." else "." },
        h.steps?.takeIf { it > 0 }?.let { "${steps(it)} passi." },
    )
    return parts.joinToString(" ").ifBlank { null }
}

// =============================================================== Oggi

private const val HEALTH_EVERY_MS = 30 * 60_000L
private var lastHealthRefresh = 0L

/** Dati per le schede di Oggi. Da chiamare fuori dal thread principale. */
suspend fun memoryTodayUi(ctx: Context): MemoryTodayUi {
    if (System.currentTimeMillis() - lastHealthRefresh > HEALTH_EVERY_MS) {
        lastHealthRefresh = System.currentTimeMillis()
        runCatching { Health.refresh(ctx) }
    }
    val m = Memory.memento(ctx)
    return MemoryTodayUi(
        mementoLabel = m?.label, mementoText = m?.text, mementoDay = m?.day,
        weekText = weekSentence(ctx),
    )
}

private fun weekSentence(ctx: Context): String {
    val w = Memory.week(ctx)
    val parts = mutableListOf<String>()
    val areas = w.areas.filter { it.minutes > 0 }.sortedByDescending { it.minutes }
    if (areas.isNotEmpty()) parts += areas.joinToString(", ") { "${areaLabel(it.area)} ${hm(it.minutes)}" }
        .replaceFirstChar { it.uppercase() } + "."
    val mon = Memory.monday(LocalDate.now(memZone))
    Memory.habits(ctx).filter { it.stato == "attiva" }.take(2).forEach { h ->
        val n = h.prove.count { it.esito == "fatto" && it.day >= mon.toString() }
        parts += "${h.testo}: " + (h.obiettivo?.let { "$n su $it." } ?: if (n == 1) "una volta." else "$n volte.")
    }
    if (parts.size < 2) {
        val days = (1..7).mapNotNull { Health.day(ctx, LocalDate.now(memZone).minusDays(it.toLong()).toString()) }
        val sleep = days.mapNotNull { it.sleepMin }
        if (sleep.isNotEmpty()) parts += "In media hai dormito ${hm(sleep.average().toInt())} a notte."
    }
    return parts.take(3).joinToString(" ")
}

// =============================================================== Settimana

@Composable
fun WeekRoute(tick: Int, onBack: () -> Unit, onQuestions: () -> Unit) {
    BackHandler(onBack = onBack)
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var version by remember { mutableIntStateOf(0) }
    var ui by remember { mutableStateOf<WeekUi?>(null) }

    val healthLauncher = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) {
        scope.launch(Dispatchers.IO) { runCatching { Health.refresh(ctx) }; withContext(Dispatchers.Main) { version++ } }
    }
    val alwaysLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { version++ }
    val locLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (Places.hasForeground(ctx)) {
            Places.enable(ctx)
            if (!Places.hasBackground(ctx)) alwaysLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        } else Toast.makeText(ctx, "Senza il permesso della posizione i luoghi non funzionano.", Toast.LENGTH_LONG).show()
        version++
    }

    LaunchedEffect(tick / 15, version) {
        ui = withContext(Dispatchers.IO) {
            if (version == 0) runCatching { Health.refresh(ctx) }
            weekUi(ctx)
        }
    }
    val s = ui ?: return
    WeekScreen(
        s, onBack = onBack,
        onHabitDay = { id, day, done -> Memory.mark(ctx, id, day, done); version++ },
        onConnectHealth = {
            when {
                Health.needsUpdate(ctx) -> runCatching { ctx.startActivity(Health.storeIntent()) }
                Health.available(ctx) -> healthLauncher.launch(Health.PERMISSIONS)
            }
        },
        onPlaces = {
            if (!Places.hasForeground(ctx)) {
                locLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            } else {
                Places.enable(ctx)
                if (!Places.hasBackground(ctx)) alwaysLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                version++
            }
        },
        onPlacesOff = { Places.disable(ctx); Places.wipe(ctx); version++ },
        onQuestions = onQuestions,
    )
}

private suspend fun weekUi(ctx: Context): WeekUi {
    val today = LocalDate.now(memZone)
    val w = Memory.week(ctx, today)
    val mon = w.from
    val letters = listOf("L", "M", "M", "G", "V", "S", "D")
    val habits = Memory.habits(ctx).filter { it.stato != "chiusa" }.map { h ->
        val byDay = h.prove.associateBy { it.day }
        val days = (0 until 14).map { i ->
            val d = mon.minusWeeks(1).plusDays(i.toLong())
            val p = byDay[d.toString()]
            HabitDay(d.toString(), letters[i % 7], when (p?.esito) { "fatto" -> 1; "saltato" -> 2; else -> 0 }, future = d.isAfter(today))
        }
        val n = h.prove.count { it.esito == "fatto" && it.day >= mon.toString() }
        val last = h.prove.lastOrNull { it.citazione.isNotBlank() && it.citazione != "segnato a mano" }
        HabitRow(
            id = h.id, title = h.testo,
            progress = when {
                h.stato == "sospesa" -> "Sospesa"
                h.obiettivo != null -> "$n su ${h.obiettivo} questa settimana"
                else -> if (n == 1) "Una volta questa settimana" else "$n volte questa settimana"
            },
            said = listOfNotNull(
                runCatching { "Detto " + LocalDate.parse(h.fonteGiorno).format(DateTimeFormatter.ofPattern("d MMMM", ITA_M)) + (if (h.fonteOra.isNotBlank()) " alle ${h.fonteOra}" else "") }.getOrNull(),
                h.detto.takeIf { it.isNotBlank() }?.let { "\"$it\"" },
            ).joinToString(": "),
            days = days,
            lastProof = last?.let { p ->
                "Ultima prova: " + runCatching { dayLabel(LocalDate.parse(p.day)).lowercase(ITA_M) }.getOrDefault(p.day) +
                    (if (p.ora.isNotBlank()) " alle ${p.ora}" else "") + ", \"${p.citazione}\"."
            }.orEmpty(),
            paused = h.stato == "sospesa",
        )
    }

    val hState = when {
        Health.needsUpdate(ctx) -> HealthState.NEEDS_UPDATE
        !Health.available(ctx) -> HealthState.UNAVAILABLE
        Health.granted(ctx) -> HealthState.CONNECTED
        else -> HealthState.NOT_CONNECTED
    }
    val healthDays = if (hState != HealthState.CONNECTED) emptyList() else (0 until 7).mapNotNull { i ->
        val d = today.minusDays(i.toLong())
        val h = Health.day(ctx, d.toString()) ?: return@mapNotNull null
        HealthDayRow(
            d.toString(), if (i == 0) "Oggi" else if (i == 1) "Ieri" else dayLabel(d),
            sleep = h.sleepMin?.let { "${hm(it)} di sonno" } ?: "sonno senza dati",
            steps = h.steps?.let { "${steps(it)} passi" } ?: "passi senza dati",
        )
    }

    val places = when {
        !ctx.placesOn -> PlacesState.OFF
        !Places.hasForeground(ctx) || !Places.hasBackground(ctx) -> PlacesState.NEEDS_ALWAYS
        else -> PlacesState.ON
    }

    return WeekUi(
        title = "Da lunedì " + mon.format(DateTimeFormatter.ofPattern("d MMMM", ITA_M)),
        areas = w.areas.map { AreaBar(areaLabel(it.area), it.minutes, it.lastWeek) },
        areasNote = when {
            !Assistant.isConfigured(ctx) -> "Collega i dati per Claude nelle Opzioni: questa stima arriva dall'analisi notturna."
            w.days == 0 -> "Nessun giorno analizzato da lunedì. Oggi conta da domani mattina."
            else -> "Contati ${if (w.days == 1) "un giorno" else "${w.days} giorni"}; oggi entra domani mattina."
        },
        habits = habits,
        health = hState,
        healthDays = healthDays,
        healthNote = if (hState == HealthState.CONNECTED) "Il sonno di stanotte compare quando Samsung Health lo sincronizza." else "",
        places = places,
        topPlaces = if (places == PlacesState.ON) Places.topOfWeek(ctx, today).map { it.first to hm(it.second) } else emptyList(),
    )
}

// =============================================================== Giorno

class DayMemoryState(val ui: DayMemoryUi?, val onName: (StayRow) -> Unit)

/** Corpo e luoghi di un giorno, con la finestra per dare un nome ai posti. */
@Composable
fun rememberDayMemory(day: String, refresh: Any?): DayMemoryState {
    val ctx = LocalContext.current
    var version by remember { mutableIntStateOf(0) }
    var ui by remember { mutableStateOf<DayMemoryUi?>(null) }
    var naming by remember { mutableStateOf<StayRow?>(null) }
    LaunchedEffect(day, refresh, version) { ui = withContext(Dispatchers.IO) { dayMemoryUi(ctx, day) } }
    naming?.let { st ->
        NamePlaceDialog(st.name, onDismiss = { naming = null }) { name ->
            Places.name(ctx, st.lat, st.lon, name)
            naming = null
            version++
        }
    }
    return DayMemoryState(ui) { naming = it }
}

private fun dayMemoryUi(ctx: Context, day: String): DayMemoryUi {
    val commitments = Assistant.commitments(ctx).filter { it.fonteGiorno == day && it.persona.isNotBlank() }
    val stays = Places.stays(ctx, day).map { s ->
        val from = Places.clock(s.fromMs)
        val to = Places.clock(s.toMs)
        val people = commitments.filter { c ->
            runCatching {
                val t = LocalTime.parse(c.fonteOra.padStart(5, '0'))
                !t.isBefore(LocalTime.parse(from.padStart(5, '0'))) && !t.isAfter(LocalTime.parse(to.padStart(5, '0')))
            }.getOrDefault(false)
        }.map { it.persona.trim() }.distinct()
        StayRow("$from–$to", s.place?.nome, people, s.lat, s.lon)
    }
    return DayMemoryUi(health = healthLine(Health.day(ctx, day)), stays = stays)
}

// =============================================================== Domande

@Composable
fun QuestionsRoute(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val ctx = LocalContext.current
    var version by remember { mutableIntStateOf(0) }
    var ui by remember { mutableStateOf<QuestionsUi?>(null) }
    LaunchedEffect(version) {
        ui = withContext(Dispatchers.IO) {
            QuestionsUi(
                questions = Questions.all(ctx).map { QuestionRow(it.nome, it.slots, it.mine) },
                people = Assistant.commitments(ctx).map { it.persona.trim() }.filter { it.isNotBlank() }
                    .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key }.take(12),
            )
        }
    }
    val s = ui ?: return
    QuestionsScreen(
        s, onBack = onBack,
        onAsk = { nome, values ->
            val q = Questions.all(ctx).firstOrNull { it.nome == nome } ?: return@QuestionsScreen
            val (_, text) = Questions.prompt(q, values)
            Questions.send(ctx, text)
            Toast.makeText(ctx, "Copiata. Incollala nella chat del progetto.", Toast.LENGTH_LONG).show()
        },
        onAdd = { Questions.add(ctx, it); version++ },
        onRemove = { Questions.remove(ctx, it); version++ },
    )
}
