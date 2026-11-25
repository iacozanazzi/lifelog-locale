package it.iaco.lifelog.people

import android.content.Context
import it.iaco.lifelog.Db
import it.iaco.lifelog.data.Assistant
import it.iaco.lifelog.data.GitHub
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.MonthDay
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Il catalogo persone: unisce quello che scrive Claude di notte (persone.json e persone/<slug>.md)
 * con quello che sa il telefono (voci sentite, persone seguite) e con gli impegni.
 */
object People {
    data class Source(val day: String, val time: String)
    data class Date(val whenText: String, val what: String, val source: Source?)
    data class Meeting(val day: String, val time: String?, val text: String, val source: Source?)

    /** Una persona come la scrive Claude in persone.json. */
    data class Remote(
        val name: String,
        val slug: String,
        val voice: String?,
        val who: String,
        val met: String,
        val lastSeen: String,
        val topics: List<String>,
        val remember: List<String>,
        val dates: List<Date>,
        val nextMeeting: Meeting?,
    )

    /** Una persona per l'interfaccia, con tutto quello che serve alla scheda. */
    data class Person(
        val name: String,
        val slug: String,
        val remote: Remote?,
        val voice: Voice?,
        /** Ultima volta sentita: la più recente tra voce (telefono) e analisi di Claude. */
        val lastSeen: LocalDate?,
        val minutesHeard: Long,
        val followed: Boolean,
        val iOwe: List<Assistant.Commitment>,
        val theyOwe: List<Assistant.Commitment>,
    )

    private val zone get() = ZoneId.systemDefault()
    private fun dir(ctx: Context) = File(ctx.filesDir, "people").apply { mkdirs() }
    private fun sp(ctx: Context) = ctx.getSharedPreferences("lifelog_people", Context.MODE_PRIVATE)

    // ------------------------------------------------------------- persone seguite (#8)

    fun followed(ctx: Context): Set<String> = sp(ctx).getStringSet("seguite", emptySet())!!.toSet()

    fun setFollowed(ctx: Context, slug: String, on: Boolean) {
        val s = followed(ctx).toMutableSet()
        if (on) s += slug else s -= slug
        sp(ctx).edit().putStringSet("seguite", s).apply()
        Assistant.uploadSoon(ctx)
    }

    // ------------------------------------------------------------- scambio con lifelog-dati

    /** Campi aggiunti a stato/app.json: nomi dati alle voci e persone seguite. Mai impronte. */
    fun stateFields(ctx: Context, into: JSONObject) {
        val voci = JSONArray()
        runCatching { Db.get(ctx).loadVoices() }.getOrDefault(emptyList())
            .filter { it.name != null }
            .forEach { v ->
                voci.put(
                    JSONObject().put("id", v.id).put("nome", v.name).put("persona", v.persona ?: JSONObject.NULL)
                        .put("quando", v.namedMs?.let { Instant.ofEpochMilli(it).atZone(zone).toOffsetDateTime().toString() } ?: JSONObject.NULL)
                )
            }
        into.put("voci", voci)
        into.put("seguite", JSONArray(followed(ctx).sorted()))
    }

    /** Scarica persone.json e le schede delle persone (al massimo 40, le più recenti). */
    fun fetch(ctx: Context, gh: GitHub) {
        val json = gh.read("persone.json") ?: return
        File(dir(ctx), "persone.json").writeText(json)
        val list = parse(json).sortedByDescending { it.lastSeen }.take(40)
        val sp = sp(ctx)
        for (p in list) {
            // La scheda si riscarica solo se Claude l'ha toccata (ultima_volta cambiata) o se non c'è ancora.
            val key = "card_${p.slug}"
            val f = File(dir(ctx), "${p.slug}.md")
            if (f.exists() && sp.getString(key, null) == p.lastSeen) continue
            gh.read("persone/${p.slug}.md")?.let { f.writeText(it); sp.edit().putString(key, p.lastSeen).apply() }
        }
    }

    fun parse(json: String): List<Remote> = runCatching {
        val a = JSONArray(json)
        (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val name = o.optString("nome").trim().ifEmpty { return@mapNotNull null }
            fun strings(k: String) = o.optJSONArray(k)?.let { arr -> (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() } } ?: emptyList()
            fun source(x: JSONObject?) = x?.let { Source(it.optString("giorno"), it.optString("ora")) }
            Remote(
                name = name,
                slug = o.optString("slug").ifBlank { Speakers.slug(name) },
                voice = o.optString("voce").takeIf { it.isNotBlank() && it != "null" },
                who = o.optString("chi_e").takeIf { it != "null" }.orEmpty(),
                met = o.optString("conosciuto").takeIf { it != "null" }.orEmpty(),
                lastSeen = o.optString("ultima_volta").takeIf { it != "null" }.orEmpty(),
                topics = strings("temi"),
                remember = strings("ricordare"),
                dates = o.optJSONArray("date")?.let { arr ->
                    (0 until arr.length()).mapNotNull { j ->
                        arr.optJSONObject(j)?.let { d -> Date(d.optString("quando"), d.optString("cosa"), source(d.optJSONObject("fonte"))) }
                    }.filter { it.whenText.isNotBlank() }
                } ?: emptyList(),
                nextMeeting = o.optJSONObject("prossimo_incontro")?.let { m ->
                    Meeting(m.optString("giorno"), m.optString("ora").takeIf { it.isNotBlank() && it != "null" }, m.optString("testo"), source(m.optJSONObject("fonte")))
                }?.takeIf { it.day.isNotBlank() },
            )
        }
    }.getOrDefault(emptyList())

    fun remote(ctx: Context): List<Remote> {
        val f = File(dir(ctx), "persone.json")
        return if (f.exists()) parse(f.readText()) else emptyList()
    }

    /** La scheda scritta da Claude (persone/<slug>.md), se scaricata. */
    fun card(ctx: Context, slug: String): String? = File(dir(ctx), "$slug.md").takeIf { it.exists() }?.readText()

    /** Le righe della cronologia di una scheda, dalla più recente. */
    fun timeline(card: String?): List<String> {
        if (card == null) return emptyList()
        val lines = card.lines()
        val start = lines.indexOfFirst { it.trim().startsWith("## Cronologia") }
        if (start < 0) return emptyList()
        return lines.drop(start + 1).takeWhile { !it.startsWith("## ") }
            .map { it.trim().removePrefix("- ").trim() }.filter { it.isNotEmpty() }.reversed()
    }

    // ------------------------------------------------------------- catalogo

    fun all(ctx: Context): List<Person> {
        val remote = remote(ctx)
        val voices = runCatching { Db.get(ctx).loadVoices() }.getOrDefault(emptyList()).filter { !it.isMe }
        val stats = runCatching { Db.get(ctx).voiceStats() }.getOrDefault(emptyMap())
        val follow = followed(ctx)
        val commitments = if (Assistant.isConfigured(ctx)) Assistant.commitments(ctx) else emptyList()

        val bySlug = LinkedHashMap<String, Person>()
        fun build(name: String, slug: String, r: Remote?, v: Voice?): Person {
            val heardMs = v?.let { stats[it.id]?.third }
            val heard = heardMs?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
            val claude = r?.lastSeen?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() }
            val open = commitments.filter { it.stato != "fatto" && samePerson(it.persona, name, slug) }
            return Person(
                name = name, slug = slug, remote = r, voice = v,
                lastSeen = listOfNotNull(heard, claude).maxOrNull(),
                minutesHeard = (v?.let { stats[it.id]?.first } ?: 0) / 60,
                followed = slug in follow,
                iOwe = open.filter { it.chi != "altro" },
                theyOwe = open.filter { it.chi == "altro" },
            )
        }
        for (r in remote) {
            val v = voices.firstOrNull { it.id == r.voice || it.persona == r.slug || it.name.equals(r.name, true) }
            bySlug[r.slug] = build(r.name, r.slug, r, v)
        }
        // Voci con un nome che Claude non ha ancora in persone.json (le avrà dalla notte dopo).
        for (v in voices.filter { it.name != null }) {
            val slug = v.persona ?: Speakers.slug(v.name!!)
            if (slug !in bySlug) bySlug[slug] = build(v.name, slug, null, v)
        }
        return bySlug.values.sortedWith(compareByDescending<Person> { it.lastSeen ?: LocalDate.MIN }.thenBy { it.name })
    }

    fun find(ctx: Context, nameOrSlug: String): Person? {
        val all = all(ctx)
        val s = Speakers.slug(nameOrSlug)
        return all.firstOrNull { it.slug == s } ?: all.firstOrNull { it.name.equals(nameOrSlug.trim(), true) }
            ?: all.firstOrNull { it.name.split(" ").first().equals(nameOrSlug.trim(), true) }
    }

    private fun samePerson(persona: String, name: String, slug: String): Boolean {
        val p = persona.trim()
        if (p.isEmpty()) return false
        return p.equals(name, true) || Speakers.slug(p) == slug || name.split(" ").first().equals(p, true)
    }

    /** Voci senza nome che vale la pena far riconoscere: confermate, dalla più sentita. */
    data class Unknown(val voice: Voice, val minutes: Long, val days: Int, val lastMs: Long)

    fun unknownVoices(ctx: Context): List<Unknown> {
        val db = Db.get(ctx)
        val stats = runCatching { db.voiceStats() }.getOrDefault(emptyMap())
        return runCatching { db.loadVoices() }.getOrDefault(emptyList())
            .filter { it.name == null && !it.isMe && it.confirmed }
            .map { v -> val s = stats[v.id]; Unknown(v, (s?.first ?: 0) / 60, s?.second ?: 0, s?.third ?: v.lastMs) }
            .sortedByDescending { it.minutes }
    }

    // ------------------------------------------------------------- promemoria (#7, #8, #11)

    data class Reminder(val slug: String, val name: String, val text: String, val detail: List<String> = emptyList())

    const val SILENCE_DAYS = 21L

    /**
     * Cosa mostrare in Oggi:
     * - chi vedi oggi, con cosa ricordare e cosa vi dovete (#11);
     * - compleanni e date nei prossimi 7 giorni (#7);
     * - le persone seguite che non senti da 3 settimane (#8), solo quelle scelte.
     */
    fun reminders(ctx: Context, today: LocalDate = LocalDate.now(zone)): List<Reminder> =
        reminders(all(ctx), today)

    fun reminders(people: List<Person>, today: LocalDate): List<Reminder> {
        val out = mutableListOf<Reminder>()
        for (p in people) {
            val m = p.remote?.nextMeeting
            if (m != null && (m.day == today.toString() || m.day == today.plusDays(1).toString())) {
                val whenText = (if (m.day == today.toString()) "Oggi" else "Domani") + (m.time?.let { " alle $it" } ?: "")
                val detail = buildList {
                    p.remote.remember.take(3).forEach { add("Ricorda: $it") }
                    p.iOwe.take(3).forEach { add("Devi: ${it.testo}") }
                    p.theyOwe.take(3).forEach { add("Ti deve: ${it.testo}") }
                    p.remote.topics.take(2).forEach { add("In sospeso: $it") }
                }
                out += Reminder(p.slug, p.name, "$whenText vedi ${p.name}" + (m.text.takeIf { it.isNotBlank() }?.let { ": $it." } ?: "."), detail)
            }
        }
        for (p in people) for (d in p.remote?.dates.orEmpty()) {
            val next = nextOccurrence(d.whenText, today) ?: continue
            val days = ChronoUnit.DAYS.between(today, next)
            if (days in 0..7) {
                val what = d.what.ifBlank { "una data importante" }
                val text = when (days) {
                    0L -> if (what == "compleanno") "Oggi è il compleanno di ${p.name}." else "Oggi per ${p.name}: $what."
                    1L -> if (what == "compleanno") "Domani è il compleanno di ${p.name}." else "Domani per ${p.name}: $what."
                    else -> if (what == "compleanno") "Tra $days giorni è il compleanno di ${p.name}." else "Tra $days giorni per ${p.name}: $what."
                }
                out += Reminder(p.slug, p.name, text)
            }
        }
        for (p in people.filter { it.followed }) {
            val last = p.lastSeen ?: continue
            val days = ChronoUnit.DAYS.between(last, today)
            if (days >= SILENCE_DAYS) {
                val weeks = days / 7
                out += Reminder(p.slug, p.name, "Non senti ${p.name} da $weeks settimane.")
            }
        }
        return out
    }

    /** "03-14" (ogni anno) oppure "2027-01-20" (una volta). */
    fun nextOccurrence(whenText: String, today: LocalDate): LocalDate? = runCatching {
        val t = whenText.trim()
        if (t.length == 5) {
            val md = MonthDay.parse("--$t")
            val thisYear = if (md.isValidYear(today.year)) md.atYear(today.year) else LocalDate.of(today.year, 2, 28)
            if (thisYear.isBefore(today)) md.atYear(today.year + 1) else thisYear
        } else LocalDate.parse(t.take(10)).takeIf { !it.isBefore(today) }
    }.getOrNull()
}
