package it.iaco.lifelog.briefing

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Il calendario del telefono (quello di Samsung o Google, sincronizzato con l'account Google).
 * Si legge in locale: serve solo il permesso READ_CALENDAR, niente account o chiavi.
 * Gli eventi nuovi non si scrivono mai da soli: si apre l'app Calendario già compilata
 * e Iaco preme Salva.
 */
object DeviceCalendar {

    data class Event(
        /** "c-<id evento>-<inizio in ms>": stabile anche per gli eventi che si ripetono. */
        val id: String,
        val title: String,
        val day: String,
        val start: String?,
        val end: String?,
        val allDay: Boolean,
        val location: String,
        val people: List<String>,
        val calendar: String,
        val beginMs: Long,
        val endMs: Long,
    )

    private val hm = DateTimeFormatter.ofPattern("HH:mm")

    fun granted(ctx: Context) =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    /** Eventi visibili da [from] per [days] giorni, in ordine di inizio. Vuoto senza permesso. */
    fun events(ctx: Context, from: LocalDate, days: Long): List<Event> {
        if (!granted(ctx)) return emptyList()
        val zone = ZoneId.systemDefault()
        val begin = from.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = from.plusDays(days).atStartOfDay(zone).toInstant().toEpochMilli()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, begin); ContentUris.appendId(it, end)
        }.build()
        val proj = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
            CalendarContract.Instances.OWNER_ACCOUNT,
            CalendarContract.Instances.STATUS,
        )
        val out = mutableListOf<Event>()
        runCatching {
            ctx.contentResolver.query(
                uri, proj, "${CalendarContract.Instances.VISIBLE} = 1", null,
                "${CalendarContract.Instances.BEGIN} ASC"
            )?.use { c ->
                while (c.moveToNext() && out.size < 300) {
                    if (c.getInt(8) == CalendarContract.Instances.STATUS_CANCELED) continue
                    val eventId = c.getLong(0)
                    val b = c.getLong(1)
                    val e = c.getLong(2)
                    val allDay = c.getInt(5) == 1
                    // Gli eventi di tutto il giorno sono salvati a mezzanotte UTC.
                    val dayZone = if (allDay) ZoneOffset.UTC else zone
                    val bz = Instant.ofEpochMilli(b).atZone(dayZone)
                    out += Event(
                        id = "c-$eventId-$b",
                        title = c.getString(3).orEmpty().trim().ifEmpty { "Senza titolo" },
                        day = bz.toLocalDate().toString(),
                        start = if (allDay) null else bz.format(hm),
                        end = if (allDay) null else Instant.ofEpochMilli(e).atZone(zone).format(hm),
                        allDay = allDay,
                        location = c.getString(4).orEmpty().trim(),
                        people = attendees(ctx, eventId, c.getString(7).orEmpty()),
                        calendar = c.getString(6).orEmpty(),
                        beginMs = b,
                        endMs = e,
                    )
                }
            }
        }
        return out
    }

    /** Nomi degli invitati, escluso Iaco. Solo nomi: le email di altri non escono dal telefono. */
    private fun attendees(ctx: Context, eventId: Long, owner: String): List<String> = runCatching {
        ctx.contentResolver.query(
            CalendarContract.Attendees.CONTENT_URI,
            arrayOf(CalendarContract.Attendees.ATTENDEE_NAME, CalendarContract.Attendees.ATTENDEE_EMAIL),
            "${CalendarContract.Attendees.EVENT_ID} = ?", arrayOf(eventId.toString()), null
        )?.use { c ->
            val names = mutableListOf<String>()
            while (c.moveToNext()) {
                val name = c.getString(0).orEmpty().trim()
                val email = c.getString(1).orEmpty().trim()
                if (email.isNotEmpty() && email.equals(owner, ignoreCase = true)) continue
                val shown = name.ifEmpty { email.substringBefore('@').replace('.', ' ') }.trim()
                if (shown.isNotEmpty() && !shown.contains("calendar.google.com")) names += shown
            }
            names.distinct()
        } ?: emptyList()
    }.getOrDefault(emptyList())

    /** calendario.json per Claude: oggi e le due settimane dopo. */
    fun toJson(events: List<Event>, from: LocalDate, days: Long): JSONObject {
        val arr = JSONArray()
        for (e in events) arr.put(
            JSONObject()
                .put("id", e.id).put("titolo", e.title).put("giorno", e.day)
                .put("inizio", e.start ?: JSONObject.NULL).put("fine", e.end ?: JSONObject.NULL)
                .put("tutto_il_giorno", e.allDay).put("luogo", e.location)
                .put("persone", JSONArray(e.people)).put("calendario", e.calendar)
        )
        return JSONObject()
            .put("da", from.toString()).put("a", from.plusDays(days - 1).toString())
            .put("eventi", arr)
    }

    /**
     * Apre l'app Calendario con l'evento già compilato: Iaco controlla e preme Salva.
     * Niente permesso di scrittura e niente eventi creati senza di lui.
     */
    fun insertIntent(p: Briefing.Proposal): Intent {
        val zone = ZoneId.systemDefault()
        val date = runCatching { LocalDate.parse(p.day) }.getOrDefault(LocalDate.now(zone))
        val time = p.time?.let { runCatching { LocalTime.parse(it) }.getOrNull() }
        val begin = (if (time != null) date.atTime(time) else date.atStartOfDay()).atZone(zone).toInstant().toEpochMilli()
        val end = if (time != null) begin + p.minutes.coerceAtLeast(15) * 60_000L
            else date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.Events.TITLE, p.title)
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, begin)
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end)
            .putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, time == null)
            .apply {
                if (p.location.isNotBlank()) putExtra(CalendarContract.Events.EVENT_LOCATION, p.location)
                if (p.quote.isNotBlank()) putExtra(CalendarContract.Events.DESCRIPTION, "Detto il ${p.sourceDay} alle ${p.sourceTime}: «${p.quote}»")
            }
    }
}
