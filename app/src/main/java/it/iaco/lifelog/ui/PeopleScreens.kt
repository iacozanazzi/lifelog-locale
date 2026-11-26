package it.iaco.lifelog.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// =============================================================== Stati (dati puri)

enum class VoiceModelState { MISSING, DOWNLOADING, READY }

data class PersonRow(val slug: String, val name: String, val line: String, val last: String)

data class UnknownVoiceRow(val id: String, val label: String, val meta: String, val sample: String)

data class PeopleUi(
    val model: VoiceModelState,
    /** Iaco ha già indicato quale voce è la sua. */
    val meKnown: Boolean,
    val unknown: List<UnknownVoiceRow>,
    val followed: List<PersonRow>,
    val others: List<PersonRow>,
    val dataConfigured: Boolean,
)

data class PersonUi(
    val slug: String,
    val name: String,
    val who: String,
    val last: String,
    val met: String,
    val voice: String,
    val followed: Boolean,
    val nextMeeting: String? = null,
    val remember: List<String> = emptyList(),
    val iOwe: List<String> = emptyList(),
    val theyOwe: List<String> = emptyList(),
    val topics: List<String> = emptyList(),
    val dates: List<String> = emptyList(),
    val timeline: List<String> = emptyList(),
)

data class VoiceUi(
    val id: String,
    val title: String,
    val meta: String,
    val samples: List<Entry>,
    /** Nomi già nel catalogo, da toccare invece di scrivere. */
    val suggestions: List<String>,
    val meKnown: Boolean,
)

data class PeopleTodayRow(val slug: String, val name: String, val text: String, val detail: List<String> = emptyList())

// =============================================================== Persone (elenco)

@Composable
fun PeopleScreen(
    s: PeopleUi,
    onBack: () -> Unit,
    onPerson: (String) -> Unit,
    onVoice: (String) -> Unit,
    onDownload: () -> Unit,
    onForgetAll: () -> Unit,
) {
    val c = Life.c
    var confirmForget by rememberSaveable { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = side, end = side, top = 8.dp, bottom = 40.dp)) {
        item {
            TextAction("‹ Indietro", onBack)
            ScreenHeader(null, "Persone")
        }

        when (s.model) {
            VoiceModelState.MISSING -> item {
                Card {
                    Text("Riconoscere le voci", style = Type.meta.copy(fontWeight = FontWeight.Bold), color = c.text)
                    Text(
                        "Lifelog può capire chi sta parlando e scrivere il nome davanti a ogni frase.",
                        style = Type.section.copy(fontSize = 21.sp, lineHeight = 26.sp), color = c.text, modifier = Modifier.padding(top = 6.dp)
                    )
                    Text(
                        "Scarica un modello da 29 MB. Le impronte delle voci restano solo su questo telefono: a Claude arrivano i nomi, mai le voci.",
                        style = Type.text, color = c.second, modifier = Modifier.padding(top = 8.dp)
                    )
                    SolidButton("Scarica e attiva", onDownload, modifier = Modifier.padding(top = 14.dp))
                }
            }
            VoiceModelState.DOWNLOADING -> item {
                Text("Scarico il riconoscimento delle voci…", style = Type.meta, color = c.accent, modifier = Modifier.padding(top = 14.dp))
            }
            VoiceModelState.READY -> if (!s.meKnown && s.unknown.isNotEmpty()) item {
                Text(
                    "Prima di tutto dimmi qual è la tua voce: di solito è quella sentita di più.",
                    style = Type.meta, color = c.second, modifier = Modifier.padding(top = 14.dp)
                )
            }
        }

        if (s.unknown.isNotEmpty()) {
            item { SectionTitle("Voci da riconoscere", "Tocca una voce, leggi cosa ha detto e dalle un nome una volta sola.") }
            items(s.unknown, key = { "u" + it.id }) { u ->
                Column(Modifier.fillMaxWidth().clickable { onVoice(u.id) }.padding(vertical = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(u.label, style = Type.rowTitle, color = c.text, modifier = Modifier.weight(1f))
                        Text("Dai un nome", style = Type.meta.copy(fontWeight = FontWeight.Bold), color = c.accent)
                    }
                    Text(u.meta, style = Type.meta, color = c.second)
                    if (u.sample.isNotBlank()) Text("\"${u.sample}\"", style = Type.text, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                }
                Hairline()
            }
        } else if (s.model == VoiceModelState.READY) item {
            Text(
                "Nessuna voce nuova da riconoscere. Le voci compaiono qui dopo almeno mezzo minuto di parlato.",
                style = Type.meta, color = c.second, modifier = Modifier.padding(top = 14.dp)
            )
        }

        if (s.followed.isNotEmpty()) {
            item { SectionTitle("Seguite", "Ti avviso se non le senti da tre settimane.") }
            items(s.followed, key = { "f" + it.slug }) { p -> PersonRowView(p) { onPerson(p.slug) } }
        }
        item {
            SectionTitle(
                "Tutte",
                if (s.others.isEmpty() && s.followed.isEmpty())
                    (if (s.dataConfigured) "Le schede nascono di notte, quando Claude sente un nome in almeno due giorni o quando dai un nome a una voce."
                    else "Collega i dati per Claude nelle Opzioni: le schede delle persone le scrive Claude di notte.")
                else null
            )
        }
        items(s.others, key = { "o" + it.slug }) { p -> PersonRowView(p) { onPerson(p.slug) } }

        if (s.model == VoiceModelState.READY) item {
            Spacer(Modifier.height(30.dp))
            Hairline()
            if (!confirmForget) TextAction("Cancella tutte le impronte delle voci", { confirmForget = true }, color = c.second, modifier = Modifier.padding(top = 10.dp))
            else Column(Modifier.padding(top = 10.dp)) {
                Text("Le frasi restano, ma tornano tutte senza nome. Non si può annullare.", style = Type.meta, color = c.text)
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    TextAction("Cancella", { confirmForget = false; onForgetAll() })
                    TextAction("Annulla", { confirmForget = false }, color = c.second)
                }
            }
        }
    }
}

@Composable
private fun PersonRowView(p: PersonRow, onClick: () -> Unit) {
    val c = Life.c
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(p.name, style = Type.rowTitle, color = c.text, modifier = Modifier.weight(1f))
            Text(p.last, style = Type.meta, color = c.second)
        }
        if (p.line.isNotBlank()) Text(p.line, style = Type.meta, color = c.second, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    Hairline()
}

@Composable
private fun SectionTitle(title: String, note: String? = null) {
    val c = Life.c
    Column(Modifier.padding(top = 30.dp, bottom = 4.dp)) {
        Text(title, style = Type.section, color = c.text)
        if (note != null) Text(note, style = Type.meta, color = c.second, modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(Modifier.padding(top = 18.dp).fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Life.c.fill).padding(18.dp)) { content() }
}

// =============================================================== Persona (scheda)

@Composable
fun PersonScreen(
    s: PersonUi,
    onBack: () -> Unit,
    onFollow: (Boolean) -> Unit,
    onSearch: () -> Unit,
) {
    val c = Life.c
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = side, end = side, top = 8.dp, bottom = 40.dp)) {
        item {
            TextAction("‹ Persone", onBack)
            ScreenHeader(s.who.ifBlank { null }, s.name)
            Text(s.last, style = Type.meta, color = c.second, modifier = Modifier.padding(top = 6.dp))
        }
        s.nextMeeting?.let { m -> item { Card { Text("Prossimo incontro", style = Type.meta.copy(fontWeight = FontWeight.Bold), color = c.text); Text(m, style = Type.section.copy(fontSize = 21.sp, lineHeight = 26.sp), color = c.text, modifier = Modifier.padding(top = 6.dp)) } } }

        list("Da ricordare", s.remember)
        list("Cosa devi tu", s.iOwe)
        list("Cosa ti deve", s.theyOwe)
        list("Temi in corso", s.topics)
        list("Date", s.dates)

        item {
            SectionTitle("Come vi siete conosciuti")
            Text(s.met.ifBlank { "Non ancora scritto. Claude lo aggiunge quando lo sente in una conversazione." }, style = Type.text, color = if (s.met.isBlank()) c.second else c.text, modifier = Modifier.padding(top = 4.dp))
        }
        if (s.timeline.isNotEmpty()) {
            item { SectionTitle("Cronologia") }
            items(s.timeline) { t -> Text(t, style = Type.text, color = c.text, modifier = Modifier.padding(vertical = 7.dp)) }
        }
        item {
            SectionTitle("Voce")
            Text(s.voice, style = Type.text, color = c.text, modifier = Modifier.padding(top = 4.dp))
            Spacer(Modifier.height(18.dp))
            Hairline()
            SwitchRow("Avvisami se non lo sento da tre settimane", s.followed, onFollow)
            Hairline()
            TextAction("Cerca \"${s.name}\" nelle trascrizioni", onSearch, modifier = Modifier.padding(top = 12.dp))
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.list(title: String, xs: List<String>) {
    if (xs.isEmpty()) return
    item { SectionTitle(title) }
    items(xs) { t -> Text(t, style = Type.text, color = Life.c.text, modifier = Modifier.padding(vertical = 7.dp)) }
}

@Composable
private fun SwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val c = Life.c
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = Type.text.copy(fontWeight = FontWeight.Medium), color = c.text, modifier = Modifier.weight(1f))
        Switch(
            checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = c.accent, checkedThumbColor = Color.White,
                uncheckedTrackColor = c.fill, uncheckedBorderColor = c.hair, uncheckedThumbColor = c.faint
            )
        )
    }
}

// =============================================================== Voce (dare un nome)

@Composable
fun VoiceScreen(
    s: VoiceUi,
    onBack: () -> Unit,
    onName: (String) -> Unit,
    onMe: () -> Unit,
    onForget: () -> Unit,
) {
    val c = Life.c
    var name by rememberSaveable { mutableStateOf("") }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = side, end = side, top = 8.dp, bottom = 40.dp)) {
        item {
            TextAction("‹ Persone", onBack)
            ScreenHeader(null, s.title)
            Text(s.meta, style = Type.meta, color = c.second, modifier = Modifier.padding(top = 6.dp))
            SectionTitle("Cosa ha detto", "L'audio non si tiene: riconoscila dalle sue parole.")
        }
        items(s.samples) { e -> EntryRow(e, c.text) }
        item {
            SectionTitle("Chi è?")
            Box(Modifier.padding(top = 8.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.fill).padding(horizontal = 14.dp, vertical = 13.dp)) {
                if (name.isEmpty()) Text("Nome", style = Type.text.copy(fontWeight = FontWeight.Medium), color = c.second)
                BasicTextField(
                    value = name, onValueChange = { name = it }, singleLine = true,
                    textStyle = Type.text.copy(color = c.text, fontWeight = FontWeight.Medium),
                    cursorBrush = SolidColor(c.accent), modifier = Modifier.fillMaxWidth()
                )
            }
            if (s.suggestions.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                FlowChips(s.suggestions, selected = name.takeIf { n -> s.suggestions.any { it == n } }) { name = it }
            }
            SolidButton("Salva", { onName(name.trim()) }, enabled = name.isNotBlank(), modifier = Modifier.padding(top = 18.dp))
            Spacer(Modifier.height(22.dp))
            Hairline()
            if (!s.meKnown) TextAction("Sono io", onMe, modifier = Modifier.padding(top = 10.dp))
            TextAction("Non la conosco o sono più persone: lasciala stare", onForget, color = c.second, modifier = Modifier.padding(top = 6.dp))
            Text(
                "Se dai un nome già usato, le due voci diventano una sola persona.",
                style = Type.meta, color = c.second, modifier = Modifier.padding(top = 12.dp)
            )
        }
    }
}

// =============================================================== Scheda in Oggi

/** Promemoria sulle persone per la cima di Oggi: chi vedi, compleanni, chi non senti da tempo. */
@Composable
fun PeopleTodayCard(rows: List<PeopleTodayRow>, onPerson: (String) -> Unit) {
    if (rows.isEmpty()) return
    val c = Life.c
    Card {
        Text("Persone", style = Type.meta.copy(fontWeight = FontWeight.Bold), color = c.text)
        rows.forEachIndexed { i, r ->
            Column(Modifier.padding(top = if (i == 0) 6.dp else 14.dp).fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { onPerson(r.slug) }) {
                Text(r.text, style = Type.section.copy(fontSize = 21.sp, lineHeight = 26.sp), color = c.text)
                r.detail.forEach { d -> Text(d, style = Type.text, color = c.text, modifier = Modifier.padding(top = 4.dp)) }
            }
        }
    }
}
