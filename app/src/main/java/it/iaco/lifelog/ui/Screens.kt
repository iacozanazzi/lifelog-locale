package it.iaco.lifelog.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import it.iaco.lifelog.R

// =============================================================== Stati (dati puri)

data class TodayUi(
    val dateLabel: String,
    val ready: Boolean,
    val mode: OrbMode,
    val wanted: Boolean,
    val stalledSince: String?,
    val level: Float,
    val speaking: Boolean,
    val speechMinutes: Long,
    val words: Int,
    val pending: Int,
    val pendingMinutes: Long,
    val batteryLine: String,
    val transcribeStatus: String,
    val canTranscribeNow: Boolean,
    val spans: List<Span>,
    val nowMin: Float,
    val latest: List<Moment>,
    val crash: String?,
)

data class Moment(val time: String, val text: String, val gap: Boolean = false, val pending: Boolean = false)

data class DayRow(
    val day: String,
    val title: String,
    val subtitle: String,
    val speechMinutes: Long,
    val words: Int,
    val pending: Int,
    val spans: List<Span>,
)

data class DayUi(
    val title: String,
    val subtitle: String,
    val spans: List<Span>,
    val moments: List<Moment>,
    val pending: Int,
)

data class SettingsUi(
    val onlyCharging: Boolean,
    val waitCharge: Boolean,
    val maxTemp: Float,
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

// =============================================================== Oggi

@Composable
fun TodayScreen(
    s: TodayUi,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onSetup: () -> Unit,
    onOpenToday: () -> Unit,
    onTranscribeNow: () -> Unit,
    onShareCrash: () -> Unit,
    onDismissCrash: () -> Unit,
) {
    val c = Life.c
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Eyebrow(s.dateLabel)
            Text(
                when {
                    !s.ready -> "Quasi pronti."
                    s.mode == OrbMode.STALLED -> "Mi sono fermato."
                    s.mode == OrbMode.LISTENING && s.speaking -> "Ti sto ascoltando."
                    s.mode == OrbMode.LISTENING -> "In ascolto."
                    else -> "In pausa."
                },
                style = MaterialTheme.typography.displayMedium, color = c.ink
            )
        }

        s.crash?.let { crash ->
            item {
                LifeCard(tint = c.gap) {
                    Text("L'app si è chiusa per un errore", fontWeight = FontWeight.SemiBold, color = c.ink)
                    Text(crash.lineSequence().take(3).joinToString("\n"), style = MaterialTheme.typography.bodySmall, color = c.muted, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PillButton("Condividi", onShareCrash, color = c.gap)
                        PillButton("Chiudi", onDismissCrash, filled = false, color = c.muted)
                    }
                }
            }
        }

        item {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                ListeningOrb(s.mode, s.level, s.speaking, size = 260.dp)
                Text(
                    when {
                        !s.ready -> "Completa la configurazione per iniziare"
                        s.mode == OrbMode.STALLED -> "Fermo dalle ${s.stalledSince}. Tocca Riprendi."
                        s.mode == OrbMode.LISTENING -> "Salvo solo quando qualcuno parla"
                        else -> "Il microfono è spento"
                    },
                    style = MaterialTheme.typography.bodyMedium, color = c.muted, textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(16.dp))
                when {
                    !s.ready -> PillButton("Completa la configurazione", onSetup)
                    s.mode == OrbMode.STALLED -> PillButton("Riprendi", onStart, color = c.gap)
                    s.wanted -> PillButton("Metti in pausa", onStop, filled = false, color = c.ink)
                    else -> PillButton("Inizia ad ascoltare", onStart)
                }
            }
        }

        item {
            LifeCard(onClick = onOpenToday) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Eyebrow("La tua giornata", modifier = Modifier.weight(1f))
                    Text("Apri ›", style = MaterialTheme.typography.bodySmall, color = c.voice)
                }
                DayStrip(s.spans, nowMin = s.nowMin)
                Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    BigStat("${s.speechMinutes}", "minuti di voce", Modifier.weight(1f))
                    BigStat(formatWords(s.words), "parole", Modifier.weight(1f))
                    BigStat("${s.pending}", "da trascrivere", Modifier.weight(1f), accent = if (s.pending > 0) c.voice else c.ink)
                }
            }
        }

        if (s.latest.isNotEmpty()) {
            item { Eyebrow("Ultime parole") }
            items(s.latest) { m -> MomentRow(m) }
        }

        item {
            LifeCard {
                Eyebrow("Trascrizione sul telefono")
                Text(
                    if (s.pending == 0) "Tutto trascritto." else "${s.pendingMinutes} min di voce in attesa.",
                    style = MaterialTheme.typography.headlineSmall, color = c.ink
                )
                if (s.transcribeStatus.isNotBlank()) Text(s.transcribeStatus, style = MaterialTheme.typography.bodyMedium, color = c.muted)
                Text(s.batteryLine, style = MaterialTheme.typography.bodySmall, color = c.muted)
                if (s.canTranscribeNow) {
                    PillButton("Trascrivi ora", onTranscribeNow, filled = false, color = c.text)
                }
            }
        }
    }
}

fun formatWords(n: Int): String = if (n >= 10_000) "${n / 1000}k" else if (n >= 1000) "%.1fk".format(n / 1000f) else "$n"

@Composable
fun MomentRow(m: Moment) {
    val c = Life.c
    Row(Modifier.fillMaxWidth()) {
        Column(Modifier.width(56.dp), horizontalAlignment = Alignment.Start) {
            Text(m.time, style = MaterialTheme.typography.labelSmall, color = if (m.gap) c.gap else c.muted)
        }
        Box(Modifier.padding(top = 4.dp).width(2.dp).height(if (m.gap) 18.dp else 22.dp).background(if (m.gap) c.gap else if (m.pending) c.voice else c.text, RoundedCornerShape(1.dp)))
        Spacer(Modifier.width(14.dp))
        if (m.gap) {
            Text(m.text, style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic), color = c.gap)
        } else {
            Text(m.text, style = MaterialTheme.typography.bodyLarge, color = if (m.pending) c.muted else c.ink)
        }
    }
}

// =============================================================== Archivio

@Composable
fun ArchiveScreen(
    days: List<DayRow>,
    query: String,
    results: List<Pair<String, Moment>>,
    onQuery: (String) -> Unit,
    onDay: (String) -> Unit,
) {
    val c = Life.c
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Eyebrow("Archivio")
            Text("I tuoi giorni.", style = MaterialTheme.typography.displayMedium, color = c.ink)
        }
        item { SearchField(query, onQuery) }
        if (query.trim().length >= 2) {
            item { Eyebrow("${results.size} risultati") }
            items(results) { (day, m) ->
                LifeCard(onClick = { onDay(day) }) {
                    Eyebrow("$day · ${m.time}", color = c.voice)
                    Text(m.text, style = MaterialTheme.typography.bodyLarge, color = c.ink, maxLines = 4, overflow = TextOverflow.Ellipsis)
                }
            }
        } else {
            if (days.isEmpty()) item {
                LifeCard {
                    Text("Ancora nessun giorno.", style = MaterialTheme.typography.headlineSmall, color = c.ink)
                    Text("Quando parli, qui compariranno le tue giornate.", color = c.muted)
                }
            }
            items(days, key = { it.day }) { d ->
                LifeCard(onClick = { onDay(d.day) }) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(d.title, style = MaterialTheme.typography.headlineMedium, color = c.ink, modifier = Modifier.weight(1f))
                        Text(d.subtitle, style = MaterialTheme.typography.bodySmall, color = c.muted)
                    }
                    DayStrip(d.spans, height = 14.dp, labels = false)
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("${d.speechMinutes} min di voce", style = MaterialTheme.typography.bodySmall, color = c.muted)
                        Text("~${formatWords(d.words)} parole", style = MaterialTheme.typography.bodySmall, color = c.muted)
                        if (d.pending > 0) Text("${d.pending} in attesa", style = MaterialTheme.typography.bodySmall, color = c.voice)
                    }
                }
            }
        }
    }
}

@Composable
fun SearchField(value: String, onChange: (String) -> Unit) {
    val c = Life.c
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(50))
            .background(c.card)
            .border(1.dp, c.track, RoundedCornerShape(50))
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(painterResource(R.drawable.ic_search), null, tint = c.muted, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text("Cerca una parola, un nome, un'idea…", color = c.muted)
            BasicTextField(
                value = value, onValueChange = onChange, singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = c.ink),
                cursorBrush = SolidColor(c.voice),
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (value.isNotEmpty()) {
            Text("✕", color = c.muted, modifier = Modifier.clickable { onChange("") }.padding(start = 8.dp))
        }
    }
}

// =============================================================== Giorno

@Composable
fun DayScreen(s: DayUi, onBack: () -> Unit) {
    val c = Life.c
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        item {
            Text("‹ Indietro", color = c.voice, modifier = Modifier.clickable(onClick = onBack).padding(vertical = 8.dp))
            Eyebrow(s.subtitle)
            Text(s.title, style = MaterialTheme.typography.displayMedium, color = c.ink)
        }
        item {
            DayStrip(s.spans)
            Spacer(Modifier.height(10.dp))
            Legend()
        }
        if (s.pending > 0) item {
            Text("${s.pending} pezzi ancora da trascrivere: compariranno qui.", style = MaterialTheme.typography.bodySmall, color = c.voice)
        }
        if (s.moments.isEmpty()) item {
            Text("Niente di trascritto per questo giorno.", color = c.muted)
        }
        items(s.moments) { m -> TimelineItem(m) }
    }
}

@Composable
private fun TimelineItem(m: Moment) {
    val c = Life.c
    Row(Modifier.fillMaxWidth()) {
        Column(Modifier.width(64.dp)) {
            Text(m.time.substringBefore("–"), style = MaterialTheme.typography.titleMedium, color = if (m.gap) c.gap else c.ink)
            if (m.time.contains("–")) Text(m.time.substringAfter("–"), style = MaterialTheme.typography.bodySmall, color = c.muted)
        }
        Column(Modifier.width(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.padding(top = 6.dp).size(10.dp).clip(CircleShape).background(if (m.gap) c.gap else c.text))
        }
        Spacer(Modifier.width(10.dp))
        if (m.gap) {
            Text(m.text, style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic), color = c.gap, modifier = Modifier.weight(1f))
        } else {
            Text(m.text, style = MaterialTheme.typography.bodyLarge, color = c.ink, modifier = Modifier.weight(1f))
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
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Eyebrow("Impostazioni")
            Text("Come lavoro.", style = MaterialTheme.typography.displayMedium, color = c.ink)
        }
        item {
            LifeCard {
                Eyebrow("Chiedi a Claude", color = c.text)
                Text("Google Drive", style = MaterialTheme.typography.headlineSmall, color = c.ink)
                if (!s.driveConfigured) {
                    Text(
                        "Copio le trascrizioni (solo testo) in un file Lifelog.md su Drive, e lo aggiorno da solo. " +
                            "Poi Claude lo legge con il connettore Google Drive.",
                        color = c.muted, style = MaterialTheme.typography.bodyMedium
                    )
                    Text("Nel selettore: menu ☰ in alto a sinistra → Drive → Salva.", color = c.muted, style = MaterialTheme.typography.bodySmall)
                    PillButton("Collega Google Drive", onConnectDrive, color = c.text)
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Dot(c.text); Spacer(Modifier.width(8.dp))
                        Text("Collegato · si aggiorna da solo", color = c.ink)
                    }
                    if (s.driveStatus.isNotBlank()) Text(s.driveStatus, color = c.muted, style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PillButton("Sincronizza ora", onSyncDrive, filled = false, color = c.text)
                        PillButton("Scollega", onDisconnectDrive, filled = false, color = c.muted)
                    }
                }
            }
        }
        item {
            LifeCard {
                Eyebrow("Trascrizione")
                Text("Gentile con la batteria", style = MaterialTheme.typography.headlineSmall, color = c.ink)
                ToggleRow("Solo quando è in carica", "Così di giorno non consumo niente", s.onlyCharging, onOnlyCharging)
                if (s.onlyCharging) {
                    ToggleRow("Aspetta l'80% di carica", "Prima ricarica, poi trascrive", s.waitCharge, onWaitCharge)
                }
                Text(
                    "Mi fermo sempre se la batteria supera %.1f °C. Uso solo 2 processori, a bassa priorità.".format(s.maxTemp),
                    style = MaterialTheme.typography.bodySmall, color = c.muted
                )
            }
        }
        item {
            LifeCard {
                Eyebrow("I tuoi dati")
                Text("Esporta tutto", style = MaterialTheme.typography.headlineSmall, color = c.ink)
                Text("Un file con tutte le trascrizioni, da conservare o dare a un'IA.", color = c.muted, style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PillButton("Markdown", onExportMd, filled = false, color = c.ink)
                    PillButton("JSON", onExportJson, filled = false, color = c.ink)
                }
            }
        }
        item {
            LifeCard(onClick = onSetup) {
                Eyebrow("Configurazione")
                Text("Permessi, modello, batteria Samsung ›", color = c.ink)
            }
        }
        item {
            Text(
                "Tutto resta sul telefono. L'audio viene cancellato appena trascritto. " +
                    "Registrare altre persone a loro insaputa può essere illegale: avvisa chi parla con te.\n\nLifelog ${s.version}",
                style = MaterialTheme.typography.bodySmall, color = c.muted
            )
        }
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val c = Life.c
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = c.ink, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, color = c.muted, style = MaterialTheme.typography.bodySmall)
        }
        Switch(
            checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = c.voice, checkedThumbColor = Color(0xFF1A1205))
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
    val steps = listOf(s.mic && s.notif, s.download == DownloadState.DONE, s.battery)
    val doneCount = steps.count { it }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Eyebrow("Benvenuto")
            Text("Ascolta la tua vita.\nTienila per te.", style = MaterialTheme.typography.displayMedium, color = c.ink)
            Spacer(Modifier.height(8.dp))
            Text(
                "Tre passi, una volta sola. Niente cloud: ascolto, trascrivo e conservo tutto sul telefono.",
                color = c.muted, style = MaterialTheme.typography.bodyLarge
            )
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                steps.forEach { ok ->
                    Box(Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp)).background(if (ok) c.voice else c.track))
                }
            }
            Text("$doneCount di 3", style = MaterialTheme.typography.labelSmall, color = c.muted, modifier = Modifier.padding(top = 6.dp))
        }
        item {
            StepCard(1, "Microfono e notifiche", s.mic && s.notif) {
                Text("Il microfono per ascoltare, le notifiche per avvisarti se mi fermo.", color = c.muted)
                PillButton("Concedi", onPermissions)
            }
        }
        item {
            StepCard(2, "Il cervello che trascrive", s.download == DownloadState.DONE) {
                Text("Un modello da ~490 MB, scaricato una volta. Poi tutto funziona anche offline.", color = c.muted)
                when (s.download) {
                    DownloadState.RUNNING -> {
                        Text(s.downloadStage, color = c.ink)
                        LinearProgressIndicator(
                            progress = { s.downloadPct / 100f },
                            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                            color = c.voice, trackColor = c.track
                        )
                    }
                    DownloadState.WAITING_WIFI -> {
                        Text("Aspetto il Wi-Fi…", color = c.ink)
                        PillButton("Usa anche i dati mobili", onDownloadMobile, filled = false)
                    }
                    DownloadState.FAILED -> {
                        Text("Non riuscito: ${s.downloadError}", color = c.gap)
                        PillButton("Riprova", onDownload)
                    }
                    else -> PillButton("Scarica", onDownload)
                }
            }
        }
        item {
            StepCard(3, "Non farmi addormentare", s.battery) {
                Text("Samsung chiude le app in sottofondo. Premi e scegli \"Consenti\".", color = c.muted)
                PillButton("Togli le restrizioni", onBattery)
            }
        }
        item {
            LifeCard {
                Eyebrow("Facoltativo · Samsung")
                if (s.battery) {
                    Text(
                        "Sono già \"Senza restrizioni\": per questo non compaio nell'elenco \"App mai in sospensione\". Va bene così.",
                        color = c.muted, style = MaterialTheme.typography.bodyMedium
                    )
                }
                Text(
                    "Consigliato: Impostazioni → Batteria → Limiti utilizzo in background → spegni \"Metti in sospensione le app inutilizzate\".",
                    color = c.muted, style = MaterialTheme.typography.bodyMedium
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PillButton("Info app", onAppInfo, filled = false, color = c.ink)
                    PillButton("Batteria", onSamsungBattery, filled = false, color = c.ink)
                }
            }
        }
        item {
            val missing = buildList {
                if (!s.mic) add("microfono")
                if (s.download != DownloadState.DONE) add("modello")
                if (!s.battery) add("batteria")
            }
            if (missing.isNotEmpty()) {
                Text("Manca: ${missing.joinToString(", ")}. Puoi continuare lo stesso.", color = c.muted, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
            }
            PillButton(if (missing.isEmpty()) "Inizia" else "Continua", onDone, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun StepCard(n: Int, title: String, done: Boolean, content: @Composable () -> Unit) {
    val c = Life.c
    LifeCard(tint = if (done) c.text else null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(34.dp).clip(CircleShape).background(if (done) c.text else c.cardHigh)
                    .border(1.dp, if (done) Color.Transparent else c.track, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(if (done) "✓" else "$n", color = if (done) Color(0xFF00201A) else c.ink, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(14.dp))
            Text(title, style = MaterialTheme.typography.headlineSmall, color = c.ink, modifier = Modifier.weight(1f))
        }
        AnimatedVisibility(!done, enter = fadeIn(), exit = fadeOut()) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
        }
    }
}

// =============================================================== Barra in basso

enum class Tab { TODAY, ARCHIVE, SETTINGS }

@Composable
fun BottomBar(current: Tab, onSelect: (Tab) -> Unit) {
    val c = Life.c
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 10.dp)
            .clip(RoundedCornerShape(50))
            .background(c.cardHigh)
            .border(1.dp, c.track, RoundedCornerShape(50))
            .padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        listOf(
            Triple(Tab.TODAY, R.drawable.ic_tab_today, "Oggi"),
            Triple(Tab.ARCHIVE, R.drawable.ic_tab_archive, "Archivio"),
            Triple(Tab.SETTINGS, R.drawable.ic_tab_settings, "Impostazioni"),
        ).forEach { (tab, icon, label) ->
            val sel = tab == current
            Row(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(50))
                    .background(if (sel) c.voice.copy(alpha = 0.14f) else Color.Transparent)
                    .clickable { onSelect(tab) }
                    .padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TabIcon(icon, sel)
                if (sel) {
                    Spacer(Modifier.width(8.dp))
                    Text(label, color = c.voice, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
