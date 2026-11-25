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
import com.k2fsa.sherpa.onnx.OfflineRecognizer
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
 * Trascrive i pezzi di parlato in attesa con Parakeet, tutto sul telefono.
 * Usa solo 2 thread a bassa priorità e si ferma se la batteria si scalda.
 * Lavora a blocchi di ~8 minuti (limite di Android per i lavori in sottofondo)
 * e poi si rimette in coda da solo.
 */
class TranscribeWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val prefs = Prefs(ctx)
        val manual = inputData.getBoolean(KEY_MANUAL, false)
        if (!Models.parakeetReady(ctx)) {
            prefs.transcribeStatus = "Modello di trascrizione non ancora scaricato"
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
            withContext(executor.asCoroutineDispatcher()) { runBatch(ctx, db, prefs, manual) }
        } finally {
            executor.shutdown()
        }
        when (outcome) {
            Outcome.DONE -> prefs.transcribeStatus = "Tutto trascritto"
            Outcome.MORE -> schedule(ctx, 0, ExistingWorkPolicy.APPEND_OR_REPLACE, manual)
            Outcome.PAUSED -> schedule(ctx, 15, ExistingWorkPolicy.APPEND_OR_REPLACE)
            Outcome.STOPPED -> {}
        }
        return Result.success()
    }

    private enum class Outcome { DONE, MORE, PAUSED, STOPPED }

    private fun runBatch(ctx: Context, db: Db, prefs: Prefs, manual: Boolean): Outcome {
        val deadline = System.currentTimeMillis() + 8 * 60_000
        prefs.transcribeStatus = "Carico il modello…"
        val recognizer = OfflineRecognizer(null, Models.recognizerConfig(ctx, threads = 2))
        var done = 0
        try {
            while (true) {
                if (isStopped) return Outcome.STOPPED
                Throttle.blockReason(ctx, manual)?.let {
                    prefs.transcribeStatus = it
                    return Outcome.PAUSED
                }
                val seg = db.nextPending() ?: return Outcome.DONE
                val file = seg.audioPath?.let { File(it) }
                val text = if (file != null && file.exists()) {
                    val samples = readPcm16(file)
                    val stream = recognizer.createStream()
                    try {
                        stream.acceptWaveform(samples, Models.SAMPLE_RATE)
                        recognizer.decode(stream)
                        recognizer.getResult(stream).text.trim()
                    } finally {
                        stream.release()
                    }
                } else ""
                db.markTranscribed(seg.id, text)
                file?.delete()
                done++
                prefs.transcribeStatus = "Trascrivo… ${db.pendingCount()} pezzi rimasti"
                setProgressAsync(workDataOf("done" to done))
                if (System.currentTimeMillis() > deadline) return Outcome.MORE
            }
        } catch (e: Exception) {
            Log.e("TranscribeWorker", "errore", e)
            prefs.transcribeStatus = "Errore di trascrizione: ${e.message}"
            return Outcome.PAUSED
        } finally {
            recognizer.release()
        }
    }

    private fun readPcm16(f: File): FloatArray {
        val bytes = f.readBytes()
        val out = FloatArray(bytes.size / 2)
        for (i in out.indices) {
            val lo = bytes[2 * i].toInt() and 0xff
            val hi = bytes[2 * i + 1].toInt()
            out[i] = ((hi shl 8) or lo).toShort() / 32768f
        }
        return out
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
