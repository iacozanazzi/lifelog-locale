package it.iaco.lifelog.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// =============================================================== Briefing (dati puri)

/** Una persona che vedrai, con al massimo due cose da ricordare (ognuna con giorno e ora). */
data class PersonNote(val name: String, val notes: List<String>)

data class AgendaRow(val time: String, val title: String, val place: String, val people: List<PersonNote>, val past: Boolean = false)

/** Preparazione a un incontro di domani, mostrata la sera. */
data class PrepCard(val whenLabel: String, val title: String, val people: List<PersonNote>, val points: List<String>)

data class ProposalRow(val id: String, val title: String, val whenLabel: String, val source: String)

data class DraftRow(val id: String, val to: String, val text: String, val source: String)

data class BriefingUi(
    val agenda: List<AgendaRow> = emptyList(),
    /** Il permesso Calendario non è stato dato: si mostra l'invito a darlo. */
    val calendarMissing: Boolean = false,
    val due: List<TodoItem> = emptyList(),
    val tomorrow: List<PrepCard> = emptyList(),
    val proposals: List<ProposalRow> = emptyList(),
    val drafts: List<DraftRow> = emptyList(),
) {
    val hasTop get() = agenda.isNotEmpty() || due.isNotEmpty() || calendarMissing
}

class BriefingActions(
    val onPerson: (String) -> Unit = {},
    val onGrantCalendar: () -> Unit = {},
    val onToggleDue: (String, Boolean) -> Unit = { _, _ -> },
    val onAddEvent: (String) -> Unit = {},
    val onSkipEvent: (String) -> Unit = {},
    val onCopyDraft: (String) -> Unit = {},
    val onSendDraft: (String) -> Unit = {},
    val onDraftDone: (String) -> Unit = {},
)

// =============================================================== In cima alla scheda di Oggi

/**
 * Parte alta della scheda in cima a Oggi: l'agenda di oggi con chi vedrai,
 * poi le scadenze. Sotto continua il riassunto "Da ieri".
 */
@Composable
fun ColumnScope.BriefingTop(b: BriefingUi, a: BriefingActions, hasYesterday: Boolean) {
    val c = Life.c
    if (b.agenda.isNotEmpty()) {
        CardTitle("In agenda oggi")
        b.agenda.forEach { e -> AgendaLine(e, a) }
    } else if (b.calendarMissing) {
        CardTitle("In agenda oggi")
        Text(
            "Lifelog può mostrarti qui gli eventi del calendario e chi vedrai. Legge il calendario solo sul telefono e lo passa all'analisi notturna.",
            style = Type.text, color = c.second, modifier = Modifier.padding(top = 6.dp)
        )
        TextAction("Mostra il calendario", a.onGrantCalendar, Modifier.padding(top = 6.dp))
    }
    if (b.due.isNotEmpty()) {
        CardTitle("In scadenza", top = if (b.agenda.isNotEmpty() || b.calendarMissing) 18.dp else 0.dp)
        b.due.forEach { t -> CheckLine(t) { a.onToggleDue(t.id, !t.done) } }
    }
    if (hasYesterday) androidx.compose.foundation.layout.Spacer(Modifier.height(22.dp))
}

@Composable
private fun CardTitle(text: String, top: androidx.compose.ui.unit.Dp = 0.dp) =
    Text(text, style = Type.meta.copy(fontWeight = FontWeight.Bold), color = Life.c.text, modifier = Modifier.padding(top = top))

@Composable
private fun AgendaLine(e: AgendaRow, a: BriefingActions) {
    val c = Life.c
    Row(Modifier.padding(top = 12.dp).fillMaxWidth()) {
        Text(e.time, style = Type.time, color = c.second, modifier = Modifier.width(58.dp).padding(top = 4.dp))
        Column(Modifier.weight(1f)) {
            Text(e.title, style = Type.rowTitle, color = if (e.past) c.second else c.text)
            if (e.place.isNotBlank()) Text(e.place, style = Type.meta, color = c.second)
            People(e.people, a)
        }
    }
}

@Composable
private fun People(people: List<PersonNote>, a: BriefingActions) {
    val c = Life.c
    people.forEach { p ->
        Column(Modifier.padding(top = 10.dp)) {
            Chip(p.name) { a.onPerson(p.name) }
            p.notes.forEach { n -> Text(n, style = Type.meta, color = c.text, modifier = Modifier.padding(top = 6.dp)) }
        }
    }
}

@Composable
private fun CheckLine(t: TodoItem, onClick: () -> Unit) {
    val c = Life.c
    Row(
        Modifier.padding(top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick),
        verticalAlignment = Alignment.Top
    ) {
        Check(t.done, onCard = true)
        Column(Modifier.padding(start = 12.dp, top = 1.dp)) {
            Text(
                t.text, style = Type.text.copy(textDecoration = if (t.done) TextDecoration.LineThrough else null),
                color = if (t.done) c.faint else c.text
            )
            if (t.meta.isNotBlank()) Text(t.meta, style = Type.meta, color = c.second)
        }
    }
}

// =============================================================== Schede sotto

/** Le altre schede del briefing: domani, date da mettere in calendario, messaggi promessi. */
fun LazyListScope.briefingCards(b: BriefingUi?, a: BriefingActions) {
    if (b == null) return
    if (b.tomorrow.isNotEmpty()) item {
        Card("Per domani") {
            b.tomorrow.forEachIndexed { i, p ->
                Column(Modifier.padding(top = if (i == 0) 6.dp else 20.dp)) {
                    Text(p.whenLabel, style = Type.meta, color = c2())
                    Text(p.title, style = Type.section.copy(fontSize = 21.sp, lineHeight = 26.sp), color = Life.c.text)
                    People(p.people, a)
                    p.points.forEach { pt -> Text(pt, style = Type.text, color = Life.c.text, modifier = Modifier.padding(top = 8.dp)) }
                }
            }
        }
    }
    if (b.proposals.isNotEmpty()) item {
        Card("Da mettere in calendario") {
            b.proposals.forEachIndexed { i, p ->
                Column(Modifier.padding(top = if (i == 0) 6.dp else 18.dp)) {
                    Text(p.title, style = Type.section.copy(fontSize = 21.sp, lineHeight = 26.sp), color = Life.c.text)
                    Text(p.whenLabel, style = Type.rowTitle, color = Life.c.text, modifier = Modifier.padding(top = 2.dp))
                    Text(p.source, style = Type.meta, color = c2(), modifier = Modifier.padding(top = 4.dp))
                    Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                        TextAction("Aggiungi", { a.onAddEvent(p.id) })
                        TextAction("Non serve", { a.onSkipEvent(p.id) }, color = c2())
                    }
                }
            }
        }
    }
    if (b.drafts.isNotEmpty()) item {
        Card("Messaggi che hai promesso") {
            b.drafts.forEachIndexed { i, d ->
                Column(Modifier.padding(top = if (i == 0) 6.dp else 18.dp)) {
                    Text(d.to, style = Type.rowTitle, color = Life.c.text)
                    Text(d.source, style = Type.meta, color = c2())
                    Text(
                        d.text, style = Type.text, color = Life.c.text,
                        modifier = Modifier.padding(top = 8.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp))
                            .background(Life.c.bg).padding(14.dp)
                    )
                    Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                        TextAction("Copia", { a.onCopyDraft(d.id) })
                        TextAction("Invia…", { a.onSendDraft(d.id) })
                        TextAction("Fatto", { a.onDraftDone(d.id) }, color = c2())
                    }
                }
            }
        }
    }
}

@Composable
private fun c2() = Life.c.second

/** Scheda di Oggi: come "Da ieri", sfondo pieno e angoli 22 dp. */
@Composable
private fun Card(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.padding(top = 14.dp).fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Life.c.fill).padding(18.dp)
    ) {
        CardTitle(title)
        content()
    }
}
