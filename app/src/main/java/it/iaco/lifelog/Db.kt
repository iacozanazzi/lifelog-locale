package it.iaco.lifelog

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import it.iaco.lifelog.people.Vec
import it.iaco.lifelog.people.Voice

data class Segment(
    val id: Long,
    val startMs: Long,
    val endMs: Long,
    val text: String?,
    val audioPath: String?,
    /** Chi parla: "io", "v-3", oppure null se non si sa (thread Persone). */
    val voice: String? = null,
)

data class Gap(val startMs: Long, val endMs: Long, val reason: String)

/** Un momento che Iaco ha segnato a mano (tendina, notifica o app). */
data class Mark(val id: Long, val ms: Long)

data class DaySummary(val day: String, val segments: Int, val pending: Int, val chars: Int, val speechMs: Long = 0)

/**
 * Archivio locale: segmenti di parlato (prima in attesa, poi trascritti) e buchi di registrazione.
 * L'audio viene cancellato appena trascritto: si tiene solo il testo.
 */
class Db private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "lifelog.db", null, 4) {

    /** Cresce a ogni scrittura: l'interfaccia ricalcola solo quando cambia qualcosa. */
    @Volatile var version = 0L
        private set
    private fun bump() { version++ }

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
        addVoices(db)
        addMarks(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            // I pezzi uniti a un blocco non devono più contare come parlato (prima si contavano due volte).
            db.execSQL("UPDATE segments SET end_ms = start_ms WHERE text = ''")
        }
        if (oldVersion < 3) addVoices(db)
        if (oldVersion < 4) addMarks(db)
    }

    /** Versione 4: momenti segnati da Iaco. Solo una tabella nuova, il resto non si tocca. */
    private fun addMarks(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS marks (id INTEGER PRIMARY KEY AUTOINCREMENT, ms INTEGER NOT NULL, day TEXT NOT NULL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_marks_day ON marks(day, ms)")
    }

    // ------------------------------------------------------------- momenti segnati

    fun insertMark(ms: Long): Long =
        writableDatabase.insert("marks", null, ContentValues().apply {
            put("ms", ms)
            put("day", dayOf(ms))
        }).also { bump() }

    fun deleteMark(id: Long) {
        writableDatabase.delete("marks", "id=?", arrayOf(id.toString()))
        bump()
    }

    fun marksOfDay(day: String): List<Mark> = readableDatabase.rawQuery(
        "SELECT id, ms FROM marks WHERE day=? ORDER BY ms", arrayOf(day)
    ).use { c -> buildList { while (c.moveToNext()) add(Mark(c.getLong(0), c.getLong(1))) } }

    /**
     * Versione 3 (Persone): chi parla in ogni segmento e le voci conosciute.
     * Solo colonne nuove e una tabella nuova: le righe esistenti non si toccano.
     * Le impronte vocali (centroid, samples) restano qui, sul telefono, e non escono mai.
     */
    private fun addVoices(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE segments ADD COLUMN voice TEXT")
        db.execSQL("ALTER TABLE segments ADD COLUMN voice_score REAL")
        db.execSQL(
            """CREATE TABLE voices (
                id TEXT PRIMARY KEY,
                n INTEGER NOT NULL,
                name TEXT,
                persona TEXT,
                centroid BLOB NOT NULL,
                samples BLOB,
                seconds REAL NOT NULL DEFAULT 0,
                first_ms INTEGER NOT NULL,
                last_ms INTEGER NOT NULL,
                named_ms INTEGER
            )"""
        )
    }

    // ------------------------------------------------------------- voci (thread Persone)

    fun setVoice(id: Long, voice: String?, score: Float?) {
        writableDatabase.update("segments", ContentValues().apply {
            if (voice == null) putNull("voice") else put("voice", voice)
            if (score == null) putNull("voice_score") else put("voice_score", score)
        }, "id=?", arrayOf(id.toString()))
        bump()
    }

    /** Sposta tutti i segmenti da una voce all'altra (o a nessuna, con to = null). */
    fun moveVoice(from: String, to: String?) {
        writableDatabase.update("segments", ContentValues().apply {
            if (to == null) putNull("voice") else put("voice", to)
        }, "voice=?", arrayOf(from))
        bump()
    }

    fun clearAllVoices() {
        writableDatabase.execSQL("UPDATE segments SET voice = NULL, voice_score = NULL")
        writableDatabase.delete("voices", null, null)
        bump()
    }

    fun loadVoices(): List<Voice> = readableDatabase.rawQuery(
        "SELECT id,n,name,persona,centroid,samples,seconds,first_ms,last_ms,named_ms FROM voices ORDER BY n", null
    ).use { c ->
        buildList {
            while (c.moveToNext()) add(
                Voice(
                    id = c.getString(0), n = c.getInt(1),
                    name = if (c.isNull(2)) null else c.getString(2),
                    persona = if (c.isNull(3)) null else c.getString(3),
                    centroid = Vec.fromBytes(c.getBlob(4)),
                    samples = if (c.isNull(5)) emptyList() else Vec.listFromBytes(c.getBlob(5)),
                    seconds = c.getDouble(6), firstMs = c.getLong(7), lastMs = c.getLong(8),
                    namedMs = if (c.isNull(9)) null else c.getLong(9),
                )
            )
        }
    }

    fun saveVoice(v: Voice) {
        writableDatabase.insertWithOnConflict("voices", null, ContentValues().apply {
            put("id", v.id); put("n", v.n)
            if (v.name == null) putNull("name") else put("name", v.name)
            if (v.persona == null) putNull("persona") else put("persona", v.persona)
            put("centroid", Vec.toBytes(v.centroid))
            put("samples", Vec.listToBytes(v.samples))
            put("seconds", v.seconds); put("first_ms", v.firstMs); put("last_ms", v.lastMs)
            if (v.namedMs == null) putNull("named_ms") else put("named_ms", v.namedMs)
        }, SQLiteDatabase.CONFLICT_REPLACE)
        bump()
    }

    fun deleteVoice(id: String) {
        writableDatabase.delete("voices", "id=?", arrayOf(id))
        bump()
    }

    /** Per la diagnosi delle voci: (voce, punteggio) di ogni pezzo già trascritto del giorno. */
    fun voiceRowsOfDay(day: String): List<Pair<String?, Float?>> = readableDatabase.rawQuery(
        "SELECT voice, voice_score FROM segments WHERE day=? AND text IS NOT NULL", arrayOf(day)
    ).use { c -> buildList { while (c.moveToNext()) add((if (c.isNull(0)) null else c.getString(0)) to (if (c.isNull(1)) null else c.getFloat(1))) } }

    /** Statistiche per voce: secondi parlati, giorni distinti, ultima volta. */
    fun voiceStats(): Map<String, Triple<Long, Int, Long>> = readableDatabase.rawQuery(
        """SELECT voice, COALESCE(SUM(end_ms-start_ms),0), COUNT(DISTINCT day), MAX(end_ms)
           FROM segments WHERE voice IS NOT NULL AND text IS NOT NULL AND text != '' GROUP BY voice""", null
    ).use { c -> buildMap { while (c.moveToNext()) put(c.getString(0), Triple(c.getLong(1) / 1000, c.getInt(2), c.getLong(3))) } }

    /** Le frasi più lunghe dette da una voce: servono a riconoscerla senza tenere l'audio. */
    fun samplesOfVoice(voice: String, limit: Int = 5): List<Segment> = readableDatabase.rawQuery(
        "SELECT id,start_ms,end_ms,text,audio_path,voice FROM segments WHERE voice=? AND text IS NOT NULL AND LENGTH(text) > 20 ORDER BY LENGTH(text) DESC LIMIT $limit",
        arrayOf(voice)
    ).use { c -> buildList { while (c.moveToNext()) add(c.toSegment()) } }

    fun insertPending(startMs: Long, endMs: Long, audioPath: String): Long =
        writableDatabase.insert("segments", null, ContentValues().apply {
            put("start_ms", startMs)
            put("end_ms", endMs)
            put("day", dayOf(startMs))
            put("audio_path", audioPath)
        }).also { bump() }

    fun nextPending(): Segment? = readableDatabase.rawQuery(
        "SELECT id,start_ms,end_ms,text,audio_path,voice FROM segments WHERE text IS NULL ORDER BY start_ms LIMIT 1",
        null
    ).use { c -> if (c.moveToFirst()) c.toSegment() else null }

    fun pendingBatch(limit: Int): List<Segment> = readableDatabase.rawQuery(
        "SELECT id,start_ms,end_ms,text,audio_path,voice FROM segments WHERE text IS NULL ORDER BY start_ms LIMIT $limit",
        null
    ).use { c -> buildList { while (c.moveToNext()) add(c.toSegment()) } }

    /** Un gruppo di pezzi trascritti insieme: il testo va sul primo, che si allunga fino alla fine del gruppo. */
    /** Seconda passata (Whisper): sostituisce il testo di un blocco già trascritto. false se il blocco non c'è più. */
    fun replaceText(id: Long, text: String): Boolean =
        (writableDatabase.update("segments", ContentValues().apply { put("text", text) },
            "id=? AND text IS NOT NULL AND text != ''", arrayOf(id.toString())) > 0).also { if (it) bump() }

    fun markGroup(group: List<Segment>, text: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val first = group.first()
            db.update("segments", ContentValues().apply {
                put("text", text)
                put("end_ms", maxOf(first.endMs, group.last().endMs))
                putNull("audio_path")
            }, "id=?", arrayOf(first.id.toString()))
            for (g in group.drop(1)) {
                db.update("segments", ContentValues().apply {
                    put("text", "")
                    put("end_ms", g.startMs) // assorbito nel primo: niente doppio conteggio
                    putNull("audio_path")
                }, "id=?", arrayOf(g.id.toString()))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
            bump()
        }
    }

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
        bump()
    }

    fun insertGap(startMs: Long, endMs: Long, reason: String) {
        writableDatabase.insert("gaps", null, ContentValues().apply {
            put("start_ms", startMs)
            put("end_ms", endMs)
            put("day", dayOf(startMs))
            put("reason", reason)
        })
        bump()
    }

    fun days(): List<DaySummary> = readableDatabase.rawQuery(
        """SELECT day, COUNT(*), SUM(CASE WHEN text IS NULL THEN 1 ELSE 0 END),
                  COALESCE(SUM(LENGTH(text)),0), COALESCE(SUM(end_ms-start_ms),0)
           FROM segments GROUP BY day ORDER BY day DESC""", null
    ).use { c ->
        buildList {
            while (c.moveToNext()) add(DaySummary(c.getString(0), c.getInt(1), c.getInt(2), c.getInt(3), c.getLong(4)))
        }
    }

    fun segmentsOfDay(day: String): List<Segment> = readableDatabase.rawQuery(
        "SELECT id,start_ms,end_ms,text,audio_path,voice FROM segments WHERE day=? ORDER BY start_ms",
        arrayOf(day)
    ).use { c -> buildList { while (c.moveToNext()) add(c.toSegment()) } }

    fun gapsOfDay(day: String): List<Gap> = readableDatabase.rawQuery(
        "SELECT start_ms,end_ms,reason FROM gaps WHERE day=? ORDER BY start_ms", arrayOf(day)
    ).use { c -> buildList { while (c.moveToNext()) add(Gap(c.getLong(0), c.getLong(1), c.getString(2))) } }

    fun allTranscribed(): List<Segment> = readableDatabase.rawQuery(
        "SELECT id,start_ms,end_ms,text,audio_path,voice FROM segments WHERE text IS NOT NULL AND text != '' ORDER BY start_ms",
        null
    ).use { c -> buildList { while (c.moveToNext()) add(c.toSegment()) } }

    fun allGaps(): List<Gap> = readableDatabase.rawQuery(
        "SELECT start_ms,end_ms,reason FROM gaps ORDER BY start_ms", null
    ).use { c -> buildList { while (c.moveToNext()) add(Gap(c.getLong(0), c.getLong(1), c.getString(2))) } }

    fun search(query: String, limit: Int = 200): List<Segment> {
        val escaped = query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        return readableDatabase.rawQuery(
            "SELECT id,start_ms,end_ms,text,audio_path,voice FROM segments WHERE text LIKE ? ESCAPE '\\' ORDER BY start_ms DESC LIMIT $limit",
            arrayOf("%$escaped%")
        ).use { c -> buildList { while (c.moveToNext()) add(c.toSegment()) } }
    }

    private fun android.database.Cursor.toSegment() = Segment(
        id = getLong(0),
        startMs = getLong(1),
        endMs = getLong(2),
        text = if (isNull(3)) null else getString(3),
        audioPath = if (isNull(4)) null else getString(4),
        voice = if (columnCount > 5 && !isNull(5)) getString(5) else null,
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
