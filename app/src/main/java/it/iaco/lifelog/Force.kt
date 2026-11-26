package it.iaco.lifelog

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationManagerCompat
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.WorkManager

/**
 * "Trascrivi adesso": Iaco decide lui. La trascrizione (Parakeet, poi la rifinitura con Whisper)
 * continua anche senza carica, col risparmio energetico e col telefono tiepido,
 * finché non è tutto fatto o finché non tocca "Ferma". Resta solo un limite di sicurezza
 * per non rovinare la batteria (vedi [Throttle.blockReason]).
 */
object Force {
    fun on(ctx: Context) = Prefs(ctx).forceTranscribe

    fun start(ctx: Context) {
        val p = Prefs(ctx)
        p.forceTranscribe = true
        p.transcribeStatus = "Avvio la trascrizione…"
        // Sostituisce le attese "in carica" già in coda; se non c'è niente per Parakeet passa a Whisper.
        TranscribeWorker.schedule(ctx, 0, ExistingWorkPolicy.REPLACE)
    }

    fun stop(ctx: Context) {
        val p = Prefs(ctx)
        p.forceTranscribe = false
        val wm = WorkManager.getInstance(ctx)
        wm.cancelUniqueWork(TranscribeWorker.NAME)
        wm.cancelUniqueWork(Refine.NAME)
        NotificationManagerCompat.from(ctx).cancel(Notifications.ID_TRANSCRIBE)
        p.transcribeStatus = "Fermata. Riprende da sola quando metti in carica"
        // Torna al funzionamento normale: in carica.
        TranscribeWorker.schedule(ctx)
        Refine.schedule(ctx)
    }

    /** Tutto trascritto e rifinito: la forzatura finisce da sola. */
    fun finished(ctx: Context) {
        val p = Prefs(ctx)
        if (!p.forceTranscribe) return
        p.forceTranscribe = false
        NotificationManagerCompat.from(ctx).cancel(Notifications.ID_TRANSCRIBE)
    }

    fun foreground(ctx: Context, text: String) = ForegroundInfo(
        Notifications.ID_TRANSCRIBE,
        Notifications.forced(ctx, text),
        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
    )
}

/** Tasto "Ferma" della notifica. */
class ForceStopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Force.stop(context.applicationContext)
}
