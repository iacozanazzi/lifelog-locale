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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import it.iaco.lifelog.R

/** Un solo carattere per tutta l'app: Schibsted Grotesk (licenza OFL). */
val Grotesk = FontFamily(
    Font(R.font.schibsted_400, FontWeight.Normal),
    Font(R.font.schibsted_500, FontWeight.Medium),
    Font(R.font.schibsted_700, FontWeight.Bold),
    Font(R.font.schibsted_800, FontWeight.ExtraBold),
    Font(R.font.schibsted_900, FontWeight.Black),
)

/**
 * Direzione "Parole": il contenuto sono le frasi che hai detto.
 * Bianco (o nero vero), testo forte, un unico accento blu usato solo per
 * stato e azioni. Niente decorazioni.
 */
@Immutable
data class Ink(
    val bg: Color,
    val text: Color,
    val second: Color,   // testo secondario
    val faint: Color,    // frasi vecchie, separatori forti
    val hair: Color,     // linee sottili
    val fill: Color,     // campi e barra in basso
    val accent: Color,
    val onText: Color,   // testo sopra un pulsante pieno
)

private val LightInk = Ink(
    bg = Color(0xFFFFFFFF), text = Color(0xFF111111), second = Color(0xFF85858B),
    faint = Color(0xFFC7C7CC), hair = Color(0xFFEBEBEF), fill = Color(0xFFF2F2F6),
    accent = Color(0xFF2B35E8), onText = Color.White,
)

private val DarkInk = Ink(
    bg = Color(0xFF000000), text = Color(0xFFF5F5F7), second = Color(0xFF8E8E93),
    faint = Color(0xFF4A4A4E), hair = Color(0xFF242426), fill = Color(0xFF1C1C1E),
    accent = Color(0xFF7C84FF), onText = Color.Black,
)

val LocalInk = staticCompositionLocalOf { LightInk }

object Life {
    val c: Ink @Composable get() = LocalInk.current
}

/** Scala tipografica: pochi passi, ben distinti. */
object Type {
    val largeTitle = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight.Black, fontSize = 40.sp, lineHeight = 42.sp, letterSpacing = (-0.02).em)
    val lyric = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight.ExtraBold, fontSize = 30.sp, lineHeight = 35.sp, letterSpacing = (-0.015).em)
    val section = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp, lineHeight = 27.sp, letterSpacing = (-0.01).em)
    val rowTitle = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight.Bold, fontSize = 18.sp, lineHeight = 23.sp)
    val body = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight.Medium, fontSize = 19.sp, lineHeight = 26.sp)
    val text = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 22.sp)
    val meta = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 20.sp)
    val time = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight.Bold, fontSize = 13.sp, lineHeight = 17.sp)
    val tab = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight.Bold, fontSize = 13.sp, lineHeight = 16.sp)
}

@Composable
fun LifeTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val ink = if (dark) DarkInk else LightInk
    val base = if (dark) darkColorScheme() else lightColorScheme()
    val scheme = base.copy(
        primary = ink.accent, onPrimary = ink.onText,
        background = ink.bg, onBackground = ink.text,
        surface = ink.bg, onSurface = ink.text,
        surfaceVariant = ink.fill, onSurfaceVariant = ink.second,
        outline = ink.hair, outlineVariant = ink.hair,
    )
    val t = Typography().let { d ->
        Typography(
            bodyLarge = d.bodyLarge.copy(fontFamily = Grotesk),
            bodyMedium = d.bodyMedium.copy(fontFamily = Grotesk),
            bodySmall = d.bodySmall.copy(fontFamily = Grotesk),
            labelLarge = d.labelLarge.copy(fontFamily = Grotesk),
        )
    }
    CompositionLocalProvider(LocalInk provides ink) {
        MaterialTheme(colorScheme = scheme, typography = t, content = content)
    }
}
