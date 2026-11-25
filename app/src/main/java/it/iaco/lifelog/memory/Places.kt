package it.iaco.lifelog.memory

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * Luoghi: dove eri durante il giorno. Niente GPS continuo: ogni 15 minuti chiede ad Android
 * la posizione di rete (Wi-Fi e celle), che costa pochissima batteria, oppure riusa l'ultima
 * posizione già nota se è recente. Tutto resta in filesDir/memory/luoghi e non esce dal telefono.
 * I nomi dei posti li dai tu ("Casa", "Ca' Foscari"): l'app non chiede indirizzi a nessuno.
 */
object Places {
    private const val WORK = "memory_places"
    private const val SP = "lifelog_memory"
    const val RADIUS_M = 150f
    private const val MIN_STAY_MS = 15 * 60_000L
    private val zone get() = ZoneId.systemDefault()
    private val hm = DateTimeFormatter.ofPattern("H:mm")

    private fun sp(ctx: Context) = ctx.getSharedPreferences(SP, Context.MODE_PRIVATE)
    private fun dir(ctx: Context) = File(Memory.dir(ctx), "luoghi").apply { mkdirs() }

    var Context.placesOn: Boolean
        get() = sp(this).getBoolean("luoghi_attivi", false)
        set(v) = sp(this).edit().putBoolean("luoghi_attivi", v).apply()

    fun hasForeground(ctx: Context) =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun hasBackground(ctx: Context) =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun enable(ctx: Context) {
        ctx.placesOn = true
        schedule(ctx)
    }

    fun disable(ctx: Context) {
        ctx.placesOn = false
        WorkManager.getInstance(ctx).cancelUniqueWork(WORK)
    }

    /** Da chiamare all'avvio: rimette in coda il lavoro se i luoghi sono attivi. */
    fun schedule(ctx: Context) {
        if (!ctx.placesOn || !hasForeground(ctx)) return
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
            WORK, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<PlacesWorker>(15, TimeUnit.MINUTES).build()
        )
    }

    /** Cancella tutte le posizioni salvate (i nomi dei posti restano). */
    fun wipe(ctx: Context) {
        dir(ctx).listFiles()?.filter { it.name.endsWith(".jsonl") }?.forEach { it.delete() }
    }

    // ------------------------------------------------------------- raccolta

    @Suppress("MissingPermission")
    suspend fun sample(ctx: Context) {
        if (!hasForeground(ctx)) return
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return
        val now = System.currentTimeMillis()
        val recent = listOf(LocationManager.PASSIVE_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .filter { now - it.time < 10 * 60_000 }
            .minByOrNull { it.accuracy }
        val loc = recent ?: if (Build.VERSION.SDK_INT >= 30 && runCatching { lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) }.getOrDefault(false)) {
            withTimeoutOrNull(30_000) {
                suspendCancellableCoroutine<Location?> { cont ->
                    val ex = Executors.newSingleThreadExecutor()
                    lm.getCurrentLocation(LocationManager.NETWORK_PROVIDER, null, ex) { l ->
                        if (cont.isActive) cont.resume(l)
                        ex.shutdown()
                    }
                }
            }
        } else null
        loc ?: return
        if (loc.accuracy > 500) return // troppo vago per dire "dove"
        val day = Instant.ofEpochMilli(loc.time).atZone(zone).toLocalDate().toString()
        File(dir(ctx), "$day.jsonl").appendText(
            JSONObject().put("t", loc.time).put("lat", loc.latitude).put("lon", loc.longitude).put("acc", loc.accuracy.toDouble()).toString() + "\n"
        )
    }

    // ------------------------------------------------------------- soste

    data class Fix(val t: Long, val lat: Double, val lon: Double)
    data class Named(val id: String, val nome: String, val lat: Double, val lon: Double)
    data class Stay(val fromMs: Long, val toMs: Long, val lat: Double, val lon: Double, val place: Named?) {
        val minutes get() = ((toMs - fromMs) / 60_000).toInt()
    }

    private fun fixes(ctx: Context, day: String): List<Fix> {
        val f = File(dir(ctx), "$day.jsonl")
        if (!f.exists()) return emptyList()
        return f.readLines().mapNotNull { l ->
            runCatching { JSONObject(l).let { Fix(it.getLong("t"), it.getDouble("lat"), it.getDouble("lon")) } }.getOrNull()
        }.sortedBy { it.t }
    }

    fun distance(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Float {
        val r = FloatArray(1)
        Location.distanceBetween(aLat, aLon, bLat, bLon, r)
        return r[0]
    }

    /** Raggruppa le posizioni vicine e consecutive: una sosta è almeno 15 minuti nello stesso posto. */
    fun stays(ctx: Context, day: String): List<Stay> {
        val named = named(ctx)
        val out = mutableListOf<Stay>()
        var group = mutableListOf<Fix>()
        fun close() {
            if (group.size >= 2 && group.last().t - group.first().t >= MIN_STAY_MS) {
                val lat = group.map { it.lat }.average()
                val lon = group.map { it.lon }.average()
                val place = named.minByOrNull { distance(lat, lon, it.lat, it.lon) }?.takeIf { distance(lat, lon, it.lat, it.lon) <= RADIUS_M }
                val prev = out.lastOrNull()
                if (prev != null && place != null && prev.place?.id == place.id) {
                    out[out.size - 1] = prev.copy(toMs = group.last().t) // stesso posto, interrotto da un punto storto
                } else out += Stay(group.first().t, group.last().t, lat, lon, place)
            }
            group = mutableListOf()
        }
        for (f in fixes(ctx, day)) {
            val g = group
            if (g.isNotEmpty()) {
                val cLat = g.map { it.lat }.average()
                val cLon = g.map { it.lon }.average()
                if (distance(cLat, cLon, f.lat, f.lon) > RADIUS_M || f.t - g.last().t > 45 * 60_000) close()
            }
            group.add(f)
        }
        close()
        return out
    }

    fun clock(ms: Long): String = Instant.ofEpochMilli(ms).atZone(zone).format(hm)

    // ------------------------------------------------------------- posti con nome

    private fun namedFile(ctx: Context) = File(dir(ctx), "posti.json")

    fun named(ctx: Context): List<Named> = runCatching {
        val a = JSONArray(namedFile(ctx).readText())
        (0 until a.length()).map { a.getJSONObject(it).let { o -> Named(o.getString("id"), o.getString("nome"), o.getDouble("lat"), o.getDouble("lon")) } }
    }.getOrDefault(emptyList())

    /** Dà un nome al posto di una sosta; se c'è già un posto vicino lo rinomina. */
    fun name(ctx: Context, lat: Double, lon: Double, nome: String) {
        val n = nome.trim()
        val list = named(ctx).toMutableList()
        val near = list.indexOfFirst { distance(lat, lon, it.lat, it.lon) <= RADIUS_M }
        when {
            n.isEmpty() && near >= 0 -> list.removeAt(near)
            n.isEmpty() -> return
            near >= 0 -> list[near] = list[near].copy(nome = n)
            else -> list += Named("p" + System.currentTimeMillis(), n, lat, lon)
        }
        namedFile(ctx).writeText(JSONArray(list.map {
            JSONObject().put("id", it.id).put("nome", it.nome).put("lat", it.lat).put("lon", it.lon)
        }).toString())
    }

    /** Posti dove sei stato di più negli ultimi 7 giorni (minuti). */
    fun topOfWeek(ctx: Context, today: LocalDate = LocalDate.now(zone)): List<Pair<String, Int>> {
        val acc = HashMap<String, Int>()
        for (i in 0 until 7) {
            stays(ctx, today.minusDays(i.toLong()).toString()).forEach { s ->
                val k = s.place?.nome ?: return@forEach
                acc[k] = (acc[k] ?: 0) + s.minutes
            }
        }
        return acc.entries.sortedByDescending { it.value }.map { it.key to it.value }.take(5)
    }
}

class PlacesWorker(ctx: Context, p: WorkerParameters) : CoroutineWorker(ctx, p) {
    override suspend fun doWork(): Result {
        runCatching { Places.sample(applicationContext) }
        return Result.success()
    }
}
