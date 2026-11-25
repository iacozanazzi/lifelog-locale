package it.iaco.lifelog

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.IBinder
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.k2fsa.sherpa.onnx.Vad
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.BufferedOutputStream

/**
 * Servizio in primo piano che ascolta tutto il giorno.
 * Il rilevatore di voce (Silero VAD) tiene solo i pezzi in cui qualcuno parla;
 * il silenzio viene buttato subito. I pezzi di parlato vengono salvati in attesa
 * di essere trascritti (di solito quando il telefono è in carica).
 */
/** Stato dal vivo del microfono, letto dall'interfaccia (stesso processo). */
object Live {
    val level = kotlinx.coroutines.flow.MutableStateFlow(0f)
    val speaking = kotlinx.coroutines.flow.MutableStateFlow(false)
}

class RecorderService : Service() {

    @Volatile private var running = false
    private var thread: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val prefs = Prefs(this)
        if (intent?.action == ACTION_STOP) {
            prefs.recordingWanted = false
            Watchdog.cancel(this)
            stopRecording()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        if (!prefs.recordingWanted) {
            // Riavviato dal sistema ma l'utente aveva fermato: non fare nulla.
            stopSelf()
            return START_NOT_STICKY
        }
        if (!Models.vadReady(this) ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        ) {
            Notifications.alert(this, "Registrazione non avviata", "Apri Lifelog per completare la configurazione.")
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            startForeground(
                Notifications.ID_RECORDING,
                Notifications.recording(this, "In ascolto: salvo solo quando qualcuno parla"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } catch (e: Exception) {
            // Android non permette di accendere il microfono partendo dallo sfondo.
            Log.w(TAG, "startForeground rifiutato", e)
            Notifications.alert(
                this, "Registrazione ferma",
                "Android ha bloccato la ripartenza automatica. Tocca qui per riprendere."
            )
            stopSelf()
            return START_NOT_STICKY
        }
        Watchdog.recordGapIfAny(this)
        Notifications.clearAlert(this)
        Watchdog.schedule(this)
        if (!running) startRecording()
        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun startRecording() {
        running = true
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "lifelog:rec").apply {
            setReferenceCounted(false)
            acquire()
        }
        thread = Thread({ loop() }, "lifelog-recorder").apply { start() }
    }

    private fun stopRecording() {
        running = false
        thread?.join(3000)
        thread = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    @SuppressLint("MissingPermission")
    private fun loop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val prefs = Prefs(this)
        val db = Db.get(this)
        val sr = Models.SAMPLE_RATE
        val minBuf = AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, sr,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, sr * 2 * 2),
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            Notifications.alert(this, "Microfono non disponibile", "Tocca per riprovare.")
            running = false
            stopSelf()
            return
        }
        val vad = Vad(null, Models.vadConfig(this))
        val window = 512
        val shorts = ShortArray(window)
        val floats = FloatArray(window)
        // Orologio: inizio della sessione in tempo reale, e campioni letti da allora.
        val sessionStartMs = System.currentTimeMillis()
        var samplesRead = 0L
        // Ultimi 30 secondi di audio: servono per aggiungere un margine prima e dopo
        // ogni pezzo di parlato, così l'inizio e la fine delle parole non vengono tagliati.
        val ring = FloatArray(sr * 30)
        val pad = sr / 4 // 0,25 s
        fun padded(start: Long, len: Int): Pair<Long, FloatArray> {
            val from = maxOf(start - pad, samplesRead - ring.size + 1, 0L)
            val to = minOf(start + len + pad, samplesRead)
            val out = FloatArray((to - from).toInt().coerceAtLeast(0))
            for (i in out.indices) out[i] = ring[((from + i) % ring.size).toInt()]
            return from to out
        }
        var lastBeat = 0L
        var zeroRunSamples = 0L
        var silentMicWarned = false
        var lastSchedule = 0L
        var segmentsToday = 0

        rec.startRecording()
        try {
            while (running) {
                val n = rec.read(shorts, 0, window)
                if (n <= 0) {
                    SystemClock.sleep(20)
                    continue
                }
                var allZero = true
                for (i in 0 until n) {
                    val s = shorts[i]
                    if (s.toInt() != 0) allZero = false
                    floats[i] = s / 32768f
                }
                for (i in 0 until n) ring[((samplesRead + i) % ring.size).toInt()] = floats[i]
                samplesRead += n
                // Se il microfono restituisce zeri perfetti per più di un minuto,
                // Android ce lo sta bloccando (es. dopo una ripartenza dallo sfondo).
                zeroRunSamples = if (allZero) zeroRunSamples + n else 0
                if (!silentMicWarned && zeroRunSamples > sr * 60L) {
                    silentMicWarned = true
                    Notifications.alert(
                        this, "Il microfono non riceve audio",
                        "Android potrebbe aver bloccato il microfono. Tocca per riprendere la registrazione."
                    )
                } else if (silentMicWarned && !allZero) {
                    silentMicWarned = false
                    Notifications.clearAlert(this)
                }

                // Livello audio per l'animazione in app (solo in memoria, niente viene salvato).
                var sum = 0f
                for (i in 0 until n) sum += floats[i] * floats[i]
                val rms = kotlin.math.sqrt(sum / n)
                Live.level.value = (Live.level.value * 0.6f + (rms * 8f).coerceIn(0f, 1f) * 0.4f)

                vad.acceptWaveform(if (n == window) floats else floats.copyOf(n))
                Live.speaking.value = vad.isSpeechDetected()
                while (!vad.empty()) {
                    val seg = vad.front()
                    vad.pop()
                    val (from, audio) = padded(seg.start.toLong(), seg.samples.size)
                    val samples = if (audio.size >= seg.samples.size) audio else seg.samples
                    val startMs = sessionStartMs + (if (audio.size >= seg.samples.size) from else seg.start.toLong()) * 1000L / sr
                    val endMs = startMs + samples.size * 1000L / sr
                    saveSegment(db, startMs, endMs, samples)
                    segmentsToday++
                }

                val now = System.currentTimeMillis()
                if (now - lastBeat > 30_000) {
                    lastBeat = now
                    prefs.lastHeartbeat = now
                    if (segmentsToday > 0) {
                        val nm = getSystemService(NotificationManager::class.java)
                        nm.notify(
                            Notifications.ID_RECORDING,
                            Notifications.recording(this, "Da trascrivere: ${db.pendingCount()} pezzi di parlato")
                        )
                    }
                }
                if (now - lastSchedule > 5 * 60_000) {
                    lastSchedule = now
                    TranscribeWorker.schedule(this)
                }
            }
            // Fermata richiesta: salva l'ultimo pezzo di parlato in corso.
            vad.flush()
            while (!vad.empty()) {
                val seg = vad.front()
                vad.pop()
                val startMs = sessionStartMs + seg.start * 1000L / sr
                saveSegment(db, startMs, startMs + seg.samples.size * 1000L / sr, seg.samples)
            }
        } catch (e: Exception) {
            Log.e(TAG, "errore di registrazione", e)
            Notifications.alert(this, "Registrazione interrotta da un errore", "Tocca per riprendere. (${e.message})")
        } finally {
            Live.level.value = 0f
            Live.speaking.value = false
            try { rec.stop() } catch (_: Exception) {}
            rec.release()
            vad.release()
            if (!Prefs(this).recordingWanted) prefs.lastHeartbeat = 0L
        }
    }

    private fun saveSegment(db: Db, startMs: Long, endMs: Long, samples: FloatArray) {
        val dir = File(filesDir, "pending").apply { mkdirs() }
        val f = File(dir, "$startMs.pcm")
        DataOutputStream(BufferedOutputStream(FileOutputStream(f))).use { it.write(Pcm.toBytes(samples)) }
        db.insertPending(startMs, endMs, f.absolutePath)
    }

    override fun onDestroy() {
        stopRecording()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "RecorderService"
        const val ACTION_STOP = "it.iaco.lifelog.STOP"

        /** Da chiamare solo con l'app in primo piano (Android lo esige per il microfono). */
        fun start(ctx: Context) {
            Prefs(ctx).recordingWanted = true
            ContextCompat.startForegroundService(ctx, Intent(ctx, RecorderService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, RecorderService::class.java).setAction(ACTION_STOP))
        }

        fun isAlive(ctx: Context): Boolean {
            val hb = Prefs(ctx).lastHeartbeat
            return hb > 0 && System.currentTimeMillis() - hb < 90_000
        }
    }
}
