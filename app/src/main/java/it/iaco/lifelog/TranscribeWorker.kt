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

    /** null = si può lavorare; altrimenti il motivo per aspettare. */
    fun blockReason(ctx: Context, manual: Boolean): String? {
        val p = Prefs(ctx)
        val b = battery(ctx)
        if (b.tempC >= p.maxBatteryTemp) {
            return "Pausa: batteria a %.1f°C (limite %.1f°C)".format(b.tempC, p.maxBatteryTemp)
        }
        val pm = ctx.getSystemService(PowerManager::class.java)
        if (pm.currentThermalStatus >= PowerManager.THERMAL_STATUS_MODERATE) {
            return "Pausa: il telefono è caldo"
        }
        if (pm.isPowerSaveMode && !b.plugged) return "Pausa: risparmio energetico attivo"
        if (!manual) {
            if (p.onlyWhileCharging && !b.plugged) return "In attesa: trascrivo quando metti in carica"
            if (p.onlyWhileCharging && p.minChargePercent > 0 && b.percent < p.minChargePercent) {
                return "In attesa: carica al ${b.percent}%, comincio dal ${p.minChargePercent}%"
            }
        } else if (!b.plugged && b.percent < 30) {
            return "Batteria sotto il 30%: metti in carica per trascrivere"
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
        val manual = inputData.getBoolean(KEY_MANUAL, false)
        // Se il motore scelto non è ancora pronto (es. Whisper in download) uso Parakeet.
        val engine = listOf(prefs.engine, Engine.PARAKEET).firstOrNull { AsrFactory.ready(ctx, it) } ?: run {
            prefs.transcribeStatus = "Il modello di trascrizione non è ancora scaricato"
            return Result.success()
        }
        val db = Db.get(ctx)
        if (db.pendingCount() == 0) {
            prefs.transcribeStatus = "Tutto trascritto"
            return Result.success()
        }
        Throttle.blockReason(ctx, manual)?.let {
            prefs.transcribeStatus = it
            schedule(ctx, delayMinutes = 15, policy = ExistingWorkPolicy.APPEND_OR_REPLACE)
            return Result.success()
        }

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
            }
            Outcome.MORE -> {
                DriveSync.syncSoon(ctx)
                schedule(ctx, 0, ExistingWorkPolicy.APPEND_OR_REPLACE, manual)
            }
            Outcome.PAUSED -> schedule(ctx, 15, ExistingWorkPolicy.APPEND_OR_REPLACE)
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
        var done = 0
        try {
            while (true) {
                if (isStopped) return Outcome.STOPPED
                Throttle.blockReason(ctx, manual)?.let {
                    prefs.transcribeStatus = it
                    return Outcome.PAUSED
                }
                val group = Chunker.next(db.pendingBatch(40)) ?: return Outcome.DONE
                val audio = Chunker.audioOf(group)
                val text = if (audio.isEmpty()) "" else asr.transcribe(audio)
                db.markGroup(group, text)
                if (audio.isNotEmpty()) Recent.save(ctx, group.first().startMs, audio)
                group.forEach { g -> g.audioPath?.let { File(it).delete() } }
                done += group.size
                prefs.transcribeStatus = "Trascrivo con ${engine.label}… ${db.pendingCount()} pezzi rimasti"
                setProgressAsync(workDataOf("done" to done))
                if (System.currentTimeMillis() > deadline) return Outcome.MORE
            }
        } catch (e: Exception) {
            Log.e("TranscribeWorker", "errore", e)
            prefs.transcribeStatus = "Errore di trascrizione (${engine.label}): ${e.message}"
            return Outcome.PAUSED
        } finally {
            asr.release()
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
            val constraints = Constraints.Builder()
                .setRequiresCharging(prefs.onlyWhileCharging && !manual)
                .build()
            val req = OneTimeWorkRequestBuilder<TranscribeWorker>()
                .setConstraints(constraints)
                .setInitialDelay(delayMinutes, TimeUnit.MINUTES)
                .setInputData(workDataOf(KEY_MANUAL to manual))
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(NAME, policy, req)
        }

        /** Pulsante "Trascrivi ora": ignora l'attesa della ricarica, non i limiti di temperatura. */
        fun runNow(ctx: Context) = schedule(ctx, 0, ExistingWorkPolicy.REPLACE, manual = true)
    }
}
