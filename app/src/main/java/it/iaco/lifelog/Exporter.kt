package it.iaco.lifelog

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Un paragrafo = pezzi di parlato consecutivi, separati da meno di 60 secondi. */
data class Paragraph(val startMs: Long, val endMs: Long, val text: String)

object Exporter {
    private const val JOIN_GAP_MS = 60_000L

    fun paragraphs(segments: List<Segment>): List<Paragraph> {
        val out = mutableListOf<Paragraph>()
        var cur: Paragraph? = null
        for (s in segments) {
            val t = s.text?.trim().orEmpty()
            if (t.isEmpty()) continue
            val c = cur
            cur = if (c != null && s.startMs - c.endMs < JOIN_GAP_MS &&
                Db.dayOf(s.startMs) == Db.dayOf(c.startMs)
            ) {
                c.copy(endMs = s.endMs, text = c.text + " " + t)
            } else {
                c?.let { out += it }
                Paragraph(s.startMs, s.endMs, t)
            }
        }
        cur?.let { out += it }
        return out
    }

    private val iso = DateTimeFormatter.ISO_OFFSET_DATE_TIME
    private fun isoOf(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(iso)

    fun markdown(db: Db, newestFirst: Boolean = false): String {
        val paras = paragraphs(db.allTranscribed())
        val gaps = db.allGaps()
        val sb = StringBuilder("# Lifelog: trascrizioni\n\n")
        sb.append("Aggiornato il ${isoOf(System.currentTimeMillis())}. Trascrizione automatica: può contenere errori, non distingue chi parla.\n")
        if (newestFirst) sb.append("Giorni dal più recente al più vecchio.\n")
        val byDay = (paras.map { Db.dayOf(it.startMs) } + gaps.map { Db.dayOf(it.startMs) }).distinct().sorted()
            .let { if (newestFirst) it.reversed() else it }
        for (day in byDay) {
            sb.append("\n## $day\n\n")
            val items = paras.filter { Db.dayOf(it.startMs) == day }.map { it.startMs to "**${Notifications.hhmm(it.startMs)}–${Notifications.hhmm(it.endMs)}** ${it.text}" } +
                gaps.filter { Db.dayOf(it.startMs) == day }.map {
                    it.startMs to "_[non registrato ${Notifications.hhmm(it.startMs)}–${Notifications.hhmm(it.endMs)}: ${it.reason}]_"
                }
            items.sortedBy { it.first }.forEach { sb.append(it.second).append("\n\n") }
        }
        return sb.toString()
    }

    fun json(db: Db): String {
        val root = JSONObject()
        root.put("format", "lifelog-locale/1")
        root.put("exported_at", isoOf(System.currentTimeMillis()))
        root.put("note", "Trascrizione automatica on-device (Parakeet TDT 0.6B v3). Può contenere errori.")
        root.put("paragraphs", JSONArray().apply {
            paragraphs(db.allTranscribed()).forEach {
                put(JSONObject().apply {
                    put("day", Db.dayOf(it.startMs))
                    put("start", isoOf(it.startMs))
                    put("end", isoOf(it.endMs))
                    put("text", it.text)
                })
            }
        })
        root.put("gaps", JSONArray().apply {
            db.allGaps().forEach {
                put(JSONObject().apply {
                    put("start", isoOf(it.startMs))
                    put("end", isoOf(it.endMs))
                    put("reason", it.reason)
                })
            }
        })
        return root.toString(2)
    }

    fun write(ctx: Context, uri: Uri, content: String) {
        val bytes = content.toByteArray()
        // "wt" = sovrascrivi; alcuni fornitori (es. Google Drive) accettano solo "w".
        val out = try {
            ctx.contentResolver.openOutputStream(uri, "wt")
        } catch (e: SecurityException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: ctx.contentResolver.openOutputStream(uri, "w")
        out?.use { it.write(bytes) } ?: error("Impossibile scrivere il file")
    }
}
