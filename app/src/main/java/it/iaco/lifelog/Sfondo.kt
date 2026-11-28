package it.iaco.lifelog

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.k2fsa.sherpa.onnx.AudioTagging
import com.k2fsa.sherpa.onnx.AudioTaggingConfig
import com.k2fsa.sherpa.onnx.AudioTaggingModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Audio di sfondo: TV, radio e musica. Un piccolo modello (CED mini, 10 MB, Apache-2.0) ascolta ogni pezzo
 * prima della trascrizione, sul telefono:
 * - musica senza parlato: non si trascrive (Whisper ci inventerebbe frasi) e non entra nel diario;
 * - TV o radio: si trascrive lo stesso, ma la riga per Claude porta "[tv?]" e la voce non insegna niente
 *   al riconoscimento delle persone (prima le voci della TV finivano in "Voce 1").
 * Niente viene cancellato per un sospetto di TV: le soglie sono da tarare con la riga "sfondo:" dei giorni.
 */
object Sfondo {
    private const val DIR = "sherpa-onnx-ced-mini-audio-tagging-2024-04-19"
    const val URL_TAR = "https://github.com/k2-fsa/sherpa-onnx/releases/download/audio-tagging-models/$DIR.tar.bz2"
    const val TAR_SHA256 = "bec8bfa0af2c20ec3a9e7c6dd6c92d0fb6e96d6b25370ebe7a48e7283a60a02c"

    /** TV o radio: somma delle due probabilità. */
    const val TV_MIN = 0.25f
    /** Musica che si salta: musica alta e parlato basso. */
    const val MUSIC_MIN = 0.5f
    const val MUSIC_MAX_SPEECH = 0.2f

    const val TV = "tv"
    const val MUSICA = "musica"
    const val NO = "no"

    private fun dir(ctx: Context) = File(Models.dir(ctx), DIR)
    fun modelFile(ctx: Context) = File(dir(ctx), "model.int8.onnx")
    fun labelsFile(ctx: Context) = File(dir(ctx), "class_labels_indices.csv")
    private fun okMarker(ctx: Context) = File(dir(ctx), "ok")
    fun ready(ctx: Context) = okMarker(ctx).exists() && modelFile(ctx).length() > 1_000_000 && labelsFile(ctx).exists()

    /** All'avvio: scarica il modello una volta (47 MB compressi, ne restano 10). */
    fun autoDownload(ctx: Context) {
        if (ready(ctx)) return
        val req = OneTimeWorkRequestBuilder<DownloadSfondo>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).setRequiresBatteryNotLow(true).build())
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(DownloadSfondo.NAME, ExistingWorkPolicy.KEEP, req)
    }

    data class Verdict(val tag: String, val tv: Float, val music: Float, val speech: Float)

    /** Regola, separata per poterla provare: decide da tre probabilità. */
    fun decide(tv: Float, music: Float, speech: Float): String = when {
        music >= MUSIC_MIN && speech < MUSIC_MAX_SPEECH -> MUSICA
        tv >= TV_MIN -> TV
        else -> NO
    }

    class Tagger private constructor(private val at: AudioTagging) {
        /** Un pezzo di parlato: fino a 2 finestre da 10 s (inizio e fine), si tiene il massimo di ogni classe. */
        fun classify(audio: FloatArray): Verdict {
            val win = Models.SAMPLE_RATE * 10
            val parts = if (audio.size <= win) listOf(audio)
            else listOf(audio.copyOfRange(0, win), audio.copyOfRange(audio.size - win, audio.size))
            var tv = 0f; var music = 0f; var speech = 0f
            for (p in parts) {
                val s = at.createStream()
                try {
                    s.acceptWaveform(p, Models.SAMPLE_RATE)
                    var t = 0f; var r = 0f
                    for (e in at.compute(s, 40)) when (e.name) {
                        "Television" -> t = e.prob
                        "Radio" -> r = e.prob
                        "Music" -> music = maxOf(music, e.prob)
                        "Speech" -> speech = maxOf(speech, e.prob)
                    }
                    tv = maxOf(tv, t + r)
                } finally {
                    s.release()
                }
            }
            return Verdict(decide(tv, music, speech), tv, music, speech)
        }

        fun release() = runCatching { at.release() }

        companion object {
            fun createOrNull(ctx: Context): Tagger? {
                if (!ready(ctx)) return null
                return runCatching {
                    val cfg = AudioTaggingConfig(
                        AudioTaggingModelConfig(ced = modelFile(ctx).absolutePath, numThreads = 1),
                        labelsFile(ctx).absolutePath, 40,
                    )
                    Tagger(AudioTagging(null, cfg))
                }.onFailure { Log.w("Sfondo", "modello", it) }.getOrNull()
            }
        }
    }

    /** Etichetta i pezzi in attesa non ancora ascoltati. Ritorna le etichette di tutto il gruppo. */
    fun tagAll(tagger: Tagger?, db: Db, batch: List<Segment>): Map<Long, String> {
        val known = db.sfondoOf(batch.map { it.id }).toMutableMap()
        if (tagger == null) return known
        for (s in batch) {
            if (s.id in known) continue
            val f = s.audioPath?.let { File(it) }?.takeIf { it.exists() } ?: continue
            val v = runCatching { tagger.classify(Pcm.read(f)) }.getOrNull() ?: continue
            db.setSfondo(s.id, v.tag, v.tv)
            known[s.id] = v.tag
        }
        return known
    }

    /** Riga di diagnosi per Claude (intestazione del giorno), come quella delle voci. */
    fun diagnosis(db: Db, day: String): String? {
        val rows = db.sfondoRowsOfDay(day)
        if (rows.isEmpty()) return null
        val tv = rows.count { it.first == TV }
        val music = rows.count { it.first == MUSICA }
        val b = intArrayOf(0, 0, 0, 0)
        rows.forEach { (_, x) -> b[when { x < 0.1f -> 0; x < 0.25f -> 1; x < 0.5f -> 2; else -> 3 }]++ }
        return "${rows.size} pezzi ascoltati · $tv TV o radio · $music solo musica (non trascritti) · " +
            "punteggi TV: <0,1: ${b[0]}, 0,1-0,25: ${b[1]}, 0,25-0,5: ${b[2]}, ≥0,5: ${b[3]}"
    }
}

class DownloadSfondo(ctx: Context, p: WorkerParameters) : CoroutineWorker(ctx, p) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val ctx = applicationContext
        val tar = File(ctx.cacheDir, "sfondo.tar.bz2")
        try {
            var url = URL(Sfondo.URL_TAR)
            var conn: HttpURLConnection
            var hops = 0
            while (true) {
                conn = url.openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 20_000; conn.readTimeout = 60_000
                val code = conn.responseCode
                if (code in 300..399 && hops++ < 5) { url = URL(url, conn.getHeaderField("Location")); conn.disconnect(); continue }
                if (code != 200) error("HTTP $code")
                break
            }
            val md = MessageDigest.getInstance("SHA-256")
            conn.inputStream.use { inp ->
                tar.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val r = inp.read(buf); if (r < 0) break
                        md.update(buf, 0, r); out.write(buf, 0, r)
                    }
                }
            }
            if (md.digest().joinToString("") { "%02x".format(it) } != Sfondo.TAR_SHA256) error("file diverso da quello atteso")
            // Si estraggono solo il modello piccolo e le etichette: il resto (modello grande, esempi) no.
            val root = Models.dir(ctx).canonicalFile
            TarArchiveInputStream(BZip2CompressorInputStream(BufferedInputStream(tar.inputStream(), 1 shl 20))).use { t ->
                while (true) {
                    val e = t.nextEntry ?: break
                    val name = e.name.substringAfterLast('/')
                    if (e.isDirectory || (name != "model.int8.onnx" && name != "class_labels_indices.csv")) continue
                    val out = File(root, e.name).canonicalFile
                    if (!out.path.startsWith(root.path + File.separator)) continue
                    out.parentFile?.mkdirs()
                    FileOutputStream(out).use { t.copyTo(it, 1 shl 20) }
                }
            }
            File(Sfondo.modelFile(ctx).parentFile, "ok").writeText("ok")
            Result.success()
        } catch (e: Exception) {
            Log.w("Sfondo", "download", e)
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        } finally {
            tar.delete()
        }
    }

    companion object { const val NAME = "sfondo_model_download" }
}
