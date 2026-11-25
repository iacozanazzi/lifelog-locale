package it.iaco.lifelog.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

// =============================================================== Stati (dati puri)

enum class RecState { OFF, LISTENING, STALLED }

data class TodayUi(
    val dateLabel: String,
    val ready: Boolean,
    val state: RecState,
    val stalledSince: String?,
    val level: Float,
    val speaking: Boolean,
    val speechMinutes: Long,
    val pending: Int,
    val pendingMinutes: Long,
    val transcribeStatus: String,
    val canTranscribeNow: Boolean,
    /** Ultime frasi trascritte, dalla più vecchia alla più recente. */
    val lines: List<String>,
    val lastTime: String?,
    val crash: String?,
    /** Il riassunto di ieri fatto da Claude, mostrato in cima la mattina. */
    val brief: Brief? = null,
    /** Agenda di oggi, scadenze, preparazione per domani, date proposte e bozze (BriefingScreens.kt). */
    val briefing: BriefingUi? = null,
)

data class Brief(val text: String, val todos: List<TodoItem>, val more: Int = 0)

data class Moment(val time: String, val text: String, val gap: Boolean = false)

data class DayRow(val day: String, val title: String, val meta: String, val spans: List<Span>, val summary: String = "", val badge: String = "")

data class DayUi(
    val title: String,
    val subtitle: String,
    val spans: List<Span>,
    val sections: List<Pair<String, List<Moment>>>,
    val pending: Int,
    /** Racconto di Claude per questo giorno, se c'è. */
    val story: YesterdayUi? = null,
    val people: List<String> = emptyList(),
    val todos: List<TodoItem> = emptyList(),
)

data class SearchHit(val day: String, val where: String, val text: String)

data class SettingsUi(
    val onlyCharging: Boolean,
    val waitCharge: Boolean,
    val maxTemp: Float,
    val battery: String,
    val driveConfigured: Boolean,
    val driveStatus: String,
    val version: String,
    val engines: List<EngineRow> = emptyList(),
    val dataConfigured: Boolean = false,
    val dataRepo: String = "iacozanazzi/lifelog-dati",
    val dataStatus: String = "",
)

/** Un motore di trascrizione nella lista delle impostazioni. */
data class EngineRow(val key: String, val label: String, val detail: String, val state: String, val selected: Boolean, val ready: Boolean)

data class CompareRow(val time: String, val results: List<Pair<String, String>>)

data class CompareUi(val running: Boolean, val status: String, val rows: List<CompareRow>)

enum class DownloadState { IDLE, WAITING_WIFI, RUNNING, FAILED, DONE }

data class SetupUi(
    val mic: Boolean,
    val notif: Boolean,
    val battery: Boolean,
    val download: DownloadState,
    val downloadPct: Int,
    val downloadStage: String,
    val downloadError: String,
)

/** Margine laterale di tutte le schermate. */
val side = 22.dp

// =============================================================== Oggi

@Composable
fun TodayScreen(
    s: TodayUi,
    onToggle: () -> Unit,
    onSetup: () -> Unit,
    onOpenToday: () -> Unit,
    onTranscribeNow: () -> Unit,
    onShareCrash: () -> Unit,
    onDismissCrash: () -> Unit,
    onSettings: () -> Unit = {},
    onToggleTodo: (String, Boolean) -> Unit = { _, _ -> },
    onOpenYesterday: () -> Unit = {},
    briefingActions: BriefingActions = BriefingActions(),
    /** Schede delle funzioni (Seral e università, ecc.), sotto "Da ieri". */
    cards: @Composable () -> Unit = {},
) {
    val c = Life.c
    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(start = side, end = side, top = 16.dp, bottom = 24.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.Bottom) {
                    ScreenHeader(s.dateLabel, "Oggi", Modifier.weight(1f))
                    RoundIcon(onSettings, Modifier.padding(bottom = 8.dp))
                }
            }

            val top = s.briefing?.takeIf { it.hasTop }
            if (s.brief != null || top != null) {
                item {
                    Column(
                        Modifier.padding(top = 18.dp).fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(c.fill)
                            .then(if (top == null) Modifier.clickable(onClick = onOpenYesterday) else Modifier).padding(18.dp)
                    ) {
                        top?.let { BriefingTop(it, briefingActions, hasYesterday = s.brief != null) }
                        val b = s.brief ?: return@Column
                        Text("Da ieri", style = Type.meta.copy(fontWeight = FontWeight.Bold), color = c.text)
                        Text(b.text, style = Type.section.copy(fontSize = 21.sp, lineHeight = 26.sp), color = c.text, modifier = Modifier.padding(top = 6.dp))
                        b.todos.forEach { t ->
                            Row(
                                Modifier.padding(top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { onToggleTodo(t.id, !t.done) },
                                verticalAlignment = Alignment.Top
                            ) {
                                Check(t.done, onCard = true)
                                Text(
                                    t.text, style = Type.text.copy(textDecoration = if (t.done) androidx.compose.ui.text.style.TextDecoration.LineThrough else null),
                                    color = if (t.done) c.faint else c.text, modifier = Modifier.padding(start = 12.dp, top = 1.dp)
                                )
                            }
                        }
                        Text(
                            if (b.more > 0) "Leggi la giornata e altri ${b.more} impegni" else "Leggi la giornata",
                            style = Type.meta.copy(fontWeight = FontWeight.Bold), color = c.accent,
                            modifier = Modifier.padding(top = 14.dp).clip(RoundedCornerShape(6.dp)).clickable(onClick = onOpenYesterday)
                        )
                    }
                }
            }
            briefingCards(s.briefing, briefingActions)

            item { cards() }

            s.crash?.let {
                item {
                    Column(Modifier.padding(top = 18.dp)) {
                        Text("L'app si è chiusa per un errore. Mandamelo per sistemarlo.", style = Type.text, color = c.text)
                        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                            TextAction("Condividi l'errore", onShareCrash)
                            TextAction("Ignora", onDismissCrash, color = c.second)
                        }
                    }
                }
            }

            if (s.state == RecState.STALLED) item {
                Column(Modifier.padding(top = 22.dp)) {
                    Text("La registrazione si è fermata alle ${s.stalledSince}.", style = Type.rowTitle, color = c.text)
                    Text("Android l'ha chiusa. Riprendi con il pulsante qui sotto.", style = Type.meta, color = c.second, modifier = Modifier.padding(top = 2.dp))
                }
            }

            item {
                Text(
                    if (s.speechMinutes > 0) "Adesso, ${durationText(s.speechMinutes)} di voce" else "Adesso",
                    style = Type.meta, color = c.second, modifier = Modifier.padding(top = 26.dp, bottom = 10.dp)
                )
            }

            if (s.lines.isEmpty()) {
                item {
                    Text(
                        when {
                            !s.ready -> "Completa la configurazione e Lifelog comincerà ad ascoltare."
                            s.pending > 0 -> "Le tue parole compariranno qui appena trascritte."
                            s.state == RecState.LISTENING -> "Quello che dici oggi comparirà qui."
                            else -> "Premi play e Lifelog ascolta la tua giornata."
                        },
                        style = Type.lyric, color = c.faint
                    )
                    if (s.ready && s.state == RecState.OFF) SolidButton("Inizia ad ascoltare", onToggle, modifier = Modifier.padding(top = 22.dp))
                    if (!s.ready) SolidButton("Completa la configurazione", onSetup, modifier = Modifier.padding(top = 22.dp))
                }
            } else {
                val n = s.lines.size
                items(n) { i ->
                    val age = n - 1 - i // 0 = più recente
                    val color = when (age) {
                        0 -> c.text
                        1 -> c.text.copy(alpha = 0.72f)
                        2 -> c.second
                        else -> c.faint
                    }
                    Text(
                        s.lines[i], style = Type.lyric, color = color,
                        modifier = Modifier.padding(bottom = 14.dp).clickable(onClick = onOpenToday)
                    )
                }
                item {
                    Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        s.lastTime?.let { Text("Ultima frase alle $it", style = Type.meta, color = c.second, modifier = Modifier.weight(1f)) }
                        TextAction("Tutta la giornata", onOpenToday)
                    }
                }
            }
        }

        // Barra di controllo, come il mini player di un'app musicale.
        Row(
            Modifier
                .padding(horizontal = 12.dp)
                .padding(bottom = 10.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(c.fill)
                .clickable(onClick = if (s.ready) onOpenToday else onSetup)
                .padding(start = 16.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (s.ready && s.state == RecState.LISTENING) {
                LevelBars(s.level, s.speaking)
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    when {
                        !s.ready -> "Configurazione da completare"
                        s.state == RecState.STALLED -> "Fermo dalle ${s.stalledSince}"
                        s.state == RecState.LISTENING -> if (s.speaking) "Qualcuno parla" else "In ascolto"
                        else -> "In pausa"
                    },
                    style = Type.rowTitle, color = if (s.state == RecState.STALLED) c.accent else c.text, maxLines = 1
                )
                val sub = when {
                    !s.ready -> "Mancano pochi passi"
                    s.pending > 0 -> "${s.pendingMinutes.coerceAtLeast(1)} min da trascrivere. " + s.transcribeStatus.removePrefix("In attesa: ")
                    else -> "Tutto trascritto"
                }
                Text(sub, style = Type.meta, color = c.second, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (s.ready && s.canTranscribeNow) TextAction("Trascrivi adesso", onTranscribeNow)
            }
            if (s.ready) {
                Spacer(Modifier.width(12.dp))
                PlayPause(listening = s.state == RecState.LISTENING, onClick = onToggle)
            }
        }
    }
}

/** Cerchio di spunta, condiviso da impegni e riassunto. */
@Composable
fun Check(done: Boolean, onCard: Boolean = false) {
    val c = Life.c
    Box(
        Modifier.size(24.dp).clip(CircleShape)
            .background(if (done) c.accent else if (onCard) c.bg else c.fill)
            .then(if (!done && onCard) Modifier.border(1.5.dp, c.faint, CircleShape) else Modifier),
        contentAlignment = Alignment.Center
    ) { if (done) Text("✓", style = Type.time, color = c.onText) }
}

/** Pulsante tondo in alto a destra per le Opzioni. */
@Composable
private fun RoundIcon(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = Life.c
    Box(
        modifier.size(38.dp).clip(CircleShape).background(c.fill).clickable(onClick = onClick)
            .semantics { contentDescription = "Opzioni" },
        contentAlignment = Alignment.Center
    ) {
        androidx.compose.foundation.Image(
            androidx.compose.ui.res.painterResource(it.iaco.lifelog.R.drawable.ic_tab_settings), null,
            Modifier.size(20.dp), colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(c.text)
        )
    }
}

/** Etichetta tonda (persone, filtri). */
@Composable
fun Chip(label: String, selected: Boolean = false, onClick: () -> Unit = {}) {
    val c = Life.c
    Box(
        Modifier.clip(RoundedCornerShape(50)).background(if (selected) c.text else c.fill).clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) { Text(label, style = Type.meta.copy(fontWeight = FontWeight.Bold), color = if (selected) c.bg else c.text) }
}

private fun durationText(m: Long) = if (m < 60) "$m min" else "${m / 60} h ${m % 60} min"

/** Tre barre che si muovono con la voce, come l'indicatore "in riproduzione". */
@Composable
private fun LevelBars(level: Float, speaking: Boolean) {
    val c = Life.c
    val l = if (speaking) level.coerceIn(0.15f, 1f) else 0.12f
    val h1 by androidx.compose.animation.core.animateFloatAsState(0.35f + l * 0.65f, androidx.compose.animation.core.tween(140), label = "b1")
    val h2 by androidx.compose.animation.core.animateFloatAsState(0.25f + l * 0.75f * 0.7f + (if (speaking) 0.2f else 0f), androidx.compose.animation.core.tween(200), label = "b2")
    val h3 by androidx.compose.animation.core.animateFloatAsState(0.3f + l * 0.55f, androidx.compose.animation.core.tween(260), label = "b3")
    Row(Modifier.height(22.dp), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.Bottom) {
        listOf(h1, h2.coerceAtMost(1f), h3).forEach { h ->
            Box(Modifier.width(4.dp).height(22.dp * h).clip(RoundedCornerShape(2.dp)).background(c.accent))
        }
    }
}

@Composable
private fun PlayPause(listening: Boolean, onClick: () -> Unit) {
    val c = Life.c
    val ink = c.bg
    Box(
        Modifier.size(46.dp).clip(CircleShape).background(c.text).clickable(onClick = onClick)
            .then(Modifier.semantics { contentDescription = if (listening) "Metti in pausa la registrazione" else "Avvia la registrazione" }),
        contentAlignment = Alignment.Center
    ) {
        androidx.compose.foundation.Canvas(Modifier.size(18.dp)) {
            if (listening) {
                val w = size.width * 0.26f
                drawRoundRect(ink, topLeft = androidx.compose.ui.geometry.Offset(size.width * 0.14f, 0f), size = androidx.compose.ui.geometry.Size(w, size.height), cornerRadius = androidx.compose.ui.geometry.CornerRadius(2f, 2f))
                drawRoundRect(ink, topLeft = androidx.compose.ui.geometry.Offset(size.width * 0.60f, 0f), size = androidx.compose.ui.geometry.Size(w, size.height), cornerRadius = androidx.compose.ui.geometry.CornerRadius(2f, 2f))
            } else {
                val p = androidx.compose.ui.graphics.Path().apply {
                    moveTo(size.width * 0.18f, 0f); lineTo(size.width * 0.98f, size.height / 2); lineTo(size.width * 0.18f, size.height); close()
                }
                drawPath(p, ink)
            }
        }
    }
}

// =============================================================== Giorni

@Composable
fun DaysScreen(days: List<DayRow>, onDay: (String) -> Unit) {
    val c = Life.c
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = side, end = side, top = 16.dp, bottom = 24.dp)) {
        item { ScreenHeader(null, "Diario"); Spacer(Modifier.height(8.dp)) }
        if (days.isEmpty()) item {
            Text("Ancora nessun giorno registrato. Premi play in Oggi.", style = Type.body, color = c.second, modifier = Modifier.padding(top = 12.dp))
        }
        items(days, key = { it.day }) { d ->
            Column(Modifier.fillMaxWidth().clickable { onDay(d.day) }.padding(vertical = 16.dp)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(d.title, style = Type.section, color = c.text, modifier = Modifier.weight(1f))
                    Text(d.meta, style = Type.meta, color = c.second)
                }
                if (d.summary.isNotBlank()) Text(d.summary, style = Type.text, color = c.text, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                if (d.badge.isNotBlank()) Text(d.badge, style = Type.meta.copy(fontWeight = FontWeight.Bold), color = c.accent, modifier = Modifier.padding(top = 4.dp))
                Spacer(Modifier.height(12.dp))
                DayLine(d.spans, height = 6.dp)
            }
            Hairline()
        }
    }
}

@Composable
fun SearchScreen(
    query: String,
    hits: List<SearchHit>,
    onQuery: (String) -> Unit,
    onDay: (String) -> Unit,
    people: List<String> = emptyList(),
) {
    val c = Life.c
    val searching = query.trim().length >= 2
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = side, end = side, top = 16.dp, bottom = 24.dp)) {
        item {
            ScreenHeader(null, "Cerca")
            Spacer(Modifier.height(14.dp))
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.fill).padding(horizontal = 14.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) Text("Parole, nomi, posti", style = Type.text.copy(fontWeight = FontWeight.Medium), color = c.second)
                    BasicTextField(
                        value = query, onValueChange = onQuery, singleLine = true,
                        textStyle = Type.text.copy(color = c.text, fontWeight = FontWeight.Medium),
                        cursorBrush = SolidColor(c.accent), modifier = Modifier.fillMaxWidth()
                    )
                }
                if (query.isNotEmpty()) Text("Annulla", style = Type.meta.copy(fontWeight = FontWeight.Bold), color = c.accent, modifier = Modifier.padding(start = 10.dp).clickable { onQuery("") })
            }
            Spacer(Modifier.height(10.dp))
            when {
                searching -> Text(
                    when (hits.size) { 0 -> "Nessuna frase con \"${query.trim()}\"."; 1 -> "1 frase"; else -> "${hits.size} frasi" },
                    style = Type.meta, color = c.second
                )
                people.isNotEmpty() -> {
                    Text("Persone", style = Type.section, color = c.text, modifier = Modifier.padding(top = 18.dp, bottom = 10.dp))
                    FlowChips(people) { onQuery(it) }
                }
                else -> Text("Cerca in tutto quello che hai detto.", style = Type.meta, color = c.second)
            }
        }
        if (searching) items(hits) { h ->
            Column(Modifier.fillMaxWidth().clickable { onDay(h.day) }.padding(vertical = 14.dp)) {
                Text(h.where, style = Type.time, color = c.second)
                Spacer(Modifier.height(3.dp))
                Text(highlight(h.text, query.trim(), c.accent), style = Type.body, color = c.text, maxLines = 4, overflow = TextOverflow.Ellipsis)
            }
            Hairline()
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun FlowChips(labels: List<String>, selected: String? = null, onClick: (String) -> Unit) {
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)
    ) { labels.forEach { l -> Chip(l, l == selected) { onClick(l) } } }
}

// =============================================================== Ieri

enum class AnState { NOT_CONFIGURED, MISSING, EMPTY, READY }

data class Entry(val time: String, val text: String, val kind: String = "")

data class YesterdayUi(
    val dateLabel: String,
    val state: AnState,
    val note: String,
    val giornata: String = "",
    val sections: List<Pair<String, List<Entry>>> = emptyList(),
    val tomorrow: List<String> = emptyList(),
    val verify: List<Entry> = emptyList(),
)

@Composable
fun YesterdayScreen(s: YesterdayUi, onSetup: () -> Unit, onRefresh: () -> Unit, onOpenDay: () -> Unit = {}) {
    val c = Life.c
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = side, end = side, top = 16.dp, bottom = 40.dp)) {
        item { ScreenHeader(s.dateLabel, "Ieri") }
        when (s.state) {
            AnState.NOT_CONFIGURED -> item {
                Text("Ogni notte Claude legge la giornata e ti lascia qui il diario, le decisioni e gli impegni.", style = Type.lyric, color = c.text, modifier = Modifier.padding(top = 18.dp))
                Text("Per cominciare collega il repository dei dati in Opzioni.", style = Type.text, color = c.second, modifier = Modifier.padding(top = 14.dp))
                TextAction("Apri Opzioni", onSetup, modifier = Modifier.padding(top = 8.dp))
            }
            AnState.MISSING -> item {
                Text("L'analisi di stanotte non è arrivata.", style = Type.lyric, color = c.text, modifier = Modifier.padding(top = 18.dp))
                if (s.note.isNotBlank()) Text(s.note, style = Type.meta, color = c.second, modifier = Modifier.padding(top = 12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp), modifier = Modifier.padding(top = 8.dp)) {
                    TextAction("Controlla di nuovo", onRefresh)
                    TextAction("Leggi la giornata", onOpenDay)
                }
            }
            AnState.EMPTY -> item {
                Text("Ieri hai parlato poco: niente da raccontare.", style = Type.lyric, color = c.second, modifier = Modifier.padding(top = 18.dp))
            }
            AnState.READY -> {
                item {
                    Text(s.giornata, style = Type.lyric, color = c.text, modifier = Modifier.padding(top = 18.dp))
                    if (s.note.isNotBlank()) Text(s.note, style = Type.meta, color = c.second, modifier = Modifier.padding(top = 10.dp))
                }
                s.sections.filter { it.second.isNotEmpty() }.forEach { (title, entries) ->
                    item { Text(title, style = Type.section, color = c.text, modifier = Modifier.padding(top = 32.dp, bottom = 4.dp)) }
                    items(entries) { e -> EntryRow(e, c.text) }
                }
                if (s.tomorrow.isNotEmpty()) {
                    item { Text("Per oggi", style = Type.section, color = c.text, modifier = Modifier.padding(top = 32.dp, bottom = 4.dp)) }
                    items(s.tomorrow.withIndex().toList()) { (i, t) -> EntryRow(Entry("${i + 1}", t), c.text) }
                }
                if (s.verify.isNotEmpty()) {
                    item {
                        Text("Da verificare", style = Type.section, color = c.text, modifier = Modifier.padding(top = 32.dp))
                        Text("Parole che la trascrizione potrebbe aver capito male.", style = Type.meta, color = c.second, modifier = Modifier.padding(top = 2.dp, bottom = 4.dp))
                    }
                    items(s.verify) { e -> EntryRow(e, c.second) }
                }
                item { TextAction("Leggi tutta la giornata", onOpenDay, modifier = Modifier.padding(top = 28.dp)) }
            }
        }
    }
}

@Composable
fun EntryRow(e: Entry, color: Color) {
    val c = Life.c
    Row(Modifier.fillMaxWidth().padding(vertical = 9.dp)) {
        Text(e.time, style = Type.time, color = c.second, modifier = Modifier.width(52.dp).padding(top = 3.dp))
        Column(Modifier.weight(1f)) {
            Text(e.text, style = Type.text, color = color)
            if (e.kind.isNotBlank()) Text(e.kind, style = Type.meta, color = c.accent, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

// =============================================================== Impegni

data class TodoItem(val id: String, val text: String, val meta: String, val done: Boolean, val age: String = "", val person: String = "")

data class TodoUi(
    val configured: Boolean,
    val mine: List<TodoItem>,
    val theirs: List<TodoItem>,
    val note: String,
    val people: List<String> = emptyList(),
)

@Composable
fun TodoScreen(s: TodoUi, onToggle: (String, Boolean) -> Unit, onSetup: () -> Unit) {
    val c = Life.c
    var who by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf<String?>(null) }
    fun keep(t: TodoItem) = who == null || t.person == who
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = side, end = side, top = 16.dp, bottom = 40.dp)) {
        item { ScreenHeader(null, "Impegni") }
        if (!s.configured) item {
            Text("Le cose che prometti, e quelle che ti promettono, raccolte da Claude dalle tue conversazioni.", style = Type.lyric, color = c.text, modifier = Modifier.padding(top = 18.dp))
            TextAction("Collega in Opzioni", onSetup, modifier = Modifier.padding(top = 12.dp))
        } else {
            if (s.note.isNotBlank()) item { Text(s.note, style = Type.meta, color = c.second, modifier = Modifier.padding(top = 6.dp)) }
            if (s.people.isNotEmpty()) item {
                Row(Modifier.padding(top = 14.dp).horizontalScroll(androidx.compose.foundation.rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("Tutti", who == null) { who = null }
                    s.people.forEach { p -> Chip(p, who == p) { who = if (who == p) null else p } }
                }
            }
            listOf("Devo io" to s.mine.filter(::keep), "Mi devono" to s.theirs.filter(::keep)).forEach { (title, list) ->
                item {
                    Row(Modifier.fillMaxWidth().padding(top = 30.dp, bottom = 4.dp), verticalAlignment = Alignment.Bottom) {
                        Text(title, style = Type.section, color = c.text, modifier = Modifier.weight(1f))
                        val open = list.count { !it.done }
                        Text(if (open == 1) "1 aperto" else "$open aperti", style = Type.meta, color = c.second)
                    }
                }
                if (list.isEmpty()) item { Text("Niente.", style = Type.text, color = c.second, modifier = Modifier.padding(vertical = 10.dp)) }
                items(list, key = { title + it.id }) { t ->
                    TodoRow(t) { onToggle(t.id, !t.done) }
                    Hairline()
                }
            }
        }
    }
}

@Composable
private fun TodoRow(t: TodoItem, onClick: () -> Unit) {
    val c = Life.c
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 14.dp)) {
        Box(Modifier.padding(top = 1.dp, end = 14.dp)) { Check(t.done) }
        Column(Modifier.weight(1f)) {
            Text(
                t.text, style = Type.text.copy(textDecoration = if (t.done) androidx.compose.ui.text.style.TextDecoration.LineThrough else null),
                color = if (t.done) c.faint else c.text
            )
            Text(t.meta, style = Type.meta, color = c.second, modifier = Modifier.padding(top = 3.dp))
            if (t.age.isNotBlank() && !t.done) Text(t.age, style = Type.meta, color = c.accent, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

private fun highlight(text: String, q: String, color: Color) = buildAnnotatedString {
    if (q.isEmpty()) { append(text); return@buildAnnotatedString }
    var i = 0
    val lower = text.lowercase()
    val ql = q.lowercase()
    while (i < text.length) {
        val j = lower.indexOf(ql, i)
        if (j < 0) { append(text.substring(i)); break }
        append(text.substring(i, j))
        withStyle(SpanStyle(color = color, fontWeight = FontWeight.Bold)) { append(text.substring(j, j + q.length)) }
        i = j + q.length
    }
}

// =============================================================== Giorno

@Composable
fun DayScreen(
    s: DayUi,
    onBack: () -> Unit,
    onShare: () -> Unit,
    onPerson: (String) -> Unit = {},
    onToggleTodo: (String, Boolean) -> Unit = { _, _ -> },
    startOnStory: Boolean = true,
    /** Apre la schermata di un'area ("seral", "uni") dal titolo della sua sezione. */
    onArea: (String) -> Unit = {},
) {
    val c = Life.c
    var story by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(startOnStory && s.story != null) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = side, end = side, top = 8.dp, bottom = 40.dp)) {
        item {
            TextAction("‹ Diario", onBack)
            Text(s.subtitle, style = Type.meta, color = c.second, modifier = Modifier.padding(top = 8.dp))
            Text(s.title, style = Type.largeTitle, color = c.text)
            if (s.story != null) Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("Racconto", story) { story = true }
                Chip("Trascrizione", !story) { story = false }
            }
        }
        if (story && s.story != null) {
            val st = s.story
            item {
                Text(st.giornata, style = Type.lyric, color = c.text, modifier = Modifier.padding(top = 16.dp))
                if (st.note.isNotBlank()) Text(st.note, style = Type.meta, color = c.second, modifier = Modifier.padding(top = 10.dp))
            }
            if (s.people.isNotEmpty()) item {
                Text("Persone", style = Type.section, color = c.text, modifier = Modifier.padding(top = 30.dp, bottom = 10.dp))
                FlowChips(s.people) { onPerson(it) }
            }
            st.sections.filter { it.second.isNotEmpty() }.forEach { (title, entries) ->
                item {
                    val area = areaOfSection(title)
                    if (area == null) Text(title, style = Type.section, color = c.text, modifier = Modifier.padding(top = 30.dp, bottom = 4.dp))
                    else Row(
                        Modifier.fillMaxWidth().padding(top = 30.dp, bottom = 4.dp).clickable { onArea(area) },
                        verticalAlignment = Alignment.Bottom
                    ) {
                        Text(title, style = Type.section, color = c.text, modifier = Modifier.weight(1f))
                        Text(if (area == "seral") "Idee e serate" else "Ripasso ed esami", style = Type.meta.copy(fontWeight = FontWeight.Bold), color = c.accent)
                    }
                }
                items(entries) { e -> EntryRow(e, c.text) }
            }
            if (s.todos.isNotEmpty()) {
                item { Text("Impegni nati qui", style = Type.section, color = c.text, modifier = Modifier.padding(top = 30.dp, bottom = 4.dp)) }
                items(s.todos, key = { "t" + it.id }) { t ->
                    Row(Modifier.fillMaxWidth().clickable { onToggleTodo(t.id, !t.done) }.padding(vertical = 10.dp)) {
                        Check(t.done)
                        Text(
                            t.text, style = Type.text.copy(textDecoration = if (t.done) androidx.compose.ui.text.style.TextDecoration.LineThrough else null),
                            color = if (t.done) c.faint else c.text, modifier = Modifier.padding(start = 14.dp, top = 1.dp)
                        )
                    }
                }
            }
            if (st.tomorrow.isNotEmpty()) {
                item { Text("Per il giorno dopo", style = Type.section, color = c.text, modifier = Modifier.padding(top = 30.dp, bottom = 4.dp)) }
                items(st.tomorrow.withIndex().toList()) { (i, t) -> EntryRow(Entry("${i + 1}", t), c.text) }
            }
            if (st.verify.isNotEmpty()) {
                item {
                    Text("Da verificare", style = Type.section, color = c.text, modifier = Modifier.padding(top = 30.dp))
                    Text("Parole che la trascrizione potrebbe aver capito male.", style = Type.meta, color = c.second, modifier = Modifier.padding(top = 2.dp, bottom = 4.dp))
                }
                items(st.verify) { e -> EntryRow(e, c.second) }
            }
            item { TextAction("Leggi la trascrizione", { story = false }, modifier = Modifier.padding(top = 26.dp)) }
        } else {
            item {
                Spacer(Modifier.height(16.dp))
                DayLine(s.spans)
                if (s.pending > 0) Text("${s.pending} frasi ancora da trascrivere.", style = Type.meta, color = c.accent, modifier = Modifier.padding(top = 10.dp))
            }
            if (s.sections.isEmpty()) item {
                Text("Niente di trascritto per questo giorno.", style = Type.body, color = c.second, modifier = Modifier.padding(top = 28.dp))
            }
            s.sections.forEachIndexed { idx, (name, moments) ->
                item {
                    Row(Modifier.fillMaxWidth().padding(top = 30.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(name, style = Type.section, color = c.text, modifier = Modifier.weight(1f))
                        if (idx == 0) TextAction("Condividi", onShare)
                    }
                }
                items(moments) { m ->
                    if (m.gap) {
                        Column(Modifier.padding(vertical = 10.dp)) {
                            Hairline()
                            Text("${m.time} ${m.text}", style = Type.meta, color = c.second, modifier = Modifier.padding(vertical = 10.dp))
                            Hairline()
                        }
                    } else {
                        Column(Modifier.padding(vertical = 10.dp)) {
                            Text(m.time, style = Type.time, color = c.second)
                            Spacer(Modifier.height(3.dp))
                            Text(m.text, style = Type.body, color = c.text)
                        }
                    }
                }
            }
        }
    }
}

// =============================================================== Impostazioni

@Composable
fun SettingsScreen(
    s: SettingsUi,
    onOnlyCharging: (Boolean) -> Unit,
    onWaitCharge: (Boolean) -> Unit,
    onConnectDrive: () -> Unit,
    onSyncDrive: () -> Unit,
    onDisconnectDrive: () -> Unit,
    onExportMd: () -> Unit,
    onExportJson: () -> Unit,
    onSetup: () -> Unit,
    onEngine: (String) -> Unit = {},
    onCompare: () -> Unit = {},
    onDataSave: (token: String, repo: String) -> Unit = { _, _ -> },
    onDataSend: () -> Unit = {},
    onDataTest: () -> Unit = {},
    onDataDisconnect: () -> Unit = {},
    onBack: (() -> Unit)? = null,
) {
    val c = Life.c
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = side, end = side, top = if (onBack != null) 8.dp else 16.dp, bottom = 32.dp)) {
        item {
            onBack?.let { TextAction("‹ Oggi", it) }
            ScreenHeader(null, "Opzioni", Modifier.padding(top = if (onBack != null) 0.dp else 0.dp))
        }

        item { Group("Dati per Claude") }
        item { DataGroup(s, onDataSave, onDataSend, onDataTest, onDataDisconnect) }

        if (s.engines.isNotEmpty()) {
            item { Group("Motore di trascrizione") }
            items(s.engines) { e ->
                EngineRowView(e) { onEngine(e.key) }
                Hairline()
            }
            item {
                NavRow("Confronta i motori sulle ultime frasi", onClick = onCompare)
                Hairline()
            }
        }

        item { Group("Google Drive") }
        item {
            if (!s.driveConfigured) {
                Text(
                    "Copia le trascrizioni in un file Lifelog.md sul tuo Drive e lo tiene aggiornato. Claude lo legge con il connettore Google Drive.",
                    style = Type.text, color = c.second
                )
                Text("Nella finestra che si apre: menu in alto a sinistra, poi Drive, poi Salva.", style = Type.meta, color = c.second, modifier = Modifier.padding(top = 8.dp))
                TextAction("Collega Google Drive", onConnectDrive, modifier = Modifier.padding(top = 6.dp))
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(c.accent); Spacer(Modifier.width(9.dp))
                    Text("Collegato a Lifelog.md", style = Type.rowTitle, color = c.text)
                }
                if (s.driveStatus.isNotBlank()) Text(s.driveStatus, style = Type.meta, color = c.second, modifier = Modifier.padding(top = 4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp), modifier = Modifier.padding(top = 4.dp)) {
                    TextAction("Sincronizza ora", onSyncDrive)
                    TextAction("Scollega", onDisconnectDrive, color = c.second)
                }
            }
        }

        item { Group("Trascrizione") }
        item {
            ToggleRow("Solo quando è in carica", s.onlyCharging, onOnlyCharging)
            Hairline()
            if (s.onlyCharging) {
                ToggleRow("Aspetta l'80% di carica", s.waitCharge, onWaitCharge)
                Hairline()
            }
            Text(
                "Si ferma se la batteria supera %.1f °C. Usa 2 processori a bassa priorità.".format(s.maxTemp),
                style = Type.meta, color = c.second, modifier = Modifier.padding(top = 10.dp)
            )
            Text(s.battery, style = Type.meta, color = c.second, modifier = Modifier.padding(top = 4.dp))
        }

        item { Group("Esporta") }
        item {
            NavRow("Tutto in Markdown", onClick = onExportMd)
            Hairline()
            NavRow("Tutto in JSON", onClick = onExportJson)
            Hairline()
        }

        item { Group("App") }
        item {
            NavRow("Permessi, modello e batteria Samsung", onClick = onSetup)
            Hairline()
            Text(
                "Tutto resta sul telefono e l'audio si cancella appena trascritto. Registrare altre persone senza che lo sappiano può essere illegale: avvisa chi parla con te.",
                style = Type.meta, color = c.second, modifier = Modifier.padding(top = 14.dp)
            )
            Text("Lifelog ${s.version}", style = Type.meta, color = c.faint, modifier = Modifier.padding(top = 10.dp))
        }
    }
}

@Composable
private fun DataGroup(
    s: SettingsUi,
    onSave: (String, String) -> Unit,
    onSend: () -> Unit,
    onTest: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val c = Life.c
    if (!s.dataConfigured) {
        var token by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf("") }
        var repo by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(s.dataRepo) }
        Text(
            "Ogni sera invia il testo della giornata (mai l'audio) al tuo repository GitHub privato. Di notte Claude lo analizza e la mattina trovi il risultato in Ieri e Impegni.",
            style = Type.text, color = c.second
        )
        Field("Token GitHub", token, secret = true) { token = it }
        Field("Repository", repo) { repo = it }
        SolidButton("Salva e prova", { onSave(token, repo) }, modifier = Modifier.padding(top = 14.dp), enabled = token.isNotBlank())
        if (s.dataStatus.isNotBlank()) Text(s.dataStatus, style = Type.meta, color = c.accent, modifier = Modifier.padding(top = 8.dp))
    } else {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(c.accent); Spacer(Modifier.width(9.dp))
            Text(s.dataRepo, style = Type.rowTitle, color = c.text)
        }
        if (s.dataStatus.isNotBlank()) Text(s.dataStatus, style = Type.meta, color = c.second, modifier = Modifier.padding(top = 4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp), modifier = Modifier.padding(top = 4.dp)) {
            TextAction("Invia ora", onSend)
            TextAction("Prova", onTest)
            TextAction("Scollega", onDisconnect, color = c.second)
        }
    }
}

@Composable
private fun Field(label: String, value: String, secret: Boolean = false, onChange: (String) -> Unit) {
    val c = Life.c
    Text(label, style = Type.meta, color = c.second, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.fill).padding(horizontal = 14.dp, vertical = 13.dp)) {
        BasicTextField(
            value = value, onValueChange = onChange, singleLine = true,
            textStyle = Type.text.copy(color = c.text, fontWeight = FontWeight.Medium),
            cursorBrush = SolidColor(c.accent), modifier = Modifier.fillMaxWidth(),
            visualTransformation = if (secret) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        )
    }
}

@Composable
private fun EngineRowView(e: EngineRow, onClick: () -> Unit) {
    val c = Life.c
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(e.label, style = Type.rowTitle, color = c.text)
            Text(e.detail, style = Type.meta, color = c.second, modifier = Modifier.padding(top = 3.dp))
            if (e.state.isNotBlank()) Text(e.state, style = Type.meta, color = if (e.ready) c.second else c.accent, modifier = Modifier.padding(top = 3.dp))
        }
        Box(
            Modifier.padding(start = 16.dp).size(24.dp).clip(CircleShape)
                .background(if (e.selected) c.accent else c.fill),
            contentAlignment = Alignment.Center
        ) { if (e.selected) Text("✓", style = Type.time, color = c.onText) }
    }
}

// =============================================================== Confronta

@Composable
fun CompareScreen(s: CompareUi, onBack: () -> Unit, onRun: () -> Unit) {
    val c = Life.c
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = side, end = side, top = 8.dp, bottom = 40.dp)) {
        item {
            TextAction("‹ Opzioni", onBack)
            Text("Confronta", style = Type.largeTitle, color = c.text, modifier = Modifier.padding(top = 8.dp))
            Text(
                "Le ultime frasi registrate, trascritte da ogni motore pronto. Scegli quello che sbaglia meno con la tua voce.",
                style = Type.text, color = c.second, modifier = Modifier.padding(top = 8.dp)
            )
            if (s.status.isNotBlank()) Text(s.status, style = Type.meta, color = if (s.running) c.accent else c.second, modifier = Modifier.padding(top = 12.dp))
            if (!s.running) SolidButton(if (s.rows.isEmpty()) "Avvia il confronto" else "Rifai il confronto", onRun, modifier = Modifier.padding(top = 16.dp))
        }
        items(s.rows) { r ->
            Column(Modifier.padding(top = 28.dp)) {
                Text(r.time, style = Type.section, color = c.text)
                r.results.forEach { (engine, text) ->
                    Text(engine, style = Type.meta, color = c.second, modifier = Modifier.padding(top = 12.dp))
                    Text(text.ifBlank { "(niente)" }, style = Type.text, color = c.text, modifier = Modifier.padding(top = 2.dp))
                }
                Spacer(Modifier.height(16.dp))
                Hairline()
            }
        }
    }
}

@Composable
private fun Group(title: String) {
    Text(title, style = Type.section, color = Life.c.text, modifier = Modifier.padding(top = 30.dp, bottom = 8.dp))
}

@Composable
private fun ToggleRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
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

// =============================================================== Configurazione

@Composable
fun SetupScreen(
    s: SetupUi,
    onPermissions: () -> Unit,
    onDownload: () -> Unit,
    onDownloadMobile: () -> Unit,
    onBattery: () -> Unit,
    onAppInfo: () -> Unit,
    onSamsungBattery: () -> Unit,
    onDone: () -> Unit,
) {
    val c = Life.c
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = side, end = side, top = 16.dp, bottom = 24.dp)) {
            item {
                ScreenHeader(null, "Prima di iniziare")
                Text(
                    "Tre passi, una volta sola. Tutto resta sul telefono: niente viene caricato online.",
                    style = Type.body, color = c.second, modifier = Modifier.padding(top = 10.dp, bottom = 10.dp)
                )
            }
            item {
                Step(1, "Microfono e notifiche", s.mic && s.notif,
                    "Il microfono per ascoltare, le notifiche per avvisarti se la registrazione si ferma.") {
                    TextAction("Consenti", onPermissions)
                }
                Hairline()
            }
            item {
                Step(2, "Modello di trascrizione", s.download == DownloadState.DONE,
                    "Circa 490 MB, si scarica una volta. Dopo funziona anche senza internet.") {
                    when (s.download) {
                        DownloadState.RUNNING -> {
                            Text(s.downloadStage, style = Type.meta, color = c.text)
                            LinearProgressIndicator(
                                progress = { s.downloadPct / 100f },
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(4.dp).clip(RoundedCornerShape(2.dp)),
                                color = c.accent, trackColor = c.fill, gapSize = 0.dp, drawStopIndicator = {}
                            )
                        }
                        DownloadState.WAITING_WIFI -> {
                            Text("In attesa del Wi-Fi.", style = Type.meta, color = c.text)
                            TextAction("Scarica anche con i dati mobili", onDownloadMobile)
                        }
                        DownloadState.FAILED -> {
                            Text("Download non riuscito: ${s.downloadError}", style = Type.meta, color = c.text)
                            TextAction("Riprova", onDownload)
                        }
                        else -> TextAction("Scarica", onDownload)
                    }
                }
                Hairline()
            }
            item {
                Step(3, "Togli le restrizioni batteria", s.battery,
                    "Senza questo, Samsung chiude l'app in sottofondo. Nella finestra scegli Consenti.") {
                    TextAction("Apri", onBattery)
                }
                Hairline()
            }
            item {
                Text("Samsung, facoltativo", style = Type.rowTitle, color = c.text, modifier = Modifier.padding(top = 24.dp))
                if (s.battery) {
                    Text(
                        "L'app è già Senza restrizioni, per questo non compare tra le App mai in sospensione. Va bene così.",
                        style = Type.meta, color = c.second, modifier = Modifier.padding(top = 6.dp)
                    )
                }
                Text(
                    "Consigliato: Impostazioni, Batteria, Limiti utilizzo in background, spegni Metti in sospensione le app inutilizzate.",
                    style = Type.meta, color = c.second, modifier = Modifier.padding(top = 6.dp)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    TextAction("Info app", onAppInfo)
                    TextAction("Impostazioni batteria", onSamsungBattery)
                }
            }
        }
        val missing = listOf(!(s.mic && s.notif), s.download != DownloadState.DONE, !s.battery).count { it }
        Column(Modifier.padding(horizontal = side, vertical = 12.dp)) {
            if (missing > 0) Text(
                if (missing == 1) "Manca ancora un passo. Puoi continuare e finirlo dopo." else "Mancano ancora $missing passi. Puoi continuare e finirli dopo.",
                style = Type.meta, color = c.second, modifier = Modifier.padding(bottom = 10.dp)
            )
            SolidButton(if (missing == 0) "Inizia" else "Continua", onDone, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun Step(n: Int, title: String, done: Boolean, desc: String, action: @Composable () -> Unit) {
    val c = Life.c
    Row(Modifier.fillMaxWidth().padding(vertical = 18.dp)) {
        Text(if (done) "✓" else "$n", style = Type.section, color = if (done) c.accent else c.text, modifier = Modifier.width(34.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = Type.section, color = if (done) c.second else c.text)
            if (!done) {
                Text(desc, style = Type.text, color = c.second, modifier = Modifier.padding(top = 4.dp, bottom = 4.dp))
                action()
            }
        }
    }
}

// =============================================================== Barra in basso

enum class Tab(val label: String) { TODAY("Oggi"), DIARY("Diario"), TODO("Impegni"), SEARCH("Cerca") }

@Composable
fun BottomBar(current: Tab, onSelect: (Tab) -> Unit) {
    val c = Life.c
    Column(Modifier.fillMaxWidth().background(c.bg)) {
        Hairline()
        Row(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 4.dp)) {
            Tab.entries.forEach { t ->
                val sel = t == current
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).clickable { onSelect(t) }.padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(Modifier.size(5.dp).clip(CircleShape).background(if (sel) c.accent else Color.Transparent))
                    Spacer(Modifier.height(5.dp))
                    Text(t.label, style = Type.tab.copy(fontSize = 14.sp, fontWeight = if (sel) FontWeight.ExtraBold else FontWeight.SemiBold), color = if (sel) c.text else c.second, maxLines = 1)
                }
            }
        }
    }
}
