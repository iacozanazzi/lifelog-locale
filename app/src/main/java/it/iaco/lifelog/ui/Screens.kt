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
)

data class Moment(val time: String, val text: String, val gap: Boolean = false)

data class DayRow(val day: String, val title: String, val meta: String, val spans: List<Span>)

data class DayUi(
    val title: String,
    val subtitle: String,
    val spans: List<Span>,
    val sections: List<Pair<String, List<Moment>>>,
    val pending: Int,
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
)

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

private val side = 22.dp

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
) {
    val c = Life.c
    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(start = side, end = side, top = 16.dp, bottom = 24.dp),
        ) {
            item {
                ScreenHeader(s.dateLabel, "Oggi")
                Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    val (color, text) = when {
                        !s.ready -> c.second to "Configurazione da completare"
                        s.state == RecState.STALLED -> c.text to "Fermo dalle ${s.stalledSince}"
                        s.state == RecState.LISTENING -> c.accent to
                            (if (s.speaking) "Qualcuno sta parlando" else "Sto ascoltando") + minutesSuffix(s.speechMinutes)
                        else -> c.second to "In pausa" + minutesSuffix(s.speechMinutes)
                    }
                    StatusDot(color, if (s.state == RecState.LISTENING) s.level else 0f)
                    Spacer(Modifier.width(9.dp))
                    Text(text, style = Type.rowTitle.copy(fontWeight = FontWeight.Bold), color = color)
                }
            }

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

            item { Spacer(Modifier.height(26.dp)) }

            if (s.lines.isEmpty()) {
                item {
                    Text(
                        if (s.pending > 0) "Le tue parole compariranno qui appena trascritte."
                        else "Quello che dici oggi comparirà qui.",
                        style = Type.lyric, color = c.faint
                    )
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
                s.lastTime?.let { item { Text("Ultima frase alle $it", style = Type.meta, color = c.second) } }
            }
            if (s.pending > 0) {
                item {
                    Text(
                        "Altri ${s.pendingMinutes.coerceAtLeast(1)} min di voce aspettano la trascrizione.",
                        style = Type.body, color = c.accent, modifier = Modifier.padding(top = 18.dp)
                    )
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
            Column(Modifier.weight(1f)) {
                Text(
                    when {
                        !s.ready -> "Completa la configurazione"
                        s.pending > 0 -> "${s.pending} frasi da trascrivere"
                        else -> "Tutto trascritto"
                    },
                    style = Type.rowTitle, color = c.text, maxLines = 1
                )
                val sub = if (!s.ready) "Mancano pochi passi" else s.transcribeStatus
                if (sub.isNotBlank()) Text(sub, style = Type.meta, color = c.second, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (s.ready && s.canTranscribeNow) TextAction("Trascrivi ora", onTranscribeNow)
            }
            if (s.ready) {
                Spacer(Modifier.width(12.dp))
                PlayPause(listening = s.state == RecState.LISTENING, onClick = onToggle)
            }
        }
    }
}

private fun minutesSuffix(m: Long) = when {
    m <= 0 -> ""
    m < 60 -> ", $m min di voce oggi"
    else -> ", ${m / 60} h ${m % 60} min di voce oggi"
}

@Composable
private fun PlayPause(listening: Boolean, onClick: () -> Unit) {
    val c = Life.c
    Box(
        Modifier.size(46.dp).clip(CircleShape).background(c.text).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (listening) {
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Box(Modifier.width(4.dp).height(16.dp).background(c.bg, RoundedCornerShape(1.dp)))
                Box(Modifier.width(4.dp).height(16.dp).background(c.bg, RoundedCornerShape(1.dp)))
            }
        } else {
            Box(Modifier.size(14.dp).clip(CircleShape).background(c.accent))
        }
    }
}

// =============================================================== Giorni

@Composable
fun DaysScreen(days: List<DayRow>, onDay: (String) -> Unit) {
    val c = Life.c
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = side, end = side, top = 16.dp, bottom = 24.dp)) {
        item { ScreenHeader(null, "Giorni"); Spacer(Modifier.height(14.dp)) }
        if (days.isEmpty()) item {
            Text("Ancora nessun giorno registrato. Avvia l'ascolto da Oggi.", style = Type.body, color = c.second)
        }
        items(days, key = { it.day }) { d ->
            Column(Modifier.fillMaxWidth().clickable { onDay(d.day) }.padding(vertical = 16.dp)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(d.title, style = Type.section, color = c.text, modifier = Modifier.weight(1f))
                    Text(d.meta, style = Type.meta, color = c.second)
                }
                Spacer(Modifier.height(12.dp))
                DayLine(d.spans, height = 6.dp)
            }
            Hairline()
        }
    }
}

// =============================================================== Cerca

@Composable
fun SearchScreen(query: String, hits: List<SearchHit>, onQuery: (String) -> Unit, onDay: (String) -> Unit) {
    val c = Life.c
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = side, end = side, top = 16.dp, bottom = 24.dp)) {
        item {
            ScreenHeader(null, "Cerca")
            Spacer(Modifier.height(14.dp))
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.fill)
                    .padding(horizontal = 14.dp, vertical = 13.dp)
            ) {
                if (query.isEmpty()) Text("Parole, nomi, idee", style = Type.text.copy(fontWeight = FontWeight.Medium), color = c.second)
                BasicTextField(
                    value = query, onValueChange = onQuery, singleLine = true,
                    textStyle = Type.text.copy(color = c.text, fontWeight = FontWeight.Medium),
                    cursorBrush = SolidColor(c.accent), modifier = Modifier.fillMaxWidth()
                )
            }
            Spacer(Modifier.height(10.dp))
            when {
                query.trim().length < 2 -> Text("Cerca in tutto quello che hai detto.", style = Type.meta, color = c.second)
                hits.isEmpty() -> Text("Nessuna frase con \"${query.trim()}\".", style = Type.meta, color = c.second)
                else -> Text(if (hits.size == 1) "1 frase" else "${hits.size} frasi", style = Type.meta, color = c.second)
            }
        }
        items(hits) { h ->
            Column(Modifier.fillMaxWidth().clickable { onDay(h.day) }.padding(vertical = 14.dp)) {
                Text(h.where, style = Type.time, color = c.second)
                Spacer(Modifier.height(3.dp))
                Text(highlight(h.text, query.trim(), c.accent), style = Type.body, color = c.text, maxLines = 4, overflow = TextOverflow.Ellipsis)
            }
            Hairline()
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
fun DayScreen(s: DayUi, onBack: () -> Unit, onShare: () -> Unit) {
    val c = Life.c
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = side, end = side, top = 8.dp, bottom = 40.dp)) {
        item {
            TextAction("‹ Giorni", onBack)
            Text(s.title, style = Type.largeTitle, color = c.text, modifier = Modifier.padding(top = 8.dp))
            Text(s.subtitle, style = Type.meta, color = c.second, modifier = Modifier.padding(top = 6.dp))
            Spacer(Modifier.height(14.dp))
            DayLine(s.spans)
            if (s.pending > 0) {
                Text("${s.pending} frasi ancora da trascrivere.", style = Type.meta, color = c.accent, modifier = Modifier.padding(top = 10.dp))
            }
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
) {
    val c = Life.c
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = side, end = side, top = 16.dp, bottom = 32.dp)) {
        item { ScreenHeader(null, "Impostazioni") }

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

enum class Tab(val label: String) { TODAY("Oggi"), DAYS("Giorni"), SEARCH("Cerca"), SETTINGS("Impostazioni") }

@Composable
fun BottomBar(current: Tab, onSelect: (Tab) -> Unit) {
    val c = Life.c
    Column(Modifier.fillMaxWidth().background(c.bg)) {
        Hairline()
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Tab.entries.forEach { t ->
                Box(
                    Modifier.weight(1f).clickable { onSelect(t) }.padding(vertical = 14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(t.label, style = Type.tab, color = if (t == current) c.accent else c.second)
                }
            }
        }
    }
}
