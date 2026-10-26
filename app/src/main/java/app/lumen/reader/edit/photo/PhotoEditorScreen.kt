package app.lumen.reader.edit.photo

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import android.os.SystemClock
import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.toSize
import app.lumen.reader.core.LocalSettings
import app.lumen.reader.core.MultiTap
import app.lumen.reader.core.PenMath
import app.lumen.reader.core.PenStroke
import app.lumen.reader.core.isPen
import app.lumen.reader.core.requestLowLatency
import app.lumen.reader.core.wantsEraser
import app.lumen.reader.image.ZoomState
import app.lumen.reader.image.zoomLayer
import app.lumen.reader.ui.components.GestureHud
import app.lumen.reader.ui.components.GestureHudView
import app.lumen.reader.ui.components.StrokePreview
import kotlin.math.abs
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Brush
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Compare
import androidx.compose.material.icons.rounded.Crop
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.setFrom
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.lumen.reader.core.DocRef
import app.lumen.reader.core.LocalPickers
import app.lumen.reader.core.Saver
import app.lumen.reader.edit.Adjustment
import app.lumen.reader.edit.Chip
import app.lumen.reader.edit.ChipRow
import app.lumen.reader.edit.ColorDots
import app.lumen.reader.edit.FilterPreset
import app.lumen.reader.edit.Look
import app.lumen.reader.edit.PillButton
import app.lumen.reader.edit.Studio
import app.lumen.reader.edit.StudioIconButton
import app.lumen.reader.edit.StudioSlider
import app.lumen.reader.edit.ToolTab
import app.lumen.reader.edit.UnsavedChangesPanel
import app.lumen.reader.edit.toast
import app.lumen.reader.ui.components.Panel
import app.lumen.reader.ui.theme.Lumen
import app.lumen.reader.viewer.LoadingIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private enum class Tool(val label: String) { Adjust("Adjust"), Filters("Filters"), Crop("Crop"), Draw("Draw"), Text("Text") }

private const val PROXY_SIZE = 1600
private const val EXPORT_MAX = 4096

@Composable
fun PhotoEditorScreen(ref: DocRef?, drawFirst: Boolean = false, onBack: () -> Unit) {
    val context = LocalContext.current
    val pickers = LocalPickers.current
    var source by remember { mutableStateOf(ref) }
    LaunchedEffect(Unit) {
        if (source == null) {
            val uri = pickers.openDocuments(arrayOf("image/*"), false).firstOrNull()
            if (uri == null) onBack() else source = runCatching {
                app.lumen.reader.core.Docs.resolve(context, uri)
            }.getOrNull() ?: run { onBack(); null }
        }
    }
    val src = source
    if (src == null) {
        Box(Modifier.fillMaxSize().background(Studio.background)) { LoadingIndicator() }
    } else {
        PhotoEditor(src, drawFirst, onBack)
    }
}

@Composable
private fun PhotoEditor(ref: DocRef, drawFirst: Boolean, onBack: () -> Unit) {
    val context = LocalContext.current
    val pickers = LocalPickers.current
    val scope = rememberCoroutineScope()
    val type = Lumen.type
    var proxy by remember { mutableStateOf<Bitmap?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val history = remember { mutableStateListOf(PhotoEdit()) }
    var index by remember { mutableIntStateOf(0) }
    var edit by remember { mutableStateOf(PhotoEdit()) }
    var tool by remember { mutableStateOf(if (drawFirst) Tool.Draw else Tool.Adjust) }
    var adjustment by remember { mutableStateOf(Adjustment.Exposure) }
    var comparing by remember { mutableStateOf(false) }
    var brushColor by remember { mutableIntStateOf(Studio.swatches[2]) }
    var brushSize by remember { mutableStateOf(0.012f) }
    var selectedText by remember { mutableStateOf<Long?>(null) }
    var saving by remember { mutableStateOf(false) }
    var confirmExit by remember { mutableStateOf(false) }
    var savedIndex by remember { mutableIntStateOf(0) }
    val zoom = remember { ZoomState(maxScale = 8f) }
    var frame by remember { mutableStateOf(Rect.Zero) }
    var live by remember { mutableStateOf<PenStroke?>(null) }
    var liveVersion by remember { mutableIntStateOf(0) }
    var brushErase by remember { mutableStateOf(false) }
    val hud = remember { GestureHud() }
    val settings = LocalSettings.current
    val haptics = LocalHapticFeedback.current
    val view = LocalView.current
    val decay = rememberSplineBasedDecay<Float>()
    LaunchedEffect(tool) { if (tool == Tool.Crop) zoom.reset() }

    LaunchedEffect(ref) {
        runCatching { withContext(Dispatchers.IO) { decode(context, ref, PROXY_SIZE) } }
            .onSuccess { proxy = it }
            .onFailure { error = it.message ?: "Can't open this image" }
    }

    fun commit(next: PhotoEdit = edit) {
        edit = next
        if (history[index] == next) return
        while (history.lastIndex > index) history.removeAt(history.lastIndex)
        history += next
        index = history.lastIndex
    }

    fun undo(): Boolean {
        if (index > 0) {
            index--; edit = history[index]; selectedText = null
            return true
        }
        return false
    }

    fun redo(): Boolean {
        if (index < history.lastIndex) {
            index++; edit = history[index]
            return true
        }
        return false
    }

    fun gestureUndo(redo: Boolean) {
        val ok = if (redo) redo() else undo()
        if (ok) {
            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
            hud.show(if (redo) "Redo" else "Undo", if (redo) Icons.AutoMirrored.Rounded.Redo else Icons.AutoMirrored.Rounded.Undo)
        } else hud.show(if (redo) "Nothing to redo" else "Nothing to undo")
    }
    val latestEdit = androidx.compose.runtime.rememberUpdatedState(edit)

    fun export(thenClose: Boolean) {
        if (saving) return
        saving = true
        scope.launch {
            val result = runCatching {
                val bytes = withContext(Dispatchers.Default) {
                    val full = withContext(Dispatchers.IO) { decode(context, ref, EXPORT_MAX) }
                    val out = edit.renderGeometry(full)
                    full.recycle()
                    if (!edit.look.isIdentity) edit.look.applyTo(out)
                    edit.drawOverlays(out)
                    java.io.ByteArrayOutputStream().also { out.compress(Bitmap.CompressFormat.JPEG, 95, it) }.toByteArray()
                }
                val name = "Lumen_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".jpg"
                Saver.saveMedia(context, pickers, name, "image/jpeg") { it.write(bytes) }
            }.getOrElse { Saver.Result.Failed(it.message ?: "Export failed") }
            saving = false
            if (result is Saver.Result.SavedCopy) toast(context, "Saved to Pictures/Lumen") else toast(context, result)
            if (result is Saver.Result.SavedCopy) {
                savedIndex = index
                if (thenClose) onBack()
            }
        }
    }

    val dirty = index != savedIndex
    fun exit() {
        if (dirty) confirmExit = true else onBack()
    }
    BackHandler(!confirmExit) {
        if (selectedText != null) selectedText = null else exit()
    }
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Box(Modifier.fillMaxSize().background(Studio.background)) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Studio.background)
                .padding(top = top + 72.dp, bottom = bottom + 200.dp, start = 12.dp, end = 12.dp)
                .onSizeChanged { zoom.viewport = it.toSize() }
                .pointerInput(tool, brushColor, brushSize, brushErase) {
                    if (tool == Tool.Crop) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        fun norm(p: Offset): Offset {
                            val l = (p - zoom.offset) / zoom.scale
                            return Offset((l.x - frame.left) / frame.width, (l.y - frame.top) / frame.height)
                        }
                        val aspect = if (frame.height > 0f) frame.width / frame.height else 1f
                        val eraseRadius = max(brushSize, 0.02f) * min(frame.width, frame.height) / max(frame.width, 1f)

                        fun erased(e: PhotoEdit, at: Offset): PhotoEdit {
                            var changed = false
                            val out = e.strokes.flatMap { st ->
                                val reach = eraseRadius + st.width * min(frame.width, frame.height) / max(frame.width, 1f) / 2
                                val pieces = PenMath.eraseFrom(st.points, st.widths, at.x, at.y, reach, aspect)
                                if (pieces == null) listOf(st) else {
                                    changed = true
                                    pieces.map { BrushStroke(it.points, st.color, st.width, it.widths) }
                                }
                            }
                            return if (changed) e.copy(strokes = out) else e
                        }

                        /** One brush stroke (or eraser pass). True = hand over to zoom/pan. */
                        suspend fun AwaitPointerEventScope.stroke(start: PointerInputChange): Boolean {
                            val pen = start.isPen
                            val startEvent = currentEvent
                            if (pen) requestLowLatency(view, startEvent)
                            val p0 = norm(start.position)
                            if (!pen && (p0.x !in -0.05f..1.05f || p0.y !in -0.05f..1.05f)) return true
                            val erasing = brushErase || startEvent.wantsEraser(start)
                            val base = latestEdit.value
                            var cur = base
                            val s = PenStroke(aspect, pressure = pen)
                            if (erasing) {
                                cur = erased(cur, p0); edit = cur
                            } else {
                                s.add(p0.x.coerceIn(0f, 1f), p0.y.coerceIn(0f, 1f), start.pressure)
                                live = s; liveVersion++
                            }
                            start.consume()
                            var lastPressure = start.pressure
                            var snapped = erasing
                            var anchor = start.position
                            var anchorTime = start.uptimeMillis
                            val still = 7.dp.toPx()
                            fun sample(screen: Offset, pressure: Float) {
                                val q = norm(screen)
                                if (erasing) {
                                    cur = erased(cur, q); edit = cur
                                } else if (s.add(q.x.coerceIn(0f, 1f), q.y.coerceIn(0f, 1f), pressure)) liveVersion++
                            }
                            while (true) {
                                val event = if (!snapped) {
                                    val remaining = anchorTime + 480L - SystemClock.uptimeMillis()
                                    if (remaining <= 0) null else withTimeoutOrNull(remaining) { awaitPointerEvent() }
                                } else awaitPointerEvent()
                                if (event == null) {
                                    snapped = true
                                    if (s.snap()) {
                                        liveVersion++
                                        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                    }
                                    continue
                                }
                                val c = event.changes.firstOrNull { it.id == start.id }
                                if (c == null || !c.pressed) {
                                    event.changes.forEach { it.consume() }
                                    break
                                }
                                if (!pen && event.changes.count { it.pressed } > 1) {
                                    live = null; liveVersion++
                                    if (erasing) edit = base
                                    return true
                                }
                                val hist = c.historical
                                for (k in hist.indices) sample(hist[k].position, lastPressure + (c.pressure - lastPressure) * (k + 1f) / (hist.size + 1))
                                sample(c.position, c.pressure)
                                lastPressure = c.pressure
                                if ((c.position - anchor).getDistance() > still) {
                                    anchor = c.position; anchorTime = c.uptimeMillis
                                }
                                event.changes.forEach { it.consume() }
                            }
                            if (erasing) {
                                if (cur !== base) commit(cur)
                            } else {
                                live = null; liveVersion++
                                commit(base.copy(strokes = base.strokes + BrushStroke(s.points(), brushColor, brushSize * s.evenWidth(), s.widths())))
                            }
                            return false
                        }

                        if (tool == Tool.Draw && proxy != null) {
                            if (down.type == PointerType.Stylus || down.type == PointerType.Eraser) {
                                if (settings.noteStylus()) hud.show("Stylus detected · fingers now zoom")
                            }
                            if (down.isPen || settings.fingerDraws) {
                                if (!stroke(down)) return@awaitEachGesture
                            }
                        }

                        // Fingers: pinch to zoom, drag to pan, quick 2/3-finger tap to undo/redo.
                        zoom.isInteracting = true
                        val taps = MultiTap(down.uptimeMillis, viewConfiguration.touchSlop)
                        taps.track(currentEvent)
                        val tracker = androidx.compose.ui.input.pointer.util.VelocityTracker()
                        var past = false
                        var zoomAcc = 1f
                        var panAcc = Offset.Zero
                        var count = 1
                        var multi = false
                        var penMidway: PointerInputChange? = null
                        while (true) {
                            val e = awaitPointerEvent()
                            taps.track(e)
                            if (tool == Tool.Draw) {
                                penMidway = e.changes.firstOrNull { it.isPen && it.changedToDown() }
                                if (penMidway != null) break
                            }
                            if (e.changes.any { it.isConsumed && it.positionChanged() }) break
                            val pressed = e.changes.count { it.pressed }
                            if (pressed == 0) {
                                val f = taps.fingers()
                                if (!past && f >= 2) {
                                    e.changes.forEach { it.consume() }
                                    gestureUndo(redo = f >= 3)
                                }
                                break
                            }
                            if (pressed != count) {
                                count = pressed; tracker.resetTracking()
                            }
                            multi = pressed > 1
                            val z = e.calculateZoom()
                            val pan = e.calculatePan()
                            if (!past) {
                                zoomAcc *= z; panAcc += pan
                                val wantsPan = zoom.isZoomed && panAcc.getDistance() > viewConfiguration.touchSlop
                                if (abs(1 - zoomAcc) * e.calculateCentroidSize(false) > viewConfiguration.touchSlop || wantsPan) past = true
                            }
                            if (past) {
                                if (z != 1f) zoom.zoomBy(z, e.calculateCentroid(true))
                                zoom.panBy(pan)
                                e.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                            e.changes.firstOrNull { it.pressed }?.let { tracker.addPosition(it.uptimeMillis, it.position) }
                        }
                        penMidway?.let {
                            zoom.isInteracting = false
                            stroke(it)
                            return@awaitEachGesture
                        }
                        val v = tracker.calculateVelocity()
                        scope.launch {
                            if (past && !multi && zoom.isZoomed) zoom.fling(v, decay) else zoom.settle()
                            zoom.isInteracting = false
                        }
                    }
                }
        ) {
            val p = proxy
            when {
                error != null -> BasicText(error!!, style = type.body.copy(color = Studio.text), modifier = Modifier.align(Alignment.Center))
                p == null -> LoadingIndicator()
                else -> Box(Modifier.fillMaxSize().zoomLayer(zoom)) {
                    PhotoCanvas(
                    proxy = p,
                    edit = if (comparing) PhotoEdit(rotation = edit.rotation, flipH = edit.flipH, straighten = edit.straighten, crop = edit.crop) else edit,
                    tool = tool,
                    brushColor = brushColor,
                    brushSize = brushSize,
                    selectedText = selectedText,
                    onSelectText = { selectedText = it },
                    onLive = { edit = it },
                    onCommit = { commit(it) },
                    onFrame = { frame = it; zoom.fitted = it },
                    live = live,
                    liveVersion = { liveVersion }
                )
                }
            }
            GestureHudView(hud)
        }

        // Top bar
        Row(
            Modifier.fillMaxWidth().padding(top = top + 10.dp, start = 12.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            StudioIconButton(Icons.Rounded.Close, "Close", ::exit)
            Spacer(Modifier.weight(1f))
            StudioIconButton(Icons.AutoMirrored.Rounded.Undo, "Undo", { undo() }, enabled = index > 0)
            StudioIconButton(Icons.AutoMirrored.Rounded.Redo, "Redo", { redo() }, enabled = index < history.lastIndex)
            Box(
                Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.08f))
                    .pointerInput(Unit) {
                        detectTapGestures(onPress = {
                            comparing = true
                            tryAwaitRelease()
                            comparing = false
                        })
                    },
                contentAlignment = Alignment.Center
            ) {
                Image(Icons.Rounded.Compare, "Hold to compare", Modifier.size(22.dp), colorFilter = ColorFilter.tint(Studio.text))
            }
            PillButton(if (saving) "Saving…" else "Save", enabled = !saving && proxy != null) { export(false) }
        }

        // Tool panel + tabs
        Panel(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(start = 10.dp, end = 10.dp, bottom = bottom + 10.dp)
                .widthIn(max = 640.dp)
                .fillMaxWidth(),
            cornerRadius = 30.dp
        ) {
            Column(Modifier.padding(vertical = 12.dp)) {
                Box(Modifier.height(110.dp).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    when (tool) {
                        Tool.Adjust -> AdjustPanel(edit.look, adjustment, { adjustment = it }, { edit = edit.copy(look = it) }, { commit() })
                        Tool.Filters -> proxy?.let { p ->
                            FilterPanel(p, edit.look, { edit = edit.copy(look = it) }, { commit(edit.copy(look = it)) }, { commit() })
                        }
                        Tool.Crop -> proxy?.let { p -> CropPanel(edit, p.width, p.height, { edit = it }, { commit(it) }, { commit() }) }
                        Tool.Draw -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            ChipRow {
                                Chip("Brush", !brushErase, { brushErase = false })
                                Chip("Eraser", brushErase, { brushErase = true })
                                Chip(if (settings.fingerDraws) "Finger draws" else "Finger zooms", settings.fingerDraws, {
                                    settings.updateFingerDraws(!settings.fingerDraws)
                                })
                            }
                            if (!brushErase) ColorDots(brushColor) { brushColor = it }
                            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                                StrokePreview(Color(brushColor), brushSize * min(frame.width, frame.height) * zoom.scale, false, Modifier.width(56.dp))
                                StudioSlider(brushSize, { brushSize = it }, {}, min = 0.003f, max = 0.05f, center = null)
                            }
                        }
                        Tool.Text -> TextPanel(edit, selectedText, onChange = { edit = it }, onCommit = { commit(it) }, onAdd = {
                            val layer = TextLayer(System.nanoTime(), "Your text", 0.5f, 0.5f, 0.08f, Studio.swatches[0], false)
                            commit(edit.copy(texts = edit.texts + layer))
                            selectedText = layer.id
                        }, onDeselect = { selectedText = null })
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(top = 6.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    listOf(
                        Tool.Adjust to Icons.Rounded.Tune, Tool.Filters to Icons.Rounded.AutoAwesome,
                        Tool.Crop to Icons.Rounded.Crop, Tool.Draw to Icons.Rounded.Brush, Tool.Text to Icons.Rounded.TextFields
                    ).forEach { (t, icon) ->
                        ToolTab(icon, t.label, tool == t, { tool = t; if (t != Tool.Text) selectedText = null })
                    }
                }
            }
        }

        if (saving) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)).clickable(interactionSource = null, indication = null) {})
            Panel(Modifier.align(Alignment.Center).width(220.dp), cornerRadius = 26.dp) {
                Column(Modifier.padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(40.dp)) { LoadingIndicator() }
                    BasicText("Rendering full resolution…", style = type.label.copy(color = Studio.text), modifier = Modifier.padding(top = 12.dp))
                }
            }
        }
        UnsavedChangesPanel(
            confirmExit,
            onSave = { confirmExit = false; export(true) },
            onDiscard = { confirmExit = false; onBack() },
            onCancel = { confirmExit = false }
        )
    }
}

/** Decodes with EXIF orientation applied, longest side capped at [maxSide], as a mutable software bitmap. */
internal fun decode(context: android.content.Context, ref: DocRef, maxSide: Int): Bitmap = runCatching {
    val src = ImageDecoder.createSource(context.contentResolver, ref.uri)
    ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
        val s = min(1f, maxSide.toFloat() / max(info.size.width, info.size.height))
        if (s < 1f) decoder.setTargetSize((info.size.width * s).roundToInt().coerceAtLeast(1), (info.size.height * s).roundToInt().coerceAtLeast(1))
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        decoder.isMutableRequired = true
    }
}.getOrElse { decodeFallback(context, ref, maxSide) }
    .let { if (it.config == Bitmap.Config.ARGB_8888 && it.isMutable) it else it.copy(Bitmap.Config.ARGB_8888, true) }

/** BitmapFactory path for files ImageDecoder rejects; applies EXIF rotation itself. */
private fun decodeFallback(context: android.content.Context, ref: DocRef, maxSide: Int): Bitmap {
    val resolver = context.contentResolver
    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(ref.uri)!!.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
    val raw = resolver.openInputStream(ref.uri)!!.use {
        android.graphics.BitmapFactory.decodeStream(it, null, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
    } ?: error("Unsupported image")
    val orientation = runCatching {
        resolver.openInputStream(ref.uri)!!.use {
            android.media.ExifInterface(it).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL)
        }
    }.getOrDefault(android.media.ExifInterface.ORIENTATION_NORMAL)
    val m = android.graphics.Matrix()
    when (orientation) {
        android.media.ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
        android.media.ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
        android.media.ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
        android.media.ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
        else -> return raw
    }
    return Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
}

@Composable
private fun PhotoCanvas(
    proxy: Bitmap,
    edit: PhotoEdit,
    tool: Tool,
    brushColor: Int,
    brushSize: Float,
    selectedText: Long?,
    onSelectText: (Long?) -> Unit,
    onLive: (PhotoEdit) -> Unit,
    onCommit: (PhotoEdit) -> Unit,
    onFrame: (Rect) -> Unit = {},
    live: PenStroke? = null,
    liveVersion: () -> Int = { 0 }
) {
    val image = remember(proxy) { proxy.asImageBitmap() }
    val cropping = tool == Tool.Crop
    val accent = Studio.accent
    val density = LocalDensity.current
    val shader = remember { if (Look.supportsShader) RuntimeShader(Look.SHADER) else null }
    val livePaint = remember {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE
            strokeCap = android.graphics.Paint.Cap.ROUND
            strokeJoin = android.graphics.Paint.Join.ROUND
        }
    }
    val latest = androidx.compose.runtime.rememberUpdatedState(edit)

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val (ow, oh) = if (cropping) edit.orientedSize(proxy.width, proxy.height) else edit.outputSize(proxy.width, proxy.height)
        val areaW = constraints.maxWidth.toFloat()
        val areaH = constraints.maxHeight.toFloat()
        val s = min(areaW / ow, areaH / oh)
        val dw = ow * s
        val dh = oh * s
        val left = (areaW - dw) / 2
        val topY = (areaH - dh) / 2
        val m = remember(edit.rotation, edit.flipH, edit.straighten, edit.crop, cropping, proxy) {
            androidx.compose.ui.graphics.Matrix().apply { setFrom(edit.matrix(proxy.width, proxy.height, cropped = !cropping)) }
        }
        val frameRect = Rect(left, topY, left + dw, topY + dh)
        LaunchedEffect(frameRect) { onFrame(frameRect) }
        val frameMod = Modifier
            .offset { IntOffset(left.roundToInt(), topY.roundToInt()) }
            .size(with(density) { dw.toDp() }, with(density) { dh.toDp() })

        // Image + colour. On Android 13+ the whole look runs as a GPU shader on the layer.
        val look = edit.look
        Box(
            frameMod.graphicsLayer {
                clip = true
                if (shader != null && !look.isIdentity && Build.VERSION.SDK_INT >= 33) {
                    look.setUniforms(shader, size.width, size.height)
                    renderEffect = RenderEffect.createRuntimeShaderEffect(shader, "image").asComposeRenderEffect()
                } else {
                    renderEffect = null
                }
            }
        ) {
            Canvas(Modifier.fillMaxSize()) {
                withTransform({
                    scale(s, s, Offset.Zero)
                    transform(m)
                }) {
                    drawImage(
                        image,
                        filterQuality = FilterQuality.Medium,
                        colorFilter = if (shader == null && !look.isIdentity) ColorFilter.colorMatrix(ColorMatrix(look.linearMatrix())) else null
                    )
                }
            }
        }

        // Overlays (not colour graded, same as export) + drawing/text gestures.
        if (!cropping) {
            Canvas(
                frameMod
                    .pointerInput(tool) {
                        if (tool == Tool.Text) {
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                val e0 = latest.value
                                val w = size.width.toFloat(); val h = size.height.toFloat()
                                val hit = e0.texts.lastOrNull { t ->
                                    val half = t.size * min(w, h) * (0.6f + t.text.length * 0.3f).coerceAtMost(6f) / 2
                                    kotlin.math.abs(down.position.x - t.x * w) < half && kotlin.math.abs(down.position.y - t.y * h) < t.size * min(w, h)
                                }
                                onSelectText(hit?.id)
                                if (hit == null) return@awaitEachGesture
                                var moved = false
                                var cur = e0
                                while (true) {
                                    val e = awaitPointerEvent()
                                    val c = e.changes.first()
                                    if (!c.pressed) break
                                    val d = c.position - c.previousPosition
                                    if (d != Offset.Zero) {
                                        moved = true
                                        cur = cur.copy(texts = cur.texts.map {
                                            if (it.id == hit.id) it.copy(x = (it.x + d.x / w).coerceIn(0f, 1f), y = (it.y + d.y / h).coerceIn(0f, 1f)) else it
                                        })
                                        onLive(cur)
                                        c.consume()
                                    }
                                }
                                if (moved) onCommit(cur)
                            }
                        }
                    }
            ) {
                drawIntoCanvas { c ->
                    PhotoEdit.drawStrokes(c.nativeCanvas, edit.strokes, size.width, size.height)
                    PhotoEdit.drawTexts(c.nativeCanvas, edit.texts, size.width, size.height)
                }
                if (tool == Tool.Text) edit.texts.firstOrNull { it.id == selectedText }?.let { t ->
                    val ref = min(size.width, size.height)
                    val hw = t.size * ref * (0.35f * t.text.lines().maxOf { it.length }.coerceAtLeast(2))
                    val hh = t.size * ref * 0.75f * t.text.lines().size
                    drawRoundRect(
                        accent,
                        Offset(t.x * size.width - hw, t.y * size.height - hh),
                        Size(hw * 2, hh * 2),
                        androidx.compose.ui.geometry.CornerRadius(8.dp.toPx()),
                        style = Stroke(2.dp.toPx())
                    )
                }
            }
            // The stroke in progress lives on its own layer: a new sample redraws only this.
            Canvas(frameMod.graphicsLayer { }) {
                liveVersion()
                val stroke = live ?: return@Canvas
                livePaint.color = brushColor
                drawIntoCanvas { stroke.draw(it.nativeCanvas, size.width, size.height, brushSize * min(size.width, size.height), livePaint) }
            }
        } else {
            CropOverlay(frameMod, edit, onLive, onCommit, proxy.width, proxy.height)
        }
    }
}

@Composable
private fun CropOverlay(
    modifier: Modifier,
    edit: PhotoEdit,
    onLive: (PhotoEdit) -> Unit,
    onCommit: (PhotoEdit) -> Unit,
    srcW: Int,
    srcH: Int
) {
    val latest = androidx.compose.runtime.rememberUpdatedState(edit)
    Canvas(
        modifier.pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown()
                val start = latest.value
                val w = size.width.toFloat(); val h = size.height.toFloat()
                val r = start.crop
                val px = down.position.x / w
                val py = down.position.y / h
                val grab = 0.08f
                // Which edges does this drag move? Near a corner/edge resizes, inside moves.
                val moveL = kotlin.math.abs(px - r.left) < grab
                val moveR = kotlin.math.abs(px - r.right) < grab
                val moveT = kotlin.math.abs(py - r.top) < grab
                val moveB = kotlin.math.abs(py - r.bottom) < grab
                val inside = !(moveL || moveR || moveT || moveB) && r.contains(Offset(px, py))
                if (!(moveL || moveR || moveT || moveB || inside)) return@awaitEachGesture
                val (ow, oh) = start.orientedSize(srcW, srcH)
                val ratio = when (start.aspect.ratio) {
                    null -> null
                    -1f -> ow.toFloat() / oh
                    else -> start.aspect.ratio
                }
                var cur = start
                while (true) {
                    val e = awaitPointerEvent()
                    val c = e.changes.first()
                    if (!c.pressed) break
                    c.consume()
                    val dx = (c.position.x - down.position.x) / w
                    val dy = (c.position.y - down.position.y) / h
                    var nl = r.left; var nt = r.top; var nr = r.right; var nb = r.bottom
                    if (inside) {
                        val cdx = dx.coerceIn(-r.left, 1 - r.right)
                        val cdy = dy.coerceIn(-r.top, 1 - r.bottom)
                        nl += cdx; nr += cdx; nt += cdy; nb += cdy
                    } else {
                        if (moveL) nl = (r.left + dx).coerceIn(0f, r.right - 0.1f)
                        if (moveR) nr = (r.right + dx).coerceIn(r.left + 0.1f, 1f)
                        if (moveT) nt = (r.top + dy).coerceIn(0f, r.bottom - 0.1f)
                        if (moveB) nb = (r.bottom + dy).coerceIn(r.top + 0.1f, 1f)
                        if (ratio != null) {
                            // Keep the aspect by adjusting height from width (in pixel terms).
                            val wPx = (nr - nl) * ow
                            val hNorm = (wPx / ratio) / oh
                            if (moveT && !moveB) nt = (nb - hNorm).coerceAtLeast(0f) else nb = (nt + hNorm).coerceAtMost(1f)
                        }
                    }
                    cur = start.copy(crop = Rect(nl, nt, nr, nb))
                    onLive(cur)
                }
                onCommit(cur)
            }
        }
    ) {
        val r = edit.crop
        val l = r.left * size.width; val t = r.top * size.height
        val rr = r.right * size.width; val b = r.bottom * size.height
        val shade = Color.Black.copy(alpha = 0.55f)
        drawRect(shade, Offset.Zero, Size(size.width, t))
        drawRect(shade, Offset(0f, b), Size(size.width, size.height - b))
        drawRect(shade, Offset(0f, t), Size(l, b - t))
        drawRect(shade, Offset(rr, t), Size(size.width - rr, b - t))
        drawRect(Color.White, Offset(l, t), Size(rr - l, b - t), style = Stroke(1.5.dp.toPx()))
        for (i in 1..2) {
            val gx = l + (rr - l) * i / 3; val gy = t + (b - t) * i / 3
            drawLine(Color.White.copy(alpha = 0.4f), Offset(gx, t), Offset(gx, b))
            drawLine(Color.White.copy(alpha = 0.4f), Offset(l, gy), Offset(rr, gy))
        }
        val k = 18.dp.toPx(); val sw = 4.dp.toPx()
        listOf(Offset(l, t) to Offset(1f, 1f), Offset(rr, t) to Offset(-1f, 1f), Offset(l, b) to Offset(1f, -1f), Offset(rr, b) to Offset(-1f, -1f))
            .forEach { (p, d) ->
                drawLine(Color.White, p, p + Offset(k * d.x, 0f), sw)
                drawLine(Color.White, p, p + Offset(0f, k * d.y), sw)
            }
    }
}

@Composable
private fun AdjustPanel(look: Look, selected: Adjustment, onSelect: (Adjustment) -> Unit, onChange: (Look) -> Unit, onDone: () -> Unit) {
    val type = Lumen.type
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ChipRow {
            Adjustment.entries.forEach { a ->
                Chip(a.label, a == selected, { onSelect(a) }, dot = look.get(a) != 0f)
            }
        }
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            StudioSlider(
                look.get(selected), { onChange(look.with(selected, it)) }, onDone,
                modifier = Modifier.weight(1f),
                min = if (selected.bipolar) -1f else 0f,
                center = if (selected.bipolar) 0f else null
            )
            BasicText(
                "%+d".format((look.get(selected) * 100).roundToInt()).replace("+0", "0"),
                style = type.label.copy(color = Studio.text),
                modifier = Modifier.width(48.dp).padding(start = 10.dp)
            )
        }
    }
}

@Composable
private fun FilterPanel(proxy: Bitmap, look: Look, onLive: (Look) -> Unit, onPick: (Look) -> Unit, onDone: () -> Unit) {
    val type = Lumen.type
    // Tiny previews of each preset, rendered once on the CPU path.
    var thumbs by remember { mutableStateOf<Map<FilterPreset, androidx.compose.ui.graphics.ImageBitmap>>(emptyMap()) }
    LaunchedEffect(proxy) {
        thumbs = withContext(Dispatchers.Default) {
            val s = 120f / max(proxy.width, proxy.height)
            val small = Bitmap.createScaledBitmap(proxy, (proxy.width * s).roundToInt().coerceAtLeast(1), (proxy.height * s).roundToInt().coerceAtLeast(1), true)
            FilterPreset.entries.associateWith { f ->
                val b = small.copy(Bitmap.Config.ARGB_8888, true)
                Look(filter = f).applyTo(b)
                b.asImageBitmap()
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            FilterPreset.entries.forEach { f ->
                val sel = look.filter == f
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { onPick(look.copy(filter = f, filterStrength = 1f)) }) {
                    Box(
                        Modifier
                            .size(54.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .border(if (sel) 2.5.dp else 0.dp, if (sel) Studio.accent else Color.Transparent, RoundedCornerShape(14.dp))
                            .background(Studio.panel)
                    ) {
                        thumbs[f]?.let { Image(it, f.label, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop) }
                    }
                    BasicText(f.label, style = type.caption.copy(color = if (sel) Studio.accent else Studio.textDim))
                }
            }
        }
        if (look.filter != FilterPreset.None) {
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                BasicText("Strength", style = type.caption.copy(color = Studio.textDim), modifier = Modifier.width(64.dp))
                StudioSlider(look.filterStrength, { onLive(look.copy(filterStrength = it)) }, onDone, min = 0f, max = 1f, center = null)
            }
        }
    }
}

@Composable
private fun CropPanel(edit: PhotoEdit, srcW: Int, srcH: Int, onLive: (PhotoEdit) -> Unit, onCommit: (PhotoEdit) -> Unit, onDone: () -> Unit) {
    val type = Lumen.type
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ChipRow {
            Chip("Rotate", false, {
                val turned = edit.copy(rotation = (edit.rotation + 1) % 4)
                onCommit(turned.copy(crop = fitAspect(turned, if (edit.aspect == AspectRatio.Free) AspectRatio.Original else edit.aspect, srcW, srcH)))
            })
            Chip("Flip", false, { onCommit(edit.copy(flipH = !edit.flipH)) })
            AspectRatio.entries.forEach { a ->
                Chip(a.label, edit.aspect == a, { onCommit(edit.copy(aspect = a, crop = fitAspect(edit, a, srcW, srcH))) })
            }
            Chip("Reset", false, { onCommit(edit.copy(rotation = 0, flipH = false, straighten = 0f, crop = Rect(0f, 0f, 1f, 1f), aspect = AspectRatio.Free)) })
        }
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            BasicText("Straighten", style = type.caption.copy(color = Studio.textDim), modifier = Modifier.width(76.dp))
            StudioSlider(edit.straighten / 45f, { onLive(edit.copy(straighten = it * 45f)) }, onDone, modifier = Modifier.weight(1f))
            BasicText("%.0f°".format(edit.straighten), style = type.label.copy(color = Studio.text), modifier = Modifier.width(44.dp).padding(start = 8.dp))
        }
    }
}

/** Largest centred crop with the requested aspect inside the oriented frame. */
private fun fitAspect(edit: PhotoEdit, aspect: AspectRatio, srcW: Int, srcH: Int): Rect {
    val (ow, oh) = edit.orientedSize(srcW, srcH)
    val frame = ow.toFloat() / oh
    val target = when (aspect.ratio) {
        null -> return edit.crop
        -1f -> frame
        else -> aspect.ratio
    }
    return if (target > frame) {
        val h = frame / target
        Rect(0f, (1 - h) / 2, 1f, (1 + h) / 2)
    } else {
        val w = target / frame
        Rect((1 - w) / 2, 0f, (1 + w) / 2, 1f)
    }
}

@Composable
private fun TextPanel(
    edit: PhotoEdit,
    selected: Long?,
    onChange: (PhotoEdit) -> Unit,
    onCommit: (PhotoEdit) -> Unit,
    onAdd: () -> Unit,
    onDeselect: () -> Unit
) {
    val type = Lumen.type
    val layer = edit.texts.firstOrNull { it.id == selected }
    if (layer == null) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            PillButton("Add text", onClick = onAdd)
            BasicText("Tap text on the photo to edit it, drag to move", style = type.caption.copy(color = Studio.textDim))
        }
        return
    }
    fun update(t: TextLayer) = onChange(edit.copy(texts = edit.texts.map { if (it.id == t.id) t else it }))
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BasicTextField(
                value = layer.text,
                onValueChange = { update(layer.copy(text = it)) },
                textStyle = type.label.copy(color = Studio.text),
                cursorBrush = SolidColor(Studio.accent),
                singleLine = true,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White.copy(alpha = 0.08f))
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            )
            Chip("Box", layer.background, { onCommit(edit.copy(texts = edit.texts.map { if (it.id == layer.id) it.copy(background = !it.background) else it })) })
            Chip("Delete", false, { onCommit(edit.copy(texts = edit.texts.filterNot { it.id == layer.id })); onDeselect() })
        }
        ColorDots(layer.color) { c -> onCommit(edit.copy(texts = edit.texts.map { if (it.id == layer.id) it.copy(color = c) else it })) }
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            BasicText("Size", style = type.caption.copy(color = Studio.textDim), modifier = Modifier.width(48.dp))
            StudioSlider(layer.size, { update(layer.copy(size = it)) }, { onCommit(edit) }, min = 0.03f, max = 0.25f, center = null)
        }
    }
}

