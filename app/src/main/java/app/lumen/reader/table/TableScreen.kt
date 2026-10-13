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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import app.lumen.reader.core.LocalPickers
import app.lumen.reader.core.Saver
import app.lumen.reader.edit.PillButton
import app.lumen.reader.edit.UnsavedChangesPanel
import app.lumen.reader.edit.toast
import app.lumen.reader.ui.glass.GlassIconButton
import app.lumen.reader.ui.glass.GlassPanel
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
    val context = LocalContext.current
    val pickers = LocalPickers.current
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var version by remember { mutableIntStateOf(0) }
    var dirty by remember { mutableStateOf(false) }
    var confirmExit by remember { mutableStateOf(false) }
    val cellText = rememberTextFieldState()
    // Editing a truncated workbook would silently drop the rows we never loaded.
    val canEdit = !book.truncated && (ref.kind == FileKind.Csv || ref.kind == FileKind.Xlsx)

    fun commitCell() {
        val sel = selected ?: return
        val old = sheet.rows.getOrNull(sel.first)?.getOrNull(sel.second) ?: ""
        val new = cellText.text.toString()
        if (new != old) {
            sheet.set(sel.first, sel.second, new)
            dirty = true
            version++
        }
    }

    fun save(thenClose: Boolean) {
        commitCell()
        scope.launch {
            val result = if (ref.kind == FileKind.Xlsx) {
                val source = Docs.localFile(context, ref)
                val bytes = withContext(Dispatchers.IO) {
                    java.io.ByteArrayOutputStream().also { XlsxWriter.write(source, book, it) }.toByteArray()
                }
                Saver.save(context, pickers, ref) { it.write(bytes) }.also {
                    if (it is Saver.Result.Saved) source.delete()
                }
            } else {
                Saver.save(context, pickers, ref) { CsvWriter.write(book.sheets[0], book.delimiter, it) }
            }
            toast(context, result)
            if (result is Saver.Result.Saved || result is Saver.Result.SavedCopy) {
                dirty = false
                book.sheets.forEach { it.edits.clear() }
                if (thenClose) onBack()
            }
        }
    }

    fun exit() {
        if (dirty) confirmExit = true else onBack()
    }
    androidx.activity.compose.BackHandler(editing || dirty) {
        when {
            selected != null -> { commitCell(); selected = null }
            editing && !dirty -> editing = false
            else -> exit()
        }
    }

    ViewerScaffold(
        ref = ref,
        title = ref.name,
        subtitle = (if (dirty) "Edited · " else "") + "${"%,d".format(sheet.rows.size)} rows × ${sheet.columns} columns" + if (book.truncated) " · truncated" else "",
        chromeVisible = chrome || editing,
        onBack = ::exit,
        actions = { b ->
            if (canEdit) {
                if (editing) {
                    GlassIconButton(Icons.Rounded.Add, "Add row", b, {
                        commitCell()
                        sheet.addRow()
                        selected = sheet.rows.lastIndex to 0
                        cellText.setTextAndPlaceCursorAtEnd("")
                        dirty = true
                        version++
                    })
                    GlassIconButton(Icons.Rounded.Check, "Save", b, { save(false) }, tint = colors.accent, iconTint = Color.White)
                } else {
                    GlassIconButton(Icons.Rounded.Edit, "Edit", b, { editing = true })
                }
            }
        },
        overlay = { b ->
            val sel = selected
            AnimatedVisibility(
                editing && sel != null,
                modifier = Modifier.align(Alignment.BottomCenter).imePadding().padding(bottom = 90.dp, start = 12.dp, end = 12.dp),
                enter = fadeIn() + slideInVertically { it / 2 },
                exit = fadeOut() + slideOutVertically { it / 2 }
            ) {
                if (sel != null) CellEditor(b, XlsxWriter.columnName(sel.second) + (sel.first + 1), cellText) {
                    commitCell(); selected = null
                }
            }
            UnsavedChangesPanel(
                b, confirmExit,
                onSave = { confirmExit = false; save(true) },
                onDiscard = { confirmExit = false; onBack() },
                onCancel = { confirmExit = false }
            )
        },
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
        Grid(
            sheet, padding, header = firstRowHeader && sheet.rows.size > 1, onTap = { chrome = !chrome },
            version = version,
            selected = if (editing) selected else null,
            onCellTap = if (editing) { r, c ->
                commitCell()
                selected = r to c
                cellText.setTextAndPlaceCursorAtEnd(sheet.rows.getOrNull(r)?.getOrNull(c) ?: "")
            } else null
        )
    }
}

@Composable
private fun CellEditor(backdrop: com.kyant.backdrop.Backdrop, label: String, state: TextFieldState, onDone: () -> Unit) {
    val colors = Lumen.colors
    val type = Lumen.type
    val focus = remember { FocusRequester() }
    LaunchedEffect(label) { focus.requestFocus() }
    GlassPanel(backdrop, Modifier.widthIn(max = 560.dp).fillMaxWidth(), cornerRadius = 24.dp) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            BasicText(
                label,
                style = type.label.copy(color = colors.accent),
                modifier = Modifier.padding(horizontal = 10.dp)
            )
            BasicTextField(
                state = state,
                lineLimits = TextFieldLineLimits.SingleLine,
                textStyle = type.body.copy(color = colors.content),
                cursorBrush = SolidColor(colors.accent),
                onKeyboardAction = { onDone() },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(colors.content.copy(alpha = 0.07f))
                    .padding(horizontal = 12.dp, vertical = 10.dp)
                    .focusRequester(focus)
            )
            PillButton("Done", Modifier.padding(start = 8.dp), onClick = onDone)
        }
    }
}

/**
 * Spreadsheet grid. Rows are virtualized by a LazyColumn; columns are virtualized by drawing
 * only the cells inside the horizontal viewport. Horizontal scrolling only invalidates drawing,
 * never composition, so even very wide sheets stay smooth.
 */
@Composable
private fun Grid(
    sheet: Sheet,
    padding: PaddingValues,
    header: Boolean,
    onTap: () -> Unit,
    version: Int = 0,
    selected: Pair<Int, Int>? = null,
    onCellTap: ((Int, Int) -> Unit)? = null
) {
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
    val widths = remember(sheet, sheet.columns) {
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
                .pointerInput(onCellTap, header, maxScroll) {
                    detectTapGestures(onTap = { pos ->
                        val cellTap = onCellTap ?: return@detectTapGestures onTap()
                        if (pos.x < gutter) return@detectTapGestures
                        val headerH = if (header) rowHeight else 0f
                        val row = if (header && pos.y >= topPad && pos.y < topPad + headerH) 0 else {
                            val y = pos.y - topPad - headerH
                            listState.layoutInfo.visibleItemsInfo
                                .firstOrNull { y >= it.offset && y < it.offset + it.size }?.index?.plus(firstData)
                        } ?: return@detectTapGestures
                        val x = scrollX + pos.x - gutter
                        val col = (0 until widths.size).firstOrNull { x >= offsets[it] && x < offsets[it + 1] } ?: return@detectTapGestures
                        cellTap(row, col)
                    })
                }
        ) {
            Box(Modifier.height(with(density) { topPad.toDp() }))
            if (header) {
                Canvas(Modifier.fillMaxWidth().height(with(density) { rowHeight.toDp() })) {
                    @Suppress("UNUSED_VARIABLE") val v = version
                    drawRow(sheet.rows[0], -1, true, scrollX, gutter, widths, offsets, measurer, headStyle, gutterStyle, padX, colors.surfaceStrong, colors.hairline, null, selected?.takeIf { it.first == 0 }?.second ?: -1, colors.accent)
                }
            }
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(bottom = padding.calculateBottomPadding()),
                modifier = Modifier.fillMaxWidth().weight(1f)
            ) {
                items(dataRows) { i ->
                    @Suppress("UNUSED_VARIABLE") val v = version
                    val row = sheet.rows[i + firstData]
                    val cache = remember(row) { arrayOfNulls<TextLayoutResult>(row.size) }
                    Canvas(Modifier.fillMaxWidth().height(with(density) { rowHeight.toDp() })) {
                        drawRow(
                            row, i + firstData, false, scrollX, gutter, widths, offsets, measurer, cellStyle, gutterStyle, padX,
                            if (i % 2 == 1) colors.surface.copy(alpha = 0.45f) else Color.Transparent, colors.hairline, cache,
                            selected?.takeIf { it.first == i + firstData }?.second ?: -1, colors.accent
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
    cache: Array<TextLayoutResult?>?,
    selectedCol: Int = -1,
    accent: Color = Color.Unspecified
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
    if (selectedCol in widths.indices) {
        val x = gutter + offsets[selectedCol] - scrollX
        clipRect(left = gutter) {
            drawRect(accent.copy(alpha = 0.16f), Offset(x, 0f), androidx.compose.ui.geometry.Size(widths[selectedCol], h))
            drawRect(
                accent, Offset(x + 1f, 1f), androidx.compose.ui.geometry.Size(widths[selectedCol] - 2f, h - 2f),
                style = androidx.compose.ui.graphics.drawscope.Stroke(2.5f)
            )
        }
    }
    drawLine(line, Offset(0f, h - 0.5f), Offset(size.width, h - 0.5f))
    drawLine(line, Offset(gutter, 0f), Offset(gutter, h))
    if (index >= 0) {
        val label = measurer.measure((index + 1).toString(), gutterStyle)
        drawText(label, topLeft = Offset(gutter - padX * 0.75f - label.size.width, (h - label.size.height) / 2))
    }
}
