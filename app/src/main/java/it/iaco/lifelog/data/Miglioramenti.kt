package it.iaco.lifelog.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.OffsetDateTime
import java.time.ZoneId

/**
 * Proposte di miglioramento scritte da Claude di notte in lifelog-dati/miglioramenti.json
 * (regole/miglioramenti.md). Iaco risponde Sì o No in Oggi; le risposte tornano in stato/app.json
 * nel campo "proposte". L'app non cambia mai da sola: un Sì vuol dire "fallo in un thread".
 */
object Miglioramenti {
    data class Proposta(val id: String, val chi: String, val titolo: String, val perche: String, val costo: String)

    private const val FILE = "miglioramenti.json"
    private const val KEY = "proposte_risposte"
    private fun file(ctx: Context) = File(File(ctx.filesDir, "assistente").apply { mkdirs() }, FILE)
    private fun sp(ctx: Context) = ctx.getSharedPreferences("assistente", Context.MODE_PRIVATE)

    fun save(ctx: Context, json: String) {
        runCatching { JSONObject(json) }.getOrNull() ?: return
        file(ctx).writeText(json)
    }

    private fun answers(ctx: Context): JSONObject =
        runCatching { JSONObject(sp(ctx).getString(KEY, "{}")!!) }.getOrDefault(JSONObject())

    /** Le proposte ancora da decidere: stato "nuova" e nessuna risposta data sul telefono. */
    fun open(ctx: Context): List<Proposta> {
        val f = file(ctx)
        if (!f.exists()) return emptyList()
        val done = answers(ctx)
        return parse(f.readText()).filter { !done.has(it.first.id) && it.second == "nuova" }.map { it.first }
    }

    fun parse(json: String): List<Pair<Proposta, String>> = runCatching {
        val a = JSONObject(json).optJSONArray("proposte") ?: JSONArray()
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            Proposta(o.optString("id"), o.optString("chi"), o.optString("titolo"), o.optString("perche"), o.optString("costo")) to
                o.optString("stato", "nuova")
        }.filter { it.first.id.isNotBlank() && it.first.titolo.isNotBlank() }
    }.getOrDefault(emptyList())

    fun answer(ctx: Context, id: String, yes: Boolean) {
        val a = answers(ctx)
        a.put(id, JSONObject().put("risposta", if (yes) "si" else "no")
            .put("quando", OffsetDateTime.now(ZoneId.of("Europe/Vienna")).withNano(0).toString()))
        sp(ctx).edit().putString(KEY, a.toString()).apply()
        Assistant.uploadSoon(ctx)
    }

    /** stato/app.json: "proposte": [{id, risposta, quando}]. */
    fun stateField(ctx: Context, into: JSONObject) {
        val a = answers(ctx)
        val arr = JSONArray()
        a.keys().forEach { id -> arr.put(JSONObject(a.getJSONObject(id).toString()).put("id", id)) }
        into.put("proposte", arr)
    }
}
