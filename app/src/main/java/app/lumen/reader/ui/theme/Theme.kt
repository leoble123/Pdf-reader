package app.lumen.reader.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Semantic colours used across Lumen. They're derived from the Material 3 colour scheme
 * (wallpaper-based on Android 12+), so every screen follows Material You automatically.
 */
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
    val accentContainer: Color,
    val onAccentContainer: Color,
    val tertiary: Color,
    val bar: Color
)

private fun ColorScheme.toLumen(dark: Boolean) = LumenColors(
    isDark = dark,
    background = surface,
    backgroundAlt = surfaceContainerLow,
    surface = surfaceContainerHigh,
    surfaceStrong = surfaceContainerHighest,
    content = onSurface,
    contentSecondary = onSurfaceVariant,
    contentTertiary = outline,
    hairline = outlineVariant,
    accent = primary,
    onAccent = onPrimary,
    accentContainer = primaryContainer,
    onAccentContainer = onPrimaryContainer,
    tertiary = tertiary,
    bar = surfaceContainer
)

// Fallback palette for Android 11 and below (no wallpaper colours): a calm indigo.
private val LightFallback = lightColorScheme(
    primary = Color(0xFF4F5BD5), onPrimary = Color.White,
    primaryContainer = Color(0xFFDFE0FF), onPrimaryContainer = Color(0xFF0B1360),
    secondary = Color(0xFF5B5D72), secondaryContainer = Color(0xFFE0E0F9), onSecondaryContainer = Color(0xFF181A2C),
    tertiary = Color(0xFF77536D), tertiaryContainer = Color(0xFFFFD7F1), onTertiaryContainer = Color(0xFF2D1228),
    surface = Color(0xFFFBF8FF), onSurface = Color(0xFF1B1B21), onSurfaceVariant = Color(0xFF46464F),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF5F2FA), surfaceContainer = Color(0xFFEFEDF4),
    surfaceContainerHigh = Color(0xFFE9E7EF), surfaceContainerHighest = Color(0xFFE4E1E9),
    outline = Color(0xFF777680), outlineVariant = Color(0xFFC7C5D0)
)

private val DarkFallback = darkColorScheme(
    primary = Color(0xFFBCC2FF), onPrimary = Color(0xFF1F2A8E),
    primaryContainer = Color(0xFF3742BC), onPrimaryContainer = Color(0xFFDFE0FF),
    secondary = Color(0xFFC4C4DD), secondaryContainer = Color(0xFF434559), onSecondaryContainer = Color(0xFFE0E0F9),
    tertiary = Color(0xFFE6BAD7), tertiaryContainer = Color(0xFF5D3C55), onTertiaryContainer = Color(0xFFFFD7F1),
    surface = Color(0xFF131318), onSurface = Color(0xFFE4E1E9), onSurfaceVariant = Color(0xFFC7C5D0),
    surfaceContainerLowest = Color(0xFF0E0E13), surfaceContainerLow = Color(0xFF1B1B21), surfaceContainer = Color(0xFF1F1F25),
    surfaceContainerHigh = Color(0xFF2A292F), surfaceContainerHighest = Color(0xFF35343A),
    outline = Color(0xFF91909A), outlineVariant = Color(0xFF46464F)
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

private fun Typography.toLumen() = LumenType(
    display = displaySmall.copy(fontWeight = FontWeight.SemiBold),
    title = titleLarge,
    headline = titleMedium,
    body = bodyLarge.copy(fontSize = 15.sp, lineHeight = 22.sp),
    label = labelLarge,
    caption = bodySmall,
    mono = bodyMedium.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 19.sp)
)

val LocalLumenColors = staticCompositionLocalOf { DarkFallback.toLumen(true) }
val LocalLumenType = staticCompositionLocalOf { Typography().toLumen() }

object Lumen {
    val colors: LumenColors @Composable get() = LocalLumenColors.current
    val type: LumenType @Composable get() = LocalLumenType.current
}

@Composable
fun LumenTheme(
    dark: Boolean = isSystemInDarkTheme(),
    dynamic: Boolean = true,
    amoled: Boolean = false,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val base = when {
        dynamic && Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkFallback
        else -> LightFallback
    }
    val scheme = if (dark && amoled) base.copy(
        surface = Color.Black, background = Color.Black,
        surfaceContainerLowest = Color.Black, surfaceContainerLow = Color(0xFF080808), surfaceContainer = Color(0xFF101010)
    ) else base
    val typography = remember { Typography() }
    val lumenColors = remember(scheme, dark) { scheme.toLumen(dark) }
    MaterialTheme(colorScheme = scheme, typography = typography) {
        CompositionLocalProvider(
            LocalLumenColors provides lumenColors,
            LocalLumenType provides remember(typography) { typography.toLumen() },
            LocalTextSelectionColors provides TextSelectionColors(scheme.primary, scheme.primary.copy(alpha = 0.3f)),
            content = content
        )
    }
}
