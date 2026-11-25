package it.iaco.lifelog.memory

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import it.iaco.lifelog.data.Assistant
import org.json.JSONArray
import org.json.JSONObject

/**
 * Domande salvate, come le "Recipes" di Granola: un nome corto ("Preparami per Marco")
 * e le istruzioni precise per Claude. La lista va anche in lifelog-dati/domande.json,
 * così nel thread basta scrivere il nome.
 */
object Questions {
    data class Question(val nome: String, val istruzioni: String, val mine: Boolean = false) {
        /** Segnaposti come {persona} o {argomento}. */
        val slots: List<String> get() = Regex("\\{([a-zà-ù]+)\\}").findAll(nome).map { it.groupValues[1] }.distinct().toList()
    }

    /** Dove Iaco parla con Claude della sua vita: il progetto con accesso a lifelog-dati. */
    const val CLAUDE_LINK = "https://claude.ai/code/project/chan_01EJ6WKgDQER4FhyeX3Yp4eV"

    val DEFAULTS = listOf(
        Question(
            "Preparami per {persona}",
            "Leggi persone/<slug>.md, gli impegni aperti con {persona} in impegni.json e le ultime tre volte in cui compare in indice/. " +
                "Rispondi in cinque righe al massimo: chi è, di cosa abbiamo parlato l'ultima volta, cosa le devo, cosa mi deve, cosa ricordare. Ogni punto con giorno e ora."
        ),
        Question(
            "Cosa ho promesso questa settimana",
            "Da impegni.json, gli impegni con chi = io nati negli ultimi 7 giorni. Prima quelli aperti, poi i fatti. Per ognuno: testo, a chi, giorno e ora in cui l'ho detto."
        ),
        Question(
            "Cosa mi devono gli altri",
            "Da impegni.json, gli impegni aperti con chi = altro, dal più vecchio. Per ognuno: chi, cosa, da quanti giorni."
        ),
        Question(
            "Cosa abbiamo deciso per Seral",
            "Dalle analisi degli ultimi 14 giorni, le voci di tipo decisione nell'area seral: serate, locali, DJ, grafiche, soldi. Una riga ciascuna con giorno e ora."
        ),
        Question(
            "Cosa ho fatto all'università questa settimana",
            "Dall'indice e dalle analisi degli ultimi 7 giorni: lezioni seguite, argomenti, scadenze ed esami nominati. Una riga ciascuno con giorno e ora."
        ),
        Question(
            "Quando ho parlato di {argomento}",
            "Cerca {argomento} in indice/ (anche in parole), poi nei giorni trovati con grep. Elenca le volte con giorno, ora e una citazione breve, dalla più recente."
        ),
        Question(
            "Com'è andata la settimana",
            "Parti da settimane/ (l'ultima) e da indice/. Tre righe: Seral, università, persone. Poi gli impegni rimasti aperti. Niente giudizi sull'umore."
        ),
    )

    private const val SP = "lifelog_memory"
    private fun sp(ctx: Context) = ctx.getSharedPreferences(SP, Context.MODE_PRIVATE)

    private fun mine(ctx: Context): List<Question> = runCatching {
        val a = JSONArray(sp(ctx).getString("domande_mie", "[]")!!)
        (0 until a.length()).map { a.getJSONObject(it).let { o -> Question(o.optString("nome"), o.optString("istruzioni"), mine = true) } }
    }.getOrDefault(emptyList())

    fun all(ctx: Context): List<Question> = DEFAULTS + mine(ctx)

    fun add(ctx: Context, text: String) {
        val t = text.trim().ifEmpty { return }
        val list = mine(ctx).filter { it.nome != t } + Question(t, t, mine = true)
        save(ctx, list)
    }

    fun remove(ctx: Context, nome: String) = save(ctx, mine(ctx).filter { it.nome != nome })

    private fun save(ctx: Context, list: List<Question>) {
        sp(ctx).edit().putString("domande_mie", JSONArray(list.map { JSONObject().put("nome", it.nome).put("istruzioni", it.istruzioni) }).toString()).apply()
        Assistant.uploadSoon(ctx)
    }

    fun toJson(list: List<Question>) = JSONArray(list.map { JSONObject().put("nome", it.nome).put("istruzioni", it.istruzioni) })

    /** Riempie i segnaposti e prepara il testo da incollare nel thread. */
    fun prompt(q: Question, values: Map<String, String>): Pair<String, String> {
        fun fill(s: String) = values.entries.fold(s) { acc, (k, v) -> acc.replace("{$k}", v.trim()) }
        val title = fill(q.nome)
        val body = "Domanda salvata: $title.\n${fill(q.istruzioni)}\n" +
            "Usa il repository lifelog-dati e le regole del suo CLAUDE.md. Cita giorno e ora, e se non trovi dillo."
        return title to body
    }

    /** Copia il testo e apre Claude: il thread del progetto ha accesso a lifelog-dati. */
    fun send(ctx: Context, text: String) {
        ctx.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Domanda per Claude", text))
        try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(CLAUDE_LINK)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
        }
    }
}
