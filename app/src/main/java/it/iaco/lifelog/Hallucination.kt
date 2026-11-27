package it.iaco.lifelog

/**
 * Frasi che Whisper inventa sul silenzio, sul rumore o sulla musica (le ha imparate dai sottotitoli dei video).
 * Il 26/11 "Grazie a tutti." era 29 righe su 302. Si scarta solo se è TUTTO il testo del blocco:
 * dentro una frase vera "grazie a tutti" resta.
 */
object Hallucination {
    private val WHOLE = setOf(
        "grazie a tutti", "grazie a tutti per la visione", "grazie per la visione", "grazie per l'ascolto",
        "grazie per l'attenzione", "grazie per aver guardato", "ciao a tutti", "alla prossima",
        "iscriviti al canale", "sottotitoli a cura di qtss", "sottotitoli creati dalla comunità amara.org",
        "thank you", "thanks for watching", "you",
    )

    private fun norm(t: String) = t.lowercase().replace(Regex("[\\p{P}&&[^'.]]|\\.$|\\.\\s*$"), " ")
        .replace(Regex("\\s+"), " ").trim().trimEnd('.').trim()

    fun isFake(text: String): Boolean {
        val n = norm(text)
        if (n.isEmpty()) return false
        if (n in WHOLE) return true
        // La stessa frase finta ripetuta: "Grazie a tutti. Grazie a tutti."
        val parts = n.split(Regex("\\.\\s*")).map { it.trim() }.filter { it.isNotEmpty() }
        return parts.isNotEmpty() && parts.all { it in WHOLE }
    }
}
