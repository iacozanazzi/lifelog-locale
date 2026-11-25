package it.iaco.lifelog.memory

import android.content.Context
import it.iaco.lifelog.Db
import it.iaco.lifelog.Exporter
import it.iaco.lifelog.data.Assistant
import it.iaco.lifelog.data.GitHub
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters

/**
 * Memoria lunga: tempo per area, abitudini e "un anno fa".
 * I dati li prepara Claude di notte in lifelog-dati (indice/AAAA.json, abitudini.json);
 * qui si scaricano, si tengono in filesDir/memory e si leggono senza rete.
 */
object Memory {
    private const val SP = "lifelog_memory"
    private fun sp(ctx: Context) = ctx.getSharedPreferences(SP, Context.MODE_PRIVATE)
    fun dir(ctx: Context) = File(ctx.filesDir, "memory").apply { mkdirs() }
    private val zone get() = ZoneId.systemDefault()

    // ------------------------------------------------------------- scambio con lifelog-dati

    /** Chiamato dentro Assistant.fetch(): scarica indice dell'anno (e del precedente) e abitudini. */
    fun fetch(ctx: Context, gh: GitHub) {
        val today = LocalDate.now(zone)
        val years = buildSet {
            add(today.year)
            add(today.minusYears(1).year) // per "un anno fa"
            add(today.minusDays(7).year)  // settimana a cavallo di capodanno
        }
        for (y in years) gh.read("indice/$y.json")?.let { File(dir(ctx), "indice-$y.json").writeText(it) }
        gh.read("abitudini.json")?.let { File(dir(ctx), "abitudini.json").writeText(it) }
    }

    /** Chiamato dentro Assistant.upload(): scrive domande.json se la lista è cambiata. */
    fun upload(ctx: Context, gh: GitHub) {
        val json = Questions.toJson(Questions.all(ctx)).toString(2)
        val h = sha1(json)
        if (sp(ctx).getString("sent_domande", null) == h) return
        gh.write("domande.json", json, "app: domande salvate")
        sp(ctx).edit().putString("sent_domande", h).apply()
    }

    private fun sha1(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    // ------------------------------------------------------------- indice

    data class IndexDay(val day: String, val parlatoMin: Int, val sintesi: String, val tempo: Map<String, Int>)

    private fun index(ctx: Context, year: Int): Map<String, IndexDay> {
        val f = File(dir(ctx), "indice-$year.json")
        if (!f.exists()) return emptyMap()
        return runCatching {
            val o = JSONObject(f.readText())
            o.keys().asSequence().associateWith { k ->
                val d = o.getJSONObject(k)
                val t = d.optJSONObject("tempo_min")
                IndexDay(
                    day = k, parlatoMin = d.optInt("parlato_min"), sintesi = d.optString("sintesi"),
                    tempo = t?.keys()?.asSequence()?.associateWith { t.optInt(it) } ?: emptyMap(),
                )
            }
        }.getOrDefault(emptyMap())
    }

    fun indexDay(ctx: Context, day: LocalDate): IndexDay? = index(ctx, day.year)[day.toString()]

    // ------------------------------------------------------------- tempo per area (#4)

    data class AreaWeek(val area: String, val minutes: Int, val lastWeek: Int)
    data class Week(val from: LocalDate, val days: Int, val areas: List<AreaWeek>)

    val AREAS = listOf("seral", "uni", "persone")

    fun monday(d: LocalDate): LocalDate = d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    /** Settimana corrente (da lunedì a ieri) confrontata con la precedente. */
    fun week(ctx: Context, today: LocalDate = LocalDate.now(zone)): Week {
        val mon = monday(today)
        fun sum(from: LocalDate, to: LocalDate): Pair<Map<String, Int>, Int> {
            val acc = HashMap<String, Int>()
            var n = 0
            var d = from
            while (!d.isAfter(to)) {
                indexDay(ctx, d)?.let { e -> n++; e.tempo.forEach { (k, v) -> acc[k] = (acc[k] ?: 0) + v } }
                d = d.plusDays(1)
            }
            return acc to n
        }
        val (now, n) = sum(mon, today)
        val (prev, _) = sum(mon.minusWeeks(1), mon.minusDays(1))
        return Week(mon, n, AREAS.map { AreaWeek(it, now[it] ?: 0, prev[it] ?: 0) })
    }

    // ------------------------------------------------------------- un anno fa (#13)

    data class Memento(val day: String, val label: String, val text: String)

    /** Un anno fa oggi; finché non c'è, un mese fa. Null se non c'è niente da ricordare. */
    fun memento(ctx: Context, today: LocalDate = LocalDate.now(zone)): Memento? {
        for ((label, d) in listOf("Un anno fa oggi" to today.minusYears(1), "Un mese fa oggi" to today.minusMonths(1))) {
            val text = indexDay(ctx, d)?.sintesi?.takeIf { it.isNotBlank() } ?: localFirstLine(ctx, d.toString())
            if (!text.isNullOrBlank()) return Memento(d.toString(), label, text)
        }
        return null
    }

    private fun localFirstLine(ctx: Context, day: String): String? = runCatching {
        val p = Exporter.paragraphs(Db.get(ctx).segmentsOfDay(day))
        p.maxByOrNull { it.text.length }?.text?.take(160)
    }.getOrNull()

    // ------------------------------------------------------------- abitudini (#5)

    data class Proof(val day: String, val ora: String, val citazione: String, val esito: String)
    data class Habit(
        val id: String, val testo: String, val detto: String, val obiettivo: Int?, val dal: String,
        val fonteGiorno: String, val fonteOra: String, val stato: String, val prove: List<Proof>,
    )

    private fun marks(ctx: Context): JSONObject =
        runCatching { JSONObject(sp(ctx).getString("abitudini_segni", "{}")!!) }.getOrDefault(JSONObject())

    /** Abitudini scaricate, con le spunte fatte sul telefono che prevalgono. */
    fun habits(ctx: Context): List<Habit> {
        val f = File(dir(ctx), "abitudini.json")
        if (!f.exists()) return emptyList()
        val m = marks(ctx)
        return runCatching {
            val a = JSONArray(f.readText())
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                val id = o.optString("id")
                val pa = o.optJSONArray("prove")
                val proofs = (0 until (pa?.length() ?: 0)).map { j ->
                    val p = pa!!.getJSONObject(j)
                    Proof(p.optString("giorno"), p.optString("ora"), p.optString("citazione"), p.optString("esito", "fatto"))
                }.associateBy { it.day }.toMutableMap()
                m.optJSONObject(id)?.let { mine ->
                    mine.keys().forEach { day ->
                        when (mine.getJSONObject(day).optString("esito")) {
                            "fatto" -> proofs[day] = Proof(day, "", "segnato a mano", "fatto")
                            "nessuno" -> proofs.remove(day)
                        }
                    }
                }
                val fonte = o.optJSONObject("fonte")
                Habit(
                    id = id, testo = o.optString("testo"), detto = o.optString("detto"),
                    obiettivo = if (o.isNull("obiettivo_settimana")) null else o.optInt("obiettivo_settimana").takeIf { it > 0 },
                    dal = o.optString("dal"), fonteGiorno = fonte?.optString("giorno").orEmpty(), fonteOra = fonte?.optString("ora").orEmpty(),
                    stato = o.optString("stato", "attiva"), prove = proofs.values.sortedBy { it.day },
                )
            }
        }.getOrDefault(emptyList())
    }

    /** Spunta a mano: fatto = c'è stato, false = togli la prova di quel giorno. */
    fun mark(ctx: Context, id: String, day: String, done: Boolean) {
        val m = marks(ctx)
        val h = m.optJSONObject(id) ?: JSONObject().also { m.put(id, it) }
        h.put(day, JSONObject().put("esito", if (done) "fatto" else "nessuno").put("quando", now()))
        sp(ctx).edit().putString("abitudini_segni", m.toString()).apply()
        Assistant.uploadSoon(ctx)
    }

    /** Per stato/app.json: [{id, giorno, esito, quando}]. */
    fun marksJson(ctx: Context): JSONArray {
        val out = JSONArray()
        val m = marks(ctx)
        m.keys().forEach { id ->
            val h = m.getJSONObject(id)
            h.keys().forEach { day ->
                val e = h.getJSONObject(day)
                out.put(JSONObject().put("id", id).put("giorno", day).put("esito", e.optString("esito")).put("quando", e.optString("quando")))
            }
        }
        return out
    }

    private fun now() = Instant.now().atZone(zone).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
}
