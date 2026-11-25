package it.iaco.lifelog

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Sorveglia la registrazione. Samsung (e Android) a volte uccidono i servizi in
 * sottofondo: se succede, registriamo il "buco" e avvisiamo l'utente.
 *
 * Nota onesta: da Android 14 un'app NON può riaccendere il microfono da sola
 * partendo dallo sfondo. Quindi il watchdog prova a ripartire, ma se Android
 * lo blocca manda una notifica: basta toccarla per riprendere.
 */
object Watchdog {
    private const val NAME = "watchdog"
    private const val STALE_MS = 3 * 60_000L

    fun schedule(ctx: Context) {
        val req = PeriodicWorkRequestBuilder<WatchdogWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    fun cancel(ctx: Context) {
        WorkManager.getInstance(ctx).cancelUniqueWork(NAME)
    }

    /** La registrazione dovrebbe essere attiva ma non dà segni di vita. */
    fun isStalled(ctx: Context): Boolean {
        val p = Prefs(ctx)
        if (!p.recordingWanted) return false
        val hb = p.lastHeartbeat
        return hb > 0 && System.currentTimeMillis() - hb > STALE_MS
    }

    fun stalledSince(ctx: Context): Long = Prefs(ctx).lastHeartbeat

    /** Alla ripartenza: se c'è stato un buco, lo salva e lo dice. */
    fun recordGapIfAny(ctx: Context) {
        val p = Prefs(ctx)
        val hb = p.lastHeartbeat
        val now = System.currentTimeMillis()
        if (hb > 0 && now - hb > STALE_MS) {
            val minutes = (now - hb) / 60_000
            Db.get(ctx).insertGap(hb, now, "servizio fermato dal sistema")
            Notifications.alert(
                ctx, "Persi $minutes minuti di registrazione",
                "Dalle ${Notifications.hhmm(hb)} alle ${Notifications.hhmm(now)} non ho registrato. " +
                    "Se succede spesso, ricontrolla le impostazioni batteria Samsung in Lifelog."
            )
        }
        p.lastHeartbeat = now
    }
}

class WatchdogWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        if (!Prefs(ctx).recordingWanted) {
            Watchdog.cancel(ctx)
            return Result.success()
        }
        if (Watchdog.isStalled(ctx)) {
            val since = Watchdog.stalledSince(ctx)
            val minutes = (System.currentTimeMillis() - since) / 60_000
            Notifications.alert(
                ctx, "Registrazione ferma da $minutes minuti",
                "Si è fermata alle ${Notifications.hhmm(since)}. Tocca qui per riprendere."
            )
            // Tentativo di ripartenza (spesso Android lo blocca: allora resta la notifica;
            // se invece riesce, il servizio cancella la notifica e registra il buco).
            try {
                RecorderService.start(ctx)
            } catch (_: Exception) {
            }
        }
        // Occasione buona anche per controllare se c'è da trascrivere.
        TranscribeWorker.schedule(ctx)
        return Result.success()
    }
}

/** Dopo un riavvio o un aggiornamento dell'app: chiede all'utente di riprendere. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val p = Prefs(context)
        if (!p.recordingWanted) return
        val reason = if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) "App aggiornata" else "Telefono riavviato"
        Notifications.alert(context, "$reason: registrazione in pausa", "Tocca qui per riprendere ad ascoltare.")
        Watchdog.schedule(context)
    }
}
