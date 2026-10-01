package app.lumen.reader.rich

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.FormatListBulleted
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.lumen.reader.core.DocRef
import app.lumen.reader.core.Docs
import app.lumen.reader.core.FileKind
import app.lumen.reader.core.Recents
import app.lumen.reader.text.TextLoader
import app.lumen.reader.text.TextScreen
import app.lumen.reader.ui.glass.GlassIconButton
import app.lumen.reader.ui.glass.GlassPanel
import app.lumen.reader.ui.theme.Lumen
import app.lumen.reader.viewer.BottomPill
import app.lumen.reader.viewer.CenteredMessage
import app.lumen.reader.viewer.LoadingIndicator
import app.lumen.reader.viewer.TopBarHeight
import app.lumen.reader.viewer.ViewerScaffold
import com.kyant.backdrop.Backdrop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

suspend fun loadRichDoc(context: android.content.Context, ref: DocRef): RichDoc = withContext(Dispatchers.IO) {
    when (ref.kind) {
        FileKind.Markdown -> Markdown.parse(TextLoader.load(context, ref).text)
        else -> {
            val archive = Archive(Docs.localFile(context, ref))
            try {
                when (ref.kind) {
                    FileKind.Docx -> DocxParser.parse(archive)
                    FileKind.Pptx -> PptxParser.parse(archive)
                    FileKind.Odt -> OdtParser.parse(archive)
                    FileKind.Epub -> EpubParser.parse(archive)
                    else -> error("Unsupported")
                }
            } catch (e: Throwable) {
                archive.close()
                throw e
            }
        }
    }
}

@Composable
fun RichDocScreen(ref: DocRef, recents: Recents, onBack: () -> Unit) {
    val context = LocalContext.current
    var doc by remember { mutableStateOf<RichDoc?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var showSource by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(ref) {
        runCatching { loadRichDoc(context, ref) }
            .onSuccess { doc = it }
            .onFailure { error = it.message ?: "This document couldn't be read" }
    }
    doc?.let { d -> DisposableEffect(d) { onDispose { d.resources?.close() } } }

    if (showSource && ref.kind == FileKind.Markdown) {
        TextScreen(ref.copy(kind = FileKind.Code), onBack = { showSource = false })
        return
    }
    val d = doc
    when {
        error != null -> ViewerScaffold(ref, ref.name, ref.kind.label, true, onBack) {
            CenteredMessage("Couldn't open this document", error)
        }
        d == null -> ViewerScaffold(ref, ref.name, ref.kind.label, true, onBack) { LoadingIndicator() }
        d.blocks.isEmpty() -> ViewerScaffold(ref, ref.name, ref.kind.label, true, onBack) {
            CenteredMessage("Nothing to show", "This document doesn't contain any readable text.")
        }
        else -> Reader(ref, d, recents, onBack, onSource = if (ref.kind == FileKind.Markdown) ({ showSource = true }) else null)
    }
}

@OptIn(kotlinx.coroutines.FlowPreview::class)
@Composable
private fun Reader(ref: DocRef, doc: RichDoc, recents: Recents, onBack: () -> Unit, onSource: (() -> Unit)?) {
    val colors = Lumen.colors
    val type = Lumen.type
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState(recents.positionOf(ref).coerceIn(0, doc.blocks.size))
    var chrome by rememberSaveable { mutableStateOf(true) }
    var fontScale by rememberSaveable { mutableFloatStateOf(1f) }
    var themeIndex by rememberSaveable { mutableIntStateOf(0) }
    var panel by remember { mutableStateOf<String?>(null) }
    val themes = readingThemes()
    val theme = themes[themeIndex.coerceIn(0, themes.lastIndex)]

    LaunchedEffect(Unit) {
        snapshotFlow { listState.firstVisibleItemIndex }.debounce(500).collect { recents.savePosition(ref, it) }
    }
    LaunchedEffect(Unit) {
        snapshotFlow { listState.isScrollInProgress }.collect { if (it && panel == null) chrome = false }
    }
    val progress by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            if (!listState.canScrollForward) 1f
            else if (info.totalItemsCount <= 1) 0f
            else (listState.firstVisibleItemIndex.toFloat() / (info.totalItemsCount - info.visibleItemsInfo.size).coerceAtLeast(1)).coerceIn(0f, 1f)
        }
    }

    ViewerScaffold(
        ref = ref,
        title = doc.title?.takeIf { it.isNotBlank() } ?: ref.name,
        subtitle = doc.kindLabel + if (doc.title != null) " · ${ref.name}" else "",
        chromeVisible = chrome || panel != null,
        onBack = { if (panel != null) panel = null else onBack() },
        actions = { b ->
            if (doc.toc.size > 1) {
                GlassIconButton(Icons.AutoMirrored.Rounded.FormatListBulleted, "Contents", b, {
                    panel = if (panel == "toc") null else "toc"
                })
            }
            GlassIconButton(Icons.Rounded.TextFields, "Reading options", b, {
                panel = if (panel == "aa") null else "aa"
            })
            if (onSource != null) GlassIconButton(Icons.Rounded.Code, "View source", b, onSource)
        },
        bottomBar = { b ->
            BottomPill(b) {
                Box(
                    Modifier
                        .padding(horizontal = 16.dp)
                        .size(width = 120.dp, height = 4.dp)
                        .clip(CircleShape)
                        .background(colors.hairline.copy(alpha = 0.4f))
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(progress)
                            .heightIn(min = 4.dp, max = 4.dp)
                            .clip(CircleShape)
                            .background(colors.accent)
                    )
                }
                BasicText(
                    "${(progress * 100).toInt()}%",
                    style = type.caption.copy(color = colors.contentSecondary),
                    modifier = Modifier.padding(end = 16.dp)
                )
            }
        },
        overlay = { b ->
            val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
            if (panel != null) {
                Box(Modifier.fillMaxSize().clickable(interactionSource = null, indication = null) { panel = null })
            }
            AnimatedVisibility(
                panel == "toc",
                modifier = Modifier.align(Alignment.TopEnd).padding(top = top + TopBarHeight + 20.dp, end = 12.dp, start = 12.dp),
                enter = fadeIn() + scaleIn(initialScale = 0.9f, transformOrigin = TransformOrigin(1f, 0f)),
                exit = fadeOut() + scaleOut(targetScale = 0.9f, transformOrigin = TransformOrigin(1f, 0f))
            ) {
                TocPanel(b, doc) { index ->
                    panel = null
                    scope.launch { listState.animateScrollToItem(index) }
                }
            }
            AnimatedVisibility(
                panel == "aa",
                modifier = Modifier.align(Alignment.TopEnd).padding(top = top + TopBarHeight + 20.dp, end = 12.dp),
                enter = fadeIn() + scaleIn(initialScale = 0.9f, transformOrigin = TransformOrigin(1f, 0f)),
                exit = fadeOut() + scaleOut(targetScale = 0.9f, transformOrigin = TransformOrigin(1f, 0f))
            ) {
                ReadingPanel(b, themes, themeIndex, { themeIndex = it }, fontScale) { fontScale = it }
            }
        }
    ) { padding ->
        RichDocView(
            doc = doc,
            listState = listState,
            padding = padding,
            theme = theme,
            fontScale = fontScale,
            onFontScale = { fontScale = it },
            onTap = { if (panel != null) panel = null else chrome = !chrome }
        )
    }
}

@Composable
private fun TocPanel(backdrop: Backdrop, doc: RichDoc, onJump: (Int) -> Unit) {
    val colors = Lumen.colors
    val type = Lumen.type
    GlassPanel(backdrop, Modifier.width(320.dp).heightIn(max = 460.dp), cornerRadius = 26.dp) {
        LazyColumn(Modifier.padding(vertical = 10.dp)) {
            item {
                BasicText(
                    "Contents",
                    style = type.caption.copy(color = colors.contentTertiary),
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                )
            }
            itemsIndexed(doc.toc) { _, (label, index) ->
                BasicText(
                    label,
                    style = type.label.copy(color = colors.content),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onJump(index) }
                        .padding(horizontal = 20.dp, vertical = 12.dp)
                )
            }
        }
    }
}

@Composable
private fun ReadingPanel(
    backdrop: Backdrop,
    themes: List<ReadingTheme>,
    selected: Int,
    onTheme: (Int) -> Unit,
    fontScale: Float,
    onFontScale: (Float) -> Unit
) {
    val colors = Lumen.colors
    val type = Lumen.type
    GlassPanel(backdrop, Modifier.width(280.dp), cornerRadius = 26.dp) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StepButton("A−") { onFontScale((fontScale - 0.1f).coerceAtLeast(0.7f)) }
                BasicText(
                    "${(fontScale * 100).toInt()}%",
                    style = type.label.copy(color = colors.content),
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                )
                StepButton("A+") { onFontScale((fontScale + 0.1f).coerceAtMost(2.2f)) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                themes.forEachIndexed { i, t ->
                    Column(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(16.dp))
                            .background(t.background)
                            .border(
                                if (i == selected) 2.dp else 1.dp,
                                if (i == selected) colors.accent else Color.Gray.copy(alpha = 0.3f),
                                RoundedCornerShape(16.dp)
                            )
                            .clickable { onTheme(i) }
                            .padding(vertical = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        BasicText("Aa", style = type.headline.copy(color = t.text))
                        BasicText(t.name, style = type.caption.copy(color = t.secondary))
                    }
                }
            }
        }
    }
}

@Composable
private fun StepButton(label: String, onClick: () -> Unit) {
    val colors = Lumen.colors
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(colors.content.copy(alpha = 0.08f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        BasicText(label, style = Lumen.type.label.copy(color = colors.content))
    }
}
