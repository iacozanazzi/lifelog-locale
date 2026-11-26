package it.iaco.lifelog.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import it.iaco.lifelog.Db
import it.iaco.lifelog.briefing.Briefing
import it.iaco.lifelog.data.Assistant
import it.iaco.lifelog.people.People
import it.iaco.lifelog.people.Speakers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

private val IT = Locale.ITALIAN
private val zone get() = ZoneId.systemDefault()

/** "oggi", "ieri", "3 giorni fa", "il 4 novembre". */
internal fun agoLabel(d: LocalDate?, today: LocalDate = LocalDate.now()): String {
    if (d == null) return "mai sentita"
    val days = ChronoUnit.DAYS.between(d, today)
    return when {
        days <= 0L -> "oggi"
        days == 1L -> "ieri"
        days < 7L -> "$days giorni fa"
        else -> "il " + d.format(DateTimeFormatter.ofPattern(if (d.year == today.year) "d MMMM" else "d MMMM yyyy", IT))
    }
}

private fun dayTime(ms: Long): String =
    Instant.ofEpochMilli(ms).atZone(zone).format(DateTimeFormatter.ofPattern("d MMM, HH:mm", IT))

private fun minutesText(m: Long) = when {
    m < 1 -> "meno di un minuto"
    m == 1L -> "1 minuto"
    m < 60 -> "$m minuti"
    else -> "${m / 60} h ${m % 60} min"
}

private fun daysText(n: Int) = if (n == 1) "1 giorno" else "$n giorni"

/** "Ieri: 41 frasi su 300 con una voce riconosciuta." Solo se il modello c'è e ieri c'è stato parlato. */
private fun voiceDiag(ctx: Context, db: Db): String? {
    if (!Speakers.ready(ctx)) return null
    val day = java.time.LocalDate.now(zone).minusDays(1).toString()
    val rows = runCatching { db.voiceRowsOfDay(day) }.getOrDefault(emptyList())
    if (rows.isEmpty()) return null
    val voices = Speakers.voiceMap(db)
    val ok = rows.count { Speakers.label(it.first, voices) != "?" }
    return "Ieri: $ok frasi su ${rows.size} con una voce riconosciuta."
}

private fun modelState(ctx: Context) = when {
    Speakers.ready(ctx) -> VoiceModelState.READY
    Speakers.isDownloading(ctx) -> VoiceModelState.DOWNLOADING
    else -> VoiceModelState.MISSING
}

private fun dateText(d: People.Date, today: LocalDate): String {
    val next = People.nextOccurrence(d.whenText, today)
    val whenLabel = runCatching {
        if (d.whenText.length == 5) java.time.MonthDay.parse("--${d.whenText}").atYear(2000).format(DateTimeFormatter.ofPattern("d MMMM", IT))
        else LocalDate.parse(d.whenText.take(10)).format(DateTimeFormatter.ofPattern("d MMMM yyyy", IT))
    }.getOrDefault(d.whenText)
    val what = d.what.ifBlank { "data importante" }.replaceFirstChar { it.uppercase() }
    val src = d.source?.let { " (detto il ${it.day} alle ${it.time})" }.orEmpty()
    val soon = next?.let { ChronoUnit.DAYS.between(today, it) }?.takeIf { it in 0..30 }?.let { if (it == 0L) ", oggi" else ", tra $it giorni" }.orEmpty()
    return "$what: $whenLabel$soon$src"
}

// =============================================================== Elenco

@Composable
fun PeopleRoute(tick: Int, onBack: () -> Unit, onPerson: (String) -> Unit, onVoice: (String) -> Unit) {
    val ctx = LocalContext.current
    var ui by remember { mutableStateOf<PeopleUi?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    val version = remember(tick) { Db.get(ctx).version }
    LaunchedEffect(version, refresh, tick / 5) {
        ui = withContext(Dispatchers.IO) {
            val today = LocalDate.now()
            val all = People.all(ctx)
            fun row(p: People.Person) = PersonRow(
                p.slug, p.name,
                line = p.remote?.who?.takeIf { it.isNotBlank() } ?: if (p.minutesHeard > 0) "Voce riconosciuta, ${minutesText(p.minutesHeard)}" else "",
                last = agoLabel(p.lastSeen, today),
            )
            val db = Db.get(ctx)
            PeopleUi(
                model = modelState(ctx),
                diag = voiceDiag(ctx, db),
                meKnown = runCatching { db.loadVoices().any { it.isMe } }.getOrDefault(false),
                unknown = People.unknownVoices(ctx).map { u ->
                    UnknownVoiceRow(
                        u.voice.id, "Voce ${u.voice.n}",
                        "${minutesText(u.minutes).replaceFirstChar { it.uppercase() }} in ${daysText(u.days)}, l'ultima volta ${agoLabel(Instant.ofEpochMilli(u.lastMs).atZone(zone).toLocalDate(), today)}",
                        db.samplesOfVoice(u.voice.id, 1).firstOrNull()?.text.orEmpty(),
                    )
                },
                followed = all.filter { it.followed }.map(::row),
                others = all.filter { !it.followed }.map(::row),
                dataConfigured = Assistant.isConfigured(ctx),
            )
        }
    }
    val s = ui ?: return
    PeopleScreen(
        s, onBack = onBack, onPerson = onPerson, onVoice = onVoice,
        onDownload = {
            Speakers.download(ctx)
            Toast.makeText(ctx, "Scarico ${Speakers.MODEL_MB} MB. Le voci si riconoscono dalle prossime trascrizioni.", Toast.LENGTH_LONG).show()
            refresh++
        },
        onForgetAll = { Speakers.forgetAll(ctx); refresh++ },
    )
}

// =============================================================== Scheda

@Composable
fun PersonRoute(nameOrSlug: String, tick: Int, onBack: () -> Unit, onSearch: (String) -> Unit) {
    val ctx = LocalContext.current
    var ui by remember { mutableStateOf<PersonUi?>(null) }
    var missing by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(nameOrSlug, refresh) {
        val p = withContext(Dispatchers.IO) { People.find(ctx, nameOrSlug) }
        if (p == null) { missing = true; return@LaunchedEffect }
        ui = withContext(Dispatchers.IO) { personUi(ctx, p) }
    }
    if (missing) {
        // Nessuna scheda (ancora): come prima, si cerca il nome nelle trascrizioni.
        LaunchedEffect(Unit) { onSearch(nameOrSlug) }
        return
    }
    val s = ui ?: return
    PersonScreen(
        s, onBack = onBack,
        onFollow = { on -> People.setFollowed(ctx, s.slug, on); ui = s.copy(followed = on) },
        onSearch = { onSearch(s.name) },
    )
}

private fun personUi(ctx: Context, p: People.Person): PersonUi {
    val today = LocalDate.now()
    val r = p.remote
    val card = People.card(ctx, p.slug)
    val voiceText = when {
        p.voice != null && p.minutesHeard > 0 -> "Riconosciuta: ${minutesText(p.minutesHeard)} di parlato finora."
        p.voice != null -> "Riconosciuta, non ancora sentita di nuovo."
        Speakers.ready(ctx) -> "Non ancora collegata. Quando compare tra le voci da riconoscere, dalle questo nome."
        else -> "Il riconoscimento delle voci non è attivo."
    }
    return PersonUi(
        slug = p.slug, name = p.name,
        who = r?.who.orEmpty(),
        last = "Ultima volta " + agoLabel(p.lastSeen, today),
        met = r?.met.orEmpty().let { m -> if (m.matches(Regex("\\d{4}-\\d{2}"))) "Da " + runCatching { java.time.YearMonth.parse(m).format(DateTimeFormatter.ofPattern("MMMM yyyy", IT)) }.getOrDefault(m) else m },
        voice = voiceText,
        followed = p.followed,
        nextMeeting = r?.nextMeeting?.let { m ->
            val d = runCatching { LocalDate.parse(m.day) }.getOrNull()
            if (d != null && d.isBefore(today)) null
            else (d?.let { agoFuture(it, today) } ?: m.day) + (m.time?.let { " alle $it" } ?: "") + (m.text.takeIf { it.isNotBlank() }?.let { ": $it" } ?: "")
        },
        remember = r?.remember.orEmpty(),
        iOwe = p.iOwe.map { it.testo + (it.scadenza?.let { s -> ", entro il $s" } ?: "") },
        theyOwe = p.theyOwe.map { it.testo + (it.scadenza?.let { s -> ", entro il $s" } ?: "") },
        topics = r?.topics.orEmpty().map { it.replace('-', ' ').replaceFirstChar { c -> c.uppercase() } },
        dates = r?.dates.orEmpty().map { dateText(it, today) },
        timeline = People.timeline(card).take(12),
    )
}

private fun agoFuture(d: LocalDate, today: LocalDate): String = when (ChronoUnit.DAYS.between(today, d)) {
    0L -> "Oggi"
    1L -> "Domani"
    else -> d.format(DateTimeFormatter.ofPattern("EEEE d MMMM", IT)).replaceFirstChar { it.uppercase() }
}

// =============================================================== Voce

@Composable
fun VoiceRoute(voiceId: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var ui by remember { mutableStateOf<VoiceUi?>(null) }
    LaunchedEffect(voiceId) {
        ui = withContext(Dispatchers.IO) {
            val db = Db.get(ctx)
            val voices = db.loadVoices()
            val v = voices.firstOrNull { it.id == voiceId } ?: return@withContext null
            val st = db.voiceStats()[v.id]
            val names = (People.remote(ctx).map { it.name } + voices.mapNotNull { it.name }).distinct().sorted()
            VoiceUi(
                id = v.id,
                title = v.name ?: "Voce ${v.n}",
                meta = "${minutesText((st?.first ?: 0) / 60).replaceFirstChar { it.uppercase() }} in ${daysText(st?.second ?: 0)}. Sentita la prima volta il ${dayTime(v.firstMs)}.",
                samples = db.samplesOfVoice(v.id, 6).sortedBy { it.startMs }.map { Entry(dayTime(it.startMs), it.text.orEmpty()) },
                suggestions = names.take(12),
                meKnown = voices.any { it.isMe },
            )
        }
    }
    val s = ui ?: return
    val done = { msg: String -> Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show(); onBack() }
    VoiceScreen(
        s, onBack = onBack,
        onName = { n ->
            val persona = People.remote(ctx).firstOrNull { it.name.equals(n, true) }?.slug
            Speakers.name(ctx, s.id, n, persona); done("Da ora è $n.")
        },
        onMe = { Speakers.name(ctx, s.id, null, null, me = true); done("Questa è la tua voce.") },
        onForget = { Speakers.forget(ctx, s.id); done("Voce lasciata stare.") },
    )
}

// =============================================================== Oggi

/** Righe per la scheda Persone in Oggi (vuota se non c'è niente da dire). */
fun peopleToday(ctx: Context): List<PeopleTodayRow> = runCatching {
    // Chi vedi oggi lo mostra già il briefing (con la preparazione): qui non lo ripeto.
    val inBriefing = runCatching {
        Briefing.load(ctx)?.today.orEmpty().flatMap { m -> m.people.map { p -> p.slug } }.toSet()
    }.getOrDefault(emptySet())
    People.reminders(ctx)
        .filterNot { r -> r.slug in inBriefing && r.text.contains(" vedi ") }
        .take(4).map { PeopleTodayRow(it.slug, it.name, it.text, it.detail) }
}.getOrDefault(emptyList())
