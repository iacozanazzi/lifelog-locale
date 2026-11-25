package it.iaco.lifelog

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Copia le trascrizioni in un file Markdown su Google Drive, così Claude le legge
 * con il connettore Google Drive. Solo testo, mai audio.
 *
 * Niente login Google dentro l'app: l'utente sceglie una volta il file con il
 * selettore di Android (Drive compare lì), e l'app lo riscrive a ogni sincronizzazione.
 * L'app Google Drive del telefono si occupa del caricamento.
 */
object DriveSync {
    private const val PERIODIC = "drive_sync_periodic"
    private const val NOW = "drive_sync_now"

    fun isConfigured(ctx: Context) = Prefs(ctx).driveUri.isNotBlank()

    /** Da chiamare con l'Uri restituito dal selettore "crea documento". */
    fun setTarget(ctx: Context, uri: Uri) {
        try {
            ctx.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (_: SecurityException) {
            // alcuni fornitori non danno il permesso permanente: proviamo comunque
        }
        Prefs(ctx).driveUri = uri.toString()
        schedule(ctx)
        syncSoon(ctx)
    }

    fun disconnect(ctx: Context) {
        val p = Prefs(ctx)
        try {
            ctx.contentResolver.releasePersistableUriPermission(
                Uri.parse(p.driveUri),
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (_: Exception) {
        }
        p.driveUri = ""
        p.driveStatus = ""
        WorkManager.getInstance(ctx).cancelUniqueWork(PERIODIC)
    }

    /** Ogni 6 ore (Android decide il momento preciso), più dopo ogni trascrizione. */
    fun schedule(ctx: Context) {
        if (!isConfigured(ctx)) return
        val req = PeriodicWorkRequestBuilder<DriveSyncWorker>(6, TimeUnit.HOURS).build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    fun syncSoon(ctx: Context) {
        if (!isConfigured(ctx)) return
        val req = OneTimeWorkRequestBuilder<DriveSyncWorker>()
            .setConstraints(Constraints.Builder().build())
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(NOW, ExistingWorkPolicy.REPLACE, req)
    }

    fun syncBlocking(ctx: Context): String {
        val p = Prefs(ctx)
        val uri = p.driveUri.takeIf { it.isNotBlank() } ?: return "Drive non collegato"
        val status = try {
            val md = Exporter.markdown(Db.get(ctx), newestFirst = true)
            Exporter.write(ctx, Uri.parse(uri), md)
            "Salvato alle ${Notifications.hhmm(System.currentTimeMillis())} (${md.length / 1024} KB)"
        } catch (e: SecurityException) {
            "Permesso perso: ricollega il file su Drive"
        } catch (e: Exception) {
            "Errore: ${e.message}"
        }
        p.driveStatus = status
        return status
    }
}

class DriveSyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        DriveSync.syncBlocking(applicationContext)
        return Result.success()
    }
}
