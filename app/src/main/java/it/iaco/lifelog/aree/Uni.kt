package it.iaco.lifelog.aree

import android.content.Context
import it.iaco.lifelog.aree.Aree.child
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/** Domande di ripasso dalle lezioni, esami e piano di studio. */
object Uni {
    data class Domanda(
        val id: String, val materia: String, val argomento: String,
        val domanda: String, val risposta: String, val fonte: Fonte?,
    )

    data class Esame(
        val id: String, val materia: String, val data: LocalDate, val ora: String?, val tipo: String?,
        val argomenti: List<String>, val aMano: Boolean,
    )

    // ------------------------------------------------------------- domande e ripasso

    fun questions(ctx: Context): List<Domanda> {
        val scartate = Aree.state(ctx).optJSONObject("scartate")
        return Aree.remoteArray(ctx, "uni/domande.json").objects().mapNotNull { o ->
            val id = o.str("id") ?: return@mapNotNull null
            if (scartate?.has(id) == true) return@mapNotNull null
            val d = o.str("domanda") ?: return@mapNotNull null
            Domanda(id, o.str("materia") ?: "Senza materia", o.optString("argomento"), d, o.optString("risposta"), Fonte.of(o.optJSONObject("fonte")))
        }
    }

    fun progress(ctx: Context): Map<String, Studio.Progresso> {
        val o = Aree.state(ctx).optJSONObject("ripasso") ?: return emptyMap()
        val out = HashMap<String, Studio.Progresso>()
        o.keys().forEach { id ->
            val p = o.optJSONObject(id) ?: return@forEach
            runCatching {
                out[id] = Studio.Progresso(
                    scatola = p.optInt("scatola"),
                    prossima = LocalDate.parse(p.getString("prossima")),
                    prima = LocalDate.parse(p.getString("prima")),
                    sbagliate = p.optJSONArray("sbagliate").strings().map { LocalDate.parse(it) },
                )
            }
        }
        return out
    }

    /** Le domande da fare oggi, nell'ordine giusto. */
    fun queue(ctx: Context, today: LocalDate = Aree.today()): List<Domanda> =
        Studio.codaDiOggi(questions(ctx), { it.id }, { it.materia }, progress(ctx), today, nextExamBySubject(ctx, today))

    fun answer(ctx: Context, q: Domanda, knew: Boolean, today: LocalDate = Aree.today()) {
        val exam = nextExamBySubject(ctx, today)[q.materia]
        val p = Studio.dopo(progress(ctx)[q.id], knew, today, exam)
        Aree.edit(ctx) { st ->
            st.child("ripasso").put(
                q.id, JSONObject().put("scatola", p.scatola).put("prossima", p.prossima.toString())
                    .put("prima", p.prima.toString()).put("sbagliate", JSONArray(p.sbagliate.map { it.toString() }))
            )
        }
    }

    fun discard(ctx: Context, id: String) = Aree.edit(ctx) { st ->
        st.child("scartate").put(id, Aree.now())
        st.optJSONObject("ripasso")?.remove(id)
    }

    data class Riassunto(val materia: String, val domande: Int, val daRipassare: Int, val sbagliate7: Int)

    fun reviewSummary(ctx: Context, today: LocalDate = Aree.today()): List<Riassunto> {
        val prog = progress(ctx)
        val queue = queue(ctx, today).map { it.id }.toSet()
        return questions(ctx).groupBy { it.materia }.map { (m, qs) ->
            Riassunto(
                materia = m, domande = qs.size, daRipassare = qs.count { it.id in queue },
                sbagliate7 = qs.sumOf { q -> prog[q.id]?.sbagliate?.count { Studio.giorni(it, today) < 7 } ?: 0 },
            )
        }.sortedBy { it.materia }
    }

    // ------------------------------------------------------------- esami

    /** Esami in arrivo: quelli trovati da Claude più quelli aggiunti a mano, tolti quelli cancellati. */
    fun exams(ctx: Context, today: LocalDate = Aree.today()): List<Esame> {
        val st = Aree.state(ctx)
        val tolti = st.optJSONObject("esami_tolti")
        val fromClaude = Aree.remoteArray(ctx, "uni/esami.json").objects().mapNotNull { o ->
            val id = o.str("id") ?: return@mapNotNull null
            val data = o.str("data")?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return@mapNotNull null
            if ((o.str("stato") ?: "in_arrivo") != "in_arrivo") return@mapNotNull null
            Esame(id, o.str("materia") ?: "Esame", data, o.str("ora"), o.str("tipo"), o.optJSONArray("argomenti").strings(),
                aMano = o.optJSONObject("fonte")?.optBoolean("app") == true)
        }
        val ids = fromClaude.map { it.id }.toSet()
        val mine = st.optJSONObject("esami_aggiunti")?.let { o ->
            o.keys().asSequence().filter { it !in ids }.mapNotNull { id ->
                val e = o.optJSONObject(id) ?: return@mapNotNull null
                val data = runCatching { LocalDate.parse(e.getString("data")) }.getOrNull() ?: return@mapNotNull null
                Esame(id, e.optString("materia"), data, null, null, emptyList(), aMano = true)
            }.toList()
        }.orEmpty()
        return (fromClaude + mine).filter { tolti?.has(it.id) != true && !it.data.isBefore(today) }.sortedBy { it.data }
    }

    fun nextExamBySubject(ctx: Context, today: LocalDate = Aree.today()): Map<String, LocalDate> =
        exams(ctx, today).groupBy { it.materia }.mapValues { (_, l) -> l.minOf { it.data } }

    fun addExam(ctx: Context, materia: String, data: LocalDate): String {
        val slug = java.text.Normalizer.normalize(materia.lowercase().trim(), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}"), "").replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "esame" }
        val id = "e-$slug-${data.toString().replace("-", "")}"
        Aree.edit(ctx) { st ->
            st.child("esami_aggiunti").put(id, JSONObject().put("materia", materia.trim()).put("data", data.toString()))
            st.optJSONObject("esami_tolti")?.remove(id)
        }
        return id
    }

    fun removeExam(ctx: Context, id: String) = Aree.edit(ctx) { st ->
        st.optJSONObject("esami_aggiunti")?.remove(id)
        st.child("esami_tolti").put(id, Aree.now())
    }

    fun doneTopics(ctx: Context, examId: String): Set<String> {
        val o = Aree.state(ctx).optJSONObject("argomenti_fatti") ?: return emptySet()
        return o.keys().asSequence().filter { it.startsWith("$examId|") }.map { it.substringAfter('|') }.toSet()
    }

    fun setTopic(ctx: Context, examId: String, topic: String, done: Boolean) = Aree.edit(ctx) { st ->
        if (done) st.child("argomenti_fatti").put("$examId|$topic", JSONObject().put("quando", Aree.now()))
        else st.optJSONObject("argomenti_fatti")?.remove("$examId|$topic")
    }

    /** Materie conosciute, per suggerirle quando si aggiunge un esame. */
    fun subjects(ctx: Context): List<String> =
        (questions(ctx).map { it.materia } + exams(ctx).map { it.materia }).filter { it.isNotBlank() && it != "Senza materia" }.distinct().sorted()
}
