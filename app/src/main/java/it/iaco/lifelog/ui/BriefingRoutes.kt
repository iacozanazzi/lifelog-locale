package it.iaco.lifelog.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import it.iaco.lifelog.briefing.Briefing
import it.iaco.lifelog.briefing.DeviceCalendar
import it.iaco.lifelog.data.Assistant
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private val IT = Locale.ITALIAN
private val dayFmt = DateTimeFormatter.ofPattern("EEEE d MMMM", IT)
private val shortFmt = DateTimeFormatter.ofPattern("d MMMM", IT)

/** "ieri", "oggi", "il 20 novembre": per dire quando è stata detta una cosa. */
private fun whenSaid(day: String): String {
    val d = runCatching { LocalDate.parse(day) }.getOrNull() ?: return ""
    val today = LocalDate.now()
    return when (d) {
        today -> "oggi"
        today.minusDays(1) -> "ieri"
        else -> "il " + d.format(shortFmt)
    }
}

private fun factLine(f: Briefing.Fact): String {
    val w = whenSaid(f.day)
    return if (w.isEmpty()) f.text else "${f.text} (${w}${if (f.time.isNotBlank()) " alle ${f.time}" else ""})"
}

private fun notes(people: List<Briefing.Person>) = people.filter { it.name.isNotBlank() }.map { p -> PersonNote(p.name, p.remember.map(::factLine)) }

/**
 * Costruisce il briefing per Oggi. Si chiama fuori dal thread principale:
 * legge il calendario del telefono e i file scaricati.
 */
fun briefingOf(ctx: Context): BriefingUi? {
    if (!Assistant.isConfigured(ctx) && !DeviceCalendar.granted(ctx)) return null
    val today = LocalDate.now()
    val now = LocalTime.now()
    val data = Briefing.load(ctx)
    val events = DeviceCalendar.events(ctx, today, 1)

    // Le note di Claude si agganciano all'evento per id, o per titolo e ora se l'id è cambiato.
    fun meetingFor(e: DeviceCalendar.Event, list: List<Briefing.Meeting>) =
        list.firstOrNull { it.eventId == e.id } ?: list.firstOrNull { it.title.equals(e.title, true) && it.time == e.start }

    val agenda = events.mapNotNull { e ->
        if (!e.allDay && e.endMs <= System.currentTimeMillis()) return@mapNotNull null
        AgendaRow(
            time = e.start.orEmpty(),
            title = e.title,
            place = listOfNotNull("Tutto il giorno".takeIf { e.allDay }, e.location.ifBlank { null }).joinToString(", "),
            people = meetingFor(e, data?.today.orEmpty())?.let { notes(it.people) }.orEmpty(),
        )
    }

    val due = Briefing.dueCommitments(ctx).map { cm ->
        val d = runCatching { LocalDate.parse(cm.scadenza!!.take(10)) }.getOrNull()
        val label = when {
            d == null -> ""
            d.isBefore(today) -> "Scaduto il " + d.format(shortFmt)
            d == today -> "Scade oggi"
            d == today.plusDays(1) -> "Scade domani"
            else -> "Scade " + d.format(dayFmt)
        }
        val who = if (cm.chi == "altro" && cm.persona.isNotBlank()) "${cm.persona} deve: ${cm.testo}" else cm.testo
        TodoItem(cm.id, who, label, done = false, person = cm.persona)
    }

    // Preparazione per domani: solo la sera, solo per gli eventi ancora in calendario.
    val tomorrow = if (now.hour >= 17 && data != null) {
        val tEvents = DeviceCalendar.events(ctx, today.plusDays(1), 1)
        data.tomorrow.filter { m -> m.people.isNotEmpty() || m.points.isNotEmpty() }
            .filter { m -> tEvents.isEmpty() || tEvents.any { it.id == m.eventId || (it.title.equals(m.title, true) && it.start == m.time) } }
            .map { m ->
                PrepCard(
                    whenLabel = if (m.time != null) "Domani alle ${m.time}" else "Domani",
                    title = m.title, people = notes(m.people), points = m.points.map(::factLine),
                )
            }
    } else emptyList()

    val doneEvents = Briefing.decidedEvents(ctx)
    val proposals = data?.proposals.orEmpty()
        .filter { it.id !in doneEvents && it.day >= today.toString() }
        .map { p ->
            val d = runCatching { LocalDate.parse(p.day) }.getOrNull()
            val day = d?.format(dayFmt)?.replaceFirstChar { it.uppercase() } ?: p.day
            ProposalRow(
                id = p.id, title = p.title,
                whenLabel = if (p.time != null) "$day alle ${p.time}" else "$day, tutto il giorno",
                source = buildString {
                    append("Detto ").append(whenSaid(p.sourceDay))
                    if (p.sourceTime.isNotBlank()) append(" alle ").append(p.sourceTime)
                    if (p.quote.isNotBlank()) append(": «").append(p.quote).append("»")
                },
            )
        }

    val doneDrafts = Briefing.decidedDrafts(ctx)
    val commitments = Assistant.commitments(ctx).associateBy { it.id }
    val drafts = data?.drafts.orEmpty()
        .filter { it.id !in doneDrafts && commitments[it.commitment]?.stato != "fatto" && it.text.isNotBlank() }
        .map { d ->
            val cm = commitments[d.commitment]
            DraftRow(
                id = d.id, to = if (d.person.isNotBlank()) "A ${d.person}" else "Messaggio",
                text = d.text,
                source = cm?.let { "Hai promesso ${whenSaid(it.fonteGiorno)}${if (it.fonteOra.isNotBlank()) " alle ${it.fonteOra}" else ""}: ${it.testo}" }
                    ?: "Per un impegno preso nei giorni scorsi",
            )
        }

    return BriefingUi(
        agenda = agenda,
        calendarMissing = !DeviceCalendar.granted(ctx),
        due = due, tomorrow = tomorrow, proposals = proposals, drafts = drafts,
    )
}

/** Azioni del briefing. [refresh] ricalcola la schermata dopo una scelta. */
@Composable
fun rememberBriefingActions(onPerson: (String) -> Unit, refresh: () -> Unit): BriefingActions {
    val ctx = LocalContext.current
    val calLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) Assistant.uploadSoon(ctx)
        refresh()
    }
    return remember(onPerson) {
        fun draft(id: String) = Briefing.load(ctx)?.drafts?.firstOrNull { it.id == id }
        BriefingActions(
            onPerson = onPerson,
            onGrantCalendar = { calLauncher.launch(Manifest.permission.READ_CALENDAR) },
            onToggleDue = { id, done -> Assistant.setDone(ctx, id, done); refresh() },
            onAddEvent = { id ->
                val p = Briefing.load(ctx)?.proposals?.firstOrNull { it.id == id }
                if (p != null) {
                    try {
                        ctx.startActivity(DeviceCalendar.insertIntent(p).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        Briefing.decideEvent(ctx, id, added = true)
                    } catch (e: Exception) {
                        Toast.makeText(ctx, "Non trovo un'app Calendario sul telefono.", Toast.LENGTH_LONG).show()
                    }
                }
                refresh()
            },
            onSkipEvent = { id -> Briefing.decideEvent(ctx, id, added = false); refresh() },
            onCopyDraft = { id ->
                draft(id)?.let {
                    ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Messaggio", it.text))
                    Toast.makeText(ctx, "Copiato. Incollalo nella chat.", Toast.LENGTH_SHORT).show()
                }
            },
            onSendDraft = { id ->
                draft(id)?.let {
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, it.text)
                    ctx.startActivity(Intent.createChooser(send, "Manda il messaggio").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            },
            onDraftDone = { id -> Briefing.decideDraft(ctx, id, used = true); refresh() },
        )
    }
}
