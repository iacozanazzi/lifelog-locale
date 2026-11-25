package it.iaco.lifelog.aree

import android.content.Context
import it.iaco.lifelog.aree.Aree.child
import java.time.LocalDate

/** Registro idee, schede delle serate e spunti per il post settimanale "NN/52". */
object Seral {
    /** Le cinque voci fisse della checklist di una serata, nell'ordine in cui si mostrano. */
    val VOCI = listOf("locale", "dj", "grafica", "post", "soldi")

    data class Idea(
        val id: String, val testo: String, val fonte: Fonte?, val ritorni: Int,
        val serata: String?, val stato: String,
    )

    data class Voce(val voce: String, val stato: String, val nota: String?, val fonte: Fonte?, val aMano: Boolean)

    data class Serata(
        val id: String, val titolo: String, val data: LocalDate?, val locale: String?,
        val stato: String, val checklist: List<Voce>,
    )

    data class Spunto(val testo: String, val fonte: Fonte?)
    data class Post(val settimana: String, val numero: String?, val serate: List<String>, val idee: List<Spunto>)

    fun ideas(ctx: Context): List<Idea> {
        val local = Aree.state(ctx).optJSONObject("idee")
        return Aree.remoteArray(ctx, "seral/idee.json").objects().mapNotNull { o ->
            val id = o.str("id") ?: return@mapNotNull null
            Idea(
                id = id, testo = o.optString("testo"), fonte = Fonte.of(o.optJSONObject("fonte")),
                ritorni = o.optJSONArray("ritorni")?.length() ?: 0,
                serata = o.str("serata"),
                stato = local?.optJSONObject(id)?.str("stato") ?: o.str("stato") ?: "nuova",
            )
        }
    }

    fun setIdea(ctx: Context, id: String, stato: String) = Aree.edit(ctx) { st ->
        st.child("idee").put(id, org.json.JSONObject().put("stato", stato).put("quando", Aree.now()))
    }

    fun nights(ctx: Context): List<Serata> {
        val local = Aree.state(ctx).optJSONObject("checklist")
        return Aree.remoteArray(ctx, "seral/serate.json").objects().mapNotNull { o ->
            val id = o.str("id") ?: return@mapNotNull null
            val cl = o.optJSONObject("checklist")
            Serata(
                id = id, titolo = o.str("titolo") ?: "Serata",
                data = o.str("data")?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
                locale = o.str("locale"), stato = o.str("stato") ?: "in_preparazione",
                checklist = VOCI.map { v ->
                    val r = cl?.optJSONObject(v)
                    val mine = local?.optJSONObject("$id|$v")?.str("stato")
                    Voce(
                        voce = v, stato = mine ?: r?.str("stato") ?: "non_detto",
                        nota = r?.str("nota"), fonte = Fonte.of(r?.optJSONObject("fonte")), aMano = mine != null,
                    )
                },
            )
        }
    }

    /** Serate ancora da fare, dalla più vicina. Quelle senza data in fondo. */
    fun upcoming(ctx: Context, today: LocalDate = Aree.today()): List<Serata> =
        nights(ctx).filter { it.stato == "in_preparazione" && (it.data == null || !it.data.isBefore(today)) }
            .sortedWith(compareBy({ it.data == null }, { it.data }))

    fun setCheck(ctx: Context, serata: String, voce: String, done: Boolean) = Aree.edit(ctx) { st ->
        st.child("checklist").put("$serata|$voce", org.json.JSONObject().put("stato", if (done) "fatto" else "da_fare").put("quando", Aree.now()))
    }

    fun post(ctx: Context): Post? {
        val o = Aree.remoteObject(ctx, "seral/post.json") ?: return null
        return Post(
            settimana = o.optString("settimana"), numero = o.str("numero"),
            serate = o.optJSONArray("serate").strings(),
            idee = o.optJSONArray("idee").objects().map { Spunto(it.optString("testo"), Fonte.of(it.optJSONObject("fonte"))) }
                .filter { it.testo.isNotBlank() },
        )
    }
}
