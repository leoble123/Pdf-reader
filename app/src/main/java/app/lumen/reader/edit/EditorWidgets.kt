package app.lumen.reader.edit

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import app.lumen.reader.ui.theme.Lumen
import kotlin.math.abs

/** Studio palette: editors stay dark regardless of system theme, like pro tools do. */
object Studio {
    val background: Color @Composable get() = MaterialTheme.colorScheme.surfaceContainerLowest
    val panel: Color @Composable get() = MaterialTheme.colorScheme.surfaceContainerHigh
    val text: Color @Composable get() = MaterialTheme.colorScheme.onSurface
    val textDim: Color @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant
    val accent: Color @Composable get() = MaterialTheme.colorScheme.primary
    val track: Color @Composable get() = MaterialTheme.colorScheme.surfaceContainerHighest

    val swatches = listOf(
        0xFFFFFFFF, 0xFF000000, 0xFFFF453A, 0xFFFF9F0A, 0xFFFFD60A, 0xFF32D74B,
        0xFF64D2FF, 0xFF0A84FF, 0xFFBF5AF2, 0xFFFF375F
    ).map { it.toInt() }
}

/**
 * Slider with an optional centre detent (for ±adjustments). Drags are relative, so grabbing
 * anywhere never makes the value jump; double tap resets.
 */
@Composable
fun StudioSlider(
    value: Float,
    onChange: (Float) -> Unit,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
    min: Float = -1f,
    max: Float = 1f,
    center: Float? = 0f
) {
    val haptics = LocalHapticFeedback.current
    var width by remember { mutableFloatStateOf(1f) }
    val current by rememberUpdatedState(value)
    val fraction = ((value - min) / (max - min)).coerceIn(0f, 1f)
    val track = Studio.track
    val accent = Studio.accent
    val dim = Studio.textDim
    val thumb = MaterialTheme.colorScheme.primary
    Box(
        modifier
            .fillMaxWidth()
            .height(40.dp)
            .onSizeChanged { width = it.width.toFloat() }
            .pointerInput(min, max) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    var v = current
                    var lastX = down.position.x
                    var moved = false
                    val downTime = down.uptimeMillis
                    while (true) {
                        val e = awaitPointerEvent()
                        val c = e.changes.firstOrNull() ?: break
                        if (!c.pressed) {
                            if (!moved && c.uptimeMillis - downTime < 250 && center != null && abs(c.position.x - down.position.x) < 8) {
                                // tap: jump to tapped position
                                onChange(min + (c.position.x / width).coerceIn(0f, 1f) * (max - min))
                            }
                            break
                        }
                        val dx = c.position.x - lastX
                        lastX = c.position.x
                        if (dx != 0f) {
                            moved = true
                            val before = v
                            v = (v + dx / width * (max - min)).coerceIn(min, max)
                            if (center != null && (before - center) * (v - center) < 0f) {
                                v = center
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            }
                            onChange(v)
                            c.consume()
                        }
                    }
                    onFinished()
                }
            }
            .drawBehind {
                val h = 4.dp.toPx()
                val y = size.height / 2
                drawRoundRect(track, Offset(0f, y - h / 2), Size(size.width, h), CornerRadius(h / 2))
                val start = if (center != null) ((center - min) / (max - min)) * size.width else 0f
                val end = fraction * size.width
                drawRoundRect(
                    accent,
                    Offset(minOf(start, end), y - h / 2),
                    Size(abs(end - start), h),
                    CornerRadius(h / 2)
                )
                if (center != null) drawCircle(dim, 2.5.dp.toPx(), Offset(start, y + 10.dp.toPx()))
                drawCircle(thumb, 10.dp.toPx(), Offset(end, y))
            }
    )
}

@Composable
fun ToolTab(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Image(icon, label, Modifier.size(22.dp), colorFilter = ColorFilter.tint(if (selected) Studio.accent else Studio.text))
        BasicText(label, style = Lumen.type.caption.copy(color = if (selected) Studio.accent else Studio.textDim))
    }
}

@Composable
fun Chip(label: String, selected: Boolean, onClick: () -> Unit, dot: Boolean = false) {
    androidx.compose.material3.FilterChip(
        selected = selected,
        onClick = onClick,
        label = { androidx.compose.material3.Text(label) },
        trailingIcon = if (dot && !selected) ({ Box(Modifier.size(6.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary)) }) else null
    )
}

@Composable
fun ChipRow(content: @Composable () -> Unit) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) { content() }
}

@Composable
fun ColorDots(selected: Int, onPick: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Studio.swatches.forEach { c ->
            val sel = c == selected
            Box(
                Modifier
                    .size(if (sel) 32.dp else 26.dp)
                    .clip(CircleShape)
                    .background(Color(c))
                    .border(if (sel) 3.dp else 1.dp, if (sel) Studio.accent else Color.White.copy(alpha = 0.3f), CircleShape)
                    .clickable { onPick(c) }
            )
        }
    }
}

@Composable
fun StudioIconButton(icon: ImageVector, label: String, onClick: () -> Unit, enabled: Boolean = true, tint: Color = Studio.text) {
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.08f))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Image(icon, label, Modifier.size(22.dp), colorFilter = ColorFilter.tint(if (enabled) tint else Studio.textDim.copy(alpha = 0.5f)))
    }
}
