package it.iaco.lifelog.briefing

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import it.iaco.lifelog.R
import it.iaco.lifelog.data.Assistant
import it.iaco.lifelog.data.GitHub
import it.iaco.lifelog.ui.MainActivity
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Briefing del mattino, preparazione agli incontri, eventi proposti e bozze di messaggi.
 *
 * Giro dei dati (repository lifelog-dati):
 * - l'app carica `calendario.json` (eventi del telefono, oggi + 14 giorni) insieme ai giorni;
 * - Claude di notte scrive `briefing/<oggi>.json` con chi vedrai e cosa ricordare,
 *   la preparazione per gli incontri di domani, le scadenze dette a voce da mettere in
 *   calendario e le bozze dei messaggi promessi;
 * - le scelte fatte qui (evento aggiunto o scartato, bozza usata o scartata) tornano a
 *   Claude in `stato/app.json`, così non le ripropone.
 */
object Briefing {
    private const val SP = "lifelog_briefing"
    private const val CAL_DAYS = 15L
    private const val CH_BRIEFING = "briefing"
    private const val ID_BRIEFING = 40

    private val zone get() = ZoneId.systemDefault()
    private fun sp(ctx: Context) = ctx.getSharedPreferences(SP, Context.MODE_PRIVATE)
    private fun dir(ctx: Context) = File(ctx.filesDir, "briefing").apply { mkdirs() }
    private fun now() = Instant.now().atZone(zone).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
    private fun sha1(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    // ------------------------------------------------------------- modello

    data class Fact(val text: String, val day: String, val time: String)
    data class Person(val name: String, val slug: String, val remember: List<Fact>)
    data class Meeting(val eventId: String, val day: String, val time: String?, val title: String, val people: List<Person>, val points: List<Fact>)
    data class Proposal(
        val id: String, val title: String, val day: String, val time: String?, val minutes: Int,
        val location: String, val sourceDay: String, val sourceTime: String, val quote: String,
    )
    data class Draft(val id: String, val commitment: String, val person: String, val channel: String, val text: String)
    data class Data(
        val day: String, val today: List<Meeting>, val tomorrow: List<Meeting>,
        val proposals: List<Proposal>, val drafts: List<Draft>,
    )

    fun parse(j: JSONObject): Data {
        fun <T> list(a: JSONArray?, f: (JSONObject) -> T): List<T> =
            (0 until (a?.length() ?: 0)).mapNotNull { a!!.optJSONObject(it)?.let { o -> runCatching { f(o) }.getOrNull() } }
        fun str(o: JSONObject, k: String) = o.optString(k).takeIf { it.isNotBlank() && it != "null" }
        fun fact(o: JSONObject) = Fact(o.optString("testo"), o.optString("giorno"), o.optString("ora"))
        val day = j.optString("data")
        fun meeting(o: JSONObject, defaultDay: String) = Meeting(
            eventId = o.optString("evento"), day = str(o, "giorno") ?: defaultDay, time = str(o, "ora"),
            title = o.optString("titolo"),
            people = list(o.optJSONArray("persone")) { p ->
                Person(p.optString("nome"), p.optString("slug"), list(p.optJSONArray("ricordare"), ::fact).take(2))
            },
            points = list(o.optJSONArray("punti"), ::fact).take(4),
        )
        val tomorrow = runCatching { LocalDate.parse(day).plusDays(1).toString() }.getOrDefault("")
        return Data(
            day = day,
            today = list(j.optJSONArray("oggi")) { meeting(it, day) },
            tomorrow = list(j.optJSONArray("domani")) { meeting(it, tomorrow) },
            proposals = list(j.optJSONArray("proposte")) { o ->
                val f = o.optJSONObject("fonte") ?: JSONObject()
                Proposal(
                    id = o.getString("id"), title = o.optString("titolo"), day = o.optString("giorno"),
                    time = str(o, "ora"), minutes = o.optInt("durata_min", 60), location = str(o, "luogo").orEmpty(),
                    sourceDay = f.optString("giorno"), sourceTime = f.optString("ora"), quote = f.optString("citazione"),
                )
            },
            drafts = list(j.optJSONArray("bozze")) { o ->
                Draft(o.getString("id"), o.optString("impegno"), o.optString("persona"), o.optString("canale"), o.optString("testo"))
            },
        )
    }

    fun today(): String = LocalDate.now(zone).toString()

    /** Il briefing scritto stanotte per oggi, se è arrivato. */
    fun load(ctx: Context, day: String = today()): Data? {
        val f = File(dir(ctx), "briefing-$day.json")
        if (!f.exists()) return null
        return runCatching { parse(JSONObject(f.readText())) }.getOrNull()
    }

    // ------------------------------------------------------------- scelte fatte nell'app

    private fun decisions(ctx: Context, kind: String): JSONObject =
        runCatching { JSONObject(sp(ctx).getString(kind, "{}")!!) }.getOrDefault(JSONObject())

    private fun decide(ctx: Context, kind: String, id: String, state: String) {
        val d = decisions(ctx, kind)
        d.put(id, JSONObject().put("stato", state).put("quando", now()))
        sp(ctx).edit().putString(kind, d.toString()).apply()
        Assistant.uploadSoon(ctx)
    }

    /** Proposta di evento: "aggiunto" (aperto il Calendario) o "scartato". */
    fun decideEvent(ctx: Context, id: String, added: Boolean) = decide(ctx, "eventi", id, if (added) "aggiunto" else "scartato")

    /** Bozza di messaggio: "usata" o "scartata". */
    fun decideDraft(ctx: Context, id: String, used: Boolean) = decide(ctx, "bozze", id, if (used) "usata" else "scartata")

    fun decidedEvents(ctx: Context): Set<String> = decisions(ctx, "eventi").keys().asSequence().toSet()
    fun decidedDrafts(ctx: Context): Set<String> = decisions(ctx, "bozze").keys().asSequence().toSet()

    /** Aggiunge a stato/app.json le liste `eventi` e `bozze`, accanto a `impegni`. */
    fun addState(ctx: Context, state: JSONObject) {
        for (kind in listOf("eventi", "bozze")) {
            val d = decisions(ctx, kind)
            val arr = JSONArray()
            d.keys().forEach { id -> arr.put(JSONObject(d.getJSONObject(id).toString()).put("id", id)) }
            state.put(kind, arr)
        }
    }

    // ------------------------------------------------------------- scambio con lifelog-dati

    /** Carica calendario.json se è cambiato. Senza permesso non carica niente. */
    fun uploadCalendar(ctx: Context, gh: GitHub): Boolean {
        if (!DeviceCalendar.granted(ctx)) return false
        val from = LocalDate.now(zone)
        val json = DeviceCalendar.toJson(DeviceCalendar.events(ctx, from, CAL_DAYS), from, CAL_DAYS)
        val h = sha1(json.toString())
        if (sp(ctx).getString("sent_calendar", null) == h) return false
        gh.write("calendario.json", JSONObject(json.toString()).put("aggiornato", now()).toString(2), "app: calendario")
        sp(ctx).edit().putString("sent_calendar", h).apply()
        return true
    }

    /** Scarica briefing/<oggi>.json. Ritorna true se c'è. */
    fun fetch(ctx: Context, gh: GitHub): Boolean {
        val day = today()
        val text = gh.read("briefing/$day.json") ?: return false
        runCatching { JSONObject(text) }.getOrNull() ?: return false
        File(dir(ctx), "briefing-$day.json").writeText(text)
        dir(ctx).listFiles()?.filter { it.name < "briefing-${LocalDate.now(zone).minusDays(7)}" }?.forEach { it.delete() }
        notifyMorning(ctx)
        return true
    }

    // ------------------------------------------------------------- notifica del mattino

    /**
     * Una notifica silenziosa la mattina, una volta al giorno, solo se c'è qualcosa:
     * eventi in agenda, scadenze, proposte o bozze.
     */
    private fun notifyMorning(ctx: Context) {
        val day = today()
        val nowT = LocalTime.now(zone)
        if (nowT.isBefore(LocalTime.of(6, 0)) || nowT.isAfter(LocalTime.of(11, 30))) return
        if (sp(ctx).getString("notified", null) == day) return
        val text = morningLine(ctx) ?: return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_BRIEFING, "Briefing del mattino", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(
            ctx, ID_BRIEFING,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(ctx, CH_BRIEFING)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("La tua giornata")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        try {
            NotificationManagerCompat.from(ctx).notify(ID_BRIEFING, n)
            sp(ctx).edit().putString("notified", day).apply()
        } catch (_: SecurityException) {
            // notifiche negate: il briefing resta in Oggi
        }
    }

    /** "Oggi 3 impegni in agenda, il primo alle 10:00. 1 scadenza. 2 messaggi da mandare." */
    fun morningLine(ctx: Context): String? {
        val d = load(ctx)
        val events = DeviceCalendar.events(ctx, LocalDate.now(zone), 1)
        val due = dueCommitments(ctx)
        val props = d?.proposals.orEmpty().count { it.id !in decidedEvents(ctx) }
        val drafts = d?.drafts.orEmpty().count { it.id !in decidedDrafts(ctx) }
        val parts = mutableListOf<String>()
        if (events.isNotEmpty()) {
            val first = events.firstOrNull { !it.allDay }?.start
            parts += (if (events.size == 1) "Oggi hai un evento in agenda" else "Oggi hai ${events.size} eventi in agenda") +
                (first?.let { ", il primo alle $it." } ?: ".")
        }
        if (due.isNotEmpty()) parts += if (due.size == 1) "In scadenza: ${due[0].testo}." else "Hai ${due.size} scadenze entro dopodomani."
        if (drafts > 0) parts += if (drafts == 1) "C'è un messaggio promesso da mandare." else "Ci sono $drafts messaggi promessi da mandare."
        if (props > 0) parts += if (props == 1) "C'è una data da mettere in calendario." else "Ci sono $props date da mettere in calendario."
        return parts.joinToString(" ").ifBlank { null }
    }

    /** Impegni aperti con scadenza entro dopodomani (o già passata), dal più urgente. */
    fun dueCommitments(ctx: Context): List<Assistant.Commitment> {
        val limit = LocalDate.now(zone).plusDays(2).toString()
        return Assistant.commitments(ctx)
            .filter { it.stato == "aperto" && it.scadenza != null && it.scadenza.take(10) <= limit }
            .sortedBy { it.scadenza }
    }
}
