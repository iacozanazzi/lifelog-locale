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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

// =============================================================== Dati per le schermate

data class TopicUi(val name: String, val done: Boolean)

data class ExamUi(
    val id: String,
    val subject: String,
    /** "Venerdì 15 gennaio, scritto" */
    val whenText: String,
    /** "tra 51 giorni", "domani" */
    val countdown: String,
    /** "Oggi: Marchi di forma", o null se non c'è un piano. */
    val today: String?,
    /** I giorni dopo oggi: data breve e cosa fare. */
    val nextDays: List<Entry>,
    val topics: List<TopicUi>,
    /** "18 domande, 3 sbagliate negli ultimi 7 giorni" */
    val reviewLine: String,
    val manual: Boolean,
)

data class UniUi(
    val configured: Boolean,
    val due: Int,
    /** "Diritto commerciale e Statistica, circa 4 minuti" */
    val dueLine: String,
    val totalQuestions: Int,
    val exams: List<ExamUi>,
    val subjects: List<String>,
)

data class ReviewCardUi(
    val subject: String,
    val topic: String,
    val question: String,
    val answer: String,
    val source: String,
    /** "3 di 12" */
    val position: String,
)

// =============================================================== Università

@Composable
fun UniScreen(
    s: UniUi,
    onBack: () -> Unit,
    onReview: () -> Unit,
    onTopic: (exam: String, topic: String, done: Boolean) -> Unit,
    onAddExam: (subject: String, date: String) -> Unit,
    onRemoveExam: (String) -> Unit,
    onSetup: () -> Unit = {},
    backLabel: String = "‹ Oggi",
) {
    val c = Life.c
    var adding by rememberSaveable { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = side, end = side, top = 8.dp, bottom = 40.dp)) {
        item {
            TextAction(backLabel, onBack)
            ScreenHeader(null, "Università")
        }
        if (!s.configured) {
            item {
                Text("Domande di ripasso dalle lezioni registrate, il conto alla rovescia per gli esami e un piano di studio che si aggiusta da solo.", style = Type.lyric, color = c.text, modifier = Modifier.padding(top = 18.dp))
                TextAction("Collega in Opzioni", onSetup, modifier = Modifier.padding(top = 12.dp))
            }
            return@LazyColumn
        }

        // ---- Ripasso
        item {
            Column(
                Modifier.padding(top = 18.dp).fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(c.fill).padding(18.dp)
            ) {
                Text("Ripasso", style = Type.meta.copy(fontWeight = FontWeight.Bold), color = c.text)
                Text(
                    when {
                        s.totalQuestions == 0 -> "Ancora nessuna domanda."
                        s.due == 0 -> "Per oggi hai finito."
                        s.due == 1 -> "Una domanda da ripassare."
                        else -> "${s.due} domande da ripassare."
                    },
                    style = Type.section.copy(fontSize = 21.sp, lineHeight = 26.sp),
                    color = c.text, modifier = Modifier.padding(top = 6.dp)
                )
                Text(
                    when {
                        s.totalQuestions == 0 -> "Dopo una lezione registrata, Claude scrive di notte le domande più importanti, solo da quello che è stato detto."
                        s.due == 0 -> "Le prossime tornano quando stai per dimenticarle."
                        else -> s.dueLine
                    },
                    style = Type.meta, color = c.second, modifier = Modifier.padding(top = 4.dp)
                )
                if (s.due > 0) SolidButton("Inizia il ripasso", onReview, modifier = Modifier.padding(top = 16.dp))
            }
        }

        // ---- Esami
        item { SectionTitle("Esami", if (s.exams.isEmpty()) "" else if (s.exams.size == 1) "1 in arrivo" else "${s.exams.size} in arrivo") }
        if (s.exams.isEmpty()) item {
            Text("Nessun esame in arrivo. Aggiungilo qui sotto, oppure dillo a voce con il giorno e Claude lo trova.", style = Type.text, color = c.second, modifier = Modifier.padding(vertical = 10.dp))
        }
        s.exams.forEach { e ->
            item {
                Column(Modifier.padding(top = 14.dp)) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(e.subject, style = Type.rowTitle, color = c.text, modifier = Modifier.weight(1f))
                        Text(e.countdown, style = Type.meta.copy(fontWeight = FontWeight.Bold), color = c.accent)
                    }
                    Text(e.whenText, style = Type.meta, color = c.second, modifier = Modifier.padding(top = 2.dp))
                    e.today?.let { Text(it, style = Type.body, color = c.text, modifier = Modifier.padding(top = 10.dp)) }
                    if (e.reviewLine.isNotBlank()) Text(e.reviewLine, style = Type.meta, color = c.second, modifier = Modifier.padding(top = 4.dp))
                }
            }
            if (e.nextDays.isNotEmpty()) items(e.nextDays) { d -> EntryRow(d, c.text) }
            if (e.topics.isNotEmpty()) {
                item {
                    Text("Programma", style = Type.meta.copy(fontWeight = FontWeight.Bold), color = c.text, modifier = Modifier.padding(top = 12.dp))
                }
                items(e.topics) { t ->
                    Row(Modifier.fillMaxWidth().clickable { onTopic(e.id, t.name, !t.done) }.padding(vertical = 8.dp)) {
                        Box(Modifier.padding(end = 14.dp)) { Check(t.done) }
                        Text(
                            t.name, style = Type.text.copy(textDecoration = if (t.done) TextDecoration.LineThrough else null),
                            color = if (t.done) c.faint else c.text, modifier = Modifier.padding(top = 1.dp)
                        )
                    }
                }
            } else item {
                Text(
                    "Il programma arriva dalle lezioni di questa materia, o se lo dici a voce (\"il programma è…\"). Intanto ripassa le domande.",
                    style = Type.meta, color = c.second, modifier = Modifier.padding(top = 8.dp)
                )
            }
            if (e.manual) item {
                TextAction("Togli questo esame", { onRemoveExam(e.id) }, color = c.second, modifier = Modifier.padding(top = 4.dp))
            }
            item { Hairline(Modifier.padding(top = 10.dp)) }
        }
        item {
            if (adding) AddExamForm(s.subjects, onCancel = { adding = false }, onSave = { m, d -> onAddExam(m, d); adding = false })
            else TextAction("Aggiungi un esame", { adding = true }, modifier = Modifier.padding(top = 16.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddExamForm(subjects: List<String>, onCancel: () -> Unit, onSave: (String, String) -> Unit) {
    val c = Life.c
    var subject by rememberSaveable { mutableStateOf("") }
    var date by rememberSaveable { mutableStateOf<String?>(null) }
    var picking by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.padding(top = 18.dp)) {
        Text("Nuovo esame", style = Type.section, color = c.text)
        Text("Materia", style = Type.meta, color = c.second, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.fill).padding(horizontal = 14.dp, vertical = 13.dp)) {
            if (subject.isEmpty()) Text("Per esempio Diritto commerciale", style = Type.text, color = c.second)
            BasicTextField(
                value = subject, onValueChange = { subject = it }, singleLine = true,
                textStyle = Type.text.copy(color = c.text), cursorBrush = SolidColor(c.accent), modifier = Modifier.fillMaxWidth()
            )
        }
        val suggestions = subjects.filter { it != subject && (subject.isBlank() || it.contains(subject.trim(), ignoreCase = true)) }.take(6)
        if (suggestions.isNotEmpty()) Box(Modifier.padding(top = 10.dp)) { FlowChips(suggestions) { subject = it } }
        Text("Giorno", style = Type.meta, color = c.second, modifier = Modifier.padding(top = 16.dp, bottom = 6.dp))
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.fill).clickable { picking = true }
                .padding(horizontal = 14.dp, vertical = 13.dp)
        ) {
            Text(date?.let { longDate(LocalDate.parse(it)) } ?: "Scegli il giorno", style = Type.text, color = if (date == null) c.second else c.text)
        }
        Row(Modifier.padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
            SolidButton("Salva", { onSave(subject.trim(), date!!) }, enabled = subject.isNotBlank() && date != null)
            TextAction("Annulla", onCancel, color = c.second)
        }
    }
    if (picking) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = date?.let { LocalDate.parse(it).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() }
        )
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextAction("Fatto", {
                    state.selectedDateMillis?.let { date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString() }
                    picking = false
                }, modifier = Modifier.padding(horizontal = 16.dp))
            },
            dismissButton = { TextAction("Annulla", { picking = false }, color = c.second, modifier = Modifier.padding(horizontal = 16.dp)) },
        ) { DatePicker(state = state, showModeToggle = false) }
    }
}

private fun longDate(d: LocalDate) =
    d.format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.ITALIAN)).replaceFirstChar { it.uppercase() }

// =============================================================== Ripasso

/**
 * Una domanda alla volta. Prima ci pensi, poi guardi la risposta e dici
 * onestamente se la sapevi: è l'unico modo perché gli intervalli funzionino.
 */
@Composable
fun ReviewScreen(
    card: ReviewCardUi?,
    revealed: Boolean,
    finishedText: String,
    onReveal: () -> Unit,
    onAnswer: (knew: Boolean) -> Unit,
    onDiscard: () -> Unit,
    onBack: () -> Unit,
) {
    val c = Life.c
    Column(Modifier.fillMaxSize().padding(horizontal = side).padding(top = 8.dp, bottom = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextAction("‹ Università", onBack, modifier = Modifier.weight(1f))
            card?.let { Text(it.position, style = Type.meta, color = c.second) }
        }
        if (card == null) {
            Spacer(Modifier.height(40.dp))
            Text("Finito.", style = Type.largeTitle, color = c.text)
            Text(finishedText, style = Type.body, color = c.second, modifier = Modifier.padding(top = 12.dp))
            SolidButton("Torna all'università", onBack, modifier = Modifier.padding(top = 28.dp))
            return@Column
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Text(
                if (card.topic.isNotBlank()) "${card.subject}, ${card.topic}" else card.subject,
                style = Type.meta, color = c.second, modifier = Modifier.padding(top = 24.dp)
            )
            Text(card.question, style = Type.lyric, color = c.text, modifier = Modifier.padding(top = 8.dp))
            if (revealed) {
                Text(card.answer, style = Type.body, color = c.text, modifier = Modifier.padding(top = 28.dp))
                if (card.source.isNotBlank()) Text(card.source, style = Type.meta, color = c.second, modifier = Modifier.padding(top = 10.dp))
                TextAction("Domanda sbagliata o inutile? Scartala", onDiscard, color = c.second, modifier = Modifier.padding(top = 18.dp))
            }
        }
        if (!revealed) {
            SolidButton("Mostra la risposta", onReveal, modifier = Modifier.fillMaxWidth())
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    Modifier.weight(1f).clip(RoundedCornerShape(50)).background(c.fill).clickable { onAnswer(false) }.padding(vertical = 13.dp),
                    contentAlignment = Alignment.Center
                ) { Text("Non la sapevo", style = Type.rowTitle, color = c.text) }
                SolidButton("La sapevo", { onAnswer(true) }, modifier = Modifier.weight(1f))
            }
        }
    }
}
