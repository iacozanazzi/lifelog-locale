package it.iaco.lifelog.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.mutableStateOf
import it.iaco.lifelog.Models
import it.iaco.lifelog.Notifications
import it.iaco.lifelog.Prefs
import it.iaco.lifelog.RecorderService

class MainActivity : ComponentActivity() {

    /** Aumenta ogni volta che una notifica chiede di riprendere la registrazione. */
    private val resumeRequests = mutableStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleResume(intent)
        setContent {
            val dark = isSystemInDarkTheme()
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                App(resumeRequests.value)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleResume(intent)
    }

    private fun handleResume(intent: Intent?) {
        if (intent?.getBooleanExtra(Notifications.EXTRA_RESUME, false) == true) {
            intent.removeExtra(Notifications.EXTRA_RESUME)
            val prefs = Prefs(this)
            // L'app ora è in primo piano: Android permette di riaccendere il microfono.
            if (prefs.recordingWanted && Models.vadReady(this)) {
                try {
                    RecorderService.start(this)
                } catch (_: Exception) {
                }
            }
            resumeRequests.value++
        }
    }
}
