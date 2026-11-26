package it.iaco.lifelog.data

import android.util.Base64
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Il minimo dell'API Contents di GitHub che serve: leggere e scrivere file di testo. */
class GitHub(private val token: String, private val repo: String) {

    class HttpError(val code: Int, msg: String) : IOException(msg)

    private fun conn(method: String, path: String, accept: String): HttpURLConnection {
        val p = path.split("/").joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
        val c = URL("https://api.github.com/repos/$repo/contents/$p").openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 20_000
        c.readTimeout = 30_000
        c.setRequestProperty("Authorization", "Bearer $token")
        c.setRequestProperty("Accept", accept)
        c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        c.setRequestProperty("User-Agent", "lifelog-locale")
        return c
    }

    private fun HttpURLConnection.body(): String =
        (if (responseCode < 400) inputStream else errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()

    /** sha del file, o null se non esiste. */
    fun sha(path: String): String? {
        val c = conn("GET", path, "application/vnd.github+json")
        try {
            return when (c.responseCode) {
                200 -> JSONObject(c.body()).optString("sha").ifEmpty { null }
                404 -> null
                else -> throw HttpError(c.responseCode, explain(c.responseCode, c.body()))
            }
        } finally { c.disconnect() }
    }

    /** Contenuto del file come testo, o null se non esiste. Funziona anche oltre 1 MB. */
    fun read(path: String): String? {
        val c = conn("GET", path, "application/vnd.github.raw+json")
        try {
            return when (c.responseCode) {
                200 -> c.body()
                404 -> null
                else -> throw HttpError(c.responseCode, explain(c.responseCode, c.body()))
            }
        } finally { c.disconnect() }
    }

    fun write(path: String, text: String, message: String) {
        val payload = JSONObject()
            .put("message", message)
            .put("content", Base64.encodeToString(text.toByteArray(), Base64.NO_WRAP))
        sha(path)?.let { payload.put("sha", it) }
        val c = conn("PUT", path, "application/vnd.github+json")
        try {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(payload.toString().toByteArray()) }
            if (c.responseCode !in 200..201) throw HttpError(c.responseCode, explain(c.responseCode, c.body()))
        } finally { c.disconnect() }
    }

    /** Verifica token e permessi sul repository. */
    fun check(): String {
        // Un token "classico" (ghp_) apre TUTTI i repository dell'account, compresa l'app
        // pubblica: chi lo rubasse dal telefono potrebbe cambiarne il codice.
        // Accettiamo solo token fine-grained, limitabili al solo repository dei dati.
        if (!token.startsWith("github_pat_")) throw IOException(
            "Serve un token fine-grained (inizia con github_pat_), limitato al solo $repo con Contents in lettura e scrittura"
        )
        val c = URL("https://api.github.com/repos/$repo").openConnection() as HttpURLConnection
        c.setRequestProperty("Authorization", "Bearer $token")
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("User-Agent", "lifelog-locale")
        c.connectTimeout = 20_000; c.readTimeout = 30_000
        try {
            if (c.responseCode != 200) throw HttpError(c.responseCode, explain(c.responseCode, c.body()))
            val j = JSONObject(c.body())
            val canPush = j.optJSONObject("permissions")?.optBoolean("push") ?: false
            if (!j.optBoolean("private")) throw IOException("Il repository è pubblico: rendilo privato prima di inviare le trascrizioni")
            if (!canPush) throw IOException("Il token può leggere ma non scrivere: serve Contents in lettura e scrittura")
            val exp = c.getHeaderField("github-authentication-token-expiration")?.take(10)
            return "Connesso a $repo" + (exp?.let { " · token valido fino al $it" } ?: "")
        } finally { c.disconnect() }
    }

    private fun explain(code: Int, body: String) = when (code) {
        401 -> "Token non valido o scaduto"
        403 -> "Il token non ha i permessi giusti (serve Contents: lettura e scrittura)"
        404 -> "Repository non trovato, oppure il token non può vederlo"
        else -> "Errore GitHub $code ${body.take(120)}"
    }
}
