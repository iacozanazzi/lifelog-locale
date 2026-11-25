package it.iaco.lifelog

import android.app.Application

class LifelogApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        Notifications.createChannels(this)
    }
}
