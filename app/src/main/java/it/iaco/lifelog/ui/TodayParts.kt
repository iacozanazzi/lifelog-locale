package it.iaco.lifelog.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * Pezzi della schermata Oggi. Una sola regola: una sezione = un titolo grande
 * e righe separate da linee sottili, come le sezioni di un'app musicale.
 * L'unico riquadro pieno è "Prossimo", la cosa più importante adesso.
 */

/** Titolo di sezione, con un'azione facoltativa a destra ("Tutti ›"). */
@Composable
fun TodaySection(title: String, action: String? = null, onAction: () -> Unit = {}, top: Int = 34) {
    val c = Life.c
    Row(Modifier.fillMaxWidth().padding(top = top.dp, bottom = 4.dp), verticalAlignment = Alignment.Bottom) {
        Text(title, style = Type.section, color = c.text, modifier = Modifier.weight(1f))
        if (action != null) Text(
            action, style = Type.meta.copy(fontWeight = FontWeight.Bold), color = c.accent,
            modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onAction).padding(vertical = 4.dp)
        )
    }
}

/** La prossima cosa in agenda (o domani, la sera): l'unico riquadro pieno della schermata. */
@Composable
fun NextUp(label: String, title: String, place: String, people: List<PersonNote>, points: List<String>, onPerson: (String) -> Unit) {
    val c = Life.c
    Column(Modifier.padding(top = 20.dp).fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(c.fill).padding(18.dp)) {
        Text(label, style = Type.meta.copy(fontWeight = FontWeight.Bold), color = c.accent)
        Text(title, style = Type.section.copy(fontSize = 24.sp, lineHeight = 29.sp), color = c.text, modifier = Modifier.padding(top = 4.dp))
        if (place.isNotBlank()) Text(place, style = Type.meta, color = c.second, modifier = Modifier.padding(top = 2.dp))
        val notes = people.flatMap { p -> p.notes.map { p.name to it } }
        if (notes.isNotEmpty() || points.isNotEmpty()) Hairline(Modifier.padding(top = 14.dp, bottom = 4.dp))
        notes.forEach { (name, n) ->
            Row(Modifier.padding(top = 8.dp).fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { onPerson(name) }) {
                Text(name, style = Type.meta.copy(fontWeight = FontWeight.Bold), color = c.text, modifier = Modifier.width(78.dp))
                Text(n, style = Type.meta, color = c.text, modifier = Modifier.weight(1f))
            }
        }
        points.forEach { pt -> Text(pt, style = Type.meta, color = c.text, modifier = Modifier.padding(top = 8.dp)) }
    }
}

/** Riga con cerchio da spuntare. */
@Composable
fun TodayCheckRow(text: String, meta: String, done: Boolean, first: Boolean, onClick: () -> Unit) {
    val c = Life.c
    if (!first) Hairline(Modifier.padding(start = 36.dp))
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp), verticalAlignment = Alignment.Top) {
        Check(done)
        Column(Modifier.padding(start = 12.dp, top = 1.dp).weight(1f)) {
            Text(
                text, style = Type.text.copy(fontSize = 17.sp, textDecoration = if (done) TextDecoration.LineThrough else null),
                color = if (done) c.faint else c.text
            )
            if (meta.isNotBlank()) Text(meta, style = Type.meta, color = c.second, modifier = Modifier.padding(top = 1.dp))
        }
    }
}

/** Riga con azioni in fila sotto (bozze da mandare, date da mettere in calendario). */
@Composable
fun TodayActionRow(title: String, meta: String, preview: String?, first: Boolean, actions: List<Triple<String, () -> Unit, Boolean>>) {
    val c = Life.c
    if (!first) Hairline(Modifier.padding(start = 36.dp))
    Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.Top) {
        Text("✎", style = Type.rowTitle, color = c.second, modifier = Modifier.width(36.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = Type.text.copy(fontSize = 17.sp), color = c.text)
            if (meta.isNotBlank()) Text(meta, style = Type.meta, color = c.second, modifier = Modifier.padding(top = 1.dp))
            if (!preview.isNullOrBlank()) Text(
                "«$preview»", style = Type.meta, color = c.second, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                actions.forEach { (label, go, main) -> TextAction(label, go, color = if (main) c.accent else c.second) }
            }
        }
    }
}

/** Riga dell'agenda: ora a sinistra, titolo e luogo. */
@Composable
fun TodayAgendaRow(time: String, title: String, place: String, past: Boolean, first: Boolean) {
    val c = Life.c
    if (!first) Hairline(Modifier.padding(start = 64.dp))
    Row(Modifier.fillMaxWidth().padding(vertical = 11.dp)) {
        Text(time, style = Type.time.copy(fontSize = 14.sp), color = c.second, modifier = Modifier.width(64.dp).padding(top = 3.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = Type.text.copy(fontSize = 17.sp, fontWeight = FontWeight.Medium), color = if (past) c.second else c.text)
            if (place.isNotBlank()) Text(place, style = Type.meta, color = c.second)
        }
    }
}

/**
 * Riga "da tenere d'occhio": etichetta piccola sopra (SERAL, PERSONE…),
 * una frase, un dettaglio e la freccina. Tutta la riga apre la schermata giusta.
 */
@Composable
fun WatchRow(label: String, title: String, sub: String = "", first: Boolean = false, onClick: () -> Unit) {
    val c = Life.c
    if (!first) Hairline()
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label.uppercase(java.util.Locale.ITALIAN), style = Type.time.copy(letterSpacing = 0.6.sp), color = c.second)
            Text(title, style = Type.text.copy(fontSize = 17.sp, fontWeight = FontWeight.Medium), color = c.text, modifier = Modifier.padding(top = 2.dp))
            if (sub.isNotBlank()) Text(sub, style = Type.meta, color = c.second, modifier = Modifier.padding(top = 1.dp))
        }
        Text("›", style = Type.section, color = c.faint, modifier = Modifier.padding(start = 10.dp))
    }
}
