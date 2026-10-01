package app.lumen.reader.pdf

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import app.lumen.reader.core.DocRef
import app.lumen.reader.core.Recents
import app.lumen.reader.ui.glass.GlassButton
import app.lumen.reader.ui.glass.GlassIconButton
import app.lumen.reader.ui.glass.GlassPanel
import app.lumen.reader.ui.theme.Lumen
import app.lumen.reader.viewer.BottomPill
import app.lumen.reader.viewer.CenteredMessage
import app.lumen.reader.viewer.LoadingIndicator
import app.lumen.reader.viewer.TopBarHeight
import app.lumen.reader.viewer.ViewerScaffold
import com.kyant.backdrop.Backdrop
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private sealed interface PdfLoad {
    data object Loading : PdfLoad
    data class Ready(val doc: PdfDoc) : PdfLoad
    data class NeedsPassword(val wrong: Boolean) : PdfLoad
    data class Failed(val message: String) : PdfLoad
}

@Composable
fun PdfScreen(ref: DocRef, recents: Recents, onBack: () -> Unit) {
    val context = LocalContext.current
    var password by remember { mutableStateOf<String?>(null) }
    var load by remember { mutableStateOf<PdfLoad>(PdfLoad.Loading) }

    LaunchedEffect(ref, password) {
        load = PdfLoad.Loading
        load = try {
            PdfLoad.Ready(PdfDoc.open(context, ref, password))
        } catch (_: PdfPasswordRequired) {
            PdfLoad.NeedsPassword(wrong = password != null)
        } catch (e: Exception) {
            PdfLoad.Failed(e.message ?: "This PDF couldn't be opened")
        }
    }
    val current = load
    if (current is PdfLoad.Ready) {
        androidx.compose.runtime.DisposableEffect(current.doc) { onDispose { current.doc.close() } }
    }

    when (current) {
        is PdfLoad.Ready -> PdfReader(ref, current.doc, recents, onBack)
        else -> ViewerScaffold(ref, ref.name, "PDF", chromeVisible = true, onBack = onBack) {
            when (current) {
                PdfLoad.Loading -> LoadingIndicator()
                is PdfLoad.Failed -> CenteredMessage("Couldn't open this PDF", current.message)
                is PdfLoad.NeedsPassword -> PasswordPrompt(current.wrong) { password = it }
            }
        }
    }
}

@Composable
private fun PasswordPrompt(wrong: Boolean, onSubmit: (String) -> Unit) {
    val colors = Lumen.colors
    val type = Lumen.type
    var text by remember { mutableStateOf("") }
    val supported = android.os.Build.VERSION.SDK_INT >= 35
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        androidx.compose.foundation.Image(
            Icons.Rounded.Lock, null, Modifier.size(44.dp),
            colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(colors.accent)
        )
        Spacer(Modifier.height(16.dp))
        BasicText("Password protected", style = type.title.copy(color = colors.content))
        Spacer(Modifier.height(6.dp))
        BasicText(
            when {
                !supported -> "Opening protected PDFs needs Android 15 or newer."
                wrong -> "That password didn't work. Try again."
                else -> "Enter the password to unlock this document."
            },
            style = type.body.copy(color = if (wrong) colors.glow3 else colors.contentSecondary)
        )
        if (supported) {
            Spacer(Modifier.height(22.dp))
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onSubmit(text) }),
                textStyle = type.headline.copy(color = colors.content),
                cursorBrush = SolidColor(colors.accent),
                modifier = Modifier
                    .widthIn(max = 360.dp)
                    .fillMaxWidth()
                    .clip(CircleShape)
                    .drawBehind { drawRect(colors.surfaceStrong) }
                    .padding(horizontal = 20.dp, vertical = 14.dp)
            )
            Spacer(Modifier.height(14.dp))
            BasicText(
                "Unlock",
                style = type.label.copy(color = colors.onAccent),
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable { onSubmit(text) }
                    .drawBehind { drawRect(colors.accent) }
                    .padding(horizontal = 28.dp, vertical = 12.dp)
            )
        }
    }
}

@OptIn(kotlinx.coroutines.FlowPreview::class)
@Composable
private fun PdfReader(ref: DocRef, doc: PdfDoc, recents: Recents, onBack: () -> Unit) {
    val context = LocalContext.current
    val colors = Lumen.colors
    val type = Lumen.type
    val scope = rememberCoroutineScope()
    val state = remember(doc) {
        PdfViewerState(recents.positionOf(ref).coerceIn(0, maxOf(0, doc.pageCount - 1)))
    }
    var chrome by rememberSaveable { mutableStateOf(true) }
    var theme by rememberSaveable { mutableStateOf(PageTheme.Light) }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val hits = remember(doc) { mutableStateMapOf<Int, SearchHit>() }
    var hitOrder by remember { mutableStateOf(listOf<Pair<Int, Int>>()) }
    var activeHit by remember { mutableIntStateOf(-1) }
    var searchProgress by remember { mutableFloatStateOf(0f) }
    var goTo by remember { mutableStateOf(false) }

    LaunchedEffect(doc) { doc.measurePages() }
    LaunchedEffect(doc) {
        snapshotFlow { state.currentPage }.debounce(400).collect { recents.savePosition(ref, it) }
    }
    // Hide chrome as soon as the reader starts moving through pages.
    LaunchedEffect(doc) {
        snapshotFlow { state.listState.isScrollInProgress || state.isZoomed }.collect { moving ->
            if (moving && !searching && !goTo) chrome = false
        }
    }
    LaunchedEffect(query, searching) {
        hits.clear(); hitOrder = emptyList(); activeHit = -1; searchProgress = 0f
        if (!searching || query.trim().length < 2) return@LaunchedEffect
        delay(250)
        val start = state.currentPage
        val order = ArrayList<Pair<Int, Int>>()
        for (k in 0 until doc.pageCount) {
            // Search outward from where the reader is so nearby results show up first.
            val page = (start + k) % doc.pageCount
            doc.search(page, query.trim())?.let { hit ->
                hits[page] = hit
                hit.rects.indices.forEach { order += page to it }
                if (activeHit < 0) {
                    activeHit = 0
                    hitOrder = order.toList()
                    state.jumpTo(page)
                }
            }
            if (k % 8 == 0) {
                searchProgress = (k + 1f) / doc.pageCount
                hitOrder = order.sortedWith(compareBy({ it.first }, { it.second }))
            }
        }
        hitOrder = order.sortedWith(compareBy({ it.first }, { it.second }))
        activeHit = hitOrder.indexOfFirst { it.first >= start }.coerceAtLeast(0)
        searchProgress = 1f
    }

    fun stepHit(delta: Int) {
        if (hitOrder.isEmpty()) return
        activeHit = ((activeHit + delta) % hitOrder.size + hitOrder.size) % hitOrder.size
        scope.launch { state.jumpTo(hitOrder[activeHit].first) }
    }

    val pageLabel = "${state.currentPage + 1} / ${doc.pageCount}"

    ViewerScaffold(
        ref = ref,
        title = ref.name,
        subtitle = "PDF · ${doc.pageCount} pages",
        chromeVisible = chrome || searching,
        onBack = { if (searching) searching = false else onBack() },
        titleSlot = if (searching) {
            { backdrop ->
                SearchField(
                    backdrop, query, { query = it },
                    status = when {
                        query.trim().length < 2 -> null
                        hitOrder.isEmpty() && searchProgress >= 1f -> "No results"
                        hitOrder.isEmpty() -> "Searching…"
                        else -> "${activeHit + 1}/${hitOrder.size}" + if (searchProgress < 1f) "…" else ""
                    }
                )
            }
        } else null,
        actions = { backdrop ->
            if (searching) {
                GlassIconButton(Icons.Rounded.KeyboardArrowUp, "Previous", backdrop, { stepHit(-1) })
                GlassIconButton(Icons.Rounded.KeyboardArrowDown, "Next", backdrop, { stepHit(1) })
            } else {
                if (doc.supportsText) {
                    GlassIconButton(Icons.Rounded.Search, "Search", backdrop, { searching = true })
                }
                GlassIconButton(Icons.Rounded.Palette, "Page color", backdrop, {
                    theme = PageTheme.entries[(theme.ordinal + 1) % PageTheme.entries.size]
                })
            }
        },
        bottomBar = { backdrop ->
            BottomPill(backdrop, Modifier.clickable(interactionSource = null, indication = null) { goTo = !goTo }) {
                BasicText(
                    pageLabel,
                    style = type.label.copy(color = colors.content),
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                BasicText(
                    theme.label,
                    style = type.caption.copy(color = colors.contentSecondary),
                    modifier = Modifier.padding(end = 14.dp)
                )
            }
        },
        overlay = { backdrop ->
            PageScrubber(backdrop, state, doc.pageCount, visible = chrome || state.isInteracting || state.listState.isScrollInProgress)
            AnimatedVisibility(
                goTo,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = fadeIn() + scaleIn(initialScale = 0.92f),
                exit = fadeOut() + scaleOut(targetScale = 0.92f)
            ) {
                GoToPanel(backdrop, state.currentPage, doc.pageCount) { page ->
                    scope.launch { state.jumpTo(page) }
                }
            }
        }
    ) { padding ->
        PdfViewer(
            doc = doc,
            state = state,
            theme = theme,
            contentPadding = padding,
            hits = hits,
            activeHit = hitOrder.getOrNull(activeHit),
            onTap = {
                if (goTo) goTo = false else chrome = !chrome
            },
            onLink = { link ->
                when {
                    link.page != null -> scope.launch { state.jumpTo(link.page) }
                    link.url != null -> runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, link.url.toUri()))
                    }
                }
            }
        )
    }
}

@Composable
private fun SearchField(backdrop: Backdrop, query: String, onQuery: (String) -> Unit, status: String?) {
    val colors = Lumen.colors
    val type = Lumen.type
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    GlassPanel(backdrop, Modifier.height(TopBarHeight).fillMaxWidth()) {
        Row(Modifier.fillMaxSize().padding(start = 18.dp, end = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) {
                    BasicText("Find in document", style = type.label.copy(color = colors.contentTertiary))
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQuery,
                    singleLine = true,
                    textStyle = type.label.copy(color = colors.content),
                    cursorBrush = SolidColor(colors.accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus)
                )
            }
            if (status != null) {
                BasicText(status, style = type.caption.copy(color = colors.contentSecondary))
            }
            if (query.isNotEmpty()) {
                androidx.compose.foundation.Image(
                    Icons.Rounded.Close, "Clear",
                    Modifier.padding(start = 8.dp).size(18.dp).clickable { onQuery("") },
                    colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(colors.contentSecondary)
                )
            }
        }
    }
}

@Composable
private fun BoxScope.PageScrubber(backdrop: Backdrop, state: PdfViewerState, pageCount: Int, visible: Boolean) {
    if (pageCount < 3) return
    val colors = Lumen.colors
    val type = Lumen.type
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    var trackHeight by remember { mutableFloatStateOf(1f) }
    var keepAlive by remember { mutableStateOf(false) }
    LaunchedEffect(visible, dragging) {
        if (visible || dragging) keepAlive = true else {
            delay(1200); keepAlive = false
        }
    }
    val alpha by animateFloatAsState(if (keepAlive || dragging) 1f else 0f, label = "scrub")
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + TopBarHeight + 28.dp
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 84.dp
    val fraction = if (dragging) dragFraction else state.currentPage / (pageCount - 1f)
    val page = (fraction * (pageCount - 1)).roundToInt()
    val thumbH = 44.dp

    // Invisible ruler that measures the travel distance.
    Box(
        Modifier
            .align(Alignment.TopEnd)
            .padding(top = top, bottom = bottom)
            .fillMaxHeight()
            .width(1.dp)
            .onSizeChanged { trackHeight = it.height.toFloat() }
    )
    Row(
        Modifier
            .align(Alignment.TopEnd)
            .padding(top = top, end = 6.dp)
            .offset { IntOffset(0, ((trackHeight - thumbH.toPx()) * fraction).roundToInt()) }
            .graphicsLayer { this.alpha = alpha },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (dragging) {
            GlassPanel(backdrop, Modifier.height(36.dp)) {
                BasicText(
                    "${page + 1}",
                    style = type.label.copy(color = colors.content),
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                )
            }
        }
        Box(
            Modifier
                .size(width = 28.dp, height = thumbH)
                .pointerInput(pageCount, keepAlive) {
                    if (!keepAlive) return@pointerInput
                    var start = 0f
                    var acc = 0f
                    detectVerticalDragGestures(
                        onDragStart = {
                            start = state.currentPage / (pageCount - 1f)
                            acc = 0f
                            dragFraction = start
                            dragging = true
                        },
                        onDragEnd = { dragging = false },
                        onDragCancel = { dragging = false }
                    ) { change, dy ->
                        change.consume()
                        acc += dy
                        val travel = (trackHeight - thumbH.toPx()).coerceAtLeast(1f)
                        dragFraction = (start + acc / travel).coerceIn(0f, 1f)
                        val p = (dragFraction * (pageCount - 1)).roundToInt()
                        if (p != state.listState.firstVisibleItemIndex) state.listState.requestScrollToItem(p)
                    }
                },
            contentAlignment = Alignment.CenterEnd
        ) {
            GlassPanel(backdrop, Modifier.size(width = 10.dp, height = thumbH)) {}
        }
    }
}

@Composable
private fun GoToPanel(backdrop: Backdrop, current: Int, pageCount: Int, onGo: (Int) -> Unit) {
    val colors = Lumen.colors
    val type = Lumen.type
    var value by remember { mutableFloatStateOf(current.toFloat()) }
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    GlassPanel(
        backdrop,
        Modifier
            .padding(start = 16.dp, end = 16.dp, bottom = bottom + 78.dp)
            .widthIn(max = 520.dp)
            .fillMaxWidth(),
        cornerRadius = 28.dp
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                BasicText("Page ", style = type.headline.copy(color = colors.contentSecondary))
                BasicText("${value.roundToInt() + 1}", style = type.title.copy(color = colors.content))
                BasicText(" of $pageCount", style = type.headline.copy(color = colors.contentSecondary))
            }
            GlassSlider(value, 0f, (pageCount - 1).toFloat(), onChange = {
                value = it
                onGo(it.roundToInt())
            })
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf("First" to 0, "Middle" to pageCount / 2, "Last" to pageCount - 1).forEach { (label, p) ->
                    GlassButton(backdrop, onClick = { value = p.toFloat(); onGo(p) }, height = 40.dp) {
                        BasicText(label, style = type.label.copy(color = colors.content))
                    }
                }
            }
        }
    }
}

@Composable
private fun GlassSlider(value: Float, min: Float, max: Float, onChange: (Float) -> Unit) {
    val colors = Lumen.colors
    var width by remember { mutableFloatStateOf(1f) }
    val f = if (max > min) (value - min) / (max - min) else 0f
    Box(
        Modifier
            .fillMaxWidth()
            .height(32.dp)
            .onSizeChangedCompat(horizontal = true) { width = it }
            .pointerInput(min, max) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    fun emit(x: Float) = onChange(min + (x / width).coerceIn(0f, 1f) * (max - min))
                    emit(down.position.x)
                    while (true) {
                        val e = awaitPointerEvent()
                        val c = e.changes.firstOrNull() ?: break
                        if (!c.pressed) break
                        c.consume()
                        emit(c.position.x)
                    }
                }
            }
            .drawBehind {
                val h = 6.dp.toPx()
                val y = size.height / 2
                drawRoundRect(
                    colors.hairline.copy(alpha = 0.35f),
                    topLeft = androidx.compose.ui.geometry.Offset(0f, y - h / 2),
                    size = androidx.compose.ui.geometry.Size(size.width, h),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(h / 2)
                )
                drawRoundRect(
                    colors.accent,
                    topLeft = androidx.compose.ui.geometry.Offset(0f, y - h / 2),
                    size = androidx.compose.ui.geometry.Size(size.width * f, h),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(h / 2)
                )
                drawCircle(androidx.compose.ui.graphics.Color.White, 12.dp.toPx(), androidx.compose.ui.geometry.Offset(size.width * f, y))
                drawCircle(colors.accent.copy(alpha = 0.25f), 12.dp.toPx(), androidx.compose.ui.geometry.Offset(size.width * f, y), style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
            }
    )
}

private fun Modifier.onSizeChangedCompat(horizontal: Boolean = false, block: (Float) -> Unit) =
    onSizeChanged { block(if (horizontal) it.width.toFloat() else it.height.toFloat()) }
