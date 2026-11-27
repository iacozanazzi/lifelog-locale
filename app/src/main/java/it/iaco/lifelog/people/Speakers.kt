package it.iaco.lifelog.people

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import it.iaco.lifelog.Db
import it.iaco.lifelog.Models
import it.iaco.lifelog.Pcm
import it.iaco.lifelog.Segment
import it.iaco.lifelog.data.Assistant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Riconoscimento di chi parla, tutto sul telefono.
 * Il modello (WeSpeaker CAM++, addestrato su VoxCeleb, 29 MB) trasforma un pezzo di voce in un'impronta
 * di 512 numeri. Non serve internet e l'impronta non esce mai dal telefono.
 * Se il modello non è scaricato, tutto resta come prima: parlante "?".
 */
object Speakers {
    const val MODEL_NAME = "wespeaker_en_voxceleb_CAM++.onnx"
    const val MODEL_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/wespeaker_en_voxceleb_CAM%2B%2B.onnx"
    const val MODEL_SHA256 = "7acd352a0372de23ce49379b02864833b7f3f501b4ea25248fc977389f08e5fc"
    const val MODEL_MB = 29

    /** Sotto questa durata l'impronta non è affidabile: il pezzo eredita la voce dei vicini. */
    const val MIN_SECONDS = 1.5

    fun modelFile(ctx: Context) = File(Models.dir(ctx), MODEL_NAME)
    private fun okMarker(ctx: Context) = File(Models.dir(ctx), "$MODEL_NAME.ok")
    fun ready(ctx: Context) = okMarker(ctx).exists() && modelFile(ctx).length() > 1_000_000

    fun isDownloading(ctx: Context): Boolean = runCatching {
        WorkManager.getInstance(ctx).getWorkInfosForUniqueWork(DownloadVoiceModel.NAME).get()
            .any { !it.state.isFinished }
    }.getOrDefault(false)

    private fun sp(ctx: Context) = ctx.getSharedPreferences("lifelog", Context.MODE_PRIVATE)

    /**
     * All'avvio dell'app: scarica il modello da solo (29 MB, anche con i dati), a meno che Iaco
     * non abbia scelto "dimentica tutte le voci". Senza modello le voci non si riconoscono mai.
     */
    fun autoDownload(ctx: Context) {
        if (ready(ctx) || sp(ctx).getBoolean("voices_off", false)) return
        enqueue(ctx)
    }

    /** Tocco su "scarica" nella schermata Persone: riattiva anche dopo "dimentica tutto". */
    fun download(ctx: Context) {
        sp(ctx).edit().putBoolean("voices_off", false).apply()
        enqueue(ctx)
    }

    private fun enqueue(ctx: Context) {
        val req = OneTimeWorkRequestBuilder<DownloadVoiceModel>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(DownloadVoiceModel.NAME, ExistingWorkPolicy.KEEP, req)
    }

    /** Cancella modello, impronte e tutte le etichette dei parlanti. Le trascrizioni restano. */
    fun forgetAll(ctx: Context) {
        sp(ctx).edit().putBoolean("voices_off", true).apply()
        Db.get(ctx).clearAllVoices()
        modelFile(ctx).delete()
        okMarker(ctx).delete()
        Assistant.uploadSoon(ctx)
    }

    // ------------------------------------------------------------- nomi

    /**
     * Il nome da scrivere davanti a una frase: "Iaco", il nome dato, "Voce N", oppure "?"
     * per le voci incerte o appena nate.
     */
    fun label(voice: String?, voices: Map<String, Voice>): String {
        if (voice == null) return "?"
        if (voice == Voice.ME) return ME_NAME
        val v = voices[voice] ?: return "?"
        return v.name ?: if (v.confirmed) "Voce ${v.n}" else "?"
    }

    const val ME_NAME = "Iaco"

    fun voiceMap(ctx: Context): Map<String, Voice> = voiceMap(Db.get(ctx))

    fun voiceMap(db: Db): Map<String, Voice> =
        runCatching { db.loadVoices().associateBy { it.id } }.getOrDefault(emptyMap())

    /**
     * Dà un nome a una voce. Se il nome è già di un'altra voce, le unisce (è la stessa persona sentita in due posti).
     * [me] = "sono io": la voce diventa quella di Iaco.
     */
    fun name(ctx: Context, voiceId: String, name: String?, persona: String?, me: Boolean = false) {
        val db = Db.get(ctx)
        val bank = VoiceBank(db.loadVoices())
        val v = bank.get(voiceId) ?: return
        val now = System.currentTimeMillis()
        if (me) {
            val existing = bank.get(Voice.ME)
            if (existing != null) {
                bank.merge(Voice.ME, voiceId)
            } else {
                bank.remove(voiceId)
                bank.put(v.copy(id = Voice.ME, n = 0, name = null, persona = null, namedMs = now))
            }
            db.moveVoice(voiceId, Voice.ME)
            db.deleteVoice(voiceId)
        } else {
            val clean = name?.trim()?.takeIf { it.isNotEmpty() }
            val twin = bank.all.firstOrNull { it.id != voiceId && clean != null && it.name.equals(clean, ignoreCase = true) }
            if (twin != null) {
                bank.merge(twin.id, voiceId)
                db.moveVoice(voiceId, twin.id)
                db.deleteVoice(voiceId)
            } else {
                bank.put(v.copy(name = clean, persona = persona ?: clean?.let { slug(it) }, namedMs = if (clean != null) now else null))
            }
        }
        bank.drainChanged().forEach { db.saveVoice(it) }
        Assistant.uploadSoon(ctx) // i giorni si ricaricano con il nome nuovo
    }

    /** "Non è una persona sola" o "non mi interessa": la voce si dimentica, le frasi tornano "?". */
    fun forget(ctx: Context, voiceId: String) {
        val db = Db.get(ctx)
        db.moveVoice(voiceId, null)
        db.deleteVoice(voiceId)
        Assistant.uploadSoon(ctx)
    }

    /** Come il CLAUDE.md dei dati: minuscolo, senza accenti, trattini. */
    fun slug(name: String): String = java.text.Normalizer.normalize(name.trim().lowercase(), java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").replace(Regex("[^a-z0-9]+"), "-").trim('-')

    /**
     * Una riga sullo stato delle voci in un giorno, per l'intestazione del file per Claude e per Persone.
     * Serve a capire perché le frasi restano "?": modello assente, pezzi troppo corti o punteggi nella fascia incerta.
     */
    fun diagnosis(ctx: Context, db: Db, day: String): String? {
        if (!ready(ctx)) return if (sp(ctx).getBoolean("voices_off", false)) "spente da Iaco" else "modello non scaricato"
        val rows = db.voiceRowsOfDay(day)
        if (rows.isEmpty()) return null
        val voices = voiceMap(db)
        val named = rows.count { label(it.first, voices) != "?" }
        val unconfirmed = rows.count { it.first != null && label(it.first, voices) == "?" }
        val uncertain = rows.filter { it.first == null && it.second != null }.map { it.second!! }
        val noPrint = rows.count { it.first == null && it.second == null }
        val sb = StringBuilder("${rows.size} pezzi · $named con nome o Voce N · $unconfirmed voce nuova non ancora confermata · ${uncertain.size} incerti · $noPrint senza impronta (troppo corti o audio già cancellato)")
        val scores = rows.mapNotNull { it.second }
        if (scores.isNotEmpty()) {
            val b = intArrayOf(0, 0, 0, 0, 0)
            scores.forEach { x -> b[when { x < 0.5f -> 0; x < 0.6f -> 1; x < 0.7f -> 2; x < 0.8f -> 3; else -> 4 }]++ }
            sb.append(" · punteggi: <0,5: ${b[0]}, 0,5-0,6: ${b[1]}, 0,6-0,7: ${b[2]}, 0,7-0,8: ${b[3]}, ≥0,8: ${b[4]}")
        }
        sb.append(" · voci in memoria: ${voices.size}")
        return sb.toString()
    }

    /**
     * Una volta sola (27/11): le voci senza nome nate con le soglie vecchie erano tutte mescolate in "Voce 1".
     * Si cancellano e quelle frasi tornano "?"; le voci con un nome restano.
     */
    private fun resetOnce(ctx: Context) {
        if (sp(ctx).getBoolean("voices_reset_2", false)) return
        runCatching {
            val db = Db.get(ctx)
            db.loadVoices().filter { it.name == null && !it.isMe }.forEach { db.moveVoice(it.id, null); db.deleteVoice(it.id) }
        }
        sp(ctx).edit().putBoolean("voices_reset_2", true).apply()
    }

    // ------------------------------------------------------------- aggancio alla trascrizione

    /**
     * Usato da TranscribeWorker: per ogni pezzo VAD ancora con l'audio, calcola l'impronta e sceglie la voce.
     * Qualunque errore → nessuna voce: la trascrizione non deve mai fermarsi per questo.
     */
    class Labeler private constructor(private val ctx: Context, private val extractor: SpeakerEmbeddingExtractor) {
        private val db = Db.get(ctx)
        private val bank = VoiceBank(db.loadVoices())
        private val seen = HashMap<Long, String?>()

        /** id segmento → voce, per i pezzi già visti in questo giro. */
        val voices: Map<Long, String?> get() = seen

        init {
            // Pulizia: voci mai nominate e quasi mai sentite da più di 30 giorni.
            bank.expired(System.currentTimeMillis()).forEach { v ->
                bank.remove(v.id); db.moveVoice(v.id, null); db.deleteVoice(v.id)
            }
        }

        fun labelAll(pending: List<Segment>) {
            for (s in pending) {
                if (s.id in seen) continue
                seen[s.id] = s.voice ?: runCatching { labelOne(s) }.onFailure { Log.w("Speakers", "impronta", it) }.getOrNull()
            }
            bank.drainChanged().forEach { db.saveVoice(it) }
        }

        private fun labelOne(s: Segment): String? {
            val f = s.audioPath?.let { File(it) }?.takeIf { it.exists() } ?: return null
            val audio = Pcm.read(f)
            val seconds = audio.size.toDouble() / Models.SAMPLE_RATE
            if (seconds < MIN_SECONDS) return null
            val e = embed(audio) ?: return null
            val m = bank.assign(e, seconds, s.startMs)
            db.setVoice(s.id, m.voice, m.score)
            return m.voice
        }

        private fun embed(audio: FloatArray): FloatArray? {
            val st = extractor.createStream()
            try {
                st.acceptWaveform(audio, Models.SAMPLE_RATE)
                st.inputFinished()
                if (!extractor.isReady(st)) return null
                return Vec.norm(extractor.compute(st))
            } finally {
                st.release()
            }
        }

        /** Dopo markGroup: la voce del gruppo va sul primo segmento (quello che tiene il testo). */
        fun finishGroup(group: List<Segment>) {
            val v = group.mapNotNull { seen[it.id] }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: return
            if (seen[group.first().id] != v) db.setVoice(group.first().id, v, null)
        }

        /** A fine giro: unisce le voci senza nome che sono chiaramente la stessa persona. */
        fun release() {
            runCatching {
                for ((a, b) in bank.mergeCandidates()) {
                    val into = if (b == Voice.ME || bank.get(b)?.name != null) b else a
                    val from = if (into == a) b else a
                    if (bank.get(from) == null || bank.get(into) == null) continue
                    bank.merge(into, from); db.moveVoice(from, into); db.deleteVoice(from)
                }
                bank.drainChanged().forEach { db.saveVoice(it) }
            }
            runCatching { extractor.release() }
        }

        companion object {
            /** null se il modello non c'è o non si carica: in quel caso l'aggancio non fa nulla. */
            fun createOrNull(ctx: Context): Labeler? {
                if (!ready(ctx)) return null
                resetOnce(ctx)
                return runCatching {
                    val cfg = SpeakerEmbeddingExtractorConfig(model = modelFile(ctx).absolutePath, numThreads = 1, debug = false, provider = "cpu")
                    Labeler(ctx, SpeakerEmbeddingExtractor(config = cfg))
                }.onFailure { Log.w("Speakers", "modello voci non caricato", it) }.getOrNull()
            }
        }
    }
}

/** Scarica il modello delle impronte vocali (29 MB) e ne verifica l'impronta sha256. */
class DownloadVoiceModel(ctx: Context, p: WorkerParameters) : CoroutineWorker(ctx, p) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val ctx = applicationContext
        val tmp = File(ctx.cacheDir, Speakers.MODEL_NAME + ".part")
        try {
            var url = URL(Speakers.MODEL_URL)
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
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val r = inp.read(buf); if (r < 0) break
                        md.update(buf, 0, r); out.write(buf, 0, r)
                    }
                }
            }
            val sha = md.digest().joinToString("") { "%02x".format(it) }
            if (sha != Speakers.MODEL_SHA256) error("file diverso da quello atteso")
            val dst = Speakers.modelFile(ctx)
            if (!tmp.renameTo(dst)) { tmp.copyTo(dst, overwrite = true); tmp.delete() }
            File(Models.dir(ctx), Speakers.MODEL_NAME + ".ok").writeText("ok")
            Result.success()
        } catch (e: Exception) {
            Log.w("Speakers", "download", e)
            tmp.delete()
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    companion object { const val NAME = "voice_model_download" }
}
