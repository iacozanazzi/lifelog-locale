package it.iaco.lifelog

import android.content.Context
import android.content.pm.ServiceInfo
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Scarica (una volta sola) il rilevatore di voce Silero e il modello Parakeet.
 * Dopo il download tutto funziona senza internet.
 */
class ModelSetupWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun getForegroundInfo() = foreground("Preparazione…", 0)

    private fun foreground(text: String, pct: Int) = ForegroundInfo(
        Notifications.ID_DOWNLOAD,
        Notifications.work(applicationContext, "Scarico il modello di trascrizione", text, pct),
        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
    )

    private suspend fun report(stage: String, pct: Int) {
        setProgress(workDataOf(KEY_STAGE to stage, KEY_PCT to pct))
        try {
            setForeground(foreground(stage, pct))
        } catch (_: Exception) {
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val ctx = applicationContext
        try {
            report("Avvio", 0)
            if (!Models.vadReady(ctx)) {
                download(Models.VAD_URL, Models.vadFile(ctx)) { }
            }
            if (!Models.parakeetReady(ctx)) {
                val tar = File(ctx.cacheDir, "parakeet.tar.bz2")
                var lastPct = -1
                download(Models.PARAKEET_URL, tar) { pct ->
                    if (pct != lastPct) {
                        lastPct = pct
                        report("Download al $pct%", pct)
                    }
                }
                report("Estrazione (qualche minuto)…", 100)
                extract(tar, Models.dir(ctx))
                Models.markParakeetComplete(ctx)
                tar.delete()
            }
            Result.success()
        } catch (e: Exception) {
            if (runAttemptCount < 5) Result.retry()
            else Result.failure(workDataOf(KEY_ERROR to (e.message ?: e.toString())))
        }
    }

    /** Download con ripresa: se si interrompe riparte da dove era arrivato. */
    private suspend fun download(url: String, dest: File, onPct: suspend (Int) -> Unit) {
        val part = File(dest.path + ".part")
        var conn = open(url, part.length())
        var code = conn.responseCode
        if (code == 416) { // già completo
            conn.disconnect()
            part.renameTo(dest)
            return
        }
        val append = code == HttpURLConnection.HTTP_PARTIAL
        if (!append && code != HttpURLConnection.HTTP_OK) throw IOException("HTTP $code")
        if (!append) part.delete()
        val already = if (append) part.length() else 0L
        val total = already + conn.contentLengthLong.coerceAtLeast(0)
        conn.inputStream.use { input ->
            FileOutputStream(part, append).use { out ->
                val buf = ByteArray(256 * 1024)
                var done = already
                while (true) {
                    if (isStopped) throw IOException("interrotto")
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    if (total > 0) onPct((done * 100 / total).toInt())
                }
            }
        }
        conn.disconnect()
        if (dest.exists()) dest.delete()
        if (!part.renameTo(dest)) throw IOException("rinomina fallita")
    }

    private fun open(url: String, from: Long): HttpURLConnection {
        var u = URL(url)
        repeat(6) {
            val c = u.openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 30_000
            c.readTimeout = 60_000
            if (from > 0) c.setRequestProperty("Range", "bytes=$from-")
            val code = c.responseCode
            if (code in 300..399) {
                val loc = c.getHeaderField("Location") ?: throw IOException("redirect senza destinazione")
                c.disconnect()
                u = URL(u, loc)
            } else {
                return c
            }
        }
        throw IOException("troppi redirect")
    }

    private fun extract(archive: File, destDir: File) {
        val root = destDir.canonicalFile
        TarArchiveInputStream(
            BZip2CompressorInputStream(BufferedInputStream(archive.inputStream(), 1 shl 20))
        ).use { tar ->
            while (true) {
                val e = tar.nextEntry ?: break
                val out = File(root, e.name).canonicalFile
                if (!out.path.startsWith(root.path + File.separator)) continue // percorso sospetto
                if (e.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    FileOutputStream(out).use { tar.copyTo(it, 1 shl 20) }
                }
            }
        }
    }

    companion object {
        const val NAME = "model_setup"
        const val KEY_STAGE = "stage"
        const val KEY_PCT = "pct"
        const val KEY_ERROR = "error"

        fun start(ctx: Context, wifiOnly: Boolean) {
            val req = OneTimeWorkRequestBuilder<ModelSetupWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                        .setRequiresStorageNotLow(true)
                        .build()
                )
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(NAME, ExistingWorkPolicy.REPLACE, req)
        }
    }
}
