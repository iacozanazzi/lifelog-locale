package it.iaco.lifelog.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// =============================================================== Dati per le schermate

enum class CheckState { DONE, TODO, UNKNOWN }

/** Una voce della checklist di una serata (locale, DJ, grafica, post, soldi). */
data class NightCheckUi(val key: String, val label: String, val state: CheckState, val note: String, val source: String)

data class NightUi(val id: String, val title: String, val whenText: String, val checks: List<NightCheckUi>) {
    val missing get() = checks.count { it.state != CheckState.DONE }
}

data class IdeaUi(val id: String, val text: String, val meta: String, val archived: Boolean = false)

data class SeralPostUi(val title: String, val nights: List<String>, val ideas: List<Entry>)

data class SeralUi(
    val configured: Boolean,
    val nights: List<NightUi>,
    val post: SeralPostUi?,
    val ideas: List<IdeaUi>,
    val archived: List<IdeaUi>,
)

/** Una riga della scheda in Oggi. */
data class AreaLine(val area: String, val title: String, val sub: String)

// =============================================================== Scheda in Oggi

/**
 * Righe di Seral e università dentro "Da tenere d'occhio" in Oggi,
 * dalla più urgente. Ogni riga apre la schermata dell'area.
 */
@Composable
fun AreeCard(lines: List<AreaLine>, onOpen: (String) -> Unit, modifier: Modifier = Modifier) {
    if (lines.isEmpty()) return
    Column(modifier.fillMaxWidth()) {
        lines.forEachIndexed { i, l ->
            WatchRow(if (l.area == "seral") "Seral" else "Università", l.title, l.sub) { onOpen(l.area) }
        }
    }
}

// =============================================================== Seral

@Composable
fun SeralScreen(
    s: SeralUi,
    onBack: () -> Unit,
    onCheck: (night: String, key: String, done: Boolean) -> Unit,
    onIdea: (id: String, stato: String) -> Unit,
    onSetup: () -> Unit = {},
    backLabel: String = "‹ Oggi",
) {
    val c = Life.c
    var showArchived by rememberSaveable { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = side, end = side, top = 8.dp, bottom = 40.dp)) {
        item {
            TextAction(backLabel, onBack)
            ScreenHeader(null, "Seral")
        }
        if (!s.configured) {
            item {
                Text("Le idee che dici a voce, le serate in preparazione e gli spunti per il post della settimana, raccolti da Claude ogni notte.", style = Type.lyric, color = c.text, modifier = Modifier.padding(top = 18.dp))
                TextAction("Collega in Opzioni", onSetup, modifier = Modifier.padding(top = 12.dp))
            }
            return@LazyColumn
        }

        // ---- Serate
        item { SectionTitle("Prossime serate", if (s.nights.isEmpty()) "" else if (s.nights.size == 1) "1 serata" else "${s.nights.size} serate") }
        if (s.nights.isEmpty()) item {
            Text("Nessuna serata in preparazione. Quando ne parli con una data, compare qui con la sua lista.", style = Type.text, color = c.second, modifier = Modifier.padding(vertical = 10.dp))
        }
        s.nights.forEach { n ->
            item {
                Column(Modifier.padding(top = 12.dp)) {
                    Text(n.title, style = Type.rowTitle, color = c.text)
                    Text(
                        n.whenText + if (n.missing == 0) ". Tutto pronto." else ". " + (if (n.missing == 1) "Manca una cosa." else "Mancano ${n.missing} cose."),
                        style = Type.meta, color = c.second, modifier = Modifier.padding(top = 2.dp, bottom = 4.dp)
                    )
                }
            }
            items(n.checks) { ch ->
                Row(Modifier.fillMaxWidth().clickable { onCheck(n.id, ch.key, ch.state != CheckState.DONE) }.padding(vertical = 10.dp)) {
                    Box(Modifier.padding(top = 1.dp, end = 14.dp)) { Check(ch.state == CheckState.DONE) }
                    Column(Modifier.weight(1f)) {
                        Text(ch.label, style = Type.text.copy(fontWeight = FontWeight.Bold), color = c.text)
                        Text(
                            ch.note.ifBlank { if (ch.state == CheckState.UNKNOWN) "Non ancora detto." else "" },
                            style = Type.text, color = if (ch.state == CheckState.UNKNOWN) c.second else c.text,
                        )
                        if (ch.source.isNotBlank()) Text(ch.source, style = Type.meta, color = c.second, modifier = Modifier.padding(top = 2.dp))
                    }
                }
            }
            item { Hairline(Modifier.padding(top = 6.dp)) }
        }

        // ---- Post della settimana
        s.post?.let { p ->
            item { SectionTitle(p.title, "") }
            if (p.nights.isNotEmpty()) item {
                Text("Serate della settimana: " + p.nights.joinToString(", ") + ".", style = Type.text, color = c.text, modifier = Modifier.padding(vertical = 6.dp))
            }
            if (p.ideas.isEmpty()) item {
                Text("Ancora nessuno spunto dai discorsi di questa settimana.", style = Type.text, color = c.second, modifier = Modifier.padding(vertical = 10.dp))
            }
            items(p.ideas) { e -> EntryRow(e, c.text) }
        }

        // ---- Idee
        item { SectionTitle("Idee", if (s.ideas.isEmpty()) "" else if (s.ideas.size == 1) "1 nuova" else "${s.ideas.size} nuove") }
        if (s.ideas.isEmpty()) item {
            Text("Ogni idea per Seral che dici ad alta voce finisce qui, con il giorno e l'ora.", style = Type.text, color = c.second, modifier = Modifier.padding(vertical = 10.dp))
        }
        items(s.ideas) { idea -> IdeaRow(idea, onUse = { onIdea(idea.id, "usata") }, onDrop = { onIdea(idea.id, "scartata") }) }
        if (s.archived.isNotEmpty()) {
            item {
                TextAction(
                    if (showArchived) "Nascondi le idee archiviate" else "Mostra le idee archiviate (${s.archived.size})",
                    { showArchived = !showArchived }, modifier = Modifier.padding(top = 16.dp)
                )
            }
            if (showArchived) items(s.archived) { idea ->
                IdeaRow(idea, onUse = { onIdea(idea.id, "nuova") }, onDrop = {})
            }
        }
    }
}

@Composable
internal fun SectionTitle(title: String, right: String) {
    val c = Life.c
    Row(Modifier.fillMaxWidth().padding(top = 30.dp, bottom = 4.dp), verticalAlignment = Alignment.Bottom) {
        Text(title, style = Type.section, color = c.text, modifier = Modifier.weight(1f))
        if (right.isNotBlank()) Text(right, style = Type.meta, color = c.second)
    }
}

@Composable
private fun IdeaRow(idea: IdeaUi, onUse: () -> Unit, onDrop: () -> Unit) {
    val c = Life.c
    Column {
        Row(Modifier.fillMaxWidth().clickable(onClick = onUse).padding(vertical = 12.dp)) {
            Box(Modifier.padding(top = 1.dp, end = 14.dp)) { Check(idea.archived) }
            Column(Modifier.weight(1f)) {
                Text(
                    idea.text, style = Type.text.copy(textDecoration = if (idea.archived) TextDecoration.LineThrough else null),
                    color = if (idea.archived) c.faint else c.text
                )
                Row(Modifier.padding(top = 3.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(idea.meta, style = Type.meta, color = c.second, modifier = Modifier.weight(1f))
                    if (!idea.archived) Text(
                        "Scarta", style = Type.meta.copy(fontWeight = FontWeight.Bold), color = c.second,
                        modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onDrop).padding(4.dp)
                    )
                }
            }
        }
        Hairline()
    }
}
