package it.iaco.lifelog.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import it.iaco.lifelog.Db
import it.iaco.lifelog.aree.Aree
import it.iaco.lifelog.briefing.Briefing
import it.iaco.lifelog.memory.Memory
import it.iaco.lifelog.people.People
import it.iaco.lifelog.people.Speakers
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/**
 * Scambio dati con Claude attraverso un repository GitHub privato (lifelog-dati):
 * l'app carica il testo dei giorni, Claude di notte scrive analisi e impegni,
 * l'app li scarica e li mostra. Solo testo, mai audio.
 */
object Assistant {
    const val DEFAULT_REPO = "iacozanazzi/lifelog-dati"
    private const val SP = "lifelog_assistant"
    val zone: ZoneId get() = ZoneId.systemDefault()
    private val iso = DateTimeFormatter.ISO_OFFSET_DATE_TIME
    private val hms = DateTimeFormatter.ofPattern("HH:mm:ss")
    private val hm = DateTimeFormatter.ofPattern("HH:mm")

    private fun sp(ctx: Context) = ctx.getSharedPreferences(SP, Context.MODE_PRIVATE)
    fun token(ctx: Context) = Vault.get(ctx, "github_token")
    fun repo(ctx: Context) = sp(ctx).getString("repo", DEFAULT_REPO)!!
    fun isConfigured(ctx: Context) = Vault.has(ctx, "github_token")

    fun configure(ctx: Context, token: String, repo: String) {
        Vault.put(ctx, "github_token", token.trim())
        sp(ctx).edit().putString("repo", repo.trim().ifEmpty { DEFAULT_REPO }).apply()
        schedule(ctx)
    }

    fun disconnect(ctx: Context) {
        Vault.put(ctx, "github_token", "")
        sp(ctx).edit().clear().apply()
        WorkManager.getInstance(ctx).cancelUniqueWork(UPLOAD_DAILY)
        WorkManager.getInstance(ctx).cancelUniqueWork(FETCH_DAILY)
    }

    var Context.uploadStatus: String
        get() = sp(this).getString("upload_status", "")!!
        set(v) = sp(this).edit().putString("upload_status", v).apply()
    var Context.fetchStatus: String
        get() = sp(this).getString("fetch_status", "")!!
        set(v) = sp(this).edit().putString("fetch_status", v).apply()

    private fun client(ctx: Context) = GitHub(token(ctx), repo(ctx))

    fun check(ctx: Context): String = client(ctx).check()

    private fun now() = Instant.now().atZone(zone).format(iso)
    private fun clock(ms: Long = System.currentTimeMillis()) = Instant.ofEpochMilli(ms).atZone(zone).format(hm)
    private fun sha1(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    // ------------------------------------------------------------- giorni/AAAA-MM-GG.md

    fun dayMarkdown(db: Db, day: String, ctx: Context? = null): String {
        val segs = db.segmentsOfDay(day)
        val gaps = db.gapsOfDay(day)
        val speechMin = segs.sumOf { it.endMs - it.startMs } / 60_000
        val sb = StringBuilder("# $day\n")
        val buchi = if (gaps.isEmpty()) "nessuno" else gaps.joinToString(", ") {
            "${clock(it.startMs)}-${clock(it.endMs)} (${it.reason})"
        }
        sb.append("parlato: $speechMin min · buchi: $buchi\n")
        val pending = segs.count { it.text == null }
        if (pending > 0) sb.append("ancora da trascrivere: $pending pezzi\n")
        ctx?.let { c -> runCatching { Speakers.diagnosis(c, db, day) }.getOrNull() }?.let { sb.append("voci: ").append(it).append("\n") }
        val rough = runCatching { it.iaco.lifelog.Refine.pendingOfDay(ctx ?: return@runCatching 0, day) }.getOrDefault(0)
        if (rough > 0) sb.append("da rifinire con Whisper: $rough blocchi (testo provvisorio di Parakeet, più errori)\n")
        sb.append("\n")
        val voices = Speakers.voiceMap(db)
        // Luoghi (se attivi): una riga quando cambia il posto, solo il nome, mai le coordinate.
        val placeOf: (Long) -> String? = ctx?.let { c -> runCatching { it.iaco.lifelog.memory.Places.locator(c, day) }.getOrNull() } ?: { _ -> null }
        var lastPlace: String? = null
        // Momenti segnati da Iaco: una riga "★" all'ora giusta, in mezzo al parlato.
        val marks = ArrayDeque(db.marksOfDay(day))
        fun markLine(ms: Long) {
            sb.append(Instant.ofEpochMilli(ms).atZone(zone).format(hms)).append(" ★ Iaco ha segnato questo momento\n")
        }
        for (s in segs) {
            val t = s.text?.trim().orEmpty()
            if (t.isEmpty()) continue
            while (marks.isNotEmpty() && marks.first().ms < s.startMs) markLine(marks.removeFirst().ms)
            val place = placeOf(s.startMs)
            if (place != lastPlace) {
                sb.append("\n— luogo: ").append(place ?: "in giro").append(" —\n")
                lastPlace = place
            }
            val who = Speakers.label(s.voice, voices)
            sb.append(Instant.ofEpochMilli(s.startMs).atZone(zone).format(hms)).append(" ").append(who).append(": ").append(t.replace("\n", " ")).append("\n")
        }
        while (marks.isNotEmpty()) markLine(marks.removeFirst().ms)
        return sb.toString()
    }

    /** Carica i giorni cambiati (ultimi 21) e lo stato degli impegni. Ritorna un riassunto. */
    fun upload(ctx: Context): String {
        val gh = client(ctx)
        val db = Db.get(ctx)
        val sp = sp(ctx)
        var sent = 0
        // Anche i giorni con soli buchi (registrazione ferma tutto il giorno): Claude deve sapere perché è vuoto.
        val days = (db.days().map { it.day } + db.gapDays()).distinct().sortedDescending().take(21)
        for (day in days) {
            val md = dayMarkdown(db, day, ctx)
            val h = sha1(md)
            if (sp.getString("sent_$day", null) == h) continue
            gh.write("giorni/$day.md", md, "app: giorno $day")
            sp.edit().putString("sent_$day", h).apply()
            sent++
        }
        if (runCatching { Briefing.uploadCalendar(ctx, gh) }.getOrDefault(false)) sent++
        runCatching { Memory.upload(ctx, gh) } // domande salvate
        val state = appStateJson(ctx)
        val sh = sha1(state.toString().replace(Regex("\"aggiornato\":\"[^\"]*\""), ""))
        if (sp.getString("sent_state", null) != sh) {
            gh.write("stato/app.json", state.toString(2), "app: stato impegni")
            sp.edit().putString("sent_state", sh).apply()
        }
        // Seral e università: un errore qui non deve fermare l'invio dei giorni.
        val areeErr = runCatching { Aree.upload(ctx, gh) }.exceptionOrNull()
        val msg = "Inviato alle ${clock()}: " + if (sent == 0) "niente di nuovo" else if (sent == 1) "1 giorno" else "$sent giorni"
        ctx.uploadStatus = msg + (areeErr?.let { ". Seral e università non inviati: ${it.message}" } ?: "")
        return msg
    }

    // ------------------------------------------------------------- stato/app.json (spunte)

    private fun localState(ctx: Context): JSONObject =
        runCatching { JSONObject(sp(ctx).getString("impegni_stato", "{}")!!) }.getOrDefault(JSONObject())

    fun setDone(ctx: Context, id: String, done: Boolean) {
        val st = localState(ctx)
        st.put(id, JSONObject().put("stato", if (done) "fatto" else "aperto").put("quando", now()))
        sp(ctx).edit().putString("impegni_stato", st.toString()).apply()
        uploadSoon(ctx)
    }

    private fun appStateJson(ctx: Context): JSONObject {
        val st = localState(ctx)
        val arr = JSONArray()
        st.keys().forEach { id -> arr.put(JSONObject(st.getJSONObject(id).toString()).put("id", id)) }
        return JSONObject().put("aggiornato", now()).put("impegni", arr)
            .put("registrazione", runCatching { recordingJson(ctx) }.getOrDefault(JSONObject()))
            .put("abitudini", runCatching { Memory.marksJson(ctx) }.getOrDefault(JSONArray()))
            .also { Briefing.addState(ctx, it) }
            .also { o -> runCatching { People.stateFields(ctx, o) } }
            .also { o -> runCatching { Miglioramenti.stateField(ctx, o) } }
    }

    /**
     * Stato del microfono adesso, per Claude: se un giorno è vuoto, l'analisi dice perché
     * (fermata da Iaco, in pausa, bloccata dal sistema) invece di "nessuna trascrizione".
     */
    private fun recordingJson(ctx: Context): JSONObject {
        val p = it.iaco.lifelog.Prefs(ctx)
        fun at(ms: Long): Any = if (ms > 0) Instant.ofEpochMilli(ms).atZone(zone).format(iso) else JSONObject.NULL
        val pauseUntil = it.iaco.lifelog.Pause.until(ctx)
        val stato = when {
            !p.recordingWanted -> "fermata da Iaco"
            pauseUntil > 0 -> "pausa privacy"
            it.iaco.lifelog.Watchdog.isStalled(ctx) -> "ferma (bloccata dal sistema o in attesa di un tocco)"
            else -> "attiva"
        }
        val version = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull()
        return JSONObject()
            .put("stato", stato)
            .put("ultimo_segnale", at(p.lastHeartbeat))
            .put("fermata_il", at(if (!p.recordingWanted) p.stoppedAt else 0L))
            .put("pausa_fino", at(pauseUntil))
            .put("versione_app", version ?: JSONObject.NULL)
    }

    // ------------------------------------------------------------- ricezione

    private fun dir(ctx: Context) = File(ctx.filesDir, "assistente").apply { mkdirs() }

    fun yesterday(): String = LocalDate.now(zone).minusDays(1).toString()

    fun fetch(ctx: Context): String {
        val gh = client(ctx)
        val day = yesterday()
        val an = gh.read("analisi/$day.json")
        if (an != null) File(dir(ctx), "analisi-$day.json").writeText(an)
        // Anche i 6 giorni prima: Claude li rianalizza quando arrivano trascrizioni in ritardo,
        // e servono per i titoli delle conversazioni nella pagina di ogni giorno.
        for (back in 2L..7L) runCatching {
            val d = LocalDate.now(zone).minusDays(back).toString()
            gh.read("analisi/$d.json")?.let { File(dir(ctx), "analisi-$d.json").writeText(it) }
        }
        gh.read("impegni.json")?.let { File(dir(ctx), "impegni.json").writeText(it) }
        runCatching { Aree.fetch(ctx, gh) } // Seral e università
        runCatching { Briefing.fetch(ctx, gh) }
        runCatching { Memory.fetch(ctx, gh) } // indice e abitudini
        runCatching { People.fetch(ctx, gh) }
        runCatching { gh.read("miglioramenti.json")?.let { Miglioramenti.save(ctx, it) } }
        val msg = if (an != null) "Analisi di ieri ricevuta alle ${clock()}" else "Controllato alle ${clock()}: l'analisi di stanotte non è ancora arrivata"
        ctx.fetchStatus = msg
        return msg
    }

    data class Item(val ora: String, val testo: String, val tipo: String, val segnato: Boolean = false)
    /** Una conversazione del giorno, col titolo dato da Claude (orari HH:MM). */
    data class Conversation(val da: String, val a: String, val titolo: String)
    data class Section(val area: String, val titolo: String, val voci: List<Item>)
    data class Analysis(
        val data: String, val generato: String, val parlatoMin: Int, val vuoto: Boolean,
        val giornata: String, val sezioni: List<Section>, val domani: List<String>, val daVerificare: List<Item>,
        val conversazioni: List<Conversation> = emptyList(),
    )
    data class Commitment(
        val id: String, val testo: String, val chi: String, val persona: String, val area: String,
        val fonteGiorno: String, val fonteOra: String, val scadenza: String?, val stato: String,
    )

    fun analysis(ctx: Context, day: String = yesterday()): Analysis? {
        val f = File(dir(ctx), "analisi-$day.json")
        if (!f.exists()) return null
        return runCatching { parseAnalysis(JSONObject(f.readText())) }.getOrNull()
    }

    fun parseAnalysis(j: JSONObject): Analysis {
        fun items(a: JSONArray?) = (0 until (a?.length() ?: 0)).map { i ->
            val o = a!!.getJSONObject(i); Item(o.optString("ora"), o.optString("testo"), o.optString("tipo"), o.optBoolean("segnato"))
        }
        val conv = j.optJSONArray("conversazioni")
        val sez = j.optJSONArray("sezioni")
        return Analysis(
            data = j.optString("data"), generato = j.optString("generato"),
            parlatoMin = j.optInt("parlato_min"), vuoto = j.optBoolean("vuoto"),
            giornata = j.optString("giornata"),
            sezioni = (0 until (sez?.length() ?: 0)).map { i ->
                val o = sez!!.getJSONObject(i)
                Section(o.optString("area"), o.optString("titolo"), items(o.optJSONArray("voci")))
            },
            domani = j.optJSONArray("domani").let { a -> (0 until (a?.length() ?: 0)).map { a!!.optString(it) } },
            daVerificare = items(j.optJSONArray("da_verificare")),
            conversazioni = (0 until (conv?.length() ?: 0)).mapNotNull { i ->
                val o = conv!!.optJSONObject(i) ?: return@mapNotNull null
                val t = o.optString("titolo").trim()
                if (t.isEmpty() || o.optString("da").length < 4) null
                else Conversation(o.optString("da").take(5), o.optString("a").take(5), t)
            },
        )
    }

    /** Impegni scaricati, con le spunte fatte sul telefono che prevalgono. */
    fun commitments(ctx: Context): List<Commitment> {
        val f = File(dir(ctx), "impegni.json")
        if (!f.exists()) return emptyList()
        val local = localState(ctx)
        return runCatching {
            val a = JSONArray(f.readText())
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                val id = o.optString("id")
                val fonte = o.optJSONObject("fonte")
                Commitment(
                    id = id, testo = o.optString("testo"), chi = o.optString("chi", "io"),
                    persona = o.optString("persona"), area = o.optString("area"),
                    fonteGiorno = fonte?.optString("giorno").orEmpty(), fonteOra = fonte?.optString("ora").orEmpty(),
                    scadenza = o.optString("scadenza").takeIf { it.isNotBlank() && it != "null" },
                    stato = local.optJSONObject(id)?.optString("stato") ?: o.optString("stato", "aperto"),
                )
            }
        }.getOrDefault(emptyList())
    }

    // ------------------------------------------------------------- pianificazione

    // Nomi vecchi (v20-v49): erano periodici con UPDATE, che a ogni apertura spostava l'orario. Si cancellano.
    private const val UPLOAD_DAILY_OLD = "assistant_upload_daily"
    private const val FETCH_DAILY_OLD = "assistant_fetch_daily"
    private const val UPLOAD_DAILY = "assistant_upload_nightly"
    private const val UPLOAD_NOW = "assistant_upload_now"
    private const val FETCH_DAILY = "assistant_fetch_morning"
    private const val FETCH_NOW = "assistant_fetch_now"
    const val KEY_RESULT = "result"

    private fun delayTo(h: Int, m: Int): Long {
        val now = LocalDateTime.now(zone)
        var t = now.toLocalDate().atTime(LocalTime.of(h, m))
        if (!t.isAfter(now)) t = t.plusDays(1)
        return Duration.between(now, t).toMinutes()
    }

    private val net = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /**
     * Invii notturni: alle 23:00 e alle 2:45, prima dell'analisi delle 3:17. La sveglia di sistema
     * (setAndAllowWhileIdle) scatta anche con il telefono in riposo, dove WorkManager può tardare di ore;
     * il lavoro periodico resta come riserva. Da chiamare all'avvio del processo e all'apertura dell'app.
     */
    val UPLOAD_TIMES: List<LocalTime> = listOf(LocalTime.of(23, 0), LocalTime.of(2, 45))
    val FETCH_TIME: LocalTime = LocalTime.of(7, 0)

    fun nextAt(times: List<LocalTime>, from: LocalDateTime = LocalDateTime.now(zone)): LocalDateTime =
        times.map { t -> from.toLocalDate().atTime(t).let { if (it.isAfter(from)) it else it.plusDays(1) } }.min()

    fun schedule(ctx: Context) {
        if (!isConfigured(ctx)) return
        runCatching { NightAlarm.arm(ctx) }
        val wm = WorkManager.getInstance(ctx)
        val sp = sp(ctx)
        if (!sp.getBoolean("periodic_v50", false)) {
            wm.cancelUniqueWork(UPLOAD_DAILY_OLD); wm.cancelUniqueWork(FETCH_DAILY_OLD)
            sp.edit().putBoolean("periodic_v50", true).apply()
        }
        wm.enqueueUniquePeriodicWork(
            UPLOAD_DAILY, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<UploadWorker>(24, TimeUnit.HOURS)
                .setInitialDelay(delayTo(23, 0), TimeUnit.MINUTES).setConstraints(net).build()
        )
        wm.enqueueUniquePeriodicWork(
            FETCH_DAILY, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<FetchWorker>(24, TimeUnit.HOURS)
                .setInitialDelay(delayTo(7, 0), TimeUnit.MINUTES).setConstraints(net).build()
        )
    }

    fun uploadSoon(ctx: Context) {
        if (!isConfigured(ctx)) return
        WorkManager.getInstance(ctx).enqueueUniqueWork(
            UPLOAD_NOW, ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<UploadWorker>().setConstraints(net).build()
        )
    }

    /** All'apertura dell'app: controlla se è arrivata l'analisi di stanotte. */
    fun fetchSoon(ctx: Context) {
        if (!isConfigured(ctx)) return
        WorkManager.getInstance(ctx).enqueueUniqueWork(
            FETCH_NOW, ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<FetchWorker>().setConstraints(net).build()
        )
    }
}

class UploadWorker(ctx: Context, p: WorkerParameters) : CoroutineWorker(ctx, p) {
    override suspend fun doWork(): Result = try {
        Result.success(workDataOf(Assistant.KEY_RESULT to Assistant.upload(applicationContext)))
    } catch (e: Exception) {
        with(Assistant) { applicationContext.uploadStatus = "Invio non riuscito: ${e.message}" }
        if (runAttemptCount < 3) Result.retry() else Result.failure()
    }
}

class FetchWorker(ctx: Context, p: WorkerParameters) : CoroutineWorker(ctx, p) {
    override suspend fun doWork(): Result = try {
        Result.success(workDataOf(Assistant.KEY_RESULT to Assistant.fetch(applicationContext)))
    } catch (e: Exception) {
        with(Assistant) { applicationContext.fetchStatus = "Ricezione non riuscita: ${e.message}" }
        if (runAttemptCount < 3) Result.retry() else Result.failure()
    }
}
