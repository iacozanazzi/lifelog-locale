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
    private val zone get() = ZoneId.systemDefault()
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

    fun dayMarkdown(db: Db, day: String): String {
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
        sb.append("\n")
        for (s in segs) {
            val t = s.text?.trim().orEmpty()
            if (t.isEmpty()) continue
            sb.append(Instant.ofEpochMilli(s.startMs).atZone(zone).format(hms)).append(" ?: ").append(t.replace("\n", " ")).append("\n")
        }
        return sb.toString()
    }

    /** Carica i giorni cambiati (ultimi 21) e lo stato degli impegni. Ritorna un riassunto. */
    fun upload(ctx: Context): String {
        val gh = client(ctx)
        val db = Db.get(ctx)
        val sp = sp(ctx)
        var sent = 0
        for (d in db.days().take(21)) {
            val md = dayMarkdown(db, d.day)
            val h = sha1(md)
            if (sp.getString("sent_${d.day}", null) == h) continue
            gh.write("giorni/${d.day}.md", md, "app: giorno ${d.day}")
            sp.edit().putString("sent_${d.day}", h).apply()
            sent++
        }
        if (runCatching { Briefing.uploadCalendar(ctx, gh) }.getOrDefault(false)) sent++
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
        return JSONObject().put("aggiornato", now()).put("impegni", arr).also { Briefing.addState(ctx, it) }
    }

    // ------------------------------------------------------------- ricezione

    private fun dir(ctx: Context) = File(ctx.filesDir, "assistente").apply { mkdirs() }

    fun yesterday(): String = LocalDate.now(zone).minusDays(1).toString()

    fun fetch(ctx: Context): String {
        val gh = client(ctx)
        val day = yesterday()
        val an = gh.read("analisi/$day.json")
        if (an != null) File(dir(ctx), "analisi-$day.json").writeText(an)
        gh.read("impegni.json")?.let { File(dir(ctx), "impegni.json").writeText(it) }
        runCatching { Aree.fetch(ctx, gh) } // Seral e università
        runCatching { Briefing.fetch(ctx, gh) }
        val msg = if (an != null) "Analisi di ieri ricevuta alle ${clock()}" else "Controllato alle ${clock()}: l'analisi di stanotte non è ancora arrivata"
        ctx.fetchStatus = msg
        return msg
    }

    data class Item(val ora: String, val testo: String, val tipo: String)
    data class Section(val area: String, val titolo: String, val voci: List<Item>)
    data class Analysis(
        val data: String, val generato: String, val parlatoMin: Int, val vuoto: Boolean,
        val giornata: String, val sezioni: List<Section>, val domani: List<String>, val daVerificare: List<Item>,
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
            val o = a!!.getJSONObject(i); Item(o.optString("ora"), o.optString("testo"), o.optString("tipo"))
        }
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

    private const val UPLOAD_DAILY = "assistant_upload_daily"
    private const val UPLOAD_NOW = "assistant_upload_now"
    private const val FETCH_DAILY = "assistant_fetch_daily"
    private const val FETCH_NOW = "assistant_fetch_now"
    const val KEY_RESULT = "result"

    private fun delayTo(h: Int, m: Int): Long {
        val now = LocalDateTime.now(zone)
        var t = now.toLocalDate().atTime(LocalTime.of(h, m))
        if (!t.isAfter(now)) t = t.plusDays(1)
        return Duration.between(now, t).toMinutes()
    }

    private val net = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun schedule(ctx: Context) {
        if (!isConfigured(ctx)) return
        val wm = WorkManager.getInstance(ctx)
        wm.enqueueUniquePeriodicWork(
            UPLOAD_DAILY, ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<UploadWorker>(24, TimeUnit.HOURS)
                .setInitialDelay(delayTo(23, 0), TimeUnit.MINUTES).setConstraints(net).build()
        )
        wm.enqueueUniquePeriodicWork(
            FETCH_DAILY, ExistingPeriodicWorkPolicy.UPDATE,
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
