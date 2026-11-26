package it.iaco.lifelog

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * "Segna questo momento": un tocco (dalla tendina, dalla notifica o dall'app) salva l'ora.
 * Nel file per Claude diventa una riga "★ Iaco ha segnato questo momento" e di notte
 * la conversazione intorno entra sempre nell'analisi.
 */
object Marks {
    /** Salva il momento, vibra un attimo e ritorna l'ora (HH:mm). */
    fun add(ctx: Context): String {
        val now = System.currentTimeMillis()
        Db.get(ctx).insertMark(now)
        buzz(ctx)
        val hm = Notifications.hhmm(now)
        // La notifica dice subito che il momento è stato preso (al giro dopo torna normale).
        if (RecorderService.isAlive(ctx) && !Pause.active(ctx)) runCatching {
            ctx.getSystemService(NotificationManager::class.java)
                .notify(Notifications.ID_RECORDING, Notifications.recording(ctx, "★ Momento segnato alle $hm"))
        }
        MarkTile.refresh(ctx)
        return hm
    }

    private fun buzz(ctx: Context) = runCatching {
        val v: Vibrator = if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(VibratorManager::class.java).defaultVibrator
        else @Suppress("DEPRECATION") ctx.getSystemService(Vibrator::class.java)
        v.vibrate(VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE))
    }
}

/**
 * Pausa privacy a tempo: il microfono si spegne davvero (Android toglie il pallino verde)
 * e la registrazione riparte da sola allo scadere. Il servizio resta acceso, così Android
 * non impedisce la ripartenza dallo sfondo. Il tempo in pausa diventa un buco "pausa privacy".
 */
object Pause {
    @Volatile private var until = -1L
    @Volatile private var since = 0L

    private fun load(ctx: Context) {
        if (until >= 0) return
        val sp = ctx.applicationContext.getSharedPreferences("lifelog", Context.MODE_PRIVATE)
        until = sp.getLong("pause_until", 0L)
        since = sp.getLong("pause_since", 0L)
    }

    private fun save(ctx: Context) {
        ctx.applicationContext.getSharedPreferences("lifelog", Context.MODE_PRIVATE).edit()
            .putLong("pause_until", until).putLong("pause_since", since).apply()
    }

    fun active(ctx: Context, now: Long = System.currentTimeMillis()): Boolean {
        load(ctx)
        return until > now
    }

    /** Fino a quando dura la pausa (ms), o 0 se non c'è. */
    fun until(ctx: Context): Long { load(ctx); return if (until > System.currentTimeMillis()) until else 0L }

    fun since(ctx: Context): Long { load(ctx); return since }

    /** Mette in pausa per [minutes] minuti. Se è già in pausa, sposta solo la fine. */
    fun start(ctx: Context, minutes: Int): Boolean {
        if (!Prefs(ctx).recordingWanted) return false
        load(ctx)
        val now = System.currentTimeMillis()
        if (until <= now) since = now
        until = now + minutes * 60_000L
        save(ctx)
        runCatching {
            ctx.getSystemService(NotificationManager::class.java)
                .notify(Notifications.ID_RECORDING, Notifications.paused(ctx, until))
        }
        MarkTile.refresh(ctx)
        return true
    }

    /** Riprende subito (il servizio se ne accorge entro un secondo). */
    fun resume(ctx: Context) {
        load(ctx)
        if (until > System.currentTimeMillis()) until = System.currentTimeMillis()
        save(ctx)
    }

    /** Pausa finita e già registrata come buco. */
    fun clear(ctx: Context) {
        until = 0L; since = 0L
        save(ctx)
        MarkTile.refresh(ctx)
    }
}

/** Tasti della notifica: "Segna momento", "Pausa 15 min", "Pausa 1 ora", "Riprendi". */
class MomentReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val ctx = context.applicationContext
        when (intent.action) {
            ACTION_MARK -> Marks.add(ctx)
            ACTION_PAUSE -> Pause.start(ctx, intent.getIntExtra(EXTRA_MINUTES, 60))
            ACTION_RESUME -> Pause.resume(ctx)
        }
    }

    companion object {
        const val ACTION_MARK = "it.iaco.lifelog.MARK"
        const val ACTION_PAUSE = "it.iaco.lifelog.PAUSE"
        const val ACTION_RESUME = "it.iaco.lifelog.RESUME"
        const val EXTRA_MINUTES = "minutes"
    }
}

/** Riquadro nella tendina rapida di Android: un tocco segna il momento. */
class MarkTile : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        update(null)
    }

    override fun onClick() {
        super.onClick()
        if (!Prefs(this).recordingWanted) {
            update("Lifelog non ascolta")
            return
        }
        update("Segnato alle ${Marks.add(this)}")
    }

    private fun update(note: String?) {
        val t = qsTile ?: return
        val on = Prefs(this).recordingWanted
        val paused = Pause.active(this)
        t.label = "Segna momento"
        t.state = if (on && !paused) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= 29) t.subtitle = note ?: when {
            !on -> "Lifelog è fermo"
            paused -> "In pausa fino alle ${Notifications.hhmm(Pause.until(this))}"
            else -> "Tocca per ricordarlo"
        }
        t.updateTile()
    }

    companion object {
        fun refresh(ctx: Context) = runCatching {
            requestListeningState(ctx, ComponentName(ctx, MarkTile::class.java))
        }
    }
}
