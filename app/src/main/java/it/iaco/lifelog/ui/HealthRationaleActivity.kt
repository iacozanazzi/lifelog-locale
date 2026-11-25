package it.iaco.lifelog.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Pagina che Health Connect apre quando chiedi perché Lifelog legge sonno e passi.
 * Android la richiede: senza, il permesso non si può nemmeno chiedere.
 */
class HealthRationaleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            LifeTheme {
                Column(Modifier.fillMaxSize().background(Life.c.bg).safeDrawingPadding().padding(horizontal = side, vertical = 16.dp)) {
                    ScreenHeader(null, "Sonno e passi")
                    Text(
                        "Lifelog legge da Health Connect le ore di sonno e i passi di ogni giorno, per mostrarli accanto alla giornata e nella settimana.",
                        style = Type.body, color = Life.c.text, modifier = Modifier.padding(top = 14.dp)
                    )
                    Text(
                        "Li legge solo quando apri l'app, li tiene sul telefono e non li manda a nessuno, nemmeno a Claude. Non scrive niente in Health Connect.",
                        style = Type.text, color = Life.c.second, modifier = Modifier.padding(top = 12.dp)
                    )
                    Text("Puoi togliere il permesso quando vuoi dalle impostazioni di Health Connect.", style = Type.text, color = Life.c.second, modifier = Modifier.padding(top = 12.dp))
                    TextAction("Chiudi", { finish() }, modifier = Modifier.padding(top = 20.dp))
                }
            }
        }
    }
}
