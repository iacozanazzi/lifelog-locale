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
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.work.WorkInfo
import androidx.work.WorkManager
import it.iaco.lifelog.CrashLog
import it.iaco.lifelog.Db
import it.iaco.lifelog.DriveSync
import it.iaco.lifelog.Exporter
import it.iaco.lifelog.Gap
import it.iaco.lifelog.Live
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
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private sealed interface Route {
    data object Main : Route
    data object Setup : Route
    data class Day(val day: String) : Route
}

@Composable
fun App(resumeTick: Int) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    var route by remember { mutableStateOf<Route>(if (prefs.onboardingDone) Route.Main else Route.Setup) }
    var tab by remember { mutableStateOf(Tab.TODAY) }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(2000); tick++
        }
    }
    LaunchedEffect(resumeTick) { tick++ }
    BackHandler(enabled = route != Route.Main || tab != Tab.TODAY) {
        if (route != Route.Main) route = Route.Main else tab = Tab.TODAY
    }

    LifeTheme {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            Column(Modifier.safeDrawingPadding()) {
                AnimatedContent(
                    targetState = route,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    modifier = Modifier.weight(1f),
                    label = "route"
                ) { r ->
                    when (r) {
                        Route.Setup -> SetupRoute(tick) {
                            prefs.onboardingDone = true
                            route = Route.Main
                        }
                        is Route.Day -> DayRoute(r.day, tick) { route = Route.Main }
                        Route.Main -> when (tab) {
                            Tab.TODAY -> TodayRoute(tick, onSetup = { route = Route.Setup }, onDay = { route = Route.Day(it) })
                            Tab.DAYS -> DaysRoute(tick, onDay = { route = Route.Day(it) })
                            Tab.SEARCH -> SearchRoute(onDay = { route = Route.Day(it) })
                            Tab.SETTINGS -> SettingsRoute(tick, onSetup = { route = Route.Setup })
                        }
                    }
                }
                if (route == Route.Main) BottomBar(tab) { tab = it }
            }
        }
    }
}

// =============================================================== Dati → interfaccia

private val zone get() = ZoneId.systemDefault()
private val ITA = Locale.ITALIAN

private fun minuteOfDay(ms: Long): Float {
    val t = Instant.ofEpochMilli(ms).atZone(zone).toLocalTime()
    return t.hour * 60f + t.minute + t.second / 60f
}

private fun spansOf(segs: List<Segment>, gaps: List<Gap>): List<Span> =
    segs.map {
        Span(minuteOfDay(it.startMs), minuteOfDay(it.endMs).coerceAtLeast(minuteOfDay(it.startMs)),
            if (it.text == null) Span.Kind.PENDING else Span.Kind.TRANSCRIBED)
    } + gaps.map {
        val from = minuteOfDay(it.startMs)
        val sameDay = Db.dayOf(it.startMs) == Db.dayOf(it.endMs)
        Span(from, if (sameDay) minuteOfDay(it.endMs) else 1440f, Span.Kind.GAP)
    }

private fun wordsOf(segs: List<Segment>) = segs.sumOf { s ->
    s.text?.trim()?.takeIf { it.isNotEmpty() }?.split(Regex("\\s+"))?.size ?: 0
}

private fun dayTitle(day: String): String {
    val d = LocalDate.parse(day)
    val today = LocalDate.now()
    return when (d) {
        today -> "Oggi"
        today.minusDays(1) -> "Ieri"
        else -> d.format(DateTimeFormatter.ofPattern("EEEE d", ITA)).replaceFirstChar { c -> c.uppercase() }
    }
}

private fun daySubtitle(day: String): String =
    LocalDate.parse(day).format(DateTimeFormatter.ofPattern("d MMMM yyyy", ITA))

private fun momentsOf(segs: List<Segment>, gaps: List<Gap>): List<Pair<Long, Moment>> =
    Exporter.paragraphs(segs).map {
        it.startMs to Moment("${Notifications.hhmm(it.startMs)}–${Notifications.hhmm(it.endMs)}", it.text)
    } + gaps.map {
        it.startMs to Moment(
            "${Notifications.hhmm(it.startMs)}–${Notifications.hhmm(it.endMs)}",
            "non registrato (${(it.endMs - it.startMs) / 60_000} min). Il sistema ha chiuso l'app.",
            gap = true
        )
    }

// =============================================================== Oggi

/** Divide i paragrafi in frasi, per mostrarle una per riga come un testo che scorre. */
private fun sentences(text: String): List<String> =
    text.split(Regex("(?<=[.!?…])\\s+")).map { it.trim() }.filter { it.length > 1 }

@Composable
private fun TodayRoute(tick: Int, onSetup: () -> Unit, onDay: (String) -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    val db = remember { Db.get(ctx) }
    val level by Live.level.collectAsState()
    val speaking by Live.speaking.collectAsState()
    var crash by remember { mutableStateOf(CrashLog.read(ctx)) }
    var base by remember { mutableStateOf<TodayUi?>(null) }

    LaunchedEffect(tick) {
        base = withContext(Dispatchers.IO) {
            val today = Db.today()
            val segs = db.segmentsOfDay(today)
            val wanted = prefs.recordingWanted
            val stalled = Watchdog.isStalled(ctx)
            val alive = RecorderService.isAlive(ctx)
            val pending = db.pendingCount()
            val paras = Exporter.paragraphs(segs)
            val lines = paras.flatMap { sentences(it.text) }.takeLast(5)
            TodayUi(
                dateLabel = LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE d MMMM", ITA)).replaceFirstChar { it.uppercase() },
                ready = Models.vadReady(ctx) && hasMic(ctx),
                state = when {
                    stalled -> RecState.STALLED
                    wanted && alive -> RecState.LISTENING
                    else -> RecState.OFF
                },
                stalledSince = if (stalled) Notifications.hhmm(prefs.lastHeartbeat) else null,
                level = 0f, speaking = false,
                speechMinutes = segs.sumOf { it.endMs - it.startMs } / 60_000,
                pending = pending,
                pendingMinutes = db.pendingSeconds() / 60,
                transcribeStatus = prefs.transcribeStatus,
                canTranscribeNow = pending > 0 && Models.parakeetReady(ctx),
                lines = lines,
                lastTime = paras.lastOrNull()?.let { Notifications.hhmm(it.endMs) },
                crash = null,
            )
        }
    }
    val s = base ?: return
    TodayScreen(
        s.copy(level = level, speaking = speaking, crash = crash),
        onToggle = {
            try {
                if (s.state == RecState.LISTENING) RecorderService.stop(ctx) else RecorderService.start(ctx)
            } catch (e: Exception) {
                Toast.makeText(ctx, "Non riesco ad avviare: ${e.message}", Toast.LENGTH_LONG).show()
            }
        },
        onSetup = onSetup,
        onOpenToday = { onDay(Db.today()) },
        onTranscribeNow = {
            TranscribeWorker.runNow(ctx)
            Toast.makeText(ctx, "Trascrizione avviata. Si ferma se la batteria si scalda.", Toast.LENGTH_SHORT).show()
        },
        onShareCrash = { shareText(ctx, crash.orEmpty(), "Condividi l'errore") },
        onDismissCrash = { CrashLog.clear(ctx); crash = null },
    )
}

private fun shareText(ctx: Context, text: String, title: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    ctx.startActivity(Intent.createChooser(send, title))
}

private fun durationLabel(ms: Long): String {
    val m = ms / 60_000
    return if (m < 60) "$m min" else "${m / 60} h ${m % 60} min"
}

// =============================================================== Giorni

@Composable
private fun DaysRoute(tick: Int, onDay: (String) -> Unit) {
    val ctx = LocalContext.current
    val db = remember { Db.get(ctx) }
    var days by remember { mutableStateOf(emptyList<DayRow>()) }
    LaunchedEffect(tick) {
        days = withContext(Dispatchers.IO) {
            db.days().take(120).map { d ->
                DayRow(
                    day = d.day, title = dayTitle(d.day),
                    meta = durationLabel(d.speechMs),
                    spans = spansOf(db.segmentsOfDay(d.day), db.gapsOfDay(d.day)),
                )
            }
        }
    }
    DaysScreen(days, onDay)
}

// =============================================================== Cerca

@Composable
private fun SearchRoute(onDay: (String) -> Unit) {
    val ctx = LocalContext.current
    val db = remember { Db.get(ctx) }
    var query by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf(emptyList<SearchHit>()) }
    LaunchedEffect(query) {
        val q = query.trim()
        if (q.length >= 2) delay(250)
        hits = if (q.length < 2) emptyList() else withContext(Dispatchers.IO) {
            db.search(q).map {
                val day = Db.dayOf(it.startMs)
                SearchHit(day, "${dayTitle(day)}, ${Notifications.hhmm(it.startMs)}", it.text.orEmpty())
            }
        }
    }
    SearchScreen(query, hits, onQuery = { query = it }, onDay = onDay)
}

// =============================================================== Giorno

private fun partOfDay(ms: Long): String {
    val h = Instant.ofEpochMilli(ms).atZone(zone).hour
    return when (h) {
        in 5..11 -> "Mattina"
        in 12..17 -> "Pomeriggio"
        in 18..22 -> "Sera"
        else -> "Notte"
    }
}

@Composable
private fun DayRoute(day: String, tick: Int, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val db = remember { Db.get(ctx) }
    var ui by remember { mutableStateOf<DayUi?>(null) }
    var plain by remember { mutableStateOf("") }
    LaunchedEffect(day, tick) {
        withContext(Dispatchers.IO) {
            val segs = db.segmentsOfDay(day)
            val gaps = db.gapsOfDay(day)
            val words = wordsOf(segs)
            val all = momentsOf(segs, gaps).sortedBy { it.first }
            val sections = all.groupBy { partOfDay(it.first) }.map { (k, v) -> k to v.map { it.second } }
            ui = DayUi(
                title = dayTitle(day),
                subtitle = "${daySubtitle(day)}. ${durationLabel(segs.sumOf { it.endMs - it.startMs })} di voce, ${"%,d".format(ITA, words)} parole",
                spans = spansOf(segs, gaps),
                sections = sections,
                pending = segs.count { it.text == null },
            )
            plain = "Lifelog, ${daySubtitle(day)}\n\n" + all.filter { !it.second.gap }.joinToString("\n\n") { "${it.second.time}\n${it.second.text}" }
        }
    }
    ui?.let { DayScreen(it, onBack, onShare = { shareText(ctx, plain, "Condividi la giornata") }) }
}

// =============================================================== Impostazioni

@Composable
private fun SettingsRoute(tick: Int, onSetup: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    val scope = rememberCoroutineScope()
    var version by remember { mutableIntStateOf(0) } // forza la rilettura dopo un cambio
    val ui = remember(tick, version) {
        SettingsUi(
            onlyCharging = prefs.onlyWhileCharging,
            waitCharge = prefs.minChargePercent > 0,
            maxTemp = prefs.maxBatteryTemp,
            battery = Throttle.battery(ctx).let { b ->
                "Ora: batteria al %d%%, %.1f °C, %s.".format(b.percent, b.tempC, if (b.plugged) "in carica" else "non in carica")
            },
            driveConfigured = DriveSync.isConfigured(ctx),
            driveStatus = prefs.driveStatus,
            version = try {
                ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: ""
            } catch (_: Exception) { "" },
        )
    }
    val pickDrive = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
        if (uri != null) {
            DriveSync.setTarget(ctx, uri)
            version++
            Toast.makeText(ctx, "Collegato. Primo salvataggio in corso…", Toast.LENGTH_SHORT).show()
        }
    }
    val exportMd = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
        if (uri != null) scope.launch(Dispatchers.IO) { export(ctx, uri, md = true) }
    }
    val exportJson = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch(Dispatchers.IO) { export(ctx, uri, md = false) }
    }
    SettingsScreen(
        ui,
        onOnlyCharging = { prefs.onlyWhileCharging = it; version++ },
        onWaitCharge = { prefs.minChargePercent = if (it) 80 else 0; version++ },
        onConnectDrive = { pickDrive.launch("Lifelog.md") },
        onSyncDrive = {
            scope.launch(Dispatchers.IO) {
                val msg = DriveSync.syncBlocking(ctx)
                withContext(Dispatchers.Main) {
                    version++
                    Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
                }
            }
        },
        onDisconnectDrive = { DriveSync.disconnect(ctx); version++ },
        onExportMd = { exportMd.launch("lifelog-${LocalDate.now()}.md") },
        onExportJson = { exportJson.launch("lifelog-${LocalDate.now()}.json") },
        onSetup = onSetup,
    )
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

// =============================================================== Configurazione

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
private fun SetupRoute(tick: Int, onDone: () -> Unit) {
    val ctx = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh++ }
    val work by WorkManager.getInstance(ctx)
        .getWorkInfosForUniqueWorkFlow(ModelSetupWorker.NAME)
        .collectAsState(initial = emptyList())
    val info = work.firstOrNull()
    val ready = remember(tick, info?.state) { Models.allReady(ctx) }
    val ui = SetupUi(
        mic = remember(tick, refresh) { hasMic(ctx) },
        notif = remember(tick, refresh) { hasNotif(ctx) },
        battery = remember(tick, refresh) { ignoringBattery(ctx) },
        download = when {
            ready -> DownloadState.DONE
            info?.state == WorkInfo.State.RUNNING -> DownloadState.RUNNING
            info?.state == WorkInfo.State.ENQUEUED || info?.state == WorkInfo.State.BLOCKED -> DownloadState.WAITING_WIFI
            info?.state == WorkInfo.State.FAILED -> DownloadState.FAILED
            else -> DownloadState.IDLE
        },
        downloadPct = info?.progress?.getInt(ModelSetupWorker.KEY_PCT, 0) ?: 0,
        downloadStage = info?.progress?.getString(ModelSetupWorker.KEY_STAGE) ?: "Avvio…",
        downloadError = info?.outputData?.getString(ModelSetupWorker.KEY_ERROR) ?: "",
    )
    SetupScreen(
        ui,
        onPermissions = { permLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)) },
        onDownload = { ModelSetupWorker.start(ctx, wifiOnly = true) },
        onDownloadMobile = { ModelSetupWorker.start(ctx, wifiOnly = false) },
        onBattery = {
            tryStart(
                ctx,
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")),
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            )
        },
        onAppInfo = { tryStart(ctx, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))) },
        onSamsungBattery = { openSamsungBattery(ctx) },
        onDone = onDone,
    )
}
