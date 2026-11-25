package it.iaco.lifelog.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Un intervallo in minuti dalla mezzanotte. */
data class Span(val fromMin: Float, val toMin: Float, val kind: Kind) {
    enum class Kind { TRANSCRIBED, PENDING, GAP }
}

/**
 * Le 24 ore come una riga sottile: nero = trascritto, blu = in attesa,
 * tratteggio = non registrato. Nessuna etichetta: serve a vedere la forma del giorno.
 */
@Composable
fun DayLine(spans: List<Span>, modifier: Modifier = Modifier, height: Dp = 8.dp) {
    val c = Life.c
    Canvas(modifier.fillMaxWidth().height(height)) {
        val w = size.width
        val h = size.height
        drawRoundRect(c.fill, size = Size(w, h), cornerRadius = CornerRadius(h / 2, h / 2))
        for (s in spans) {
            val x0 = w * s.fromMin / 1440f
            val x1 = maxOf(x0 + 2.dp.toPx(), w * s.toMin / 1440f)
            when (s.kind) {
                Span.Kind.GAP -> drawLine(
                    c.second, Offset(x0, h / 2), Offset(x1, h / 2),
                    strokeWidth = 1.5.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx()))
                )
                else -> drawRoundRect(
                    if (s.kind == Span.Kind.PENDING) c.accent else c.text,
                    topLeft = Offset(x0, 0f), size = Size(x1 - x0, h),
                    cornerRadius = CornerRadius(1.5.dp.toPx(), 1.5.dp.toPx())
                )
            }
        }
    }
}

/** Puntino di stato. Mentre qualcuno parla si allarga col volume: l'unico movimento dell'app. */
@Composable
fun StatusDot(color: Color, level: Float = 0f) {
    val s by animateFloatAsState(1f + level.coerceIn(0f, 1f) * 0.8f, tween(120), label = "dot")
    Box(Modifier.size(9.dp).scale(s).clip(CircleShape).background(color))
}

/** Pulsante pieno: nero su bianco (bianco su nero in tema scuro). */
@Composable
fun SolidButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val c = Life.c
    Box(
        modifier
            .clip(RoundedCornerShape(50))
            .background(if (enabled) c.text else c.faint)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 13.dp),
        contentAlignment = Alignment.Center
    ) { Text(text, style = Type.rowTitle, color = c.bg) }
}

/** Azione testuale nel colore d'accento. */
@Composable
fun TextAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = Life.c.accent) {
    Text(
        text, style = Type.rowTitle, color = color,
        modifier = modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onClick).padding(vertical = 6.dp)
    )
}

@Composable
fun Hairline(modifier: Modifier = Modifier) = HorizontalDivider(modifier, thickness = 1.dp, color = Life.c.hair)

@Composable
fun ScreenHeader(over: String?, title: String, modifier: Modifier = Modifier) {
    val c = Life.c
    androidx.compose.foundation.layout.Column(modifier.padding(top = 20.dp, bottom = 4.dp)) {
        if (over != null) Text(over, style = Type.meta, color = c.second)
        Text(title, style = Type.largeTitle, color = c.text)
    }
}

/** Riga con testo e freccina, come nelle impostazioni di sistema. */
@Composable
fun NavRow(title: String, value: String? = null, onClick: () -> Unit) {
    val c = Life.c
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, style = Type.text.copy(fontSize = 17.sp), color = c.text, modifier = Modifier.weight(1f))
        if (value != null) Text(value, style = Type.meta, color = c.second, modifier = Modifier.padding(end = 8.dp))
        Text("›", style = Type.section, color = c.faint)
    }
}
