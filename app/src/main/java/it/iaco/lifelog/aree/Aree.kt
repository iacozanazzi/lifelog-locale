package it.iaco.lifelog.aree

import android.content.Context
import it.iaco.lifelog.data.Assistant
import it.iaco.lifelog.data.GitHub
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Seral e università: file che Claude scrive di notte in lifelog-dati
 * (seral/…, uni/…) e le scelte fatte sul telefono (stato/seral-uni.json).
 * Formati concordati in regole/seral-uni.md del repository dati.
 *
 * Tutto sta in filesDir/aree/: le copie dei file di Claude e stato.json,
 * che tiene spunte, idee archiviate, ripassi ed esami aggiunti a mano.
 */
object Aree {
    val REMOTE = listOf("seral/idee.json", "seral/serate.json", "seral/post.json", "uni/domande.json", "uni/esami.json")
    private const val STATE_REMOTE = "stato/seral-uni.json"

    private fun dir(ctx: Context) = File(ctx.filesDir, "aree").apply { mkdirs() }
    private fun local(ctx: Context, remotePath: String) = File(dir(ctx), remotePath.replace('/', '-'))

    fun now(): String = Instant.now().atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
    fun today(): LocalDate = LocalDate.now(ZoneId.systemDefault())

    /** Scarica i file di Claude. Un file che manca non è un errore: Claude lo crea alla prima voce. */
    fun fetch(ctx: Context, gh: GitHub) {
        for (p in REMOTE) gh.read(p)?.let { local(ctx, p).writeText(it) }
    }

    fun remoteArray(ctx: Context, path: String): JSONArray {
        val f = local(ctx, path)
        if (!f.exists()) return JSONArray()
        return runCatching { JSONArray(f.readText()) }.getOrDefault(JSONArray())
    }

    fun remoteObject(ctx: Context, path: String): JSONObject? {
        val f = local(ctx, path)
        if (!f.exists()) return null
        return runCatching { JSONObject(f.readText()) }.getOrNull()
    }

    // ------------------------------------------------------------- stato locale

    private val lock = Any()

    fun state(ctx: Context): JSONObject = synchronized(lock) {
        val f = File(dir(ctx), "stato.json")
        if (!f.exists()) JSONObject() else runCatching { JSONObject(f.readText()) }.getOrDefault(JSONObject())
    }

    /** Cambia lo stato locale e lo manda a Claude appena c'è rete. */
    fun edit(ctx: Context, block: (JSONObject) -> Unit) {
        synchronized(lock) {
            val st = state(ctx)
            block(st)
            File(dir(ctx), "stato.json").writeText(st.toString())
        }
        Assistant.uploadSoon(ctx)
    }

    /** Oggetto figlio, creato se manca. */
    fun JSONObject.child(key: String): JSONObject = optJSONObject(key) ?: JSONObject().also { put(key, it) }

    // ------------------------------------------------------------- invio a Claude

    /** stato/seral-uni.json nel formato concordato. */
    fun stateForClaude(ctx: Context): JSONObject {
        val st = state(ctx)
        fun entries(key: String, map: (String, JSONObject) -> JSONObject): JSONArray {
            val o = st.optJSONObject(key) ?: return JSONArray()
            val a = JSONArray()
            o.keys().forEach { k -> o.optJSONObject(k)?.let { a.put(map(k, it)) } }
            return a
        }
        val idee = entries("idee") { id, v -> JSONObject().put("id", id).put("stato", v.optString("stato")).put("quando", v.optString("quando")) }
        val checklist = entries("checklist") { k, v ->
            val (serata, voce) = k.split('|', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            JSONObject().put("serata", serata).put("voce", voce).put("stato", v.optString("stato")).put("quando", v.optString("quando"))
        }
        val scartate = JSONArray().also { a -> st.optJSONObject("scartate")?.keys()?.forEach { a.put(it) } }
        val aggiunti = entries("esami_aggiunti") { id, v -> JSONObject().put("id", id).put("materia", v.optString("materia")).put("data", v.optString("data")) }
        val tolti = JSONArray().also { a -> st.optJSONObject("esami_tolti")?.keys()?.forEach { a.put(it) } }
        val fatti = entries("argomenti_fatti") { k, v ->
            val (esame, arg) = k.split('|', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            JSONObject().put("esame", esame).put("argomento", arg).put("quando", v.optString("quando"))
        }
        val ripasso = JSONArray()
        Uni.reviewSummary(ctx).forEach { s ->
            ripasso.put(
                JSONObject().put("materia", s.materia).put("domande", s.domande)
                    .put("da_ripassare", s.daRipassare).put("sbagliate_7_giorni", s.sbagliate7)
            )
        }
        return JSONObject()
            .put("aggiornato", now())
            .put("idee", idee)
            .put("checklist", checklist)
            .put("domande_scartate", scartate)
            .put("ripasso", ripasso)
            .put("esami_aggiunti", aggiunti)
            .put("esami_tolti", tolti)
            .put("argomenti_fatti", fatti)
    }

    /** Manda lo stato solo se è cambiato. Ritorna true se ha scritto. */
    fun upload(ctx: Context, gh: GitHub): Boolean {
        val st = stateForClaude(ctx)
        val body = st.toString(2)
        val h = MessageDigest.getInstance("SHA-1")
            .digest(body.replace(Regex("\"aggiornato\": \"[^\"]*\""), "").toByteArray())
            .joinToString("") { "%02x".format(it) }
        val sp = ctx.getSharedPreferences("lifelog_aree", Context.MODE_PRIVATE)
        if (sp.getString("sent_state", null) == h) return false
        // Niente da dire finché Iaco non ha toccato niente: non creo un file vuoto.
        if (sp.getString("sent_state", null) == null && isEmpty(st)) return false
        gh.write(STATE_REMOTE, body, "app: stato seral e università")
        sp.edit().putString("sent_state", h).apply()
        return true
    }

    private fun isEmpty(st: JSONObject): Boolean =
        listOf("idee", "checklist", "domande_scartate", "ripasso", "esami_aggiunti", "esami_tolti", "argomenti_fatti")
            .all { (st.optJSONArray(it)?.length() ?: 0) == 0 }
}

/** Da dove viene un fatto: giorno e ora nelle trascrizioni. */
data class Fonte(val giorno: String, val ora: String) {
    companion object {
        fun of(o: JSONObject?): Fonte? {
            if (o == null) return null
            val g = o.optString("giorno")
            if (g.isBlank() || g == "null") return null
            return Fonte(g, o.optString("ora").takeIf { it != "null" }.orEmpty())
        }
    }
}

internal fun JSONObject.str(key: String): String? = optString(key).takeIf { has(key) && !isNull(key) && it.isNotBlank() && it != "null" }

internal fun JSONArray?.objects(): List<JSONObject> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

internal fun JSONArray?.strings(): List<String> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optString(it).takeIf { s -> s.isNotBlank() && s != "null" } }
