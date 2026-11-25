package it.iaco.lifelog

import android.content.Context
import android.os.Process
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import it.iaco.lifelog.data.Assistant
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Seconda passata con Whisper (più preciso, circa 5 volte più lento).
 * Di giorno Parakeet scrive subito il testo; l'audio di ogni blocco resta sul telefono
 * finché, in carica e a trascrizione finita, Whisper lo riascolta e sostituisce il testo.
 * Poi l'audio si cancella. Nessun audio esce mai dal telefono; dopo [KEEP_DAYS] giorni si cancella comunque.
 */
object Refine {
    private const val KEEP_DAYS = 3
    /** Tetto di spazio: oltre, i blocchi nuovi restano con il testo di Parakeet. */
    private const val MAX_BYTES = 2_000_000_000L
    const val NAME = "refine"

    fun dir(ctx: Context) = File(ctx.filesDir, "refine").apply { mkdirs() }

    /** File "inizioMs_idSegmento.pcm". */
    private fun parse(f: File): Pair<Long, Long>? {
        val p = f.nameWithoutExtension.split('_')
        if (p.size != 2) return null
        val start = p[0].toLongOrNull() ?: return null
        val id = p[1].toLongOrNull() ?: return null
        return start to id
    }

    fun save(ctx: Context, head: Segment, audio: FloatArray) {
        val d = dir(ctx)
        if (d.listFiles().orEmpty().sumOf { it.length() } > MAX_BYTES) return
        File(d, "${head.startMs}_${head.id}.pcm").writeBytes(Pcm.toBytes(audio))
    }

    /** Dal blocco più recente: il giorno appena finito è quello che Claude analizza stanotte. */
    fun queue(ctx: Context): List<File> =
        dir(ctx).listFiles().orEmpty().filter { parse(it) != null }.sortedByDescending { parse(it)!!.first }

    fun pendingOfDay(ctx: Context, day: String): Int =
        dir(ctx).listFiles().orEmpty().count { f -> parse(f)?.let { Db.dayOf(it.first) == day } == true }

    fun cleanup(ctx: Context) {
        val limit = System.currentTimeMillis() - KEEP_DAYS * 86_400_000L
        dir(ctx).listFiles().orEmpty().forEach { f -> val p = parse(f); if (p == null || p.first < limit) f.delete() }
    }

    /** Frasi che Whisper inventa sul rumore (sottotitoli dei video con cui è stato addestrato). */
    private val HALLUCINATION = Regex(
        "sottotitoli|amara\\.org|qtss|iscriviti al canale|grazie per (la visione|l'attenzione|aver guardato)|^\\W*$",
        RegexOption.IGNORE_CASE
    )

    fun accept(whisper: String): Boolean = whisper.isNotBlank() && !HALLUCINATION.containsMatchIn(whisper)

    /** Parte da sola in carica; se la trascrizione è ancora in corso, aspetta. */
    fun schedule(ctx: Context, delayMinutes: Long = 0, policy: ExistingWorkPolicy = ExistingWorkPolicy.KEEP) {
        val req = OneTimeWorkRequestBuilder<RefineWorker>()
            .setConstraints(Constraints.Builder().setRequiresCharging(true).build())
            .setInitialDelay(delayMinutes, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(NAME, policy, req)
    }

    /** Whisper serve alla seconda passata: si scarica da solo una volta (Iaco ha dati illimitati). */
    fun ensureWhisper(ctx: Context) {
        if (Models.whisperReady(ctx)) return
        Thread {
            runCatching {
                val running = WorkManager.getInstance(ctx).getWorkInfosForUniqueWork(ModelSetupWorker.NAME_WHISPER).get()
                    .any { !it.state.isFinished }
                if (!running) ModelSetupWorker.startWhisper(ctx, wifiOnly = false)
            }
        }.start()
    }
}

class RefineWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val prefs = Prefs(ctx)
        runCatching { Refine.cleanup(ctx) }
        if (Refine.queue(ctx).isEmpty()) return Result.success()
        if (!Models.whisperReady(ctx)) { Refine.ensureWhisper(ctx); Refine.schedule(ctx, 60, ExistingWorkPolicy.APPEND_OR_REPLACE); return Result.success() }
        val db = Db.get(ctx)
        // Prima la trascrizione di Parakeet, poi la rifinitura: mai due modelli grandi insieme.
        if (db.pendingCount() > 0 || Throttle.blockReason(ctx, manual = false) != null) {
            Refine.schedule(ctx, 15, ExistingWorkPolicy.APPEND_OR_REPLACE)
            return Result.success()
        }
        val executor = Executors.newSingleThreadExecutor { r ->
            Thread({ Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND); r.run() }, "lifelog-refine")
        }
        val more = try {
            withContext(executor.asCoroutineDispatcher()) { run(ctx, db, prefs) }
        } catch (e: Throwable) {
            Log.e("Refine", "errore", e)
            prefs.transcribeStatus = "Rifinitura con Whisper in pausa: ${e.message}"
            Refine.schedule(ctx, 30, ExistingWorkPolicy.APPEND_OR_REPLACE)
            return Result.success()
        } finally {
            executor.shutdown()
        }
        if (more) Refine.schedule(ctx, if (Throttle.blockReason(ctx, false) == null && db.pendingCount() == 0) 0 else 15, ExistingWorkPolicy.APPEND_OR_REPLACE)
        else {
            prefs.transcribeStatus = "Tutto trascritto e rifinito con Whisper"
            runCatching { Assistant.uploadSoon(ctx) }
        }
        return Result.success()
    }

    /** true = resta altro da fare. */
    private suspend fun run(ctx: Context, db: Db, prefs: Prefs): Boolean {
        val deadline = System.currentTimeMillis() + 8 * 60_000
        val asr = AsrFactory.create(ctx, Engine.WHISPER, 4)
        var changed = 0
        try {
            for (f in Refine.queue(ctx)) {
                if (isStopped || System.currentTimeMillis() > deadline) return true
                if (db.pendingCount() > 0 || Throttle.blockReason(ctx, false) != null) return true
                val id = f.nameWithoutExtension.substringAfter('_').toLongOrNull()
                if (id != null && f.exists()) {
                    val text = asr.transcribe(Pcm.read(f)).trim()
                    if (Refine.accept(text) && db.replaceText(id, text)) changed++
                }
                f.delete()
                prefs.transcribeStatus = "Rifinisco con Whisper… ${Refine.queue(ctx).size} blocchi rimasti"
            }
        } finally {
            asr.release()
            if (changed > 0) runCatching { Assistant.uploadSoon(ctx) }
        }
        return Refine.queue(ctx).isNotEmpty()
    }
}
