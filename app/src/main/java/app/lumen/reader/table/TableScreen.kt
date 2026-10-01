package app.lumen.reader.table

import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.lumen.reader.core.DocRef
import app.lumen.reader.core.Docs
import app.lumen.reader.core.FileKind
import app.lumen.reader.rich.Archive
import app.lumen.reader.text.TextLoader
import app.lumen.reader.ui.theme.Lumen
import app.lumen.reader.viewer.BottomPill
import app.lumen.reader.viewer.CenteredMessage
import app.lumen.reader.viewer.LoadingIndicator
import app.lumen.reader.viewer.ViewerScaffold
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

@Composable
fun TableScreen(ref: DocRef, onBack: () -> Unit) {
    val context = LocalContext.current
    var book by remember { mutableStateOf<Workbook?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(ref) {
        runCatching {
            withContext(Dispatchers.IO) {
                if (ref.kind == FileKind.Xlsx) {
                    Archive(Docs.localFile(context, ref)).use { Xlsx.parse(it) }
                } else {
                    Csv.parse(TextLoader.load(context, ref).text)
                }
            }
        }.onSuccess { book = it }.onFailure { error = it.message ?: "Unreadable table" }
    }
    val b = book
    when {
        error != null -> ViewerScaffold(ref, ref.name, null, true, onBack) { CenteredMessage("Couldn't open this table", error) }
        b == null -> ViewerScaffold(ref, ref.name, null, true, onBack) { LoadingIndicator() }
        b.sheets.isEmpty() || b.sheets.all { it.rows.isEmpty() } ->
            ViewerScaffold(ref, ref.name, null, true, onBack) { CenteredMessage("Empty", "There's no data in this file.") }
        else -> TableReader(ref, b, onBack)
    }
}

@Composable
private fun TableReader(ref: DocRef, book: Workbook, onBack: () -> Unit) {
    val colors = Lumen.colors
    val type = Lumen.type
    var sheetIndex by rememberSaveable { mutableIntStateOf(book.sheets.indexOfFirst { it.rows.isNotEmpty() }.coerceAtLeast(0)) }
    var chrome by rememberSaveable { mutableStateOf(true) }
    var firstRowHeader by rememberSaveable { mutableStateOf(true) }
    val sheet = book.sheets[sheetIndex]

    ViewerScaffold(
        ref = ref,
        title = ref.name,
        subtitle = "${"%,d".format(sheet.rows.size)} rows × ${sheet.columns} columns" + if (book.truncated) " · truncated" else "",
        chromeVisible = chrome,
        onBack = onBack,
        bottomBar = { b ->
            BottomPill(b) {
                if (book.sheets.size > 1) {
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp)) {
                        book.sheets.forEachIndexed { i, s ->
                            val sel = i == sheetIndex
                            BasicText(
                                s.name,
                                style = type.label.copy(color = if (sel) colors.onAccent else colors.content),
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .then(if (sel) Modifier.background(colors.accent) else Modifier)
                                    .clickable { sheetIndex = i }
                                    .padding(horizontal = 14.dp, vertical = 8.dp)
                            )
                        }
                    }
                } else {
                    BasicText(
                        if (firstRowHeader) "Header row on" else "Header row off",
                        style = type.label.copy(color = colors.content),
                        modifier = Modifier
                            .clip(CircleShape)
                            .clickable { firstRowHeader = !firstRowHeader }
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
            }
        }
    ) { padding ->
        Grid(sheet, padding, header = firstRowHeader && sheet.rows.size > 1, onTap = { chrome = !chrome })
    }
}

/**
 * Spreadsheet grid. Rows are virtualized by a LazyColumn; columns are virtualized by drawing
 * only the cells inside the horizontal viewport. Horizontal scrolling only invalidates drawing,
 * never composition, so even very wide sheets stay smooth.
 */
@Composable
private fun Grid(sheet: Sheet, padding: PaddingValues, header: Boolean, onTap: () -> Unit) {
    val colors = Lumen.colors
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer(cacheSize = 0)
    val scope = rememberCoroutineScope()
    val decay = rememberSplineBasedDecay<Float>()
    val listState = rememberLazyListState()
    var scrollX by remember(sheet) { mutableFloatStateOf(0f) }
    val cellStyle = TextStyle(fontSize = 13.5.sp, color = colors.content)
    val headStyle = cellStyle.copy(fontWeight = FontWeight.SemiBold)
    val gutterStyle = TextStyle(fontSize = 11.sp, color = colors.contentTertiary)

    val rowHeight = with(density) { 38.dp.toPx() }
    val padX = with(density) { 12.dp.toPx() }
    val widths = remember(sheet) {
        val probe = measurer.measure("0000000000", cellStyle).size.width / 10f
        FloatArray(sheet.columns) { c ->
            var longest = 0
            for (r in 0 until min(sheet.rows.size, 300)) longest = max(longest, sheet.rows[r].getOrNull(c)?.length ?: 0)
            (longest * probe * 1.05f + padX * 2).coerceIn(with(density) { 64.dp.toPx() }, with(density) { 300.dp.toPx() })
        }
    }
    val offsets = remember(widths) {
        FloatArray(widths.size + 1).also { for (i in widths.indices) it[i + 1] = it[i] + widths[i] }
    }
    val gutter = remember(sheet) {
        measurer.measure(sheet.rows.size.toString(), gutterStyle).size.width + padX * 1.5f
    }
    val total = offsets.last()
    val dataRows = if (header) sheet.rows.size - 1 else sheet.rows.size
    val firstData = if (header) 1 else 0

    BoxWithConstraints(Modifier.fillMaxSize().clipToBounds()) {
        val viewW = constraints.maxWidth.toFloat()
        val maxScroll = max(0f, total + gutter - viewW)
        val drag = rememberDraggableState { d -> scrollX = (scrollX - d).coerceIn(0f, maxScroll) }
        val topPad = with(density) { padding.calculateTopPadding().toPx() }

        Column(
            Modifier
                .fillMaxSize()
                .draggable(
                    drag, Orientation.Horizontal,
                    onDragStopped = { v ->
                        scope.launch {
                            AnimationState(scrollX, -v).animateDecay(decay) {
                                val c = value.coerceIn(0f, maxScroll)
                                scrollX = c
                                if (c != value) cancelAnimation()
                            }
                        }
                    }
                )
                .pointerInput(Unit) { detectTapGestures(onTap = { onTap() }) }
        ) {
            Box(Modifier.height(with(density) { topPad.toDp() }))
            if (header) {
                Canvas(Modifier.fillMaxWidth().height(with(density) { rowHeight.toDp() })) {
                    drawRow(sheet.rows[0], -1, true, scrollX, gutter, widths, offsets, measurer, headStyle, gutterStyle, padX, colors.surfaceStrong, colors.hairline, null)
                }
            }
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(bottom = padding.calculateBottomPadding()),
                modifier = Modifier.fillMaxWidth().weight(1f)
            ) {
                items(dataRows) { i ->
                    val row = sheet.rows[i + firstData]
                    val cache = remember(row) { arrayOfNulls<TextLayoutResult>(row.size) }
                    Canvas(Modifier.fillMaxWidth().height(with(density) { rowHeight.toDp() })) {
                        drawRow(
                            row, i + firstData, false, scrollX, gutter, widths, offsets, measurer, cellStyle, gutterStyle, padX,
                            if (i % 2 == 1) colors.surface.copy(alpha = 0.45f) else Color.Transparent, colors.hairline, cache
                        )
                    }
                }
            }
        }
    }
}

private fun DrawScope.drawRow(
    row: Array<String>,
    index: Int,
    isHeader: Boolean,
    scrollX: Float,
    gutter: Float,
    widths: FloatArray,
    offsets: FloatArray,
    measurer: TextMeasurer,
    style: TextStyle,
    gutterStyle: TextStyle,
    padX: Float,
    background: Color,
    line: Color,
    cache: Array<TextLayoutResult?>?
) {
    if (background != Color.Transparent) drawRect(background)
    val h = size.height
    // First visible column via binary search over the column offsets.
    var lo = 0
    var hi = widths.size - 1
    while (lo < hi) {
        val mid = (lo + hi + 1) / 2
        if (offsets[mid] <= scrollX) lo = mid else hi = mid - 1
    }
    clipRect(left = gutter) {
        var c = lo
        while (c < widths.size) {
            val x = gutter + offsets[c] - scrollX
            if (x > size.width) break
            val text = row.getOrNull(c)
            if (!text.isNullOrEmpty()) {
                val layout = cache?.getOrNull(c) ?: measurer.measure(
                    text.replace('\n', ' '), style,
                    overflow = TextOverflow.Ellipsis, maxLines = 1, softWrap = false,
                    constraints = Constraints(maxWidth = (widths[c] - padX * 2).toInt().coerceAtLeast(1))
                ).also { if (cache != null && c < cache.size) cache[c] = it }
                val numeric = text.firstOrNull()?.let { it.isDigit() || it == '-' } == true && text.toDoubleOrNull() != null
                val tx = if (numeric && !isHeader) x + widths[c] - padX - layout.size.width else x + padX
                drawText(layout, topLeft = Offset(tx, (h - layout.size.height) / 2))
            }
            drawLine(line, Offset(x + widths[c], 0f), Offset(x + widths[c], h))
            c++
        }
    }
    drawLine(line, Offset(0f, h - 0.5f), Offset(size.width, h - 0.5f))
    drawLine(line, Offset(gutter, 0f), Offset(gutter, h))
    if (index >= 0) {
        val label = measurer.measure((index + 1).toString(), gutterStyle)
        drawText(label, topLeft = Offset(gutter - padX * 0.75f - label.size.width, (h - label.size.height) / 2))
    }
}
