package it.iaco.lifelog.memory

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.ExistingWorkPolicy
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
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
 * Luoghi: dove eri durante il giorno, con il metodo di Google Maps Timeline (soste + spostamenti)
 * e quasi senza batteria. Mai il GPS: solo posizione di rete (Wi-Fi e celle) e posizioni gratis.
 *
 * - Posizioni gratis: se un'altra app (Maps, meteo) ha appena chiesto la posizione, la riusa
 *   (priorità passiva di Fused Location, nessuna richiesta in più al telefono).
 * - Fermo in un posto: dopo 20+ minuti di letture nello stesso punto l'app ti considera "fermo".
 *   Mette un recinto virtuale (geofence) di 150 m intorno al posto e smette di chiedere la posizione:
 *   ogni 15 minuti ricopia la stessa, senza radio. Il geofence lo sorveglia Google Play Services
 *   con i cambi di celle e Wi-Fi che il telefono vede comunque, in modo raggruppato (ogni ~5 min).
 * - Esci dal recinto: torna a leggere la posizione di rete ogni 15 minuti finché non ti fermi di nuovo.
 * - Sicurezza: da ferma ricontrolla con una lettura vera ogni 4 ore, nel caso il recinto non scatti.
 *
 * Tutto resta in filesDir/memory/luoghi e non esce dal telefono.
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
        clearAnchor(ctx)
        if (gms(ctx)) runCatching { LocationServices.getFusedLocationProviderClient(ctx).removeLocationUpdates(pending(ctx, ACTION_PASSIVE)) }
    }

    /** Da chiamare all'avvio: rimette in coda il lavoro se i luoghi sono attivi. */
    fun schedule(ctx: Context) {
        if (!ctx.placesOn || !hasForeground(ctx)) return
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
            WORK, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<PlacesWorker>(15, TimeUnit.MINUTES).build()
        )
        listenPassive(ctx)
        // Riavvio e aggiornamento cancellano i geofence: se eri fermo, rimette il recinto.
        anchor(ctx)?.let { addFence(ctx, it.lat, it.lon) }
    }

    // ------------------------------------------------------------- risparmio (come Maps Timeline)

    private const val ACTION_PASSIVE = "it.iaco.lifelog.PLACES_PASSIVE"
    private const val ACTION_FENCE = "it.iaco.lifelog.PLACES_FENCE"
    private const val FENCE_ID = "sosta"
    private const val STILL_MS = 20 * 60_000L          // letture vere nello stesso posto per questo tempo = fermo
    private const val RECHECK_MS = 4 * 60 * 60_000L    // da fermo, una lettura vera ogni 4 ore
    private const val MIN_GAP_MS = 4 * 60_000L         // non scrivere posizioni più fitte di così

    data class Anchor(val lat: Double, val lon: Double, val checkedMs: Long)

    private fun gms(ctx: Context) = runCatching {
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(ctx) == ConnectionResult.SUCCESS
    }.getOrDefault(false)

    private fun pending(ctx: Context, action: String): PendingIntent {
        val i = Intent(ctx, PlacesReceiver::class.java).setAction(action)
        // MUTABLE: Play Services aggiunge all'intent la posizione o l'evento del recinto.
        return PendingIntent.getBroadcast(ctx, action.hashCode(), i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
    }

    fun anchor(ctx: Context): Anchor? {
        val p = sp(ctx)
        if (!p.contains("fermo_t")) return null
        return Anchor(Double.fromBits(p.getLong("fermo_lat_b", 0)), Double.fromBits(p.getLong("fermo_lon_b", 0)), p.getLong("fermo_t", 0))
    }

    private fun setAnchor(ctx: Context, lat: Double, lon: Double, now: Long) {
        sp(ctx).edit().putLong("fermo_lat_b", lat.toBits())
            .putLong("fermo_lon_b", lon.toBits()).putLong("fermo_t", now).apply()
    }

    fun clearAnchor(ctx: Context) {
        sp(ctx).edit().remove("fermo_lat_b").remove("fermo_lon_b").remove("fermo_t").apply()
        if (gms(ctx)) runCatching { LocationServices.getGeofencingClient(ctx).removeGeofences(listOf(FENCE_ID)) }
    }

    /** Posizioni gratis: arrivano solo quando un'altra app ha già acceso la radio. */
    @Suppress("MissingPermission")
    private fun listenPassive(ctx: Context) {
        if (!gms(ctx) || !hasBackground(ctx)) return
        val req = LocationRequest.Builder(Priority.PRIORITY_PASSIVE, 5 * 60_000L)
            .setMinUpdateIntervalMillis(MIN_GAP_MS).setMaxUpdateDelayMillis(30 * 60_000L).build()
        runCatching { LocationServices.getFusedLocationProviderClient(ctx).requestLocationUpdates(req, pending(ctx, ACTION_PASSIVE)) }
    }

    /** Recinto di uscita intorno alla sosta. Ritorna false se non si può (niente Play Services o permesso "sempre"). */
    @Suppress("MissingPermission")
    private fun addFence(ctx: Context, lat: Double, lon: Double): Boolean {
        if (!gms(ctx) || !hasBackground(ctx)) return false
        val fence = Geofence.Builder().setRequestId(FENCE_ID)
            .setCircularRegion(lat, lon, RADIUS_M)
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_EXIT)
            .setNotificationResponsiveness(5 * 60_000) // raggruppa i controlli: molta meno batteria
            .build()
        val req = GeofencingRequest.Builder().setInitialTrigger(0).addGeofence(fence).build()
        return runCatching { LocationServices.getGeofencingClient(ctx).addGeofences(req, pending(ctx, ACTION_FENCE)); true }.getOrDefault(false)
    }

    /** Uscito dal recinto: non più fermo, e una lettura subito per segnare l'ora di partenza. */
    fun onLeft(ctx: Context) {
        clearAnchor(ctx)
        WorkManager.getInstance(ctx).enqueueUniqueWork("memory_places_now", ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<PlacesWorker>().build())
    }

    /** Una posizione gratis arrivata da sola: la scrive e, se è lontana dalla sosta, chiude la sosta. */
    fun onPassive(ctx: Context, locs: List<Location>) {
        if (!ctx.placesOn) return
        val l = locs.filter { it.accuracy <= 200 }.maxByOrNull { it.time } ?: return
        val a = anchor(ctx)
        if (a != null && distance(a.lat, a.lon, l.latitude, l.longitude) > RADIUS_M + l.accuracy) clearAnchor(ctx)
        if (l.time - lastWritten(ctx) >= MIN_GAP_MS) write(ctx, l.time, l.latitude, l.longitude, l.accuracy, false)
    }

    private fun lastWritten(ctx: Context) = sp(ctx).getLong("ultima_t", 0)

    private fun write(ctx: Context, t: Long, lat: Double, lon: Double, acc: Float, copy: Boolean) {
        val day = Instant.ofEpochMilli(t).atZone(zone).toLocalDate().toString()
        val o = JSONObject().put("t", t).put("lat", lat).put("lon", lon).put("acc", acc.toDouble())
        if (copy) o.put("fermo", true)
        File(dir(ctx), "$day.jsonl").appendText(o.toString() + "\n")
        val sp = sp(ctx)
        val p = sp.edit().putLong("ultima_t", t)
        if (!copy) {
            // "qui_da": da quando le letture vere restano nello stesso posto.
            val prevT = sp.getLong("vera_t", 0)
            val same = prevT > 0 && t - prevT <= 60 * 60_000L &&
                distance(Double.fromBits(sp.getLong("vera_lat", 0)), Double.fromBits(sp.getLong("vera_lon", 0)), lat, lon) <= RADIUS_M
            p.putLong("qui_da", if (same) sp.getLong("qui_da", t) else t)
            p.putLong("vera_t", t).putLong("vera_lat", lat.toBits()).putLong("vera_lon", lon.toBits())
        }
        p.apply()
    }

    /** Lettura vera ma leggera: Fused Location a consumo bilanciato (Wi-Fi e celle, ~100 m), mai GPS. */
    @Suppress("MissingPermission")
    private suspend fun networkFix(ctx: Context): Location? {
        if (gms(ctx)) {
            val req = CurrentLocationRequest.Builder()
                .setPriority(Priority.PRIORITY_BALANCED_POWER_ACCURACY)
                .setMaxUpdateAgeMillis(5 * 60_000L) // se il telefono ne ha una di 5 minuti fa, va bene quella
                .setDurationMillis(30_000L)
                .build()
            return withTimeoutOrNull(35_000) {
                suspendCancellableCoroutine { cont ->
                    LocationServices.getFusedLocationProviderClient(ctx).getCurrentLocation(req, null)
                        .addOnCompleteListener { t -> if (cont.isActive) cont.resume(if (t.isSuccessful) t.result else null) }
                }
            }
        }
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return null
        if (Build.VERSION.SDK_INT < 30 || !runCatching { lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) }.getOrDefault(false)) return null
        return withTimeoutOrNull(30_000) {
            suspendCancellableCoroutine<Location?> { cont ->
                val ex = Executors.newSingleThreadExecutor()
                lm.getCurrentLocation(LocationManager.NETWORK_PROVIDER, null, ex) { l ->
                    if (cont.isActive) cont.resume(l)
                    ex.shutdown()
                }
            }
        }
    }

    /** Cancella tutte le posizioni salvate (i nomi dei posti restano). */
    fun wipe(ctx: Context) {
        dir(ctx).listFiles()?.filter { it.name.endsWith(".jsonl") }?.forEach { it.delete() }
    }

    // ------------------------------------------------------------- raccolta

    @Suppress("MissingPermission")
    suspend fun sample(ctx: Context) {
        if (!hasForeground(ctx)) return
        val lm = ctx.getSystemService(LocationManager::class.java)
        val now = System.currentTimeMillis()
        // 1. Gratis: l'ultima posizione già nota, se recente (nessuna radio accesa da noi).
        val free = listOf(LocationManager.PASSIVE_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            .mapNotNull { p -> lm?.let { runCatching { it.getLastKnownLocation(p) }.getOrNull() } }
            .filter { now - it.time < 10 * 60_000 && it.accuracy <= 500 }
            .minByOrNull { it.accuracy }

        // 2. Fermo in un posto: ricopia la sosta senza chiedere niente, salvo prova contraria.
        val a = anchor(ctx)
        if (a != null) {
            val moved = free != null && distance(a.lat, a.lon, free.latitude, free.longitude) > RADIUS_M + free.accuracy
            if (!moved && now - a.checkedMs < RECHECK_MS) {
                write(ctx, now, a.lat, a.lon, 0f, copy = true)
                return
            }
            clearAnchor(ctx) // spostato, oppure è ora del controllo di sicurezza
        }

        // 3. In movimento (o controllo): una lettura di rete leggera.
        val loc = free ?: networkFix(ctx) ?: return
        if (loc.accuracy > 500) return // troppo vago per dire "dove"
        if (loc.time <= lastWritten(ctx)) return // già scritta (es. arrivata gratis poco fa)
        write(ctx, loc.time, loc.latitude, loc.longitude, loc.accuracy, copy = false)

        // 4. Sempre nello stesso posto da 20+ minuti: sei fermo, metti il recinto e smetti di leggere.
        if (loc.time - sp(ctx).getLong("qui_da", loc.time) >= STILL_MS && addFence(ctx, loc.latitude, loc.longitude))
            setAnchor(ctx, loc.latitude, loc.longitude, now)
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

    /**
     * Dove eri in un certo istante della giornata: il nome del posto, "luogo senza nome" per una sosta
     * non ancora nominata, null se eri in giro o i luoghi sono spenti. Serve a dividere le conversazioni.
     * Margine di 10 minuti: la posizione si legge ogni 15, quindi l'arrivo vero è un po' prima della prima lettura.
     */
    fun locator(ctx: Context, day: String): (Long) -> String? {
        val st = runCatching { stays(ctx, day) }.getOrDefault(emptyList())
        if (st.isEmpty()) return { null }
        val margin = 10 * 60_000L
        return { ms -> st.firstOrNull { ms in (it.fromMs - margin)..(it.toMs + margin) }?.let { it.place?.nome ?: UNNAMED } }
    }

    const val UNNAMED = "luogo senza nome"

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

/** Riceve da Play Services le posizioni gratis e l'uscita dal recinto della sosta. */
class PlacesReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        runCatching {
            when (intent.action) {
                "it.iaco.lifelog.PLACES_FENCE" -> {
                    val ev = GeofencingEvent.fromIntent(intent) ?: return
                    if (!ev.hasError() && ev.geofenceTransition == Geofence.GEOFENCE_TRANSITION_EXIT) Places.onLeft(ctx)
                }
                "it.iaco.lifelog.PLACES_PASSIVE" -> {
                    if (LocationResult.hasResult(intent)) LocationResult.extractResult(intent)?.let { Places.onPassive(ctx, it.locations) }
                }
            }
        }
    }
}

class PlacesWorker(ctx: Context, p: WorkerParameters) : CoroutineWorker(ctx, p) {
    override suspend fun doWork(): Result {
        runCatching { Places.sample(applicationContext) }
        return Result.success()
    }
}
