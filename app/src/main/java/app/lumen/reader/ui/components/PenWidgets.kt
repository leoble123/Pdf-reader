package app.lumen.reader.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** A rainbow strip; drag along it to pick any hue. */
@Composable
fun HueStrip(onPick: (Int) -> Unit, modifier: Modifier = Modifier) {
    val hues = remember { List(13) { Color.hsv(it * 30f % 360f, 0.85f, 0.92f) } }
    Canvas(
        modifier
            .fillMaxWidth()
            .height(28.dp)
            .clip(RoundedCornerShape(14.dp))
            .pointerInput(Unit) {
                fun pick(x: Float) {
                    val t = (x / size.width).coerceIn(0f, 1f)
                    // Last 8% of the strip runs through greys down to black.
                    val c = if (t > 0.92f) Color.hsv(0f, 0f, 1f - (t - 0.92f) / 0.08f)
                    else Color.hsv(t / 0.92f * 360f % 360f, 0.85f, 0.92f)
                    onPick(c.toArgb())
                }
                awaitEachGesture {
                    val down = awaitFirstDown()
                    pick(down.position.x)
                    while (true) {
                        val e = awaitPointerEvent()
                        val c = e.changes.first()
                        if (!c.pressed) break
                        pick(c.position.x); c.consume()
                    }
                }
            }
    ) {
        val split = size.width * 0.92f
        drawRect(Brush.horizontalGradient(hues, endX = split), size = size.copy(width = split))
        drawRect(Brush.horizontalGradient(listOf(Color.White, Color.Black), startX = split, endX = size.width), topLeft = Offset(split, 0f), size = size.copy(width = size.width - split))
    }
}

/** A short squiggle drawn at the real on-screen thickness, so you know what a size means. */
@Composable
fun StrokePreview(color: Color, widthPx: Float, highlighter: Boolean, modifier: Modifier = Modifier) {
    Canvas(modifier.size(56.dp, 32.dp)) {
        val path = Path().apply {
            moveTo(size.width * 0.12f, size.height * 0.65f)
            cubicTo(size.width * 0.35f, size.height * 0.05f, size.width * 0.6f, size.height * 0.95f, size.width * 0.88f, size.height * 0.35f)
        }
        drawPath(
            path,
            if (highlighter) color.copy(alpha = 0.45f) else color,
            style = Stroke(widthPx.coerceIn(1f, size.height * 0.8f), cap = if (highlighter) StrokeCap.Square else StrokeCap.Round)
        )
    }
}

/** Brief "Undo"/"Redo" confirmation for gestures, so an invisible two-finger tap gets feedback. */
@Stable
class GestureHud {
    var message by mutableStateOf<Pair<String, ImageVector?>?>(null)
        private set
    var serial by mutableIntStateOf(0)
        private set

    fun show(text: String, icon: ImageVector? = null) {
        message = text to icon
        serial++
    }

    internal fun hide() {
        message = null
    }
}

@Composable
fun BoxScope.GestureHudView(hud: GestureHud, modifier: Modifier = Modifier) {
    LaunchedEffect(hud.serial) {
        if (hud.message == null) return@LaunchedEffect
        delay(if ((hud.message?.first?.length ?: 0) > 16) 2600 else 900)
        hud.hide()
    }
    var last by remember { mutableStateOf<Pair<String, ImageVector?>?>(null) }
    hud.message?.let { last = it }
    AnimatedVisibility(
        hud.message != null,
        modifier = modifier.align(Alignment.TopCenter),
        enter = fadeIn() + scaleIn(initialScale = 0.85f),
        exit = fadeOut() + scaleOut(targetScale = 0.9f)
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.inverseSurface,
            contentColor = MaterialTheme.colorScheme.inverseOnSurface,
            shadowElevation = 6.dp
        ) {
            Row(Modifier.padding(horizontal = 18.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                last?.second?.let {
                    Image(it, null, Modifier.size(18.dp).padding(end = 0.dp), colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.inverseOnSurface))
                    Box(Modifier.size(8.dp))
                }
                Text(last?.first.orEmpty(), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}
