package it.iaco.lifelog

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import android.os.Process
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Regole per non scaldare il telefono e non consumare batteria. */
object Throttle {
    data class Battery(val tempC: Float, val percent: Int, val plugged: Boolean)

    fun battery(ctx: Context): Battery {
        val i: Intent? = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val temp = (i?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f
        val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val plugged = (i?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else 0
        return Battery(temp, pct, plugged)
    }

    /** Limiti che valgono anche quando Iaco forza la trascrizione: proteggono la batteria. */
    const val HARD_TEMP_C = 42f
    private const val HARD_MIN_PERCENT = 15

    /** null = si può lavorare; altrimenti il motivo per aspettare. */
    fun blockReason(ctx: Context, manual: Boolean): String? {
        val p = Prefs(ctx)
        val b = battery(ctx)
        val pm = ctx.getSystemService(PowerManager::class.java)
        if (manual || p.forceTranscribe) {
            if (b.tempC >= HARD_TEMP_C) {
                return "Pausa di sicurezza: batteria a %.1f°C, riprendo sotto i %.0f°C".format(b.tempC, HARD_TEMP_C)
            }
            if (pm.currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE) return "Pausa di sicurezza: il telefono è molto caldo"
            if (!b.plugged && b.percent < HARD_MIN_PERCENT) return "Batteria al ${b.percent}%: metti in carica per continuare"
            return null
        }
        if (b.tempC >= p.maxBatteryTemp) {
            return "Pausa: batteria a %.1f°C (limite %.1f°C)".format(b.tempC, p.maxBatteryTemp)
        }
        if (pm.currentThermalStatus >= PowerManager.THERMAL_STATUS_MODERATE) {
            return "Pausa: il telefono è caldo"
        }
        if (pm.isPowerSaveMode && !b.plugged) return "Pausa: risparmio energetico attivo"
        if (p.onlyWhileCharging && !b.plugged) return "In attesa: trascrivo quando metti in carica"
        if (p.onlyWhileCharging && p.minChargePercent > 0 && b.percent < p.minChargePercent) {
            return "In attesa: carica al ${b.percent}%, comincio dal ${p.minChargePercent}%"
        }
        return null
    }
}

/**
 * Trascrive i pezzi di parlato in attesa con il motore scelto, tutto sul telefono.
 * Usa solo 2 thread a bassa priorità e si ferma se la batteria si scalda.
 * Lavora a blocchi di ~8 minuti (limite di Android per i lavori in sottofondo)
 * e poi si rimette in coda da solo.
 */
class TranscribeWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val prefs = Prefs(ctx)
        val manual = prefs.forceTranscribe
        // Se il motore scelto non è ancora pronto (es. Whisper in download) uso Parakeet.
        val engine = listOf(prefs.engine, Engine.PARAKEET).firstOrNull { AsrFactory.ready(ctx, it) } ?: run {
            prefs.transcribeStatus = "Il modello di trascrizione non è ancora scaricato"
            return Result.success()
        }
        val db = Db.get(ctx)
        if (db.pendingCount() == 0) {
            prefs.transcribeStatus = "Tutto trascritto"
            // Forzata: passa subito alla rifinitura con Whisper, anche senza carica.
            if (prefs.forceTranscribe) runCatching { Refine.schedule(ctx, 0, ExistingWorkPolicy.REPLACE) }
            return Result.success()
        }
        Throttle.blockReason(ctx, manual)?.let {
            prefs.transcribeStatus = it
            schedule(ctx, delayMinutes = if (manual) 5 else 15, policy = ExistingWorkPolicy.APPEND_OR_REPLACE)
            return Result.success()
        }
        // Forzata: notifica fissa con "Ferma", così Android non la sospende a schermo spento.
        if (prefs.forceTranscribe) runCatching { setForeground(Force.foreground(ctx, "Trascrivo con ${engine.label}…")) }

        val executor = Executors.newSingleThreadExecutor { r ->
            Thread({
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                r.run()
            }, "lifelog-asr")
        }
        val outcome = try {
            withContext(executor.asCoroutineDispatcher()) { runBatch(ctx, db, prefs, manual, engine) }
        } finally {
            executor.shutdown()
        }
        when (outcome) {
            Outcome.DONE -> {
                prefs.transcribeStatus = "Tutto trascritto"
                DriveSync.syncSoon(ctx)
                runCatching {
                    if (prefs.forceTranscribe) Refine.schedule(ctx, 0, ExistingWorkPolicy.REPLACE) else Refine.schedule(ctx)
                }
                runCatching { it.iaco.lifelog.data.Assistant.uploadSoon(ctx) } // testo nuovo: subito a Claude
            }
            Outcome.MORE -> {
                DriveSync.syncSoon(ctx)
                schedule(ctx, 0, ExistingWorkPolicy.APPEND_OR_REPLACE, manual)
            }
            Outcome.PAUSED -> schedule(ctx, if (manual) 5 else 15, ExistingWorkPolicy.APPEND_OR_REPLACE, manual)
            Outcome.STOPPED -> {}
        }
        return Result.success()
    }

    private enum class Outcome { DONE, MORE, PAUSED, STOPPED }

    private suspend fun runBatch(ctx: Context, db: Db, prefs: Prefs, manual: Boolean, engine: Engine): Outcome {
        val deadline = System.currentTimeMillis() + 8 * 60_000
        prefs.transcribeStatus = "Carico ${engine.label}…"
        val threads = if (engine != Engine.PARAKEET && Throttle.battery(ctx).plugged) 4 else 2
        val asr = AsrFactory.create(ctx, engine, threads)
        // Persone: chi parla. Nessun effetto se il modello delle voci non è scaricato; ogni errore → voce sconosciuta.
        val speakers = try { it.iaco.lifelog.people.Speakers.Labeler.createOrNull(ctx) } catch (_: Throwable) { null }
        var done = 0
        try {
            while (true) {
                if (isStopped) return Outcome.STOPPED
                Throttle.blockReason(ctx, manual)?.let {
                    prefs.transcribeStatus = it
                    return Outcome.PAUSED
                }
                val batch = db.pendingBatch(40)
                try { speakers?.labelAll(batch) } catch (_: Throwable) {}
                val group = Chunker.next(batch, speakers?.voices ?: emptyMap()) ?: return Outcome.DONE
                val audio = Chunker.audioOf(group)
                val raw = if (audio.isEmpty()) "" else asr.transcribe(audio)
                val whisperNow = engine == Engine.WHISPER || (asr as? AutoAsr)?.lastWasWhisper == true
                // Whisper sul silenzio o sul rumore inventa frasi come "Grazie a tutti.": si buttano.
                val text = if (whisperNow && Hallucination.isFake(raw)) "" else raw
                db.markGroup(group, text)
                try { speakers?.finishGroup(group) } catch (_: Throwable) {}
                if (audio.isNotEmpty()) Recent.save(ctx, group.first().startMs, audio)
                // Seconda passata con Whisper in carica: si tiene l'audio del blocco finché non è rifinito.
                val usedWhisper = engine == Engine.WHISPER || (asr as? AutoAsr)?.lastWasWhisper == true
                if (audio.isNotEmpty() && text.isNotBlank() && !usedWhisper) {
                    try { Refine.save(ctx, group.first(), audio) } catch (_: Throwable) {}
                }
                group.forEach { g -> g.audioPath?.let { File(it).delete() } }
                done += group.size
                prefs.transcribeStatus = "Trascrivo con ${engine.label}… ${db.pendingCount()} pezzi rimasti"
                if (manual && prefs.forceTranscribe) runCatching { setForeground(Force.foreground(ctx, prefs.transcribeStatus)) }
                setProgressAsync(workDataOf("done" to done))
                if (System.currentTimeMillis() > deadline) return Outcome.MORE
            }
        } catch (e: Exception) {
            Log.e("TranscribeWorker", "errore", e)
            prefs.transcribeStatus = "Errore di trascrizione (${engine.label}): ${e.message}"
            return Outcome.PAUSED
        } finally {
            asr.release()
            try { speakers?.release() } catch (_: Throwable) {}
        }
    }

    companion object {
        const val NAME = "transcribe"
        private const val KEY_MANUAL = "manual"

        fun schedule(
            ctx: Context,
            delayMinutes: Long = 0,
            policy: ExistingWorkPolicy = ExistingWorkPolicy.KEEP,
            manual: Boolean = false,
        ) {
            val prefs = Prefs(ctx)
            val forced = manual || prefs.forceTranscribe
            val constraints = Constraints.Builder()
                .setRequiresCharging(prefs.onlyWhileCharging && !forced)
                .build()
            val req = OneTimeWorkRequestBuilder<TranscribeWorker>()
                .setConstraints(constraints)
                .setInitialDelay(delayMinutes, TimeUnit.MINUTES)
                .setInputData(workDataOf(KEY_MANUAL to forced))
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(NAME, policy, req)
        }

        /** Pulsante "Trascrivi adesso": vedi [Force]. */
        fun runNow(ctx: Context) = Force.start(ctx)
    }
}
