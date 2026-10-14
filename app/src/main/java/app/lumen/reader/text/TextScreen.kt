package app.lumen.reader.text

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.WrapText
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Search
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import app.lumen.reader.core.DocRef
import app.lumen.reader.core.FileKind
import app.lumen.reader.core.formatBytes
import app.lumen.reader.ui.components.AppIconButton
import app.lumen.reader.ui.components.Panel
import app.lumen.reader.ui.theme.Lumen
import app.lumen.reader.viewer.CenteredMessage
import app.lumen.reader.viewer.LoadingIndicator
import app.lumen.reader.viewer.TopBarHeight
import app.lumen.reader.viewer.ViewerScaffold
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private class TextDoc(
    val raw: String,
    val lines: List<String>,
    val comments: BooleanArray,
    val lang: Lang,
    val longest: Int,
    val truncated: Boolean,
    val charset: String
)

@Composable
fun TextScreen(ref: DocRef, onBack: () -> Unit, startEditing: Boolean = false, onSaved: () -> Unit = {}) {
    val context = LocalContext.current
    var doc by remember { mutableStateOf<TextDoc?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var editing by rememberSaveable { mutableStateOf(startEditing) }
    var version by remember { mutableIntStateOf(0) }
    val isCode = ref.kind == FileKind.Code
    LaunchedEffect(ref, version) {
        runCatching {
            val loaded = TextLoader.load(context, ref)
            withContext(Dispatchers.Default) {
                val lines = TextLoader.splitLines(loaded.text)
                val lang = if (isCode) Highlighter.langFor(ref.extension.ifEmpty { ref.name.lowercase() }) else Lang.Plain
                TextDoc(
                    loaded.text, lines, Highlighter.blockCommentStarts(lines, lang), lang,
                    lines.maxOfOrNull { it.length } ?: 0, loaded.truncated, loaded.charset
                )
            }
        }.onSuccess { doc = it }.onFailure { error = it.message ?: "Unreadable file" }
    }
    val d = doc
    when {
        error != null -> ViewerScaffold(ref, ref.name, null, true, onBack) { CenteredMessage("Couldn't read this file", error) }
        d == null -> ViewerScaffold(ref, ref.name, null, true, onBack) { LoadingIndicator() }
        editing && !d.truncated -> TextEditor(
            ref = ref,
            initial = d.raw,
            charset = d.charset,
            lang = d.lang,
            mono = isCode || ref.kind == FileKind.Markdown,
            onClose = { saved ->
                editing = false
                if (saved) {
                    version++
                    onSaved()
                }
                if (startEditing) onBack()
            }
        )
        else -> TextReader(ref, d, isCode, onBack, onEdit = if (d.truncated) null else ({ editing = true }))
    }
}

@Composable
private fun TextReader(ref: DocRef, doc: TextDoc, isCode: Boolean, onBack: () -> Unit, onEdit: (() -> Unit)?) {
    val colors = Lumen.colors
    val type = Lumen.type
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var chrome by rememberSaveable { mutableStateOf(true) }
    var wrap by rememberSaveable { mutableStateOf(!isCode) }
    var fontSize by rememberSaveable { mutableFloatStateOf(if (isCode) 13f else 16f) }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var matches by remember { mutableStateOf(emptyList<Pair<Int, Int>>()) }
    var active by remember { mutableIntStateOf(0) }

    LaunchedEffect(query, searching) {
        matches = emptyList(); active = 0
        val q = query
        if (!searching || q.isEmpty()) return@LaunchedEffect
        delay(180)
        matches = withContext(Dispatchers.Default) {
            val out = ArrayList<Pair<Int, Int>>()
            doc.lines.forEachIndexed { i, l ->
                var idx = l.indexOf(q, ignoreCase = true)
                while (idx >= 0 && out.size < 50_000) {
                    out += i to idx
                    idx = l.indexOf(q, idx + q.length, ignoreCase = true)
                }
            }
            out
        }
        val first = listState.firstVisibleItemIndex
        active = matches.indexOfFirst { it.first >= first }.coerceAtLeast(0)
        matches.getOrNull(active)?.let { listState.scrollToItem((it.first - 3).coerceAtLeast(0)) }
    }
    fun step(delta: Int) {
        if (matches.isEmpty()) return
        active = (active + delta).mod(matches.size)
        scope.launch { listState.animateScrollToItem((matches[active].first - 3).coerceAtLeast(0)) }
    }

    val subtitle = buildString {
        append(if (isCode) ref.extension.uppercase().ifEmpty { "Code" } else "Text")
        append(" · ${"%,d".format(doc.lines.size)} lines · ${doc.charset}")
        if (doc.truncated) append(" · first ${formatBytes(TextLoader.MAX_BYTES.toLong())}")
    }

    ViewerScaffold(
        ref = ref,
        title = ref.name,
        subtitle = subtitle,
        chromeVisible = chrome || searching,
        onBack = { if (searching) searching = false else onBack() },
        titleSlot = if (searching) {
            {
                FindField(query, { query = it }, if (query.isEmpty()) null else if (matches.isEmpty()) "0" else "${active + 1}/${matches.size}")
            }
        } else null,
        actions = {
            if (searching) {
                AppIconButton(Icons.Rounded.KeyboardArrowUp, "Previous", { step(-1) })
                AppIconButton(Icons.Rounded.KeyboardArrowDown, "Next", { step(1) })
            } else {
                if (onEdit != null) AppIconButton(Icons.Rounded.Edit, "Edit", onEdit)
                AppIconButton(Icons.Rounded.Search, "Find", { searching = true })
                AppIconButton(
                    Icons.AutoMirrored.Rounded.WrapText, "Wrap lines", { wrap = !wrap },
                    iconTint = if (wrap) colors.accent else Color.Unspecified
                )
            }
        }
    ) { padding ->
        val activeMatch = matches.getOrNull(active)
        CodeLines(
            doc = doc,
            listState = listState,
            padding = padding,
            wrap = wrap,
            mono = isCode,
            fontSize = fontSize,
            onFontSize = { fontSize = it },
            query = if (searching) query else "",
            activeMatch = activeMatch,
            onTap = { chrome = !chrome }
        )
    }
}

@Composable
private fun CodeLines(
    doc: TextDoc,
    listState: LazyListState,
    padding: PaddingValues,
    wrap: Boolean,
    mono: Boolean,
    fontSize: Float,
    onFontSize: (Float) -> Unit,
    query: String,
    activeMatch: Pair<Int, Int>?,
    onTap: () -> Unit
) {
    val colors = Lumen.colors
    val palette = if (colors.isDark) DarkCode else LightCode
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val style = TextStyle(
        fontSize = fontSize.sp,
        lineHeight = (fontSize * (if (mono) 1.5f else 1.6f)).sp,
        fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
        color = palette.plain
    )
    val gutterDigits = doc.lines.size.toString().length
    val charWidth = remember(fontSize) {
        measurer.measure("0000000000", style.copy(fontFamily = FontFamily.Monospace)).size.width / 10f
    }
    val gutter: Dp = if (mono) with(density) { (charWidth * gutterDigits).toDp() } + 18.dp else 20.dp
    val highlightBg = Color(0xFFFFD60A).copy(alpha = 0.35f)
    val activeBg = Color(0xFFFF9F0A).copy(alpha = 0.6f)
    var currentSize by remember { mutableFloatStateOf(fontSize) }
    currentSize = fontSize

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            // Two-finger pinch adjusts the text size; single-finger drags fall through to scrolling.
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    while (true) {
                        val e = awaitPointerEvent(PointerEventPass.Initial)
                        val pressed = e.changes.count { it.pressed }
                        if (pressed == 0) break
                        if (pressed >= 2) {
                            val z = e.calculateZoom()
                            if (z != 1f) {
                                val next = (currentSize * z).coerceIn(9f, 30f)
                                currentSize = next
                                onFontSize(next)
                            }
                            e.changes.forEach { it.consume() }
                        }
                    }
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(onTap = { onTap() })
            }
    ) {
        val contentWidth = if (wrap) maxWidth else maxOf(
            maxWidth,
            gutter + with(density) { (charWidth * minOf(doc.longest, 4000)).toDp() } + 32.dp
        )
        val hScroll = rememberScrollState()
        Box(
            Modifier
                .fillMaxSize()
                .then(if (wrap) Modifier else Modifier.horizontalScroll(hScroll))
        ) {
            SelectionContainer {
                LazyColumn(
                    state = listState,
                    contentPadding = padding,
                    modifier = Modifier.width(contentWidth).fillMaxSize()
                ) {
                    items(doc.lines.size) { i ->
                        val line = doc.lines[i]
                        val base = remember(line, doc.lang, palette, i) {
                            Highlighter.highlight(line, doc.lang, palette, doc.comments.getOrElse(i) { false })
                        }
                        val text = if (query.isNotEmpty() && line.contains(query, ignoreCase = true)) {
                            withMatches(base, line, query, highlightBg, activeBg, activeMatch?.takeIf { it.first == i }?.second)
                        } else base
                        Row(Modifier.fillMaxWidth()) {
                            if (mono) BasicText(
                                (i + 1).toString(),
                                style = style.copy(
                                    color = colors.contentTertiary.copy(alpha = 0.7f),
                                    fontFamily = FontFamily.Monospace,
                                    textAlign = TextAlign.End,
                                    fontSize = (fontSize * 0.85f).sp
                                ),
                                modifier = Modifier.width(gutter - 12.dp)
                            ) else Box(Modifier.width(gutter - 12.dp))
                            Box(Modifier.width(12.dp))
                            BasicText(
                                text,
                                style = style,
                                softWrap = wrap,
                                modifier = Modifier.padding(end = 16.dp)
                            )
                        }
                    }
                    item { Box(Modifier.height(24.dp)) }
                }
            }
        }
    }
}

private fun withMatches(
    base: AnnotatedString,
    line: String,
    q: String,
    bg: Color,
    activeBg: Color,
    activeStart: Int?
): AnnotatedString {
    val b = AnnotatedString.Builder(base)
    var idx = line.indexOf(q, ignoreCase = true)
    while (idx >= 0) {
        b.addStyle(SpanStyle(background = if (idx == activeStart) activeBg else bg), idx, idx + q.length)
        idx = line.indexOf(q, idx + q.length, ignoreCase = true)
    }
    return b.toAnnotatedString()
}

@Composable
fun FindField(query: String, onQuery: (String) -> Unit, status: String?) {
    val colors = Lumen.colors
    val type = Lumen.type
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Panel(Modifier.height(TopBarHeight).fillMaxWidth()) {
        Row(
            Modifier.fillMaxSize().padding(start = 18.dp, end = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) BasicText("Find", style = type.label.copy(color = colors.contentTertiary))
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
            if (status != null) BasicText(status, style = type.caption.copy(color = colors.contentSecondary))
            if (query.isNotEmpty()) {
                Image(
                    Icons.Rounded.Close, "Clear",
                    Modifier.size(18.dp).clickable { onQuery("") },
                    colorFilter = ColorFilter.tint(colors.contentSecondary)
                )
            }
        }
    }
}
