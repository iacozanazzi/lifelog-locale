package it.iaco.lifelog.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin

// ------------------------------------------------------------ Orb dell'ascolto

enum class OrbMode { OFF, LISTENING, STALLED }

/**
 * Il cuore visivo dell'app: una sfera di luce calda che respira mentre ascolta,
 * si accende quando qualcuno parla e diventa corallo se la registrazione si è fermata.
 */
@Composable
fun ListeningOrb(mode: OrbMode, level: Float, speaking: Boolean, modifier: Modifier = Modifier, size: Dp = 240.dp) {
    val c = Life.c
    val t = rememberInfiniteTransition(label = "orb")
    val breath by t.animateFloat(
        0f, 1f, infiniteRepeatable(tween(4200, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "breath"
    )
    val wave by t.animateFloat(
        0f, 1f, infiniteRepeatable(tween(2600, easing = LinearEasing)), label = "wave"
    )
    val spin by t.animateFloat(
        0f, (2 * PI).toFloat(), infiniteRepeatable(tween(9000, easing = LinearEasing)), label = "spin"
    )
    val lvl by animateFloatAsState(if (mode == OrbMode.LISTENING) level else 0f, tween(180), label = "lvl")
    val heat by animateFloatAsState(if (speaking && mode == OrbMode.LISTENING) 1f else 0f, tween(500), label = "heat")

    val main = when (mode) {
        OrbMode.LISTENING -> c.voice
        OrbMode.STALLED -> c.gap
        OrbMode.OFF -> c.muted
    }

    Canvas(modifier.size(size)) {
        val center = Offset(this.size.width / 2, this.size.height / 2)
        val r = this.size.minDimension / 2
        val core = r * (0.42f + 0.03f * breath + 0.10f * lvl)

        if (mode == OrbMode.OFF) {
            drawCircle(main.copy(alpha = 0.18f), radius = r * 0.44f, center = center, style = Stroke(width = 2.dp.toPx()))
            drawCircle(main.copy(alpha = 0.10f), radius = r * 0.62f, center = center, style = Stroke(width = 1.dp.toPx()))
            drawCircle(
                Brush.radialGradient(listOf(main.copy(alpha = 0.25f), Color.Transparent), center, r * 0.42f),
                radius = r * 0.42f, center = center
            )
            return@Canvas
        }

        // Alone esterno
        drawCircle(
            Brush.radialGradient(
                listOf(main.copy(alpha = 0.22f + 0.25f * heat), main.copy(alpha = 0.05f), Color.Transparent),
                center, r
            ),
            radius = r, center = center
        )
        // Onde che si allontanano (3 anelli sfasati)
        for (i in 0 until 3) {
            val p = (wave + i / 3f) % 1f
            val rr = core + (r - core) * p
            drawCircle(
                main.copy(alpha = (1f - p) * (0.30f + 0.35f * heat)),
                radius = rr, center = center,
                style = Stroke(width = (2.5f - 1.5f * p).dp.toPx())
            )
        }
        // Anello "vivo" deformato dalla voce
        val pts = 96
        val path = androidx.compose.ui.graphics.Path()
        for (k in 0..pts) {
            val a = (k.toFloat() / pts) * 2f * PI.toFloat()
            val wob = sin(a * 5 + spin) * 0.5f + sin(a * 3 - spin * 1.3f) * 0.5f
            val rr = core * (1.08f + 0.06f * lvl * wob + 0.015f * wob)
            val x = center.x + rr * kotlin.math.cos(a)
            val y = center.y + rr * sin(a)
            if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, main.copy(alpha = 0.55f), style = Stroke(width = 1.5.dp.toPx()))
        // Nucleo
        drawCircle(
            Brush.radialGradient(
                listOf(Color.White.copy(alpha = 0.85f * (0.5f + 0.5f * heat)), main, main.copy(alpha = 0.6f)),
                center = Offset(center.x - core * 0.25f, center.y - core * 0.3f),
                radius = core * 1.3f
            ),
            radius = core, center = center
        )
    }
}

// ------------------------------------------------------------ Timeline del giorno

/** Un intervallo in minuti dalla mezzanotte. */
data class Span(val fromMin: Float, val toMin: Float, val kind: Kind) {
    enum class Kind { TRANSCRIBED, PENDING, GAP }
}

/** Le 24 ore del giorno come una striscia: voce, attesa e buchi. */
@Composable
fun DayStrip(spans: List<Span>, modifier: Modifier = Modifier, nowMin: Float? = null, height: Dp = 28.dp, labels: Boolean = true) {
    val c = Life.c
    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().height(height)) {
            val w = size.width
            val h = size.height
            val rad = CornerRadius(h / 2, h / 2)
            drawRoundRect(c.track, size = Size(w, h), cornerRadius = rad)
            // tacche delle ore
            for (hr in 1 until 24) {
                val x = w * hr / 24f
                drawLine(
                    c.muted.copy(alpha = if (hr % 6 == 0) 0.35f else 0.12f),
                    Offset(x, h * 0.3f), Offset(x, h * 0.7f), strokeWidth = 1.dp.toPx()
                )
            }
            for (s in spans) {
                val x0 = w * s.fromMin / 1440f
                val x1 = maxOf(x0 + 2.dp.toPx(), w * s.toMin / 1440f)
                val col = when (s.kind) {
                    Span.Kind.TRANSCRIBED -> c.text
                    Span.Kind.PENDING -> c.voice
                    Span.Kind.GAP -> c.gap.copy(alpha = 0.55f)
                }
                val inset = if (s.kind == Span.Kind.GAP) h * 0.38f else h * 0.18f
                drawRoundRect(
                    col, topLeft = Offset(x0, inset), size = Size(x1 - x0, h - 2 * inset),
                    cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx())
                )
            }
            nowMin?.let {
                val x = w * it / 1440f
                drawCircle(c.ink, radius = 3.dp.toPx(), center = Offset(x, h / 2))
                drawCircle(c.ink.copy(alpha = 0.25f), radius = 7.dp.toPx(), center = Offset(x, h / 2))
            }
        }
        if (labels) {
            Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                listOf("00", "06", "12", "18", "24").forEachIndexed { i, l ->
                    Text(
                        l, style = MaterialTheme.typography.labelSmall, color = c.muted,
                        modifier = Modifier.weight(1f),
                        textAlign = when (i) { 0 -> TextAlign.Start; 4 -> TextAlign.End; else -> TextAlign.Center }
                    )
                }
            }
        }
    }
}

// ------------------------------------------------------------ Mattoncini

@Composable
fun Eyebrow(text: String, color: Color = Life.c.muted, modifier: Modifier = Modifier) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = color, modifier = modifier)
}

@Composable
fun BigStat(value: String, label: String, modifier: Modifier = Modifier, accent: Color = Life.c.ink) {
    Column(modifier) {
        Text(value, style = MaterialTheme.typography.displaySmall, color = accent)
        Text(label, style = MaterialTheme.typography.bodySmall, color = Life.c.muted)
    }
}

@Composable
fun LifeCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    tint: Color? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = Life.c
    val shape = RoundedCornerShape(28.dp)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(tint?.copy(alpha = 0.12f) ?: c.card)
            .border(1.dp, tint?.copy(alpha = 0.35f) ?: c.track.copy(alpha = 0.6f), shape)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content
    )
}

@Composable
fun Dot(color: Color, size: Dp = 8.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(color))
}

@Composable
fun Legend() {
    val c = Life.c
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        LegendItem(c.text, "trascritto")
        LegendItem(c.voice, "in attesa")
        LegendItem(c.gap, "non registrato")
    }
}

@Composable
private fun LegendItem(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Dot(color, 7.dp)
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.bodySmall, color = Life.c.muted)
    }
}

/** Pulsante principale a pillola, grande e morbido. */
@Composable
fun PillButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, filled: Boolean = true, color: Color = Life.c.voice, enabled: Boolean = true) {
    val shape = RoundedCornerShape(50)
    val bg = if (filled) color else Color.Transparent
    val fg = if (filled) Color(0xFF1A1205) else color
    Box(
        modifier
            .clip(shape)
            .background(if (enabled) bg else bg.copy(alpha = 0.3f))
            .border(1.5.dp, if (filled) Color.Transparent else color.copy(alpha = if (enabled) 0.7f else 0.25f), shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 26.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = if (enabled) fg else fg.copy(alpha = 0.5f), fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun TabIcon(res: Int, selected: Boolean) {
    Icon(
        painterResource(res), contentDescription = null,
        tint = if (selected) Life.c.voice else Life.c.muted,
        modifier = Modifier.size(22.dp)
    )
}
