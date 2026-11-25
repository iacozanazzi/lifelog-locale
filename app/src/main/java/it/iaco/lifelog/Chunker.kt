package it.iaco.lifelog

import java.io.File

/**
 * Unisce i pezzi di parlato vicini in blocchi fino a 25 secondi prima di trascriverli.
 * Un modello capisce molto meglio una frase intera che tre frammenti da 2 secondi.
 */
object Chunker {
    private const val JOIN_GAP_MS = 1500L
    private const val MAX_MS = 25_000L
    private const val MAX_SILENCE_SAMPLES = Models.SAMPLE_RATE * 3 / 10 // 0,3 s tra un pezzo e l'altro

    fun next(pending: List<Segment>): List<Segment>? {
        if (pending.isEmpty()) return null
        val group = mutableListOf(pending.first())
        for (s in pending.drop(1)) {
            val last = group.last()
            val gap = s.startMs - last.endMs
            val total = s.endMs - group.first().startMs
            if (gap in -500..JOIN_GAP_MS && total <= MAX_MS && Db.dayOf(s.startMs) == Db.dayOf(group.first().startMs)) {
                group += s
            } else break
        }
        return group
    }

    fun audioOf(group: List<Segment>): FloatArray {
        val pieces = group.mapNotNull { g -> g.audioPath?.let { File(it) }?.takeIf { it.exists() }?.let { g to Pcm.read(it) } }
        if (pieces.isEmpty()) return FloatArray(0)
        val out = ArrayList<FloatArray>()
        var prevEnd = -1L
        for ((g, a) in pieces) {
            if (prevEnd >= 0) {
                val gapSamples = ((g.startMs - prevEnd) * Models.SAMPLE_RATE / 1000).coerceIn(0, MAX_SILENCE_SAMPLES.toLong())
                if (gapSamples > 0) out += FloatArray(gapSamples.toInt())
            }
            out += a
            prevEnd = g.endMs
        }
        val total = out.sumOf { it.size }
        val joined = FloatArray(total)
        var i = 0
        for (p in out) { p.copyInto(joined, i); i += p.size }
        return joined // normalizzare il volume non migliorava i risultati nei test
    }
}

/** Tiene l'audio degli ultimi blocchi trascritti, per poter confrontare i motori. */
object Recent {
    private const val KEEP = 12
    private fun dir(ctx: android.content.Context) = File(ctx.filesDir, "recent").apply { mkdirs() }

    fun save(ctx: android.content.Context, startMs: Long, audio: FloatArray) {
        File(dir(ctx), "$startMs.pcm").writeBytes(Pcm.toBytes(audio))
        dir(ctx).listFiles().orEmpty().sortedByDescending { it.name }.drop(KEEP).forEach { it.delete() }
    }

    /** Dal più recente; ogni elemento è (inizio in ms, audio). */
    fun list(ctx: android.content.Context, n: Int): List<Pair<Long, File>> =
        dir(ctx).listFiles().orEmpty()
            .mapNotNull { f -> f.nameWithoutExtension.toLongOrNull()?.let { it to f } }
            .sortedByDescending { it.first }
            .take(n)
}
