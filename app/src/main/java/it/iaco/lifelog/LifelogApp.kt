package it.iaco.lifelog

import android.app.Application
import it.iaco.lifelog.memory.Places

class LifelogApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        Notifications.createChannels(this)
        // Un errore dei luoghi non deve mai impedire all'app (e alla registrazione) di partire.
        runCatching { Places.schedule(this) }
        // Modello delle voci: si scarica da solo una volta (senza, nessuna voce viene riconosciuta).
        runCatching { it.iaco.lifelog.people.Speakers.autoDownload(this) }
        runCatching { Sfondo.autoDownload(this) }
        // Seconda passata con Whisper: modello scaricato da solo, lavoro in coda per la prossima carica.
        runCatching { Refine.ensureWhisper(this); Refine.schedule(this) }
        // Invii notturni a Claude: sveglie da riarmare a ogni avvio (riavvio e aggiornamento le cancellano).
        runCatching { it.iaco.lifelog.data.Assistant.schedule(this) }
    }
}
