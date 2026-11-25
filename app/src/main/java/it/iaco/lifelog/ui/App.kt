package it.iaco.lifelog.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.work.WorkInfo
import androidx.work.WorkManager
import it.iaco.lifelog.CrashLog
import it.iaco.lifelog.Db
import it.iaco.lifelog.DriveSync
import it.iaco.lifelog.DaySummary
import it.iaco.lifelog.Exporter
import it.iaco.lifelog.ModelSetupWorker
import it.iaco.lifelog.Models
import it.iaco.lifelog.Notifications
import it.iaco.lifelog.Prefs
import it.iaco.lifelog.RecorderService
import it.iaco.lifelog.Segment
import it.iaco.lifelog.Throttle
import it.iaco.lifelog.TranscribeWorker
import it.iaco.lifelog.Watchdog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

private sealed interface Screen {
    data object Home : Screen
    data object Setup : Screen
    data class Day(val day: String) : Screen
}

@Composable
fun App(resumeTick: Int) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    var screen by remember { mutableStateOf<Screen>(if (prefs.onboardingDone) Screen.Home else Screen.Setup) }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(2000); tick++
        }
    }
    LaunchedEffect(resumeTick) { tick++ }

    BackHandler(enabled = screen != Screen.Home) { screen = Screen.Home }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.safeDrawingPadding()) {
            when (val s = screen) {
                Screen.Home -> HomeScreen(tick, onSetup = { screen = Screen.Setup }, onDay = { screen = Screen.Day(it) })
                Screen.Setup -> SetupScreen(tick, onDone = {
                    prefs.onboardingDone = true
                    screen = Screen.Home
                })
                is Screen.Day -> DayScreen(s.day, tick, onBack = { screen = Screen.Home })
            }
        }
    }
}

// ---------------------------------------------------------------- Home

@Composable
private fun HomeScreen(tick: Int, onSetup: () -> Unit, onDay: (String) -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    val db = remember { Db.get(ctx) }
    val scope = rememberCoroutineScope()

    var query by remember { mutableStateOf("") }
    var days by remember { mutableStateOf(emptyList<DaySummary>()) }
    var results by remember { mutableStateOf(emptyList<Segment>()) }
    var pending by remember { mutableIntStateOf(0) }
    var pendingSec by remember { mutableStateOf(0L) }

    LaunchedEffect(tick, query) {
        withContext(Dispatchers.IO) {
            days = db.days()
            pending = db.pendingCount()
            pendingSec = db.pendingSeconds()
            results = if (query.trim().length >= 2) db.search(query.trim()) else emptyList()
        }
    }

    val exportMd = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
        if (uri != null) scope.launch(Dispatchers.IO) { export(ctx, uri, md = true) }
    }
    val exportJson = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch(Dispatchers.IO) { export(ctx, uri, md = false) }
    }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Spacer(Modifier.height(8.dp)); Text("Lifelog", style = MaterialTheme.typography.headlineMedium) }

        item { CrashCard() }

        item { RecordingCard(tick, onSetup) }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Trascrizione", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    val b = remember(tick) { Throttle.battery(ctx) }
                    Text("Da trascrivere: $pending pezzi (${pendingSec / 60} min di parlato)")
                    Text(
                        "Batteria %d%% · %.1f°C · %s".format(b.percent, b.tempC, if (b.plugged) "in carica" else "non in carica"),
                        style = MaterialTheme.typography.bodySmall
                    )
                    val status = remember(tick) { prefs.transcribeStatus }
                    if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        enabled = pending > 0 && Models.parakeetReady(ctx),
                        onClick = {
                            TranscribeWorker.runNow(ctx)
                            Toast.makeText(ctx, "Avviata (si ferma da sola se la batteria si scalda)", Toast.LENGTH_SHORT).show()
                        }
                    ) { Text("Trascrivi ora") }
                    SettingsRows(prefs)
                }
            }
        }

        item { DriveCard(tick) }

        item {
            OutlinedTextField(
                value = query, onValueChange = { query = it },
                label = { Text("Cerca nelle trascrizioni") },
                singleLine = true, modifier = Modifier.fillMaxWidth()
            )
        }

        if (query.trim().length >= 2) {
            item { Text("${results.size} risultati", style = MaterialTheme.typography.labelLarge) }
            items(results, key = { it.id }) { s ->
                Column(Modifier.fillMaxWidth().clickable { onDay(Db.dayOf(s.startMs)) }.padding(vertical = 4.dp)) {
                    Text("${Db.dayOf(s.startMs)} ${Notifications.hhmm(s.startMs)}", style = MaterialTheme.typography.labelMedium)
                    Text(s.text.orEmpty())
                }
                HorizontalDivider()
            }
        } else {
            item { Text("Giorni", style = MaterialTheme.typography.titleMedium) }
            if (days.isEmpty()) item { Text("Ancora niente. Avvia la registrazione e parla.") }
            items(days, key = { it.day }) { d ->
                Row(
                    Modifier.fillMaxWidth().clickable { onDay(d.day) }.padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(dayLabel(d.day), fontWeight = FontWeight.Medium)
                        Text(
                            "${d.segments} pezzi · ~${d.chars / 6} parole" + if (d.pending > 0) " · ${d.pending} da trascrivere" else "",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Text("›")
                }
                HorizontalDivider()
            }
        }

        item {
            Spacer(Modifier.height(8.dp))
            Text("Esporta tutto (per usarlo poi con Claude o ChatGPT)", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { exportMd.launch("lifelog-${LocalDate.now()}.md") }) { Text("Markdown") }
                OutlinedButton(onClick = { exportJson.launch("lifelog-${LocalDate.now()}.json") }) { Text("JSON") }
            }
            TextButton(onClick = onSetup) { Text("Configurazione e impostazioni Samsung") }
            Spacer(Modifier.height(24.dp))
        }
    }
}

private suspend fun export(ctx: Context, uri: Uri, md: Boolean) {
    val msg = try {
        val db = Db.get(ctx)
        Exporter.write(ctx, uri, if (md) Exporter.markdown(db) else Exporter.json(db))
        "Esportato"
    } catch (e: Exception) {
        "Errore: ${e.message}"
    }
    withContext(Dispatchers.Main) { Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show() }
}

@Composable
private fun RecordingCard(tick: Int, onSetup: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    val wanted = remember(tick) { prefs.recordingWanted }
    val alive = remember(tick) { RecorderService.isAlive(ctx) }
    val stalled = remember(tick) { Watchdog.isStalled(ctx) }
    val ready = remember(tick) { Models.vadReady(ctx) && hasMic(ctx) }

    val colors = when {
        stalled -> CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
        wanted && alive -> CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        else -> CardDefaults.cardColors()
    }
    Card(Modifier.fillMaxWidth(), colors = colors) {
        Column(Modifier.padding(16.dp)) {
            val title = when {
                !ready -> "Configurazione da completare"
                stalled -> "Registrazione ferma dalle ${Notifications.hhmm(prefs.lastHeartbeat)}"
                wanted && alive -> "Sto ascoltando"
                wanted -> "Avvio…"
                else -> "Registrazione spenta"
            }
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(
                "Salvo solo i momenti in cui qualcuno parla. L'audio viene cancellato appena trascritto.",
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(8.dp))
            if (!ready) {
                Button(onClick = onSetup) { Text("Completa la configurazione") }
            } else if (wanted && !stalled) {
                OutlinedButton(onClick = { RecorderService.stop(ctx) }) { Text("Ferma") }
            } else {
                Button(onClick = {
                    try {
                        RecorderService.start(ctx)
                    } catch (e: Exception) {
                        Toast.makeText(ctx, "Non riesco ad avviare: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }) { Text(if (stalled) "Riprendi" else "Avvia registrazione") }
            }
        }
    }
}

@Composable
private fun SettingsRows(prefs: Prefs) {
    var onlyCharging by remember { mutableStateOf(prefs.onlyWhileCharging) }
    var waitCharge by remember { mutableStateOf(prefs.minChargePercent > 0) }
    Spacer(Modifier.height(8.dp))
    SwitchRow("Trascrivi solo in carica", onlyCharging) {
        onlyCharging = it; prefs.onlyWhileCharging = it
    }
    if (onlyCharging) {
        SwitchRow("Aspetta che la carica arrivi all'80%", waitCharge) {
            waitCharge = it; prefs.minChargePercent = if (it) 80 else 0
        }
    }
    Text(
        "Si ferma sempre se la batteria supera %.1f°C.".format(prefs.maxBatteryTemp),
        style = MaterialTheme.typography.bodySmall
    )
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

private fun dayLabel(day: String): String {
    val d = LocalDate.parse(day)
    val today = LocalDate.now()
    return when (d) {
        today -> "Oggi"
        today.minusDays(1) -> "Ieri"
        else -> day
    }
}

// ---------------------------------------------------------------- Giorno

@Composable
private fun DayScreen(day: String, tick: Int, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val db = remember { Db.get(ctx) }
    var items by remember { mutableStateOf(emptyList<Pair<Long, String>>()) }
    var pending by remember { mutableIntStateOf(0) }
    LaunchedEffect(day, tick) {
        withContext(Dispatchers.IO) {
            val segs = db.segmentsOfDay(day)
            pending = segs.count { it.text == null }
            val paras = Exporter.paragraphs(segs).map {
                it.startMs to "${Notifications.hhmm(it.startMs)}–${Notifications.hhmm(it.endMs)}\n${it.text}"
            }
            val gaps = db.gapsOfDay(day).map {
                it.startMs to "⚠ Non registrato ${Notifications.hhmm(it.startMs)}–${Notifications.hhmm(it.endMs)} " +
                    "(${(it.endMs - it.startMs) / 60_000} min): ${it.reason}"
            }
            items = (paras + gaps).sortedBy { it.first }
        }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ Indietro") }
            Text(dayLabel(day), style = MaterialTheme.typography.titleLarge)
        }
        if (pending > 0) Text("$pending pezzi ancora da trascrivere", style = MaterialTheme.typography.bodySmall)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(items) { (_, text) ->
                Text(text)
                HorizontalDivider(Modifier.padding(top = 10.dp))
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

// ---------------------------------------------------------------- Configurazione

private fun hasMic(ctx: Context) =
    ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

private fun hasNotif(ctx: Context) =
    ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

private fun ignoringBattery(ctx: Context) =
    ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)

private fun tryStart(ctx: Context, vararg intents: Intent): Boolean {
    for (i in intents) {
        try {
            ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return true
        } catch (_: ActivityNotFoundException) {
        } catch (_: SecurityException) {
        }
    }
    return false
}

private fun openSamsungBattery(ctx: Context) {
    val ok = tryStart(
        ctx,
        Intent().setComponent(ComponentName("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity")),
        Intent().setComponent(ComponentName("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity")),
        Intent(Intent.ACTION_POWER_USAGE_SUMMARY),
        Intent(Settings.ACTION_SETTINGS),
    )
    if (!ok) Toast.makeText(ctx, "Apri Impostazioni → Batteria a mano", Toast.LENGTH_LONG).show()
}

@Composable
private fun SetupScreen(tick: Int, onDone: () -> Unit) {
    val ctx = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh++ }
    val mic = remember(tick, refresh) { hasMic(ctx) }
    val notif = remember(tick, refresh) { hasNotif(ctx) }
    val battery = remember(tick, refresh) { ignoringBattery(ctx) }
    val vad = remember(tick) { Models.vadReady(ctx) }
    val parakeet = remember(tick) { Models.parakeetReady(ctx) }

    val work by WorkManager.getInstance(ctx)
        .getWorkInfosForUniqueWorkFlow(ModelSetupWorker.NAME)
        .collectAsState(initial = emptyList())
    val info = work.firstOrNull()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Configurazione", style = MaterialTheme.typography.headlineMedium)
        Text("Quattro passi, da fare una volta sola. Tutto resta sul telefono: niente cloud.")

        Step("1. Permessi microfono e notifiche", mic && notif) {
            Text("Il microfono serve per ascoltare. Le notifiche servono per avvisarti se la registrazione si ferma.")
            Button(onClick = {
                permLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS))
            }) { Text("Concedi i permessi") }
        }

        Step("2. Scarica il modello (circa ${Models.PARAKEET_MB} MB)", vad && parakeet) {
            Text("Si scarica una volta sola, poi la trascrizione funziona anche senza internet. Usa il Wi-Fi.")
            when (info?.state) {
                WorkInfo.State.RUNNING -> {
                    val pct = info.progress.getInt(ModelSetupWorker.KEY_PCT, 0)
                    val stage = info.progress.getString(ModelSetupWorker.KEY_STAGE) ?: "…"
                    Text(stage)
                    LinearProgressIndicator(progress = { pct / 100f }, modifier = Modifier.fillMaxWidth())
                }
                WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> {
                    Text("In attesa del Wi-Fi…")
                    TextButton(onClick = { ModelSetupWorker.start(ctx, wifiOnly = false) }) {
                        Text("Usa anche i dati mobili")
                    }
                }
                WorkInfo.State.FAILED -> {
                    Text("Download non riuscito: ${info.outputData.getString(ModelSetupWorker.KEY_ERROR) ?: ""}")
                    Button(onClick = { ModelSetupWorker.start(ctx, wifiOnly = true) }) { Text("Riprova") }
                }
                else -> Button(onClick = { ModelSetupWorker.start(ctx, wifiOnly = true) }) { Text("Scarica") }
            }
        }

        Step("3. Non far addormentare l'app", battery) {
            Text("Samsung chiude le app in sottofondo per risparmiare batteria. Premi il pulsante e scegli \"Consenti\".")
            Button(onClick = {
                tryStart(
                    ctx,
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")),
                    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
                )
            }) { Text("Togli le restrizioni batteria") }
        }

        Step("4. Controllo finale Samsung (facoltativo)", null) {
            if (battery) {
                Text(
                    "Fatto: Lifelog è già \"Senza restrizioni\" (lo hai concesso al passo 3). " +
                        "Per questo Samsung NON la mostra nell'elenco \"App mai in sospensione\": " +
                        "quell'elenco propone solo le app ancora con restrizioni. Va bene così, non serve altro."
                )
            } else {
                Text("Se il passo 3 non ha funzionato, fallo a mano: Info app → Batteria → Senza restrizioni.")
            }
            OutlinedButton(onClick = {
                tryStart(ctx, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")))
            }) { Text("Apri Info app per controllare") }
            Text(
                "Consigliato: in Impostazioni → Batteria → Limiti utilizzo in background " +
                    "(su One UI 6: Assistenza dispositivo → Batteria) spegni \"Metti in sospensione le app inutilizzate\"."
            )
            OutlinedButton(onClick = { openSamsungBattery(ctx) }) { Text("Apri impostazioni batteria") }
            Text(
                "Anche così, Samsung a volte chiude l'app. Se succede ti arriva una notifica con i minuti persi: toccala per riprendere.",
                style = MaterialTheme.typography.bodySmall
            )
        }

        Text(
            "Attenzione: registrare altre persone senza che lo sappiano può essere illegale (privacy, GDPR). " +
                "Usala per te e avvisa chi parla con te.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )

        // Mai bloccare: la schermata principale mostra comunque cosa manca.
        val missing = buildList {
            if (!mic) add("permesso microfono (passo 1)")
            if (!vad || !parakeet) add("modello (passo 2, può finire di scaricarsi da solo)")
            if (!battery) add("restrizioni batteria (passo 3)")
        }
        if (missing.isNotEmpty()) {
            Text("Manca ancora: " + missing.joinToString(", "), style = MaterialTheme.typography.bodySmall)
        }
        Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
            Text(if (missing.isEmpty()) "Fatto" else "Continua lo stesso")
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Step(title: String, done: Boolean?, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (done == true) Text("✓ fatto", color = MaterialTheme.colorScheme.primary)
            }
            if (done != true) content()
            else Spacer(Modifier.width(0.dp))
        }
    }
}

// ---------------------------------------------------------------- Errori

/** Se l'app si è chiusa per un errore, lo mostra e permette di inviarlo. */
@Composable
private fun CrashCard() {
    val ctx = LocalContext.current
    var report by remember { mutableStateOf(CrashLog.read(ctx)) }
    val r = report ?: return
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("L'app si è chiusa per un errore", fontWeight = FontWeight.Bold)
            Text("Invialo a Claude: tocca Condividi e incolla il testo nella chat.", style = MaterialTheme.typography.bodySmall)
            Text(r.lineSequence().take(4).joinToString("\n"), style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, r)
                    }
                    ctx.startActivity(Intent.createChooser(send, "Condividi errore"))
                }) { Text("Condividi") }
                OutlinedButton(onClick = { CrashLog.clear(ctx); report = null }) { Text("Chiudi") }
            }
        }
    }
}

// ---------------------------------------------------------------- Google Drive

@Composable
private fun DriveCard(tick: Int) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    val scope = rememberCoroutineScope()
    var configured by remember { mutableStateOf(DriveSync.isConfigured(ctx)) }
    val status = remember(tick, configured) { prefs.driveStatus }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
        if (uri != null) {
            DriveSync.setTarget(ctx, uri)
            configured = true
            Toast.makeText(ctx, "Collegato. Primo salvataggio in corso…", Toast.LENGTH_SHORT).show()
        }
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Google Drive (per chiedere a Claude)", fontWeight = FontWeight.Bold)
            if (!configured) {
                Text(
                    "Le trascrizioni (solo testo) vengono copiate in un file su Drive e aggiornate da sole. " +
                        "Tocca il pulsante, apri il menu ☰ in alto a sinistra, scegli \"Drive\", " +
                        "entra nella cartella che vuoi e premi \"Salva\".",
                    style = MaterialTheme.typography.bodySmall
                )
                Button(onClick = { pick.launch("Lifelog.md") }) { Text("Collega Google Drive") }
            } else {
                Text("Si aggiorna da solo dopo ogni trascrizione e ogni 6 ore.", style = MaterialTheme.typography.bodySmall)
                if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        scope.launch(Dispatchers.IO) {
                            val msg = DriveSync.syncBlocking(ctx)
                            withContext(Dispatchers.Main) { Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show() }
                        }
                    }) { Text("Sincronizza ora") }
                    TextButton(onClick = { DriveSync.disconnect(ctx); configured = false }) { Text("Scollega") }
                }
            }
        }
    }
}
