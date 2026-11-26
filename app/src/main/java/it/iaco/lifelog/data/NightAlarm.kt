package it.iaco.lifelog.data

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

/**
 * Sveglie per l'invio notturno a Claude (23:00 e 2:45) e la ricezione del mattino (7:00).
 * Inesatte ma consentite anche in riposo (setAndAllowWhileIdle, nessun permesso speciale).
 * Il processo dell'app è vivo per la registrazione, quindi ha rete anche di notte: l'invio parte
 * subito dentro la sveglia, e un lavoro WorkManager fa da riserva se non riesce.
 */
object NightAlarm {
    private const val ACTION_UPLOAD = "it.iaco.lifelog.NIGHT_UPLOAD"
    private const val ACTION_FETCH = "it.iaco.lifelog.MORNING_FETCH"

    private fun pi(ctx: Context, action: String) = PendingIntent.getBroadcast(
        ctx, action.hashCode(), Intent(ctx, NightAlarmReceiver::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun at(ctx: Context, action: String, t: LocalDateTime) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val ms = t.atZone(Assistant.zone).toInstant().toEpochMilli()
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, pi(ctx, action))
    }

    /** Idempotente: la stessa sveglia si sovrascrive. */
    fun arm(ctx: Context) {
        if (!Assistant.isConfigured(ctx)) return
        at(ctx, ACTION_UPLOAD, Assistant.nextAt(Assistant.UPLOAD_TIMES))
        at(ctx, ACTION_FETCH, Assistant.nextAt(listOf(Assistant.FETCH_TIME)))
    }

    fun handle(ctx: Context, action: String?) {
        when (action) {
            ACTION_UPLOAD -> {
                runCatching { Assistant.upload(ctx) }.onFailure { Log.w("NightAlarm", "invio", it) }
                Assistant.uploadSoon(ctx) // riserva: se è andato bene non rimanda niente (hash uguali)
            }
            ACTION_FETCH -> {
                runCatching { Assistant.fetch(ctx) }.onFailure { Log.w("NightAlarm", "ricezione", it); Assistant.fetchSoon(ctx) }
            }
        }
        runCatching { arm(ctx) }
    }
}

class NightAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val ctx = context.applicationContext
        val pending = goAsync()
        Thread {
            try {
                NightAlarm.handle(ctx, intent.action)
            } catch (_: Throwable) {
            } finally {
                pending.finish()
            }
        }.start()
    }
}
