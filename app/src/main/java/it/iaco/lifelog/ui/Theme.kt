package it.iaco.lifelog.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import it.iaco.lifelog.R

/** Carattere da titolo: Instrument Serif (licenza OFL). */
val Serif = FontFamily(
    Font(R.font.instrument_serif, FontWeight.Normal, FontStyle.Normal),
    Font(R.font.instrument_serif_italic, FontWeight.Normal, FontStyle.Italic),
)

/** Colori "di senso" dell'app, oltre a quelli di Material. */
@Immutable
data class LifeColors(
    val voice: Color,      // ambra: voce, ascolto, da trascrivere
    val text: Color,       // menta: già trascritto
    val gap: Color,        // corallo: buchi di registrazione, errori
    val ink: Color,        // testo principale
    val muted: Color,      // testo secondario
    val track: Color,      // binari, separatori
    val card: Color,
    val cardHigh: Color,
    val glow: Color,
)

private val DarkLife = LifeColors(
    voice = Color(0xFFF4B860),
    text = Color(0xFF7FD6C2),
    gap = Color(0xFFFF7A6B),
    ink = Color(0xFFEDEAE3),
    muted = Color(0xFF9A9AA6),
    track = Color(0xFF262A33),
    card = Color(0xFF15171D),
    cardHigh = Color(0xFF1C1F27),
    glow = Color(0xFFFF9F43),
)

private val LightLife = LifeColors(
    voice = Color(0xFFC27A0E),
    text = Color(0xFF1E8C77),
    gap = Color(0xFFD2483A),
    ink = Color(0xFF1B1A1F),
    muted = Color(0xFF6E6C75),
    track = Color(0xFFE6E0D4),
    card = Color(0xFFFFFFFF),
    cardHigh = Color(0xFFFBF7F0),
    glow = Color(0xFFF4A340),
)

val LocalLife = staticCompositionLocalOf { DarkLife }

object Life {
    val c: LifeColors @Composable get() = LocalLife.current
}

private val base = Typography()
private val AppTypography = Typography(
    displayLarge = TextStyle(fontFamily = Serif, fontSize = 64.sp, lineHeight = 64.sp, letterSpacing = (-0.02).em),
    displayMedium = TextStyle(fontFamily = Serif, fontSize = 48.sp, lineHeight = 50.sp, letterSpacing = (-0.01).em),
    displaySmall = TextStyle(fontFamily = Serif, fontSize = 36.sp, lineHeight = 40.sp),
    headlineLarge = TextStyle(fontFamily = Serif, fontSize = 32.sp, lineHeight = 36.sp),
    headlineMedium = TextStyle(fontFamily = Serif, fontSize = 28.sp, lineHeight = 32.sp),
    headlineSmall = TextStyle(fontFamily = Serif, fontSize = 24.sp, lineHeight = 28.sp),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    bodyLarge = base.bodyLarge.copy(lineHeight = 26.sp),
    bodyMedium = base.bodyMedium.copy(lineHeight = 22.sp),
    labelSmall = base.labelSmall.copy(letterSpacing = 0.12.em, fontWeight = FontWeight.SemiBold),
)

@Composable
fun LifeTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val life = if (dark) DarkLife else LightLife
    val scheme = if (dark) darkColorScheme(
        primary = life.voice,
        onPrimary = Color(0xFF241500),
        secondary = life.text,
        onSecondary = Color(0xFF00201A),
        error = life.gap,
        background = Color(0xFF0B0C10),
        onBackground = life.ink,
        surface = Color(0xFF0B0C10),
        onSurface = life.ink,
        surfaceVariant = life.cardHigh,
        onSurfaceVariant = life.muted,
        outline = life.track,
        outlineVariant = life.track,
        surfaceContainer = life.card,
        surfaceContainerHigh = life.cardHigh,
        surfaceContainerHighest = Color(0xFF242833),
    ) else lightColorScheme(
        primary = life.voice,
        onPrimary = Color.White,
        secondary = life.text,
        onSecondary = Color.White,
        error = life.gap,
        background = Color(0xFFF6F2EA),
        onBackground = life.ink,
        surface = Color(0xFFF6F2EA),
        onSurface = life.ink,
        surfaceVariant = life.cardHigh,
        onSurfaceVariant = life.muted,
        outline = life.track,
        outlineVariant = life.track,
        surfaceContainer = life.card,
        surfaceContainerHigh = life.cardHigh,
        surfaceContainerHighest = Color(0xFFEFE9DE),
    )
    CompositionLocalProvider(LocalLife provides life) {
        MaterialTheme(colorScheme = scheme, typography = AppTypography, content = content)
    }
}
