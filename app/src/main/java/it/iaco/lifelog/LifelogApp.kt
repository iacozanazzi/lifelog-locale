package it.iaco.lifelog

import android.app.Application
import it.iaco.lifelog.memory.Places

class LifelogApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        Notifications.createChannels(this)
        Places.schedule(this)
    }
}
