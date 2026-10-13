package app.lumen.reader.pdf

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastAny
import androidx.compose.ui.util.fastFirstOrNull
import androidx.compose.ui.util.fastForEach
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class PageTheme(val label: String, val paper: Color) {
    Light("Light", Color.White),
    Sepia("Sepia", Color(0xFFF4ECD8)),
    Dark("Night", Color(0xFF1A1A1E));

    val filter: ColorFilter? by lazy {
        when (this) {
            Light -> null
            Sepia -> ColorFilter.colorMatrix(
                ColorMatrix(
                    floatArrayOf(
                        0.957f, 0f, 0f, 0f, 0f,
                        0f, 0.925f, 0f, 0f, 0f,
                        0f, 0f, 0.847f, 0f, 0f,
                        0f, 0f, 0f, 1f, 0f
                    )
                )
            )
            // Invert luminance into a soft charcoal/ivory range so long reads don't glare.
            Dark -> ColorFilter.colorMatrix(
                ColorMatrix(
                    floatArrayOf(
                        -0.80f, 0f, 0f, 0f, 230f,
                        0f, -0.80f, 0f, 0f, 228f,
                        0f, 0f, -0.80f, 0f, 224f,
                        0f, 0f, 0f, 1f, 0f
                    )
                )
            )
        }
    }
}

@Immutable
private class Detail(val image: ImageBitmap, val region: NormRect, val pageWidthPx: Int)

private val PageSpacing = 12.dp
private val PageHorizontalPadding = 10.dp

@Composable
fun PdfViewer(
    doc: PdfDoc,
    state: PdfViewerState,
    theme: PageTheme,
    contentPadding: PaddingValues,
    hits: Map<Int, SearchHit>,
    activeHit: Pair<Int, Int>?,
    onTap: () -> Unit,
    onLink: (PdfLink) -> Unit,
    modifier: Modifier = Modifier,
    ink: InkController? = null,
    inkActive: Boolean = false,
    onNoteRequest: (page: Int, x: Float, y: Float) -> Unit = { _, _, _ -> }
) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val decay = rememberSplineBasedDecay<Float>()
    val scope = rememberCoroutineScope()
    val details = remember(doc) { mutableStateMapOf<Int, Detail>() }
    val links = remember(doc) { mutableStateMapOf<Int, List<PdfLink>>() }
    var flingJob by remember { mutableStateOf<Job?>(null) }

    BoxWithConstraints(modifier.fillMaxSize().clipToBounds()) {
        val widthPx = constraints.maxWidth.toFloat()
        val heightPx = constraints.maxHeight.toFloat()
        val hPadPx = with(density) { PageHorizontalPadding.toPx() }
        val pageWidthPx = (widthPx - 2 * hPadPx).roundToInt().coerceAtLeast(1)
        state.viewportWidth = widthPx
        state.viewportHeight = heightPx

        // Keep pages crisp after a zoom settles: re-render only the visible window at full res.
        LaunchedEffect(doc, pageWidthPx) {
            details.clear()
            snapshotFlow {
                DetailTrigger(
                    state.scale, state.offsetX, state.listState.firstVisibleItemIndex,
                    state.listState.firstVisibleItemScrollOffset, state.isInteracting
                )
            }.collectLatest { t ->
                if (t.interacting) return@collectLatest
                if (t.scale < 1.12f) {
                    details.clear(); return@collectLatest
                }
                delay(70)
                val info = state.listState.layoutInfo
                val vx0 = -t.offsetX / t.scale
                val vx1 = (widthPx - t.offsetX) / t.scale
                val vy1 = heightPx / t.scale
                val wanted = HashSet<Int>()
                for (item in info.visibleItemsInfo) {
                    if (item.index >= doc.pageCount) continue
                    val top = (item.offset - info.viewportStartOffset).toFloat()
                    val ph = item.size.toFloat()
                    val l = max(vx0, hPadPx) - hPadPx
                    val r = min(vx1, hPadPx + pageWidthPx) - hPadPx
                    val tp = max(0f, top) - top
                    val bt = min(vy1, top + ph) - top
                    if (r <= l || bt <= tp) continue
                    // A little bleed so small pans don't reveal the blurry base layer.
                    val bx = (r - l) * 0.08f
                    val by = (bt - tp) * 0.08f
                    val region = NormRect(
                        ((l - bx) / pageWidthPx).coerceIn(0f, 1f),
                        ((tp - by) / ph).coerceIn(0f, 1f),
                        ((r + bx) / pageWidthPx).coerceIn(0f, 1f),
                        ((bt + by) / ph).coerceIn(0f, 1f)
                    )
                    wanted += item.index
                    val bmp = doc.renderRegion(item.index, pageWidthPx * t.scale, region)
                    details[item.index] = Detail(bmp.asImageBitmap(), region, pageWidthPx)
                }
                details.keys.retainAll(wanted)
            }
        }

        // Warm the neighbours so turning a page never shows a blank sheet.
        LaunchedEffect(doc, pageWidthPx) {
            snapshotFlow { state.currentPage }.collectLatest { page ->
                delay(120)
                doc.prefetch(page + 1, pageWidthPx)
                doc.prefetch(page + 2, pageWidthPx)
                doc.prefetch(page - 1, pageWidthPx)
            }
        }

        LazyColumn(
            state = state.listState,
            userScrollEnabled = false,
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(PageSpacing),
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    transformOrigin = TransformOrigin(0f, 0f)
                    scaleX = state.scale
                    scaleY = state.scale
                    translationX = state.offsetX
                }
        ) {
            items(doc.pageCount, key = { it }) { index ->
                PdfPage(
                    doc = doc,
                    index = index,
                    widthPx = pageWidthPx,
                    theme = theme,
                    detail = details[index],
                    hit = hits[index],
                    activeRect = activeHit?.takeIf { it.first == index }?.second,
                    onLinks = { links[index] = it },
                    ink = ink,
                    measurer = measurer
                )
            }
            item(key = "end") {
                // Lets the last page scroll fully into view while zoomed in.
                Spacer(
                    Modifier
                        .fillMaxWidth()
                        .layout { m, c ->
                            val h = (state.viewportHeight * (1f - 1f / state.scale)).roundToInt().coerceAtLeast(0)
                            val p = m.measure(c.copy(minHeight = h, maxHeight = h))
                            layout(p.width, p.height) { p.place(0, 0) }
                        }
                )
            }
        }

        // Transparent gesture surface above the list: owns taps, pinch, pan and fling.
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(doc, inkActive) {
                    detectTapGestures(
                        onTap = { screen ->
                            val pos = Offset((screen.x - state.offsetX) / state.scale, screen.y / state.scale)
                            if (inkActive && ink?.tool == InkTool.Text) {
                                pageAt(state, doc, pos, hPadPx, pageWidthPx)?.let { (p, x, y) -> onNoteRequest(p, x, y) }
                                return@detectTapGestures
                            }
                            val link = hitLink(state, doc, links, pos, hPadPx, pageWidthPx)
                            if (link != null) onLink(link) else onTap()
                        },
                        onDoubleTap = { screen ->
                            scope.launch { state.animateZoomTo(if (state.isZoomed) 1f else 2.5f, screen) }
                        }
                    )
                }
                .pointerInput(doc, inkActive) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        // Markup: one finger draws, two fingers still pinch and pan.
                        val drawing = inkActive && ink != null && ink.tool != InkTool.Text
                        var inking = false
                        if (drawing) {
                            val pos = Offset((down.position.x - state.offsetX) / state.scale, down.position.y / state.scale)
                            pageAt(state, doc, pos, hPadPx, pageWidthPx)?.let { (p, x, y) ->
                                inking = true
                                if (ink.tool == InkTool.Eraser) ink.erase(p, x, y) else ink.begin(p, x, y)
                                down.consume()
                            }
                        }
                        if (inking) {
                            var stillInking = true
                            while (stillInking) {
                                val event = awaitPointerEvent()
                                val pressed = event.changes.count { it.pressed }
                                if (pressed == 0) {
                                    ink!!.end(); return@awaitEachGesture
                                }
                                if (pressed > 1) {
                                    ink!!.cancel(); stillInking = false
                                } else {
                                    val c = event.changes.first { it.pressed }
                                    val pos = Offset((c.position.x - state.offsetX) / state.scale, c.position.y / state.scale)
                                    pageAt(state, doc, pos, hPadPx, pageWidthPx)?.let { (p, x, y) ->
                                        if (ink!!.tool == InkTool.Eraser) ink.erase(p, x, y)
                                        else if (ink.live?.first == p) ink.extend(x, y)
                                    }
                                    c.consume()
                                }
                            }
                        }
                        if (flingJob?.isActive == true) {
                            flingJob?.cancel()
                            down.consume()
                        }
                        state.isInteracting = true
                        val tracker = VelocityTracker()
                        tracker.addPosition(down.uptimeMillis, down.position)
                        var trackedId = down.id
                        var pastSlop = false
                        var zoomAcc = 1f
                        var panAcc = Offset.Zero
                        var lastPressed = 1
                        var multiTouchAtEnd = false
                        val slop = viewConfiguration.touchSlop
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.changes.fastAny { it.isConsumed && it.pressed && it.positionChanged() }) break
                            val pressed = event.changes.count { it.pressed }
                            if (pressed == 0) break
                            if (pressed != lastPressed) {
                                tracker.resetTracking()
                                trackedId = event.changes.fastFirstOrNull { it.pressed }!!.id
                                lastPressed = pressed
                            }
                            multiTouchAtEnd = pressed > 1
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            if (!pastSlop) {
                                zoomAcc *= zoom
                                panAcc += pan
                                val span = event.calculateCentroidSize(useCurrent = false)
                                if (abs(1f - zoomAcc) * span > slop || panAcc.getDistance() > slop) pastSlop = true
                            }
                            if (pastSlop) {
                                val centroid = event.calculateCentroid(useCurrent = true)
                                if (zoom != 1f) state.zoomBy(zoom, centroid)
                                if (pan != Offset.Zero) state.panBy(pan)
                                event.changes.fastForEach { if (it.positionChanged()) it.consume() }
                            }
                            event.changes.fastFirstOrNull { it.id == trackedId }?.let {
                                tracker.addPosition(it.uptimeMillis, it.position)
                            }
                        }
                        state.isInteracting = false
                        if (pastSlop && !multiTouchAtEnd) {
                            val v = tracker.calculateVelocity()
                            flingJob = scope.launch { state.fling(v, decay) }
                        }
                    }
                }
        )
    }
}

private data class DetailTrigger(
    val scale: Float,
    val offsetX: Float,
    val first: Int,
    val firstOffset: Int,
    val interacting: Boolean
)

/** Screen-independent hit test: list coordinates → (page, normalized x, normalized y). */
private fun pageAt(state: PdfViewerState, doc: PdfDoc, pos: Offset, hPad: Float, pageWidth: Int): Triple<Int, Float, Float>? {
    val info = state.listState.layoutInfo
    val item = info.visibleItemsInfo.firstOrNull {
        val top = it.offset - info.viewportStartOffset
        it.index < doc.pageCount && pos.y >= top && pos.y <= top + it.size
    } ?: return null
    val top = item.offset - info.viewportStartOffset
    val nx = (pos.x - hPad) / pageWidth
    val ny = (pos.y - top) / item.size
    if (nx !in 0f..1f || ny !in 0f..1f) return null
    return Triple(item.index, nx, ny)
}

private fun hitLink(
    state: PdfViewerState,
    doc: PdfDoc,
    links: SnapshotStateMap<Int, List<PdfLink>>,
    pos: Offset, // in unscaled list coordinates
    hPad: Float,
    pageWidth: Int
): PdfLink? {
    val info = state.listState.layoutInfo
    val item = info.visibleItemsInfo.firstOrNull {
        val top = it.offset - info.viewportStartOffset
        it.index < doc.pageCount && pos.y >= top && pos.y <= top + it.size
    } ?: return null
    val top = item.offset - info.viewportStartOffset
    val nx = (pos.x - hPad) / pageWidth
    val ny = (pos.y - top) / item.size
    return links[item.index]?.firstOrNull { link ->
        link.bounds.any { nx >= it.left && nx <= it.right && ny >= it.top && ny <= it.bottom }
    }
}

@Composable
private fun PdfPage(
    doc: PdfDoc,
    index: Int,
    widthPx: Int,
    theme: PageTheme,
    detail: Detail?,
    hit: SearchHit?,
    activeRect: Int?,
    onLinks: (List<PdfLink>) -> Unit,
    ink: InkController?,
    measurer: androidx.compose.ui.text.TextMeasurer
) {
    var image by remember(index, widthPx) {
        mutableStateOf(
            (doc.cached(index, widthPx) ?: doc.cachedPreview(index))?.asImageBitmap()
        )
    }
    var isFull by remember(index, widthPx) { mutableStateOf(doc.cached(index, widthPx) != null) }
    val fade = remember(index, widthPx) { Animatable(if (image != null) 1f else 0f) }

    LaunchedEffect(index, widthPx) {
        if (!isFull) {
            if (image == null) {
                image = doc.renderPreview(index).asImageBitmap()
                launch { fade.animateTo(1f, tween(140)) }
            }
            image = doc.render(index, widthPx).asImageBitmap()
            isFull = true
            if (fade.value < 1f) fade.animateTo(1f, tween(140))
        }
    }
    LaunchedEffect(index) {
        if (doc.supportsText) onLinks(doc.links(index))
    }

    val aspect = doc.aspect(index)
    val paper = theme.paper
    val filter = theme.filter
    Box(
        Modifier
            .padding(horizontal = PageHorizontalPadding)
            .fillMaxWidth()
            .layout { m, c ->
                val w = c.maxWidth
                val h = (w / aspect).roundToInt()
                val p = m.measure(c.copy(minWidth = w, minHeight = h, maxHeight = h))
                layout(w, h) { p.place(0, 0) }
            }
            .graphicsLayer {
                shadowElevation = 3.dp.toPx()
                shape = RoundedCornerShape(3.dp)
                clip = true
            }
            .drawBehind {
                drawRect(paper)
                image?.let { img ->
                    drawImage(
                        img,
                        dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                        alpha = fade.value,
                        colorFilter = filter,
                        filterQuality = FilterQuality.Low
                    )
                }
                detail?.takeIf { it.pageWidthPx == widthPx }?.let { d ->
                    val r = d.region
                    drawImage(
                        d.image,
                        dstOffset = IntOffset((r.left * size.width).roundToInt(), (r.top * size.height).roundToInt()),
                        dstSize = IntSize(
                            ((r.right - r.left) * size.width).roundToInt(),
                            ((r.bottom - r.top) * size.height).roundToInt()
                        ),
                        colorFilter = filter,
                        filterQuality = FilterQuality.Low
                    )
                }
                hit?.let { drawHits(it, activeRect) }
                if (ink != null) {
                    ink.pages[index]?.let { drawMarks(it, measurer) }
                    ink.live?.takeIf { it.first == index }?.let { drawMarks(listOf(it.second), measurer) }
                }
            }
    )
}

private fun DrawScope.drawHits(hit: SearchHit, active: Int?) {
    hit.rects.forEachIndexed { i, r ->
        val isActive = i == active
        drawRoundRect(
            color = if (isActive) Color(0xFFFF9F0A).copy(alpha = 0.55f) else Color(0xFFFFD60A).copy(alpha = 0.38f),
            topLeft = Offset(r.left * size.width - 1.5f, r.top * size.height - 1.5f),
            size = Size((r.right - r.left) * size.width + 3f, (r.bottom - r.top) * size.height + 3f),
            cornerRadius = CornerRadius(3f)
        )
    }
}

