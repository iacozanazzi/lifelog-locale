package it.iaco.lifelog.aree

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Logica pura (niente Android, niente file) per ripasso ed esami, così si può testare.
 *
 * Ripasso: scatole di Leitner. Ogni risposta giusta sposta la domanda alla scatola
 * successiva e allunga l'attesa (1, 3, 7, 14, 30, 60, 120 giorni); una sbagliata la
 * riporta all'inizio, da rivedere domani. Se c'è un esame della stessa materia,
 * l'attesa non va mai oltre il giorno prima dell'esame.
 */
object Studio {
    val INTERVALLI = intArrayOf(1, 3, 7, 14, 30, 60, 120)

    /** Domande nuove al giorno: abbastanza per avanzare, poche per non annoiarsi. */
    const val NUOVE_AL_GIORNO = 8

    data class Progresso(val scatola: Int, val prossima: LocalDate, val prima: LocalDate, val sbagliate: List<LocalDate>)

    fun giorni(da: LocalDate, a: LocalDate): Long = ChronoUnit.DAYS.between(da, a)

    fun dopo(p: Progresso?, sapevo: Boolean, oggi: LocalDate, esame: LocalDate? = null): Progresso {
        val scatola = if (sapevo) minOf((p?.scatola ?: -1) + 1, INTERVALLI.size - 1) else 0
        var attesa = if (sapevo) INTERVALLI[scatola].toLong() else 1L
        if (esame != null) {
            val mancano = giorni(oggi, esame)
            if (mancano >= 2) attesa = minOf(attesa, mancano - 1)
        }
        val sbagliate = (p?.sbagliate.orEmpty() + if (sapevo) emptyList<LocalDate>() else listOf(oggi)).takeLast(10)
        return Progresso(scatola, oggi.plusDays(attesa), p?.prima ?: oggi, sbagliate)
    }

    /**
     * Le domande di oggi: prima quelle già viste e scadute (dalla più in ritardo),
     * poi le nuove fino al limite del giorno, partendo dalla materia con l'esame più vicino.
     */
    fun <Q> codaDiOggi(
        domande: List<Q>, id: (Q) -> String, materia: (Q) -> String,
        progressi: Map<String, Progresso>, oggi: LocalDate, esamePerMateria: Map<String, LocalDate>,
    ): List<Q> {
        val scadute = domande.filter { q -> progressi[id(q)]?.let { !it.prossima.isAfter(oggi) } == true }
            .sortedBy { progressi[id(it)]!!.prossima }
        val nuoveGiaOggi = progressi.values.count { it.prima == oggi }
        val posto = (NUOVE_AL_GIORNO - nuoveGiaOggi).coerceAtLeast(0)
        val nuove = domande.filter { progressi[id(it)] == null }
            .sortedBy { esamePerMateria[materia(it)]?.let { e -> giorni(oggi, e) } ?: Long.MAX_VALUE }
            .take(posto)
        return scadute + nuove
    }

    data class GiornoDiStudio(val data: LocalDate, val argomenti: List<String>, val ripasso: Boolean)

    /**
     * Divide gli argomenti che restano nei giorni fino all'esame, in ordine.
     * Gli ultimi giorni (da 1 a 3, circa il 15%) restano per il ripasso generale.
     * Si ricalcola ogni giorno: se resti indietro, il resto si ridistribuisce.
     */
    fun piano(restanti: List<String>, oggi: LocalDate, esame: LocalDate): List<GiornoDiStudio> {
        val mancano = giorni(oggi, esame).toInt()
        if (mancano <= 0) return emptyList()
        if (mancano == 1) return listOf(GiornoDiStudio(oggi, restanti, ripasso = true))
        val ripasso = (mancano * 0.15).toInt().coerceIn(1, 3).coerceAtMost(mancano - 1)
        val studio = mancano - ripasso
        val n = restanti.size
        val giorniStudio = (0 until studio).map { i ->
            val da = i * n / studio
            val a = (i + 1) * n / studio
            GiornoDiStudio(oggi.plusDays(i.toLong()), restanti.subList(da, a), ripasso = false)
        }
        val giorniRipasso = (studio until mancano).map { GiornoDiStudio(oggi.plusDays(it.toLong()), emptyList(), ripasso = true) }
        return giorniStudio + giorniRipasso
    }
}
