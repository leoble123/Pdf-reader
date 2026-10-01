package app.lumen.reader.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

@Immutable
data class LumenColors(
    val isDark: Boolean,
    val background: Color,
    val backgroundAlt: Color,
    val surface: Color,
    val surfaceStrong: Color,
    val content: Color,
    val contentSecondary: Color,
    val contentTertiary: Color,
    val hairline: Color,
    val accent: Color,
    val onAccent: Color,
    val glassTint: Color,
    val glassFallback: Color,
    val glow1: Color,
    val glow2: Color,
    val glow3: Color
)

val DarkColors = LumenColors(
    isDark = true,
    background = Color(0xFF0B0D14),
    backgroundAlt = Color(0xFF12151F),
    surface = Color(0xFF171A25),
    surfaceStrong = Color(0xFF1F2330),
    content = Color(0xFFF2F3F8),
    contentSecondary = Color(0xFFB4B8C7),
    contentTertiary = Color(0xFF7A7F91),
    hairline = Color(0x1FFFFFFF),
    accent = Color(0xFF8B7CFF),
    onAccent = Color.White,
    glassTint = Color(0xFF1A1D2A).copy(alpha = 0.38f),
    glassFallback = Color(0xFF1C1F2C).copy(alpha = 0.92f),
    glow1 = Color(0xFF5B6CFF),
    glow2 = Color(0xFFB05CFF),
    glow3 = Color(0xFFFF6F91)
)

val LightColors = LumenColors(
    isDark = false,
    background = Color(0xFFF4F5FA),
    backgroundAlt = Color(0xFFEAECF4),
    surface = Color(0xFFFFFFFF),
    surfaceStrong = Color(0xFFF0F1F7),
    content = Color(0xFF12141C),
    contentSecondary = Color(0xFF4A4F60),
    contentTertiary = Color(0xFF8A8FA0),
    hairline = Color(0x14000000),
    accent = Color(0xFF6A58FF),
    onAccent = Color.White,
    glassTint = Color.White.copy(alpha = 0.42f),
    glassFallback = Color.White.copy(alpha = 0.94f),
    glow1 = Color(0xFF7D8BFF),
    glow2 = Color(0xFFC48BFF),
    glow3 = Color(0xFFFF9DB5)
)

@Immutable
data class LumenType(
    val display: TextStyle,
    val title: TextStyle,
    val headline: TextStyle,
    val body: TextStyle,
    val label: TextStyle,
    val caption: TextStyle,
    val mono: TextStyle
)

private val Type = LumenType(
    display = TextStyle(fontSize = 34.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.6).sp),
    title = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
    headline = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    body = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
    label = TextStyle(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
    caption = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
    mono = TextStyle(fontSize = 13.sp, lineHeight = 19.sp, fontFamily = FontFamily.Monospace)
)

val LocalLumenColors = staticCompositionLocalOf { DarkColors }
val LocalLumenType = staticCompositionLocalOf { Type }

object Lumen {
    val colors: LumenColors @Composable get() = LocalLumenColors.current
    val type: LumenType @Composable get() = LocalLumenType.current
}

@Composable
fun LumenTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors = if (dark) DarkColors else LightColors
    CompositionLocalProvider(
        LocalLumenColors provides colors,
        LocalLumenType provides Type,
        LocalTextSelectionColors provides TextSelectionColors(colors.accent, colors.accent.copy(alpha = 0.3f)),
        content = content
    )
}
