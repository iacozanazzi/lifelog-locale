package it.iaco.lifelog.people

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Una voce sentita più volte. L'impronta (centroid + pochi campioni) resta solo sul telefono.
 * id "io" = Iaco; "v-N" = le altre, mostrate come "Voce N" finché non hanno un nome.
 */
data class Voice(
    val id: String,
    val n: Int,
    val name: String?,
    /** slug della scheda persona collegata (persone/<slug>.md), se c'è. */
    val persona: String?,
    val centroid: FloatArray,
    val samples: List<FloatArray>,
    val seconds: Double,
    val firstMs: Long,
    val lastMs: Long,
    val namedMs: Long?,
) {
    val isMe get() = id == ME
    /** Una voce nuova si mostra come "Voce N" solo dopo un po' di parlato: prima è rumore o una frase di passaggio. */
    val confirmed get() = isMe || name != null || seconds >= CONFIRM_SECONDS

    companion object {
        const val ME = "io"
        const val CONFIRM_SECONDS = 30.0
    }
}

/** Vettori: normalizzazione, coseno, conversione in byte per il database. */
object Vec {
    fun norm(x: FloatArray): FloatArray {
        var s = 0.0
        for (v in x) s += v * v
        val n = sqrt(s).toFloat().takeIf { it > 1e-9f } ?: return x.copyOf()
        return FloatArray(x.size) { x[it] / n }
    }

    /** Coseno tra due vettori già normalizzati. */
    fun cos(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return -1f
        var s = 0f
        for (i in a.indices) s += a[i] * b[i]
        return s
    }

    fun toBytes(x: FloatArray): ByteArray =
        ByteBuffer.allocate(x.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply { x.forEach { putFloat(it) } }.array()

    fun fromBytes(b: ByteArray): FloatArray {
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(b.size / 4) { bb.float }
    }

    /** Più vettori della stessa dimensione: [dim:int][v1][v2]… */
    fun listToBytes(xs: List<FloatArray>): ByteArray {
        val dim = xs.firstOrNull()?.size ?: 0
        val bb = ByteBuffer.allocate(4 + xs.size * dim * 4).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(dim)
        xs.forEach { v -> v.forEach { bb.putFloat(it) } }
        return bb.array()
    }

    fun listFromBytes(b: ByteArray): List<FloatArray> {
        if (b.size < 4) return emptyList()
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        val dim = bb.int
        if (dim <= 0) return emptyList()
        val n = (b.size - 4) / (dim * 4)
        return List(n) { FloatArray(dim) { bb.float } }
    }
}

/**
 * Decide di chi è una voce, confrontando l'impronta di un pezzo di parlato con le voci note.
 * Logica pura (niente Android), così si può provare con i test.
 *
 * Soglie sul coseno (modello WeSpeaker CAM++): sopra SAME è la stessa persona; tra NEW e SAME
 * è incerto e il pezzo resta "?" senza toccare le voci; sotto NEW, se il pezzo è abbastanza lungo,
 * nasce una voce nuova. Valori prudenti: meglio un "?" in più che una frase attribuita alla persona sbagliata.
 */
class VoiceBank(initial: List<Voice>) {
    private val voices = initial.associateBy { it.id }.toMutableMap()
    val all: List<Voice> get() = voices.values.sortedBy { it.n }
    private val changed = mutableSetOf<String>()

    data class Match(val voice: String?, val score: Float)

    /** Il punteggio di una voce: il migliore tra centroide e campioni (una persona suona diversa vicino e lontano). */
    fun score(v: Voice, e: FloatArray): Float {
        var best = Vec.cos(v.centroid, e)
        for (s in v.samples) best = maxOf(best, Vec.cos(s, e) - SAMPLE_PENALTY)
        return best
    }

    fun best(e: FloatArray): Pair<Voice, Float>? =
        voices.values.map { it to score(it, e) }.maxByOrNull { it.second }

    /**
     * Assegna un pezzo di parlato. [e] normalizzato, [seconds] durata, [atMs] quando.
     * Ritorna la voce scelta (o null se incerto) e il punteggio.
     */
    fun assign(e: FloatArray, seconds: Double, atMs: Long): Match {
        val b = best(e)
        if (b != null && b.second >= SAME) {
            learn(b.first, e, seconds, atMs)
            return Match(b.first.id, b.second)
        }
        if (b != null && b.second >= NEW) return Match(null, b.second)
        if (seconds < MIN_NEW_SECONDS) return Match(null, b?.second ?: 0f)
        val n = (voices.values.maxOfOrNull { it.n } ?: 0) + 1
        val v = Voice("v-$n", n, null, null, e, listOf(e), seconds, atMs, atMs, null)
        voices[v.id] = v
        changed += v.id
        return Match(v.id, 1f)
    }

    private fun learn(v: Voice, e: FloatArray, seconds: Double, atMs: Long) {
        // Media mobile pesata sulla durata, con un tetto: la voce può cambiare un po' nel tempo (raffreddore, posto).
        val w = (seconds / (v.seconds + seconds)).coerceIn(0.02, 0.5).toFloat()
        val c = Vec.norm(FloatArray(e.size) { v.centroid[it] * (1 - w) + e[it] * w })
        val samples = if (v.samples.size < MAX_SAMPLES && v.samples.none { Vec.cos(it, e) > 0.9f }) v.samples + listOf(e)
        else if (v.samples.size >= MAX_SAMPLES && seconds >= 4) v.samples.drop(1) + listOf(e) else v.samples
        voices[v.id] = v.copy(centroid = c, samples = samples, seconds = v.seconds + seconds, lastMs = maxOf(v.lastMs, atMs))
        changed += v.id
    }

    /** Coppie di voci senza nome così simili che sono quasi certamente la stessa persona. */
    fun mergeCandidates(): List<Pair<String, String>> {
        val list = all
        val out = mutableListOf<Pair<String, String>>()
        for (i in list.indices) for (j in i + 1 until list.size) {
            val a = list[i]; val b = list[j]
            if (a.name != null && b.name != null && a.name != b.name) continue
            if (a.isMe && b.name != null || b.isMe && a.name != null) continue
            if (Vec.cos(a.centroid, b.centroid) >= MERGE) out += a.id to b.id
        }
        return out
    }

    /** Unisce [from] dentro [into]. Il chiamante sposta anche i segmenti nel database. */
    fun merge(into: String, from: String): Voice? {
        val a = voices[into] ?: return null
        val b = voices.remove(from) ?: return a
        val tot = a.seconds + b.seconds
        val wa = if (tot > 0) (a.seconds / tot).toFloat() else 0.5f
        val c = Vec.norm(FloatArray(a.centroid.size) { a.centroid[it] * wa + b.centroid[it] * (1 - wa) })
        val m = a.copy(
            name = a.name ?: b.name, persona = a.persona ?: b.persona, centroid = c,
            samples = (a.samples + b.samples).take(MAX_SAMPLES), seconds = tot,
            firstMs = minOf(a.firstMs, b.firstMs), lastMs = maxOf(a.lastMs, b.lastMs),
            namedMs = a.namedMs ?: b.namedMs,
        )
        voices[into] = m
        changed += into
        return m
    }

    fun put(v: Voice) { voices[v.id] = v; changed += v.id }
    fun remove(id: String) = voices.remove(id)
    fun get(id: String) = voices[id]

    /** Voci cambiate dall'ultima chiamata (da salvare). */
    fun drainChanged(): List<Voice> = changed.mapNotNull { voices[it] }.also { changed.clear() }

    /** Voci mai nominate, sentite poco e non da 30 giorni: si cancellano (minimizzazione). */
    fun expired(nowMs: Long): List<Voice> = voices.values.filter {
        it.name == null && !it.isMe && it.seconds < 120 && nowMs - it.lastMs > 30L * 86_400_000
    }

    companion object {
        const val SAME = 0.55f
        const val NEW = 0.35f
        const val MERGE = 0.62f
        const val SAMPLE_PENALTY = 0.03f
        const val MAX_SAMPLES = 20
        const val MIN_NEW_SECONDS = 2.0
    }
}
