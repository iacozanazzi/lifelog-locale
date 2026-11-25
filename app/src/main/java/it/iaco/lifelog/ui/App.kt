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
                        Route.Main -> AnimatedContent(tab, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "tab") { t ->
                            when (t) {
                                Tab.TODAY -> TodayRoute(tick, onSetup = { route = Route.Setup }, onDay = { route = Route.Day(it) })
                                Tab.ARCHIVE -> ArchiveRoute(tick, onDay = { route = Route.Day(it) })
                                Tab.SETTINGS -> SettingsRoute(tick, onSetup = { route = Route.Setup })
                            }
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
            "Non registrato per ${(it.endMs - it.startMs) / 60_000} minuti (${it.reason})",
            gap = true
        )
    }

// =============================================================== Oggi

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
            val gaps = db.gapsOfDay(today)
            val wanted = prefs.recordingWanted
            val stalled = Watchdog.isStalled(ctx)
            val alive = RecorderService.isAlive(ctx)
            val b = Throttle.battery(ctx)
            val pending = db.pendingCount()
            TodayUi(
                dateLabel = LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE d MMMM", ITA)),
                ready = Models.vadReady(ctx) && hasMic(ctx),
                mode = when {
                    stalled -> OrbMode.STALLED
                    wanted && alive -> OrbMode.LISTENING
                    else -> OrbMode.OFF
                },
                wanted = wanted,
                stalledSince = if (stalled) Notifications.hhmm(prefs.lastHeartbeat) else null,
                level = 0f, speaking = false,
                speechMinutes = segs.sumOf { it.endMs - it.startMs } / 60_000,
                words = wordsOf(segs),
                pending = pending,
                pendingMinutes = db.pendingSeconds() / 60,
                batteryLine = "Batteria %d%% · %.1f °C · %s".format(b.percent, b.tempC, if (b.plugged) "in carica" else "non in carica"),
                transcribeStatus = prefs.transcribeStatus,
                canTranscribeNow = pending > 0 && Models.parakeetReady(ctx),
                spans = spansOf(segs, gaps),
                nowMin = minuteOfDay(System.currentTimeMillis()),
                latest = momentsOf(segs, gaps).sortedByDescending { it.first }.take(3).map { it.second },
                crash = null,
            )
        }
    }
    val s = base ?: return
    TodayScreen(
        s.copy(level = level, speaking = speaking, crash = crash),
        onStart = {
            try {
                RecorderService.start(ctx)
            } catch (e: Exception) {
                Toast.makeText(ctx, "Non riesco ad avviare: ${e.message}", Toast.LENGTH_LONG).show()
            }
        },
        onStop = { RecorderService.stop(ctx) },
        onSetup = onSetup,
        onOpenToday = { onDay(Db.today()) },
        onTranscribeNow = {
            TranscribeWorker.runNow(ctx)
            Toast.makeText(ctx, "Avviata. Si ferma da sola se la batteria si scalda.", Toast.LENGTH_SHORT).show()
        },
        onShareCrash = {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, crash)
            }
            ctx.startActivity(Intent.createChooser(send, "Condividi errore"))
        },
        onDismissCrash = { CrashLog.clear(ctx); crash = null },
    )
}

// =============================================================== Archivio

@Composable
private fun ArchiveRoute(tick: Int, onDay: (String) -> Unit) {
    val ctx = LocalContext.current
    val db = remember { Db.get(ctx) }
    var query by remember { mutableStateOf("") }
    var days by remember { mutableStateOf(emptyList<DayRow>()) }
    var results by remember { mutableStateOf(emptyList<Pair<String, Moment>>()) }
    LaunchedEffect(tick) {
        days = withContext(Dispatchers.IO) {
            db.days().take(90).map { d ->
                val segs = db.segmentsOfDay(d.day)
                DayRow(
                    day = d.day, title = dayTitle(d.day), subtitle = daySubtitle(d.day),
                    speechMinutes = d.speechMs / 60_000, words = wordsOf(segs), pending = d.pending,
                    spans = spansOf(segs, db.gapsOfDay(d.day)),
                )
            }
        }
    }
    LaunchedEffect(query) {
        val q = query.trim()
        results = if (q.length < 2) emptyList() else withContext(Dispatchers.IO) {
            db.search(q).map { Db.dayOf(it.startMs) to Moment(Notifications.hhmm(it.startMs), it.text.orEmpty()) }
        }
    }
    ArchiveScreen(days, query, results, onQuery = { query = it }, onDay = onDay)
}

// =============================================================== Giorno

@Composable
private fun DayRoute(day: String, tick: Int, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val db = remember { Db.get(ctx) }
    var ui by remember { mutableStateOf<DayUi?>(null) }
    LaunchedEffect(day, tick) {
        ui = withContext(Dispatchers.IO) {
            val segs = db.segmentsOfDay(day)
            val gaps = db.gapsOfDay(day)
            DayUi(
                title = dayTitle(day), subtitle = daySubtitle(day),
                spans = spansOf(segs, gaps),
                moments = momentsOf(segs, gaps).sortedBy { it.first }.map { it.second },
                pending = segs.count { it.text == null },
            )
        }
    }
    ui?.let { DayScreen(it, onBack) }
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
