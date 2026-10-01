package app.lumen.reader.rich

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.lumen.reader.ui.theme.Lumen
import kotlin.math.roundToInt

@Immutable
data class ReadingTheme(
    val name: String,
    val background: Color,
    val surface: Color,
    val text: Color,
    val secondary: Color,
    val accent: Color,
    val rule: Color
)

@Composable
fun readingThemes(): List<ReadingTheme> {
    val c = Lumen.colors
    return listOf(
        ReadingTheme("Auto", c.backgroundAlt, c.surface, c.content, c.contentSecondary, c.accent, c.hairline),
        ReadingTheme("Paper", Color(0xFFF6F0E3), Color(0xFFECE3D0), Color(0xFF2F2A22), Color(0xFF6E6455), Color(0xFF9C5B2E), Color(0x22000000)),
        ReadingTheme("Night", Color(0xFF0E0F13), Color(0xFF191B22), Color(0xFFD9D6CF), Color(0xFF8F8C86), Color(0xFFE0A86A), Color(0x1FFFFFFF))
    )
}

private val imageCache = object : LruCache<String, Bitmap>(32 shl 20) {
    override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
}

@Composable
fun RichDocView(
    doc: RichDoc,
    listState: LazyListState,
    padding: PaddingValues,
    theme: ReadingTheme,
    fontScale: Float,
    onFontScale: (Float) -> Unit,
    onTap: () -> Unit
) {
    val scale by rememberUpdatedState(fontScale)
    val body = TextStyle(
        fontSize = (17 * fontScale).sp,
        lineHeight = (17 * fontScale * 1.62f).sp,
        color = theme.text
    )
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(theme.background)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    var s = scale
                    while (true) {
                        val e = awaitPointerEvent(PointerEventPass.Initial)
                        val pressed = e.changes.count { it.pressed }
                        if (pressed == 0) break
                        if (pressed >= 2) {
                            val z = e.calculateZoom()
                            if (z != 1f) {
                                s = (s * z).coerceIn(0.7f, 2.2f)
                                onFontScale(s)
                            }
                            e.changes.forEach { it.consume() }
                        }
                    }
                }
            }
            .pointerInput(Unit) { detectTapGestures(onTap = { onTap() }) }
    ) {
        val columnWidth = minOf(maxWidth, 720.dp)
        val sidePad = if (maxWidth > 760.dp) 32.dp else 22.dp
        val imageWidthPx = with(LocalDensity.current) { (columnWidth - sidePad * 2).toPx().roundToInt() }
        SelectionContainer {
            LazyColumn(
                state = listState,
                contentPadding = padding,
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxSize()
            ) {
                itemsIndexed(doc.blocks, contentType = { _, b -> b::class }) { _, block ->
                    Box(Modifier.width(columnWidth).padding(horizontal = sidePad)) {
                        BlockView(block, body, theme, fontScale, imageWidthPx)
                    }
                }
                item { Spacer(Modifier.height(40.dp)) }
            }
        }
    }
}

@Composable
private fun BlockView(block: Block, body: TextStyle, theme: ReadingTheme, fontScale: Float, imageWidthPx: Int) {
    when (block) {
        is Block.Heading -> {
            val size = when (block.level) {
                1 -> 29f; 2 -> 23f; 3 -> 20f; 4 -> 18f; else -> 16.5f
            } * fontScale
            BasicText(
                block.text,
                style = body.copy(
                    fontSize = size.sp,
                    lineHeight = (size * 1.28f).sp,
                    fontWeight = if (block.level <= 2) FontWeight.Bold else FontWeight.SemiBold,
                    letterSpacing = if (block.level == 1) (-0.4).sp else 0.sp
                ),
                modifier = Modifier.padding(top = if (block.level <= 2) 26.dp else 18.dp, bottom = 8.dp)
            )
        }
        is Block.Paragraph -> BasicText(
            block.text,
            style = body.copy(
                textAlign = when (block.align) {
                    Align.Center -> TextAlign.Center
                    Align.End -> TextAlign.End
                    Align.Justify -> TextAlign.Justify
                    Align.Start -> TextAlign.Start
                }
            ),
            modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp)
        )
        is Block.ListItem -> Row(Modifier.padding(start = (block.depth * 20).dp, top = 3.dp, bottom = 3.dp)) {
            BasicText(
                block.marker,
                style = body.copy(color = theme.accent, textAlign = TextAlign.End),
                modifier = Modifier.widthIn(min = 22.dp).padding(end = 10.dp)
            )
            BasicText(block.text, style = body, modifier = Modifier.weight(1f))
        }
        is Block.Quote -> Row(
            Modifier
                .padding(vertical = 10.dp)
                .height(IntrinsicSize.Min)
        ) {
            Box(
                Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(2.dp))
                    .background(theme.accent.copy(alpha = 0.7f))
            )
            BasicText(
                block.text,
                style = body.copy(color = theme.secondary, fontStyle = FontStyle.Italic),
                modifier = Modifier.padding(start = 16.dp)
            )
        }
        is Block.Code -> Box(
            Modifier
                .padding(vertical = 10.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(theme.surface)
                .horizontalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            BasicText(
                block.text,
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = (13.5f * fontScale).sp,
                    lineHeight = (13.5f * fontScale * 1.5f).sp,
                    color = theme.text
                ),
                softWrap = false
            )
        }
        Block.Rule -> Box(
            Modifier
                .padding(vertical = 22.dp)
                .fillMaxWidth()
                .height(1.dp)
                .background(theme.rule)
        )
        is Block.Image -> RichImage(block, theme, imageWidthPx)
        is Block.Table -> TableBlock(block, body.copy(fontSize = body.fontSize * 0.88f, lineHeight = body.lineHeight * 0.88f), theme)
        is Block.Section -> Column(
            Modifier.fillMaxWidth().padding(top = 48.dp, bottom = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(Modifier.width(48.dp).height(3.dp).clip(RoundedCornerShape(2.dp)).background(theme.accent.copy(alpha = 0.6f)))
            Spacer(Modifier.height(12.dp))
            BasicText(
                "CHAPTER ${block.index}",
                style = TextStyle(fontSize = 11.sp, letterSpacing = 2.sp, color = theme.secondary, fontWeight = FontWeight.SemiBold)
            )
        }
        is Block.Slide -> SlideCard(block, body, theme, fontScale, imageWidthPx)
    }
}

@Composable
private fun SlideCard(slide: Block.Slide, body: TextStyle, theme: ReadingTheme, fontScale: Float, imageWidthPx: Int) {
    Column(
        Modifier
            .padding(vertical = 10.dp)
            .fillMaxWidth()
            .heightIn(min = 180.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(theme.surface)
            .padding(horizontal = 22.dp, vertical = 20.dp)
    ) {
        BasicText(
            "${slide.index}",
            style = TextStyle(fontSize = 12.sp, color = theme.accent, fontWeight = FontWeight.Bold)
        )
        Spacer(Modifier.height(6.dp))
        slide.title?.let {
            BasicText(
                it,
                style = body.copy(fontSize = (22 * fontScale).sp, lineHeight = (28 * fontScale).sp, fontWeight = FontWeight.Bold)
            )
            Spacer(Modifier.height(10.dp))
        }
        slide.body.forEach { b -> BlockView(b, body.copy(fontSize = body.fontSize * 0.94f), theme, fontScale, imageWidthPx) }
        if (slide.title == null && slide.body.isEmpty()) {
            BasicText("Empty slide", style = body.copy(color = theme.secondary, fontStyle = FontStyle.Italic))
        }
    }
}

@Composable
private fun RichImage(block: Block.Image, theme: ReadingTheme, maxWidthPx: Int) {
    var bitmap by remember(block.key) { mutableStateOf(imageCache.get(block.key)) }
    var failed by remember(block.key) { mutableStateOf(false) }
    val fade = remember(block.key) { Animatable(if (bitmap != null) 1f else 0f) }
    LaunchedEffect(block.key) {
        if (bitmap == null) {
            val b = block.load(maxWidthPx)
            if (b == null) failed = true else {
                imageCache.put(block.key, b)
                bitmap = b
                fade.animateTo(1f, tween(200))
            }
        }
    }
    if (failed) return
    val b = bitmap
    val aspect = if (b != null) b.width.toFloat() / b.height.coerceAtLeast(1) else 1.6f
    // Small images (icons, inline glyphs) stay small instead of being blown up.
    val density = LocalDensity.current
    val naturalWidth: Dp = if (b != null) with(density) { b.width.toDp() } else Dp.Unspecified
    Box(
        Modifier
            .padding(vertical = 12.dp)
            .fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .then(if (b != null && naturalWidth < 240.dp) Modifier.width(naturalWidth * 1.5f) else Modifier.fillMaxWidth())
                .aspectRatio(aspect.coerceIn(0.25f, 6f))
                .clip(RoundedCornerShape(12.dp))
                .background(theme.surface)
        ) {
            if (b != null) {
                Image(
                    b.asImageBitmap(), block.alt,
                    Modifier.fillMaxSize().graphicsLayer { alpha = fade.value },
                    contentScale = ContentScale.Fit
                )
            }
        }
    }
}

@Composable
private fun TableBlock(table: Block.Table, style: TextStyle, theme: ReadingTheme) {
    val cols = table.rows.maxOfOrNull { it.size } ?: 0
    if (cols == 0) return
    val widths = remember(table) {
        IntArray(cols) { c ->
            val longest = table.rows.take(200).maxOfOrNull { r -> r.getOrNull(c)?.text?.lines()?.maxOfOrNull { it.length } ?: 0 } ?: 0
            (longest * 8 + 28).coerceIn(72, 280)
        }
    }
    BoxWithConstraints(
        Modifier
            .padding(vertical = 12.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .border(1.dp, theme.rule, RoundedCornerShape(14.dp))
    ) {
        // Narrow tables stretch to the column width; wide ones scroll.
        val natural = widths.sum()
        val stretch = (maxWidth.value / natural).coerceAtLeast(1f)
        Column(Modifier.horizontalScroll(rememberScrollState())) {
            table.rows.forEachIndexed { r, row ->
                val header = table.header && r == 0
                Row(
                    Modifier
                        .height(IntrinsicSize.Min)
                        .background(if (header) theme.surface else if (r % 2 == 0) theme.surface.copy(alpha = 0.35f) else Color.Transparent)
                ) {
                    for (c in 0 until cols) {
                        Box(
                            Modifier
                                .width((widths[c] * stretch).dp)
                                .fillMaxHeight()
                                .drawBehind {
                                    if (c > 0) drawLine(theme.rule, Offset(0f, 0f), Offset(0f, size.height))
                                }
                                .padding(horizontal = 12.dp, vertical = 9.dp)
                        ) {
                            BasicText(
                                row.getOrNull(c) ?: AnnotatedString(""),
                                style = if (header) style.copy(fontWeight = FontWeight.SemiBold) else style
                            )
                        }
                    }
                }
            }
        }
    }
}

