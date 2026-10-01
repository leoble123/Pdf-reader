package app.lumen.reader.image

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.DecayAnimationSpec
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.spring
import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.util.fastAny
import androidx.compose.ui.util.fastFirstOrNull
import androidx.compose.ui.util.fastForEach
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 2D zoom/pan for a single piece of content fitted into the viewport.
 * Everything is applied as a graphicsLayer transform, so gestures never trigger relayout.
 */
@Stable
class ZoomState(val maxScale: Float = 12f) {
    var scale by mutableFloatStateOf(1f)
        private set
    var offset by mutableStateOf(Offset.Zero)
        private set
    var viewport by mutableStateOf(Size.Zero)
        internal set
    /** Where the fitted content sits inside the viewport at scale 1. */
    var fitted by mutableStateOf(Rect.Zero)
        internal set
    var isInteracting by mutableStateOf(false)
        internal set

    val isZoomed get() = scale > 1.01f

    private fun clamp(o: Offset, s: Float): Offset {
        fun axis(t: Float, start: Float, end: Float, view: Float): Float {
            val len = (end - start) * s
            return if (len <= view) (view - len) / 2f - start * s
            else t.coerceIn(view - end * s, -start * s)
        }
        if (viewport == Size.Zero) return o
        return Offset(
            axis(o.x, fitted.left, fitted.right, viewport.width),
            axis(o.y, fitted.top, fitted.bottom, viewport.height)
        )
    }

    fun zoomBy(factor: Float, focus: Offset) {
        val old = scale
        val new = (old * factor).coerceIn(0.7f, maxScale)
        val k = new / old
        scale = new
        offset = clamp(focus - (focus - offset) * k, new)
    }

    fun panBy(delta: Offset) {
        offset = clamp(offset + delta, scale)
    }

    fun reset() {
        scale = 1f
        offset = Offset.Zero
    }

    suspend fun animateTo(target: Float, focus: Offset) {
        val anim = Animatable(scale)
        anim.animateTo(target, spring(0.9f, 400f)) {
            val old = scale
            val k = value / old
            scale = value
            offset = clamp(focus - (focus - offset) * k, value)
        }
    }

    suspend fun settle() {
        if (scale < 1f) animateTo(1f, Offset(viewport.width / 2, viewport.height / 2))
    }

    suspend fun fling(v: androidx.compose.ui.unit.Velocity, decay: DecayAnimationSpec<Float>) = coroutineScope {
        launch {
            AnimationState(offset.x, v.x).animateDecay(decay) {
                val before = offset
                panBy(Offset(value - before.x, 0f))
                if (offset.x == before.x) cancelAnimation()
            }
        }
        launch {
            AnimationState(offset.y, v.y).animateDecay(decay) {
                val before = offset
                panBy(Offset(0f, value - before.y))
                if (offset.y == before.y) cancelAnimation()
            }
        }
    }

    /** The visible part of the fitted content, in normalized (0..1) content coordinates. */
    fun visibleRegion(): Rect {
        val l = (-offset.x / scale - fitted.left) / fitted.width
        val t = (-offset.y / scale - fitted.top) / fitted.height
        val r = ((viewport.width - offset.x) / scale - fitted.left) / fitted.width
        val b = ((viewport.height - offset.y) / scale - fitted.top) / fitted.height
        return Rect(max(0f, l), max(0f, t), min(1f, r), min(1f, b))
    }
}

fun Modifier.zoomLayer(state: ZoomState) = graphicsLayer {
    transformOrigin = TransformOrigin(0f, 0f)
    scaleX = state.scale
    scaleY = state.scale
    translationX = state.offset.x
    translationY = state.offset.y
}

@Composable
fun Modifier.zoomGestures(state: ZoomState, onTap: () -> Unit, doubleTapScale: Float = 3f): Modifier {
    val scope = rememberCoroutineScope()
    val decay = rememberSplineBasedDecay<Float>()
    val holder = remember { arrayOfNulls<Job>(1) }
    return this
        .pointerInput(state) {
            detectTapGestures(
                onTap = { onTap() },
                onDoubleTap = { p ->
                    scope.launch {
                        state.isInteracting = true
                        state.animateTo(if (state.isZoomed) 1f else doubleTapScale, p)
                        state.isInteracting = false
                    }
                }
            )
        }
        .pointerInput(state) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                holder[0]?.cancel()
                state.isInteracting = true
                val tracker = VelocityTracker()
                tracker.addPosition(down.uptimeMillis, down.position)
                var id = down.id
                var past = false
                var zoomAcc = 1f
                var panAcc = Offset.Zero
                var multi = false
                var count = 1
                while (true) {
                    val e = awaitPointerEvent()
                    if (e.changes.fastAny { it.isConsumed && it.positionChanged() }) break
                    val pressed = e.changes.count { it.pressed }
                    if (pressed == 0) break
                    if (pressed != count) {
                        count = pressed
                        tracker.resetTracking()
                        id = e.changes.fastFirstOrNull { it.pressed }!!.id
                    }
                    multi = pressed > 1
                    val zoom = e.calculateZoom()
                    val pan = e.calculatePan()
                    if (!past) {
                        zoomAcc *= zoom
                        panAcc += pan
                        val span = e.calculateCentroidSize(false)
                        // At 1x a one-finger drag is not ours (lets pagers / parents handle it).
                        val wantsPan = state.isZoomed && panAcc.getDistance() > viewConfiguration.touchSlop
                        if (abs(1 - zoomAcc) * span > viewConfiguration.touchSlop || wantsPan) past = true
                    }
                    if (past) {
                        val c = e.calculateCentroid(true)
                        if (zoom != 1f) state.zoomBy(zoom, c)
                        state.panBy(pan)
                        e.changes.fastForEach { if (it.positionChanged()) it.consume() }
                    }
                    e.changes.fastFirstOrNull { it.id == id }?.let { tracker.addPosition(it.uptimeMillis, it.position) }
                }
                if (past && !multi && state.isZoomed) {
                    val v = tracker.calculateVelocity()
                    holder[0] = scope.launch {
                        state.fling(v, decay)
                        state.isInteracting = false
                    }
                } else {
                    holder[0] = scope.launch {
                        state.settle()
                        state.isInteracting = false
                    }
                }
            }
        }
}
