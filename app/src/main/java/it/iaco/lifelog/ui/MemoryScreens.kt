package it.iaco.lifelog.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// =============================================================== Stati (dati puri)

/** Le schede di Oggi: "un anno fa" (se c'è) e la settimana. */
data class MemoryTodayUi(
    val mementoLabel: String? = null,
    val mementoText: String? = null,
    val mementoDay: String? = null,
    /** Una o due frasi sulla settimana; vuoto se non c'è ancora niente. */
    val weekText: String = "",
)

data class AreaBar(val label: String, val minutes: Int, val lastWeek: Int)

/** 0 = nessuna prova, 1 = fatto, 2 = saltato. */
data class HabitDay(val day: String, val letter: String, val state: Int, val future: Boolean = false)

data class HabitRow(
    val id: String, val title: String, val progress: String, val said: String,
    val days: List<HabitDay>, val lastProof: String, val paused: Boolean = false,
)

data class HealthDayRow(val day: String, val label: String, val sleep: String, val steps: String)

enum class HealthState { UNAVAILABLE, NEEDS_UPDATE, NOT_CONNECTED, CONNECTED }
enum class PlacesState { OFF, NEEDS_ALWAYS, ON }

data class WeekUi(
    val title: String,
    val areas: List<AreaBar>,
    val areasNote: String,
    val habits: List<HabitRow>,
    val health: HealthState,
    val healthDays: List<HealthDayRow>,
    val healthNote: String = "",
    val places: PlacesState,
    val topPlaces: List<Pair<String, String>>,
)

data class StayRow(val time: String, val name: String?, val people: List<String>, val lat: Double, val lon: Double)

/** Sezione "Corpo e luoghi" nel Racconto di un giorno. */
data class DayMemoryUi(val health: String? = null, val stays: List<StayRow> = emptyList()) {
    val isEmpty get() = health == null && stays.isEmpty()
}

data class QuestionRow(val nome: String, val slots: List<String>, val mine: Boolean)
data class QuestionsUi(val questions: List<QuestionRow>, val people: List<String>)

// =============================================================== Oggi

/** Righe della memoria in Oggi, dentro "Da tenere d'occhio". */
fun LazyListScope.memoryCards(s: MemoryTodayUi?, onMemento: (String) -> Unit, onWeek: () -> Unit) {
    if (s == null) return
    item("memoria-settimana") {
        if (s.weekText.isBlank()) WatchRow("Questa settimana", "Tempo, abitudini, sonno e passi", "Collega Samsung Health e i luoghi", onClick = onWeek)
        else WatchRow("Questa settimana", s.weekText, onClick = onWeek)
    }
    if (s.mementoText != null && s.mementoDay != null) item("memoria-anno") {
        WatchRow(s.mementoLabel ?: "Un anno fa oggi", s.mementoText) { onMemento(s.mementoDay) }
    }
}

// =============================================================== Settimana

@Composable
private fun MemTitle(text: String, top: Int = 30) {
    Text(text, style = Type.section, color = Life.c.text, modifier = Modifier.padding(top = top.dp, bottom = 6.dp))
}

@Composable
private fun MemNote(text: String) {
    Text(text, style = Type.meta, color = Life.c.second, modifier = Modifier.padding(top = 4.dp))
}

private fun memHours(m: Int) = when {
    m <= 0 -> "0"
    m < 60 -> "$m min"
    m % 60 == 0 -> "${m / 60} h"
    else -> "${m / 60} h ${m % 60}"
}

@Composable
private fun MemBar(a: AreaBar, max: Int) {
    val c = Life.c
    Column(Modifier.fillMaxWidth().padding(vertical = 9.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(a.label, style = Type.rowTitle, color = c.text, modifier = Modifier.weight(1f))
            Text(memHours(a.minutes), style = Type.rowTitle, color = c.text)
        }
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(c.fill)) {
            if (a.minutes > 0) Box(
                Modifier.fillMaxWidth((a.minutes.toFloat() / max).coerceIn(0.02f, 1f)).height(8.dp)
                    .clip(RoundedCornerShape(4.dp)).background(c.text)
            )
        }
        Text(
            if (a.lastWeek > 0) "La settimana scorsa ${memHours(a.lastWeek)}." else "La settimana scorsa niente.",
            style = Type.meta, color = c.second, modifier = Modifier.padding(top = 4.dp)
        )
    }
}

@Composable
private fun HabitDots(days: List<HabitDay>, onDay: (HabitDay) -> Unit) {
    val c = Life.c
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        days.forEach { d ->
            Column(
                Modifier.clip(RoundedCornerShape(8.dp)).clickable(enabled = !d.future) { onDay(d) }.padding(vertical = 4.dp, horizontal = 2.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    Modifier.size(16.dp).clip(CircleShape)
                        .background(
                            when {
                                d.state == 1 -> c.text
                                d.future -> c.bg
                                else -> c.fill
                            }
                        )
                        .then(if (d.state == 2) Modifier.border(1.5.dp, c.second, CircleShape) else Modifier)
                )
                Text(d.letter, style = Type.time, color = if (d.future) c.faint else c.second, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

@Composable
fun WeekScreen(
    s: WeekUi,
    onBack: () -> Unit,
    onHabitDay: (habitId: String, day: String, done: Boolean) -> Unit,
    onConnectHealth: () -> Unit,
    onPlaces: () -> Unit,
    onPlacesOff: () -> Unit,
    onQuestions: () -> Unit,
) {
    val c = Life.c
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = side, end = side, top = 8.dp, bottom = 40.dp)) {
        item {
            TextAction("‹ Oggi", onBack)
            ScreenHeader(s.title, "Settimana")
        }

        item {
            MemTitle("Tempo per area")
            MemNote("Stima da quanto ne parli, non da un cronometro.")
        }
        if (s.areas.all { it.minutes == 0 && it.lastWeek == 0 }) item {
            Text(s.areasNote.ifBlank { "Arriva dopo la prima analisi notturna." }, style = Type.text, color = c.second, modifier = Modifier.padding(top = 10.dp))
        } else {
            val max = s.areas.maxOf { maxOf(it.minutes, it.lastWeek) }.coerceAtLeast(1)
            items(s.areas, key = { "a" + it.label }) { MemBar(it, max) }
            if (s.areasNote.isNotBlank()) item { MemNote(s.areasNote) }
        }

        item {
            MemTitle("Abitudini")
            MemNote(
                if (s.habits.isEmpty()) "Quando dici a voce qualcosa come \"da lunedì vado in palestra\", Claude la segue qui con le prove."
                else "Pieno: c'è una prova o l'hai segnato tu. Cerchio: hai detto di averlo saltato. Tocca un giorno per segnarlo."
            )
        }
        items(s.habits, key = { "h" + it.id }) { h ->
            Column(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(h.title, style = Type.rowTitle, color = if (h.paused) c.second else c.text, modifier = Modifier.weight(1f))
                    Text(h.progress, style = Type.meta.copy(fontWeight = FontWeight.Bold), color = if (h.paused) c.second else c.accent)
                }
                if (h.said.isNotBlank()) Text(h.said, style = Type.meta, color = c.second, modifier = Modifier.padding(top = 2.dp, bottom = 10.dp))
                HabitDots(h.days) { d -> onHabitDay(h.id, d.day, d.state != 1) }
                if (h.lastProof.isNotBlank()) Text(h.lastProof, style = Type.meta, color = c.second, modifier = Modifier.padding(top = 8.dp))
            }
            Hairline()
        }

        item { MemTitle("Sonno e passi") }
        when (s.health) {
            HealthState.CONNECTED -> {
                if (s.healthDays.isEmpty()) item { MemNote(s.healthNote.ifBlank { "Ancora nessun dato. Apri Samsung Health una volta per farlo sincronizzare." }) }
                items(s.healthDays, key = { "s" + it.day }) { d ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(d.label, style = Type.text.copy(fontWeight = FontWeight.Medium), color = c.text, modifier = Modifier.weight(1f))
                        Text(d.sleep, style = Type.meta, color = c.text, modifier = Modifier.padding(end = 18.dp))
                        Text(d.steps, style = Type.meta, color = c.second)
                    }
                    Hairline()
                }
                if (s.healthDays.isNotEmpty() && s.healthNote.isNotBlank()) item { MemNote(s.healthNote) }
            }
            HealthState.NOT_CONNECTED -> item {
                Text(
                    "Mostra accanto a ogni giorno quanto hai dormito e quanti passi hai fatto, presi da Samsung Health. Restano sul telefono.",
                    style = Type.text, color = c.second
                )
                SolidButton("Collega Samsung Health", onConnectHealth, modifier = Modifier.padding(top = 14.dp))
                MemNote("Se non vedi i dati: Samsung Health, Impostazioni, Health Connect, consenti la condivisione di sonno e passi.")
            }
            HealthState.NEEDS_UPDATE -> item {
                Text("Serve aggiornare Health Connect dal Play Store.", style = Type.text, color = c.second)
                TextAction("Aggiorna Health Connect", onConnectHealth, modifier = Modifier.padding(top = 6.dp))
            }
            HealthState.UNAVAILABLE -> item {
                Text("Health Connect non è disponibile su questo telefono.", style = Type.text, color = c.second)
            }
        }

        item { MemTitle("Luoghi") }
        when (s.places) {
            PlacesState.OFF -> item {
                Text(
                    "Ricorda dove sei stato e per quanto, così ogni giorno dice anche dove. Usa la posizione di rete ogni 15 minuti, non il GPS: consuma pochissimo. Le posizioni restano sul telefono e i nomi dei posti li dai tu.",
                    style = Type.text, color = c.second
                )
                SolidButton("Attiva i luoghi", onPlaces, modifier = Modifier.padding(top = 14.dp))
            }
            PlacesState.NEEDS_ALWAYS -> item {
                Text(
                    "Manca un permesso: nella pagina che si apre scegli \"Consenti sempre\". Senza, Android non dà la posizione quando l'app è chiusa.",
                    style = Type.text, color = c.second
                )
                SolidButton("Consenti sempre", onPlaces, modifier = Modifier.padding(top = 14.dp))
                TextAction("Disattiva i luoghi", onPlacesOff, color = c.second, modifier = Modifier.padding(top = 8.dp))
            }
            PlacesState.ON -> {
                if (s.topPlaces.isEmpty()) item {
                    MemNote("Attivi. Apri un giorno e dai un nome ai posti dove sei stato: da lì in poi li riconosco da solo.")
                }
                items(s.topPlaces, key = { "p" + it.first }) { (name, time) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                        Text(name, style = Type.text.copy(fontWeight = FontWeight.Medium), color = c.text, modifier = Modifier.weight(1f))
                        Text(time, style = Type.meta, color = c.second)
                    }
                    Hairline()
                }
                item { TextAction("Disattiva e cancella le posizioni", onPlacesOff, color = c.second, modifier = Modifier.padding(top = 10.dp)) }
            }
        }

        item {
            MemTitle("Chiedi a Claude")
            NavRow("Domande salvate", onClick = onQuestions)
            Hairline()
        }
    }
}

// =============================================================== Giorno: corpo e luoghi

/** Da inserire nel Racconto di un giorno. */
fun LazyListScope.dayMemorySection(s: DayMemoryUi?, onPerson: (String) -> Unit, onName: (StayRow) -> Unit) {
    if (s == null || s.isEmpty) return
    item("memoria-giorno-titolo") {
        Text("Corpo e luoghi", style = Type.section, color = Life.c.text, modifier = Modifier.padding(top = 30.dp, bottom = 4.dp))
        s.health?.let { Text(it, style = Type.text, color = Life.c.text, modifier = Modifier.padding(vertical = 6.dp)) }
    }
    items(s.stays, key = { "st" + it.time }) { st ->
        val c = Life.c
        Column(Modifier.fillMaxWidth().clickable { onName(st) }.padding(vertical = 10.dp)) {
            Text(st.time, style = Type.time, color = c.second)
            Text(st.name ?: "Posto senza nome. Tocca per darglielo.", style = Type.body, color = if (st.name == null) c.second else c.text, modifier = Modifier.padding(top = 3.dp))
            if (st.people.isNotEmpty()) Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                st.people.forEach { p -> Chip(p) { onPerson(p) } }
            }
        }
    }
}

/** Finestra per dare un nome a un posto. */
@Composable
fun NamePlaceDialog(current: String?, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    val c = Life.c
    var text by remember { mutableStateOf(current.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.bg,
        title = { Text("Che posto è?", style = Type.section, color = c.text) },
        text = {
            Column {
                MemField(text, "Casa, Ca' Foscari, Rosa…") { text = it }
                MemNote("Il nome resta sul telefono. Lascia vuoto per toglierlo.")
            }
        },
        confirmButton = { TextAction("Salva", { onSave(text) }) },
        dismissButton = { TextAction("Annulla", onDismiss, color = c.second) },
    )
}

@Composable
private fun MemField(value: String, hint: String, onChange: (String) -> Unit) {
    val c = Life.c
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.fill).padding(horizontal = 14.dp, vertical = 13.dp)) {
        if (value.isEmpty()) Text(hint, style = Type.text, color = c.second)
        BasicTextField(
            value = value, onValueChange = onChange, singleLine = true,
            textStyle = Type.text.copy(color = c.text, fontWeight = FontWeight.Medium),
            cursorBrush = SolidColor(c.accent), modifier = Modifier.fillMaxWidth()
        )
    }
}

// =============================================================== Domande salvate

@Composable
fun QuestionsScreen(
    s: QuestionsUi,
    onBack: () -> Unit,
    onAsk: (nome: String, values: Map<String, String>) -> Unit,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
) {
    val c = Life.c
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    val values = remember { mutableStateMapOf<String, String>() }
    var custom by rememberSaveable { mutableStateOf("") }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = side, end = side, top = 8.dp, bottom = 40.dp)) {
        item {
            TextAction("‹ Settimana", onBack)
            ScreenHeader(null, "Domande")
            Text(
                "Tocca una domanda: la copio e apro Claude. Incollala nella chat del progetto, che legge i tuoi dati. Puoi anche scrivere solo il nome della domanda.",
                style = Type.text, color = c.second, modifier = Modifier.padding(top = 8.dp, bottom = 8.dp)
            )
        }
        items(s.questions, key = { it.nome }) { q ->
            Column(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().clickable {
                        if (q.slots.isEmpty()) onAsk(q.nome, emptyMap()) else { values.clear(); open = if (open == q.nome) null else q.nome }
                    }.padding(vertical = 15.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        q.nome.replace(Regex("\\{([a-zà-ù]+)\\}"), "…"),
                        style = Type.text.copy(fontSize = 17.sp, fontWeight = FontWeight.Medium), color = c.text, modifier = Modifier.weight(1f))
                    if (q.mine) TextAction("Togli", { onRemove(q.nome) }, color = c.second, modifier = Modifier.padding(start = 12.dp))
                    else Text("›", style = Type.section, color = c.faint)
                }
                if (open == q.nome) Column(Modifier.padding(bottom = 16.dp)) {
                    q.slots.forEach { slot ->
                        Text(slot.replaceFirstChar { it.uppercase() }, style = Type.meta.copy(fontWeight = FontWeight.Bold), color = c.text, modifier = Modifier.padding(bottom = 6.dp, top = 4.dp))
                        if (slot == "persona" && s.people.isNotEmpty()) {
                            FlowChips(s.people, selected = values[slot]) { values[slot] = it }
                            Spacer(Modifier.height(10.dp))
                        }
                        MemField(values[slot].orEmpty(), if (slot == "persona") "Oppure scrivi un nome" else "Scrivi qui") { values[slot] = it }
                    }
                    SolidButton(
                        "Chiedi a Claude", { onAsk(q.nome, values.toMap()) }, modifier = Modifier.padding(top = 14.dp),
                        enabled = q.slots.all { !values[it].isNullOrBlank() }
                    )
                }
            }
            Hairline()
        }
        item {
            MemTitle("Aggiungi una domanda")
            MemField(custom, "Per esempio: quali locali abbiamo sentito questo mese") { custom = it }
            Row(Modifier.padding(top = 10.dp)) {
                SolidButton("Salva", { onAdd(custom); custom = "" }, enabled = custom.isNotBlank())
            }
            MemNote("Le domande salvate arrivano anche a Claude: nel thread basta scrivere il nome.")
            Spacer(Modifier.width(1.dp))
        }
    }
}
