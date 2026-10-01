package app.lumen.reader.pdf

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.DecayAnimationSpec
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.spring
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Zoom/pan model for the page list.
 *
 * The list itself is laid out at fit-width; zoom is a pure GPU transform (graphicsLayer) with
 * origin at the top-left. Horizontal pan is [offsetX]; vertical pan is real list scrolling
 * expressed in unscaled content pixels. Pinching therefore never re-measures or re-composes
 * anything – it only moves a layer – which is why it stays at full frame rate.
 */
@Stable
class PdfViewerState(initialPage: Int) {

    val listState = LazyListState(firstVisibleItemIndex = initialPage)

    var scale by mutableFloatStateOf(1f)
        private set
    var offsetX by mutableFloatStateOf(0f)
        private set
    var viewportWidth by mutableFloatStateOf(0f)
        internal set
    var viewportHeight by mutableFloatStateOf(0f)
        internal set

    /** True while a finger is down or a fling/zoom animation runs. */
    var isInteracting by mutableStateOf(false)
        internal set

    val isZoomed: Boolean get() = scale > 1.01f

    val currentPage: Int by derivedStateOf {
        val info = listState.layoutInfo
        val visible = info.visibleItemsInfo
        if (visible.isEmpty()) return@derivedStateOf listState.firstVisibleItemIndex
        // The page that owns the vertical middle of the *displayed* viewport.
        val probe = (viewportHeight / 2f) / scale + info.viewportStartOffset
        val hit = visible.firstOrNull { it.offset <= probe && it.offset + it.size >= probe }
            ?: visible.first()
        hit.index.coerceAtMost(maxOf(0, info.totalItemsCount - 2))
    }

    private fun clampX(x: Float): Float = x.coerceIn(minOf(0f, viewportWidth - viewportWidth * scale), 0f)

    fun zoomBy(factor: Float, focus: Offset, min: Float = MIN_SCALE, max: Float = MAX_SCALE) {
        val old = scale
        val new = (old * factor).coerceIn(min, max)
        if (new == old) return
        val k = new / old
        scale = new
        offsetX = clampX(focus.x - (focus.x - offsetX) * k)
        listState.dispatchRawDelta(focus.y / old - focus.y / new)
    }

    fun panBy(delta: Offset) {
        offsetX = clampX(offsetX + delta.x)
        if (delta.y != 0f) listState.dispatchRawDelta(-delta.y / scale)
    }

    suspend fun fling(velocity: Velocity, decay: DecayAnimationSpec<Float>) {
        isInteracting = true
        try {
            coroutineScope {
                if (isZoomed && abs(velocity.x) > 50f) {
                    launch {
                        AnimationState(offsetX, velocity.x).animateDecay(decay) {
                            val clamped = clampX(value)
                            offsetX = clamped
                            if (clamped != value) cancelAnimation()
                        }
                    }
                }
                if (abs(velocity.y) > 50f) {
                    launch {
                        listState.scroll(MutatePriority.UserInput) {
                            var last = 0f
                            AnimationState(0f, -velocity.y / scale).animateDecay(decay) {
                                val delta = value - last
                                last = value
                                val consumed = scrollBy(delta)
                                if (abs(delta - consumed) > 0.5f) cancelAnimation()
                            }
                        }
                    }
                }
            }
        } finally {
            isInteracting = false
        }
    }

    /** Animated zoom (double-tap, snap back). */
    suspend fun animateZoomTo(target: Float, focus: Offset) {
        isInteracting = true
        try {
            val anim = Animatable(scale)
            anim.animateTo(target, spring(dampingRatio = 0.9f, stiffness = 420f)) {
                zoomBy(value / scale, focus, min = 1f, max = MAX_SCALE)
            }
            if (target <= 1f) offsetX = 0f
        } finally {
            isInteracting = false
        }
    }

    suspend fun jumpTo(page: Int) {
        listState.scrollToItem(page.coerceAtLeast(0))
    }

    companion object {
        const val MIN_SCALE = 1f
        const val MAX_SCALE = 8f
    }
}
