package it.iaco.lifelog.memory

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.Period
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Sonno e passi da Samsung Health, letti attraverso Health Connect.
 * Samsung Health deve condividere i dati con Health Connect (lo chiede al primo collegamento).
 * Si legge solo con l'app aperta, si salva in filesDir/memory/salute.json e non esce dal telefono.
 */
object Health {
    data class DayHealth(val day: String, val steps: Long?, val sleepMin: Int?, val bed: String?, val wake: String?)

    val PERMISSIONS = setOf(
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(SleepSessionRecord::class),
    )

    private val zone get() = ZoneId.systemDefault()
    private val hm = DateTimeFormatter.ofPattern("H:mm")
    private fun file(ctx: Context) = File(Memory.dir(ctx), "salute.json")

    fun available(ctx: Context): Boolean =
        runCatching { HealthConnectClient.getSdkStatus(ctx) == HealthConnectClient.SDK_AVAILABLE }.getOrDefault(false)

    /** Health Connect c'è ma va aggiornato (Android 13 o precedenti). */
    fun needsUpdate(ctx: Context): Boolean = runCatching {
        HealthConnectClient.getSdkStatus(ctx) == HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED
    }.getOrDefault(false)

    fun storeIntent(): Intent = Intent(
        Intent.ACTION_VIEW,
        Uri.parse("market://details?id=com.google.android.apps.healthdata&url=healthconnect%3A%2F%2Fonboarding")
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    suspend fun granted(ctx: Context): Boolean = available(ctx) && runCatching {
        HealthConnectClient.getOrCreate(ctx).permissionController.getGrantedPermissions().containsAll(PERMISSIONS)
    }.getOrDefault(false)

    /** Rilegge gli ultimi giorni (il sonno di stanotte arriva quando Samsung Health sincronizza). */
    suspend fun refresh(ctx: Context, days: Int = 15): String {
        if (!granted(ctx)) return "Samsung Health non è collegato."
        val client = HealthConnectClient.getOrCreate(ctx)
        val today = LocalDate.now(zone)
        val from = today.minusDays(days.toLong() - 1)
        val out = cache(ctx)

        val steps = client.aggregateGroupByPeriod(
            AggregateGroupByPeriodRequest(
                metrics = setOf(StepsRecord.COUNT_TOTAL),
                timeRangeFilter = TimeRangeFilter.between(from.atStartOfDay(), today.plusDays(1).atStartOfDay()),
                timeRangeSlicer = Period.ofDays(1),
            )
        ).associate { it.startTime.toLocalDate().toString() to it.result[StepsRecord.COUNT_TOTAL] }

        // Il sonno appartiene al giorno in cui ti svegli: sessioni finite tra mezzanotte e le 18.
        val sessions = client.readRecords(
            ReadRecordsRequest(
                recordType = SleepSessionRecord::class,
                timeRangeFilter = TimeRangeFilter.between(
                    from.minusDays(1).atTime(12, 0).atZone(zone).toInstant(),
                    today.plusDays(1).atStartOfDay(zone).toInstant(),
                ),
            )
        ).records
        val byDay = sessions.groupBy { it.endTime.atZone(zone).toLocalDate().toString() }
            .mapValues { (_, list) -> list.filter { it.endTime.atZone(zone).hour < 18 } }

        var d = from
        while (!d.isAfter(today)) {
            val key = d.toString()
            val sl = byDay[key].orEmpty()
            val prev = out.optJSONObject(key) ?: JSONObject()
            steps[key]?.let { prev.put("passi", it) }
            if (sl.isNotEmpty()) {
                prev.put("sonno_min", sl.sumOf { asleepMinutes(it) })
                prev.put("da", sl.minOf { it.startTime }.atZone(zone).format(hm))
                prev.put("a", sl.maxOf { it.endTime }.atZone(zone).format(hm))
            }
            if (prev.length() > 0) out.put(key, prev)
            d = d.plusDays(1)
        }
        file(ctx).writeText(out.toString())
        return "Aggiornato"
    }

    /** Minuti dormiti: le fasi senza i risvegli, o tutta la sessione se le fasi mancano. */
    private fun asleepMinutes(s: SleepSessionRecord): Int {
        fun min(a: Instant, b: Instant) = ((b.toEpochMilli() - a.toEpochMilli()) / 60_000).toInt()
        if (s.stages.isEmpty()) return min(s.startTime, s.endTime)
        val awake = setOf(SleepSessionRecord.STAGE_TYPE_AWAKE, SleepSessionRecord.STAGE_TYPE_OUT_OF_BED)
        return s.stages.filter { it.stage !in awake }.sumOf { min(it.startTime, it.endTime) }
    }

    private fun cache(ctx: Context): JSONObject =
        runCatching { JSONObject(file(ctx).readText()) }.getOrDefault(JSONObject())

    fun day(ctx: Context, day: String): DayHealth? {
        val o = cache(ctx).optJSONObject(day) ?: return null
        return DayHealth(
            day = day,
            steps = if (o.has("passi")) o.optLong("passi") else null,
            sleepMin = if (o.has("sonno_min")) o.optInt("sonno_min") else null,
            bed = o.optString("da").ifBlank { null }, wake = o.optString("a").ifBlank { null },
        )
    }

    fun hasData(ctx: Context) = file(ctx).exists()
}
