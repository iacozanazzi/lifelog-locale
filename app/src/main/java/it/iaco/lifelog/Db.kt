package it.iaco.lifelog

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class Segment(
    val id: Long,
    val startMs: Long,
    val endMs: Long,
    val text: String?,
    val audioPath: String?,
)

data class Gap(val startMs: Long, val endMs: Long, val reason: String)

data class DaySummary(val day: String, val segments: Int, val pending: Int, val chars: Int)

/**
 * Archivio locale: segmenti di parlato (prima in attesa, poi trascritti) e buchi di registrazione.
 * L'audio viene cancellato appena trascritto: si tiene solo il testo.
 */
class Db private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "lifelog.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE segments (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                start_ms INTEGER NOT NULL,
                end_ms INTEGER NOT NULL,
                day TEXT NOT NULL,
                text TEXT,
                audio_path TEXT
            )"""
        )
        db.execSQL("CREATE INDEX idx_seg_day ON segments(day, start_ms)")
        db.execSQL("CREATE INDEX idx_seg_pending ON segments(text) WHERE text IS NULL")
        db.execSQL(
            """CREATE TABLE gaps (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                start_ms INTEGER NOT NULL,
                end_ms INTEGER NOT NULL,
                day TEXT NOT NULL,
                reason TEXT NOT NULL
            )"""
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    fun insertPending(startMs: Long, endMs: Long, audioPath: String): Long =
        writableDatabase.insert("segments", null, ContentValues().apply {
            put("start_ms", startMs)
            put("end_ms", endMs)
            put("day", dayOf(startMs))
            put("audio_path", audioPath)
        })

    fun nextPending(): Segment? = readableDatabase.rawQuery(
        "SELECT id,start_ms,end_ms,text,audio_path FROM segments WHERE text IS NULL ORDER BY start_ms LIMIT 1",
        null
    ).use { c -> if (c.moveToFirst()) c.toSegment() else null }

    fun pendingCount(): Int = readableDatabase.rawQuery(
        "SELECT COUNT(*) FROM segments WHERE text IS NULL", null
    ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    fun pendingSeconds(): Long = readableDatabase.rawQuery(
        "SELECT COALESCE(SUM(end_ms-start_ms),0) FROM segments WHERE text IS NULL", null
    ).use { c -> if (c.moveToFirst()) c.getLong(0) / 1000 else 0 }

    fun markTranscribed(id: Long, text: String) {
        writableDatabase.update("segments", ContentValues().apply {
            put("text", text)
            putNull("audio_path")
        }, "id=?", arrayOf(id.toString()))
    }

    fun insertGap(startMs: Long, endMs: Long, reason: String) {
        writableDatabase.insert("gaps", null, ContentValues().apply {
            put("start_ms", startMs)
            put("end_ms", endMs)
            put("day", dayOf(startMs))
            put("reason", reason)
        })
    }

    fun days(): List<DaySummary> = readableDatabase.rawQuery(
        """SELECT day, COUNT(*), SUM(CASE WHEN text IS NULL THEN 1 ELSE 0 END),
                  COALESCE(SUM(LENGTH(text)),0)
           FROM segments GROUP BY day ORDER BY day DESC""", null
    ).use { c ->
        buildList {
            while (c.moveToNext()) add(DaySummary(c.getString(0), c.getInt(1), c.getInt(2), c.getInt(3)))
        }
    }

    fun segmentsOfDay(day: String): List<Segment> = readableDatabase.rawQuery(
        "SELECT id,start_ms,end_ms,text,audio_path FROM segments WHERE day=? ORDER BY start_ms",
        arrayOf(day)
    ).use { c -> buildList { while (c.moveToNext()) add(c.toSegment()) } }

    fun gapsOfDay(day: String): List<Gap> = readableDatabase.rawQuery(
        "SELECT start_ms,end_ms,reason FROM gaps WHERE day=? ORDER BY start_ms", arrayOf(day)
    ).use { c -> buildList { while (c.moveToNext()) add(Gap(c.getLong(0), c.getLong(1), c.getString(2))) } }

    fun allTranscribed(): List<Segment> = readableDatabase.rawQuery(
        "SELECT id,start_ms,end_ms,text,audio_path FROM segments WHERE text IS NOT NULL AND text != '' ORDER BY start_ms",
        null
    ).use { c -> buildList { while (c.moveToNext()) add(c.toSegment()) } }

    fun allGaps(): List<Gap> = readableDatabase.rawQuery(
        "SELECT start_ms,end_ms,reason FROM gaps ORDER BY start_ms", null
    ).use { c -> buildList { while (c.moveToNext()) add(Gap(c.getLong(0), c.getLong(1), c.getString(2))) } }

    fun search(query: String, limit: Int = 200): List<Segment> {
        val escaped = query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        return readableDatabase.rawQuery(
            "SELECT id,start_ms,end_ms,text,audio_path FROM segments WHERE text LIKE ? ESCAPE '\\' ORDER BY start_ms DESC LIMIT $limit",
            arrayOf("%$escaped%")
        ).use { c -> buildList { while (c.moveToNext()) add(c.toSegment()) } }
    }

    private fun android.database.Cursor.toSegment() = Segment(
        id = getLong(0),
        startMs = getLong(1),
        endMs = getLong(2),
        text = if (isNull(3)) null else getString(3),
        audioPath = if (isNull(4)) null else getString(4),
    )

    companion object {
        @Volatile private var instance: Db? = null
        fun get(context: Context): Db =
            instance ?: synchronized(this) { instance ?: Db(context).also { instance = it } }

        fun dayOf(ms: Long): String =
            Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate().toString()

        fun today(): String = LocalDate.now().toString()
    }
}
