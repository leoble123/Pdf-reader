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
import app.lumen.reader.core.Docs
import androidx.compose.runtime.key
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import app.lumen.reader.core.LocalPickers
import app.lumen.reader.core.Saver
import app.lumen.reader.edit.PillButton
import app.lumen.reader.edit.toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.BorderColor
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.Draw
import androidx.compose.material.icons.rounded.ViewModule
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.SaveAlt
import androidx.compose.material.icons.rounded.PostAdd
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.ui.graphics.Color
import app.lumen.reader.core.Recents
import app.lumen.reader.ui.components.AppIconButton
import app.lumen.reader.ui.components.Panel
import app.lumen.reader.ui.components.GestureHudView
import app.lumen.reader.ui.theme.Lumen
import app.lumen.reader.viewer.BottomPill
import app.lumen.reader.viewer.CenteredMessage
import app.lumen.reader.viewer.LoadingIndicator
import app.lumen.reader.viewer.TopBarHeight
import app.lumen.reader.viewer.ViewerScaffold
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
    var reload by remember { mutableIntStateOf(0) }
    var resumeAt by remember { mutableStateOf<Int?>(null) }
    var load by remember { mutableStateOf<PdfLoad>(PdfLoad.Loading) }

    LaunchedEffect(ref, password, reload) {
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
        is PdfLoad.Ready -> key(current.doc) { PdfReader(ref, current.doc, recents, onBack, resumeAt, onReload = { page -> resumeAt = page; reload++ }) }
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
            style = type.body.copy(color = if (wrong) androidx.compose.material3.MaterialTheme.colorScheme.error else colors.contentSecondary)
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
private fun PdfReader(ref: DocRef, doc: PdfDoc, recents: Recents, onBack: () -> Unit, resumeAt: Int?, onReload: (resumePage: Int?) -> Unit) {
    val context = LocalContext.current
    val colors = Lumen.colors
    val type = Lumen.type
    val scope = rememberCoroutineScope()
    val state = remember(doc) {
        PdfViewerState((resumeAt ?: recents.positionOf(ref)).coerceIn(0, maxOf(0, doc.pageCount - 1)))
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
    val ink = remember(ref) { InkController.forDoc(context, ref) }
    val pickers = LocalPickers.current
    // Notebooks (blank paper made by Lumen) open straight into drawing.
    val notebook = remember(doc) { app.lumen.reader.notebook.Notebooks.info(context, ref) }
    var markup by rememberSaveable { mutableStateOf(notebook != null) }
    var note by remember { mutableStateOf<Triple<Int, Float, Float>?>(null) }
    var exportProgress by remember { mutableStateOf<Float?>(null) }
    val settings = app.lumen.reader.core.LocalSettings.current
    val hud = remember { app.lumen.reader.ui.components.GestureHud() }
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    fun undoInk() {
        if (ink.undo()) {
            haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.Confirm)
            hud.show("Undo", Icons.AutoMirrored.Rounded.Undo)
        } else hud.show("Nothing to undo")
    }
    fun redoInk() {
        if (ink.redo()) {
            haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.Confirm)
            hud.show("Redo", Icons.AutoMirrored.Rounded.Redo)
        } else hud.show("Nothing to redo")
    }

    /** Notebooks keep their ink live; exporting writes an annotated copy wherever you choose. */
    fun exportNotebook() {
        val nb = notebook ?: return
        scope.launch {
            exportProgress = 0f
            val bytes = runCatching {
                withContext(Dispatchers.IO) {
                    val reader = app.lumen.reader.pdf.engine.PdfReader(nb.file.readBytes())
                    java.io.ByteArrayOutputStream().also { app.lumen.reader.pdf.engine.PdfAnnotator.save(reader, ink.pages.toMap(), it) }.toByteArray()
                }
            }.getOrNull()
            exportProgress = null
            if (bytes == null) {
                toast(context, "Export failed"); return@launch
            }
            toast(context, Saver.saveCopy(context, pickers, nb.name.replace(Regex("[\\\\/:*?\"<>|]"), "-") + ".pdf", "application/pdf") { it.write(bytes) })
        }
    }

    fun addPage() {
        scope.launch {
            val info = app.lumen.reader.notebook.Notebooks.addPage(context, ref) ?: return@launch
            hud.show("Page ${info.pages} added")
            onReload(info.pages - 1)
        }
    }

    /**
     * Writes the markup into the PDF as real annotations (incremental update, original bytes
     * untouched). Encrypted or unparseable files fall back to a flattened copy.
     */
    fun export() {
        if (exportProgress != null || ink.isEmpty) return
        if (notebook != null) {
            exportNotebook(); return
        }
        scope.launch {
            exportProgress = 0f
            val bytes = runCatching {
                withContext(Dispatchers.IO) {
                    val original = Docs.open(context, ref).use { it.readBytes() }
                    val reader = app.lumen.reader.pdf.engine.PdfReader(original)
                    java.io.ByteArrayOutputStream().also { app.lumen.reader.pdf.engine.PdfAnnotator.save(reader, ink.pages.toMap(), it) }.toByteArray()
                }
            }.getOrNull()
            if (bytes != null) {
                exportProgress = null
                val result = Saver.save(context, pickers, ref) { it.write(bytes) }
                toast(context, result)
                if (result is Saver.Result.Saved) {
                    // The marks now live inside the PDF itself; reload so PDFium draws them.
                    ink.discard()
                    markup = false
                    onReload(null)
                } else if (result is Saver.Result.SavedCopy) {
                    ink.discard()
                    markup = false
                }
                return@launch
            }
            val tmp = java.io.File(context.cacheDir, "export.pdf")
            val ok = runCatching { tmp.outputStream().use { exportFlattened(doc, ink, it) { p -> exportProgress = p } } }.isSuccess
            exportProgress = null
            if (!ok) {
                toast(context, "Export failed"); return@launch
            }
            val name = ref.name.substringBeforeLast('.') + " (annotated).pdf"
            toast(context, Saver.saveCopy(context, pickers, name, "application/pdf") { out -> tmp.inputStream().use { it.copyTo(out) } })
            tmp.delete()
        }
    }
    androidx.activity.compose.BackHandler(markup && note == null && (notebook == null || ink.selection != null)) {
        if (ink.selection != null) ink.clearSelection() else markup = false
    }
    LaunchedEffect(markup) { if (!markup) ink.clearSelection() }

    LaunchedEffect(doc) { doc.measurePages() }
    LaunchedEffect(doc) {
        snapshotFlow { state.currentPage }.debounce(400).collect { recents.savePosition(ref, it) }
    }
    // Hide chrome as soon as the reader starts moving through pages.
    LaunchedEffect(doc) {
        snapshotFlow { state.listState.isScrollInProgress || state.isZoomed }.collect { moving ->
            if (moving && !searching && !goTo && !markup) chrome = false
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

    val navigate = app.lumen.reader.core.LocalNavigator.current
    ViewerScaffold(
        ref = ref,
        title = notebook?.name ?: ref.name,
        subtitle = if (notebook != null) "Notebook · page ${state.currentPage + 1} of ${doc.pageCount}" else "PDF · ${doc.pageCount} pages",
        menu = listOfNotNull(
            if (notebook == null) app.lumen.reader.viewer.MenuAction("Organise pages", Icons.Rounded.ViewModule) {
                navigate(app.lumen.reader.core.Route.PdfTools(listOf(ref)))
            } else app.lumen.reader.viewer.MenuAction("Add page", Icons.Rounded.PostAdd) { addPage() },
            app.lumen.reader.viewer.MenuAction("Page colour: ${theme.label}", Icons.Rounded.Palette) {
                theme = PageTheme.entries[(theme.ordinal + 1) % PageTheme.entries.size]
            }
        ),
        chromeVisible = chrome || searching || markup,
        onBack = { if (searching) searching = false else onBack() },
        titleSlot = if (searching) {
            {
                SearchField(
                    query, { query = it },
                    status = when {
                        query.trim().length < 2 -> null
                        hitOrder.isEmpty() && searchProgress >= 1f -> "No results"
                        hitOrder.isEmpty() -> "Searching…"
                        else -> "${activeHit + 1}/${hitOrder.size}" + if (searchProgress < 1f) "…" else ""
                    }
                )
            }
        } else null,
        actions = {
            if (markup) {
                AppIconButton(Icons.AutoMirrored.Rounded.Undo, "Undo", { undoInk() },
                    iconTint = if (ink.canUndo) Color.Unspecified else colors.contentTertiary)
                AppIconButton(Icons.AutoMirrored.Rounded.Redo, "Redo", { redoInk() },
                    iconTint = if (ink.canRedo) Color.Unspecified else colors.contentTertiary)
                if (notebook != null) AppIconButton(Icons.Rounded.PostAdd, "Add page", ::addPage)
                AppIconButton(Icons.Rounded.SaveAlt, if (notebook != null) "Export PDF" else "Save markup into PDF", ::export, enabled = !ink.isEmpty)
                AppIconButton(Icons.Rounded.Check, "Done", { markup = false }, tint = colors.accent, iconTint = Color.White)
            } else if (searching) {
                AppIconButton(Icons.Rounded.KeyboardArrowUp, "Previous", { stepHit(-1) })
                AppIconButton(Icons.Rounded.KeyboardArrowDown, "Next", { stepHit(1) })
            } else {
                if (doc.supportsText) {
                    AppIconButton(Icons.Rounded.Search, "Search", { searching = true })
                }
                AppIconButton(Icons.Rounded.Draw, "Markup", { markup = true; goTo = false })
            }
        },
        bottomBar = {
            if (markup) {
                InkToolbar(ink)
                return@ViewerScaffold
            }
            BottomPill(Modifier.clickable(interactionSource = null, indication = null) { goTo = !goTo }) {
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
        overlay = {
            exportProgress?.let { p ->
                Panel(Modifier.align(Alignment.Center).width(260.dp), cornerRadius = 26.dp) {
                    Column(Modifier.padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        BasicText("Exporting… ${(p * 100).toInt()}%", style = type.headline.copy(color = colors.content))
                    }
                }
            }
            note?.let { (page, x, y) ->
                NotePanel(onAdd = { text -> ink.addNote(page, x, y, text); note = null }, onCancel = { note = null })
            }
            PageScrubber(state, doc.pageCount, visible = chrome || state.isInteracting || state.listState.isScrollInProgress)
            GestureHudView(hud, Modifier.padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + TopBarHeight + 16.dp))
            AnimatedVisibility(
                goTo,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = fadeIn() + scaleIn(initialScale = 0.92f),
                exit = fadeOut() + scaleOut(targetScale = 0.92f)
            ) {
                GoToPanel(state.currentPage, doc.pageCount) { page ->
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
                if (goTo) goTo = false else if (!markup) chrome = !chrome
            },
            ink = ink,
            inkActive = markup,
            onNoteRequest = { p, x, y -> note = Triple(p, x, y) },
            fingerDraws = settings.fingerDraws,
            onStylus = {
                if (settings.noteStylus()) hud.show("Stylus detected · fingers now scroll")
            },
            onMultiTap = { fingers ->
                if (markup) when (fingers) {
                    2 -> undoInk()
                    3 -> redoInk()
                }
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
private fun BoxScope.NotePanel(onAdd: (String) -> Unit, onCancel: () -> Unit) {
    val colors = Lumen.colors
    val type = Lumen.type
    var text by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    androidx.activity.compose.BackHandler(onBack = onCancel)
    Panel(
        Modifier.align(Alignment.BottomCenter).imePadding().padding(16.dp).widthIn(max = 520.dp).fillMaxWidth(),
        cornerRadius = 26.dp
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                textStyle = type.body.copy(color = colors.content),
                cursorBrush = SolidColor(colors.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onAdd(text) }),
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(colors.content.copy(alpha = 0.07f))
                    .padding(horizontal = 12.dp, vertical = 12.dp)
                    .focusRequester(focus)
            )
            PillButton("Add", Modifier.padding(start = 8.dp)) { onAdd(text) }
        }
    }
}

@Composable
private fun SearchField(query: String, onQuery: (String) -> Unit, status: String?) {
    val colors = Lumen.colors
    val type = Lumen.type
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Panel(Modifier.height(TopBarHeight).fillMaxWidth()) {
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
private fun BoxScope.PageScrubber(state: PdfViewerState, pageCount: Int, visible: Boolean) {
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
            Panel(Modifier.height(36.dp)) {
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
            Panel(Modifier.size(width = 10.dp, height = thumbH)) {}
        }
    }
}

@Composable
private fun GoToPanel(current: Int, pageCount: Int, onGo: (Int) -> Unit) {
    val colors = Lumen.colors
    val type = Lumen.type
    var value by remember { mutableFloatStateOf(current.toFloat()) }
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Panel(
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
            androidx.compose.material3.Slider(
                value = value,
                onValueChange = { value = it; onGo(it.roundToInt()) },
                valueRange = 0f..(pageCount - 1).coerceAtLeast(1).toFloat()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf("First" to 0, "Middle" to pageCount / 2, "Last" to pageCount - 1).forEach { (label, p) ->
                    androidx.compose.material3.FilledTonalButton(onClick = { value = p.toFloat(); onGo(p) }) {
                        androidx.compose.material3.Text(label)
                    }
                }
            }
        }
    }
}

private fun Modifier.onSizeChangedCompat(horizontal: Boolean = false, block: (Float) -> Unit) =
    onSizeChanged { block(if (horizontal) it.width.toFloat() else it.height.toFloat()) }
