package it.iaco.lifelog

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import it.iaco.lifelog.ui.MainActivity
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object Notifications {
    const val CH_RECORDING = "recording"
    const val CH_ALERTS = "alerts"
    const val CH_WORK = "work"

    const val ID_RECORDING = 1
    const val ID_ALERT = 2
    const val ID_DOWNLOAD = 3
    const val ID_TRANSCRIBE = 4

    const val EXTRA_RESUME = "resume_recording"

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_RECORDING, "Registrazione attiva", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ALERTS, "Avvisi (registrazione interrotta)", NotificationManager.IMPORTANCE_HIGH)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_WORK, "Download e trascrizione", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun openApp(ctx: Context, resume: Boolean): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_RESUME, resume)
        }
        return PendingIntent.getActivity(
            ctx, if (resume) 1 else 0, i,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    fun recording(ctx: Context, text: String) =
        NotificationCompat.Builder(ctx, CH_RECORDING)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("Lifelog sta ascoltando")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(openApp(ctx, false))
            .build()

    fun work(ctx: Context, title: String, text: String, progress: Int = -1) =
        NotificationCompat.Builder(ctx, CH_WORK)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .apply { if (progress >= 0) setProgress(100, progress, false) }
            .build()

    /** Trascrizione forzata: resta visibile finché lavora, con il tasto per fermarla. */
    fun forced(ctx: Context, text: String) =
        NotificationCompat.Builder(ctx, CH_WORK)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("Trascrivo anche senza carica")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp(ctx, false))
            .addAction(
                0, "Ferma",
                PendingIntent.getBroadcast(
                    ctx, 7, Intent(ctx, ForceStopReceiver::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
            .build()

    /** Avviso ben visibile: tocca per riprendere la registrazione. */
    fun alert(ctx: Context, title: String, text: String) {
        val n = NotificationCompat.Builder(ctx, CH_ALERTS)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openApp(ctx, true))
            .build()
        try {
            NotificationManagerCompat.from(ctx).notify(ID_ALERT, n)
        } catch (_: SecurityException) {
            // permesso notifiche negato: l'avviso resta visibile dentro l'app
        }
    }

    fun clearAlert(ctx: Context) = NotificationManagerCompat.from(ctx).cancel(ID_ALERT)

    private val hm = DateTimeFormatter.ofPattern("HH:mm")
    fun hhmm(ms: Long): String = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(hm)
}
