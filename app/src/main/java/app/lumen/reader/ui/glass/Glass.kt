package app.lumen.reader.ui.glass

import android.graphics.RuntimeShader
import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.fastFirstOrNull
import androidx.compose.ui.util.lerp
import app.lumen.reader.ui.theme.Lumen
import app.lumen.reader.ui.theme.LumenColors
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.Capsule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tanh
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.ColorFilter

private val hasRenderEffect = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/**
 * The one glass recipe used across Lumen: vibrancy + soft blur + edge refraction (lens),
 * with an opaque-ish fallback on devices that can't run RenderEffects.
 */
fun Modifier.glass(
    backdrop: Backdrop,
    colors: LumenColors,
    shape: () -> Shape = { Capsule() },
    blurRadius: Dp = 6.dp,
    refraction: Dp = 14.dp,
    refractionAmount: Dp = 28.dp,
    tint: Color = Color.Unspecified,
    highlight: GlassPress? = null
): Modifier = this.drawBackdrop(
    backdrop = backdrop,
    shape = shape,
    effects = {
        vibrancy()
        blur(blurRadius.toPx())
        lens(refraction.toPx(), refractionAmount.toPx())
    },
    highlight = { if (colors.isDark) Highlight.Default.copy(alpha = 0.55f) else Highlight.Default },
    shadow = { Shadow(radius = 20.dp, color = Color.Black.copy(alpha = if (colors.isDark) 0.28f else 0.10f)) },
    layerBlock = highlight?.let { h ->
        {
            val width = size.width
            val height = size.height
            val progress = h.pressProgress
            val scale = lerp(1f, 1f + 4.dp.toPx() / size.height, progress)
            val maxOffset = size.minDimension
            val offset = h.offset
            translationX = maxOffset * tanh(0.05f * offset.x / maxOffset)
            translationY = maxOffset * tanh(0.05f * offset.y / maxOffset)
            val maxDragScale = 4.dp.toPx() / size.height
            val angle = atan2(offset.y, offset.x)
            scaleX = scale + maxDragScale * abs(cos(angle) * offset.x / size.maxDimension) *
                (width / height).coerceAtMost(1f)
            scaleY = scale + maxDragScale * abs(sin(angle) * offset.y / size.maxDimension) *
                (height / width).coerceAtMost(1f)
        }
    },
    onDrawSurface = {
        if (!hasRenderEffect) drawRect(colors.glassFallback)
        drawRect(colors.glassTint)
        if (tint.isSpecified) {
            drawRect(tint, blendMode = BlendMode.Hue)
            drawRect(tint.copy(alpha = 0.7f))
        }
    }
)

/**
 * Press feedback for glass: a spring "squish" toward the finger plus a soft light bloom.
 * Adapted from the Backdrop catalog's InteractiveHighlight (Apache-2.0, Kyant).
 */
class GlassPress(private val scope: CoroutineScope) {

    private val pressSpec = spring(0.5f, 300f, 0.001f)
    private val positionSpec = spring(0.5f, 300f, Offset.VisibilityThreshold)
    private val press = Animatable(0f, 0.001f)
    private val position = Animatable(Offset.Zero, Offset.VectorConverter, Offset.VisibilityThreshold)
    private var start = Offset.Zero

    val pressProgress: Float get() = press.value
    val offset: Offset get() = position.value - start

    private val shader = if (Build.VERSION.SDK_INT >= 33) RuntimeShader(
        """
uniform float2 size;
layout(color) uniform half4 color;
uniform float radius;
uniform float2 position;
half4 main(float2 coord) {
    float dist = distance(coord, position);
    return color * smoothstep(radius, radius * 0.5, dist);
}"""
    ) else null

    val drawModifier: Modifier = Modifier.drawWithContent {
        val p = press.value
        if (p > 0f) {
            drawRect(Color.White.copy(0.08f * p), blendMode = BlendMode.Plus)
            if (shader != null && Build.VERSION.SDK_INT >= 33) {
                shader.setFloatUniform("size", size.width, size.height)
                shader.setColorUniform("color", android.graphics.Color.valueOf(1f, 1f, 1f, 0.15f * p).pack())
                shader.setFloatUniform("radius", size.minDimension * 1.5f)
                shader.setFloatUniform(
                    "position",
                    position.value.x.fastCoerceIn(0f, size.width),
                    position.value.y.fastCoerceIn(0f, size.height)
                )
                drawRect(ShaderBrush(shader), blendMode = BlendMode.Plus)
            } else {
                drawRect(Color.White.copy(0.12f * p), blendMode = BlendMode.Plus)
            }
        }
        drawContent()
    }

    val gestureModifier: Modifier = Modifier.pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            start = down.position
            scope.launch {
                launch { press.animateTo(1f, pressSpec) }
                launch { position.snapTo(start) }
            }
            var id = down.id
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.fastFirstOrNull { it.id == id } ?: break
                if (!change.pressed) break
                scope.launch { position.snapTo(change.position) }
                id = change.id
            }
            scope.launch {
                launch { press.animateTo(0f, pressSpec) }
                launch { position.animateTo(start, positionSpec) }
            }
        }
    }
}

@Composable
fun rememberGlassPress(): GlassPress {
    val scope = rememberCoroutineScope()
    return remember(scope) { GlassPress(scope) }
}

@Composable
fun GlassIconButton(
    icon: ImageVector,
    contentDescription: String?,
    backdrop: Backdrop,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    tint: Color = Color.Unspecified,
    iconTint: Color = Color.Unspecified
) {
    val colors = Lumen.colors
    val press = rememberGlassPress()
    Box(
        modifier
            .size(size)
            .glass(backdrop, colors, tint = tint, highlight = press)
            .clickable(interactionSource = null, indication = null, role = Role.Button, onClick = onClick)
            .then(press.drawModifier)
            .then(press.gestureModifier),
        contentAlignment = Alignment.Center
    ) {
        Image(
            icon,
            contentDescription,
            Modifier.size(22.dp),
            colorFilter = ColorFilter.tint(if (iconTint.isSpecified) iconTint else colors.content)
        )
    }
}

@Composable
fun GlassButton(
    backdrop: Backdrop,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Color.Unspecified,
    height: Dp = 52.dp,
    content: @Composable RowScope.() -> Unit
) {
    val colors = Lumen.colors
    val press = rememberGlassPress()
    Row(
        modifier
            .glass(backdrop, colors, tint = tint, highlight = press)
            .clickable(interactionSource = null, indication = null, role = Role.Button, onClick = onClick)
            .then(press.drawModifier)
            .then(press.gestureModifier)
            .height(height)
            .padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
        content = content
    )
}

/** A static glass container (bars, pills, panels). */
@Composable
fun GlassPanel(
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    cornerRadius: Dp? = null,
    content: @Composable () -> Unit
) {
    val colors = Lumen.colors
    Box(
        modifier.glass(
            backdrop,
            colors,
            shape = if (cornerRadius == null) ({ Capsule() }) else ({ RoundedCornerShape(cornerRadius) }),
            blurRadius = 10.dp,
            refraction = 18.dp,
            refractionAmount = 36.dp
        )
    ) { content() }
}

internal fun Size.center() = Offset(width / 2, height / 2)
