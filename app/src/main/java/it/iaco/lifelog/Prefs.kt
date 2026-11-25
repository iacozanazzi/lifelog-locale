package it.iaco.lifelog

import android.content.Context
import android.content.SharedPreferences

/** Impostazioni e stato persistente (sopravvive se Samsung uccide l'app). */
class Prefs(context: Context) {
    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("lifelog", Context.MODE_PRIVATE)

    /** L'utente vuole che la registrazione sia attiva. */
    var recordingWanted: Boolean
        get() = sp.getBoolean("recording_wanted", false)
        set(v) = sp.edit().putBoolean("recording_wanted", v).apply()

    /** Ultimo segnale di vita del servizio di registrazione (ms epoch). */
    var lastHeartbeat: Long
        get() = sp.getLong("last_heartbeat", 0L)
        set(v) = sp.edit().putLong("last_heartbeat", v).apply()

    /** Trascrivi solo quando il telefono è in carica. */
    var onlyWhileCharging: Boolean
        get() = sp.getBoolean("only_charging", true)
        set(v) = sp.edit().putBoolean("only_charging", v).apply()

    /** Carica minima (in carica) prima di iniziare a trascrivere. 0 = non aspettare. */
    var minChargePercent: Int
        get() = sp.getInt("min_charge", 80)
        set(v) = sp.edit().putInt("min_charge", v).apply()

    /** Temperatura batteria oltre la quale ci si ferma (gradi C). */
    var maxBatteryTemp: Float
        get() = sp.getFloat("max_temp", 37.5f)
        set(v) = sp.edit().putFloat("max_temp", v).apply()

    /** Ultimo messaggio di stato della trascrizione, da mostrare in app. */
    var transcribeStatus: String
        get() = sp.getString("transcribe_status", "") ?: ""
        set(v) = sp.edit().putString("transcribe_status", v).apply()

    /** File su Google Drive (scelto dall'utente) dove salvare le trascrizioni. */
    var driveUri: String
        get() = sp.getString("drive_uri", "") ?: ""
        set(v) = sp.edit().putString("drive_uri", v).apply()

    var driveStatus: String
        get() = sp.getString("drive_status", "") ?: ""
        set(v) = sp.edit().putString("drive_status", v).apply()

    var onboardingDone: Boolean
        get() = sp.getBoolean("onboarding_done", false)
        set(v) = sp.edit().putBoolean("onboarding_done", v).apply()
}
