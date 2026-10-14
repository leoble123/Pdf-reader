package app.lumen.reader.edit.video

import android.view.SurfaceView
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
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
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.CompositionPlayer
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import app.lumen.reader.core.DocRef
import app.lumen.reader.core.Docs
import app.lumen.reader.core.LocalPickers
import app.lumen.reader.core.Saver
import app.lumen.reader.edit.Adjustment
import app.lumen.reader.edit.Chip
import app.lumen.reader.edit.ChipRow
import app.lumen.reader.edit.ColorDots
import app.lumen.reader.edit.FilterPreset
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.roundToInt
import kotlin.math.roundToLong

private enum class Panel { None, Speed, Volume, Text, Filters, Adjust, Ratio, Music, Export }

/** Only the linear adjustments can run on video (they become one GPU colour matrix). */
private val videoAdjustments = listOf(
    Adjustment.Exposure, Adjustment.Contrast, Adjustment.Saturation, Adjustment.Warmth, Adjustment.Tint, Adjustment.Fade
)

@OptIn(UnstableApi::class)
@Composable
fun VideoEditorScreen(refs: List<DocRef>, onBack: () -> Unit) {
    val context = LocalContext.current
    val pickers = LocalPickers.current
    var initial by remember { mutableStateOf<VideoProject?>(null) }

    LaunchedEffect(Unit) {
        val clips = refs.mapIndexedNotNull { i, r -> VideoProject.probe(context, r.uri, r.name, r.mime, System.nanoTime() + i) }
        initial = when {
            clips.isNotEmpty() -> VideoProject(clips)
            else -> {
                // Resume the last project if there is one, otherwise start by picking clips.
                val saved = VideoProject.file(context).takeIf { it.exists() }?.readText()?.let(VideoProject::fromJson)
                if (saved != null && saved.clips.isNotEmpty()) saved
                else {
                    val picked = pickClips(context, pickers)
                    if (picked.isEmpty()) {
                        onBack(); return@LaunchedEffect
                    }
                    VideoProject(picked)
                }
            }
        }
    }
    val p = initial
    if (p == null) Box(Modifier.fillMaxSize().background(Studio.background)) { LoadingIndicator() }
    else VideoEditor(p, onBack)
}

private suspend fun pickClips(context: android.content.Context, pickers: app.lumen.reader.core.Pickers): List<Clip> {
    val uris = pickers.openDocuments(arrayOf("video/*", "image/*"), true)
    return uris.mapIndexedNotNull { i, uri ->
        val ref = runCatching { Docs.resolve(context, uri) }.getOrNull() ?: return@mapIndexedNotNull null
        VideoProject.probe(context, uri, ref.name, ref.mime, System.nanoTime() + i)
    }
}

@OptIn(UnstableApi::class)
@kotlin.OptIn(kotlinx.coroutines.FlowPreview::class)
@Composable
private fun VideoEditor(start: VideoProject, onBack: () -> Unit) {
    val context = LocalContext.current
    val pickers = LocalPickers.current
    val scope = rememberCoroutineScope()
    val type = Lumen.type
    val density = LocalDensity.current

    val history = remember { mutableStateListOf(start) }
    var index by remember { mutableIntStateOf(0) }
    var project by remember { mutableStateOf(start) }
    var selectedId by remember { mutableStateOf<Long?>(null) }
    var panel by remember { mutableStateOf(Panel.None) }
    var adjustment by remember { mutableStateOf(Adjustment.Exposure) }
    var playing by remember { mutableStateOf(false) }
    var position by remember { mutableLongStateOf(0L) }
    var pxPerSec by remember { mutableFloatStateOf(with(density) { 64.dp.toPx() }) }
    var exportHeight by remember { mutableIntStateOf(1080) }
    var exportProgress by remember { mutableStateOf<Int?>(null) }
    var exporter by remember { mutableStateOf<Transformer?>(null) }
    var savedIndex by remember { mutableIntStateOf(-1) }
    var confirmExit by remember { mutableStateOf(false) }
    var playerError by remember { mutableStateOf<String?>(null) }
    // CompositionPlayer can't seek or scrub until it has a composition.
    var playerReady by remember { mutableStateOf(false) }

    fun commit(next: VideoProject = project) {
        project = next
        if (history[index] == next) return
        while (history.lastIndex > index) history.removeAt(history.lastIndex)
        history += next
        index = history.lastIndex
    }

    val selected = project.clips.firstOrNull { it.id == selectedId }
    fun updateClip(c: Clip, live: Boolean = false) {
        val next = project.copy(clips = project.clips.map { if (it.id == c.id) c else it })
        if (live) project = next else commit(next)
    }

    // ---- Player -------------------------------------------------------------------------
    val player = remember { CompositionPlayer.Builder(context).build() }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
            }

            override fun onPlayerError(error: PlaybackException) {
                playerError = error.errorCodeName
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }
    // Rebuild the preview when the edit changes; debounced so slider drags stay fluid.
    LaunchedEffect(player) {
        snapshotFlow { project }.debounce(220).collectLatest { p ->
            if (p.clips.isEmpty()) return@collectLatest
            val wasPlaying = player.isPlaying
            playerError = null
            player.setComposition(CompositionBuilder.build(p), position.coerceIn(0, (p.durationMs - 1).coerceAtLeast(0)))
            player.prepare()
            playerReady = true
            player.playWhenReady = wasPlaying
        }
    }
    // Autosave so a project survives the app being closed.
    LaunchedEffect(Unit) {
        snapshotFlow { project }.debounce(800).collect { p ->
            withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { VideoProject.file(context).writeText(p.toJson()) } }
        }
    }

    // ---- Timeline scroll ⇄ playhead -----------------------------------------------------
    val scroll = rememberScrollState()
    val pxPerMs = pxPerSec / 1000f
    LaunchedEffect(playing, pxPerMs) {
        if (!playing) return@LaunchedEffect
        while (true) {
            withFrameNanos { }
            position = player.currentPosition
            val target = (position * pxPerMs).roundToInt()
            scroll.dispatchRawDelta((target - scroll.value).toFloat())
        }
    }
    LaunchedEffect(pxPerMs, playerReady) {
        if (!playerReady) return@LaunchedEffect
        snapshotFlow { scroll.isScrollInProgress }.collect { scrubbing ->
            if (scrubbing && player.isPlaying) player.pause()
            player.isScrubbingModeEnabled = scrubbing
        }
    }
    LaunchedEffect(pxPerMs, playerReady) {
        if (!playerReady) return@LaunchedEffect
        snapshotFlow { scroll.value }.collect { v ->
            if (!player.isPlaying) {
                val t = (v / pxPerMs).roundToLong().coerceIn(0, project.durationMs)
                position = t
                player.seekTo(t)
            }
        }
    }

    // ---- Export -------------------------------------------------------------------------
    fun export() {
        if (exportProgress != null || project.clips.isEmpty()) return
        player.pause()
        val out = File(context.cacheDir, "export.mp4").apply { delete() }
        val composition = CompositionBuilder.build(project, exportHeight)
        exportProgress = 0
        panel = Panel.None
        scope.launch {
            val transformer = Transformer.Builder(context).setVideoMimeType("video/avc").build()
            exporter = transformer
            val ok = suspendCancellableCoroutine { cont ->
                transformer.addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        if (cont.isActive) cont.resume(true)
                    }

                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        playerError = exportException.errorCodeName
                        if (cont.isActive) cont.resume(false)
                    }
                })
                transformer.start(composition, out.absolutePath)
                cont.invokeOnCancellation { transformer.cancel() }
                scope.launch {
                    val holder = ProgressHolder()
                    while (cont.isActive) {
                        if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) exportProgress = holder.progress
                        delay(200)
                    }
                }
            }
            exporter = null
            if (ok) {
                exportProgress = 100
                val name = "Lumen_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
                val result = Saver.saveMedia(context, pickers, name, "video/mp4") { o -> out.inputStream().use { it.copyTo(o) } }
                if (result is Saver.Result.SavedCopy) {
                    toast(context, "Saved to Movies/Lumen")
                    savedIndex = index
                } else toast(context, result)
            } else if (exportProgress != null) {
                toast(context, "Export failed" + (playerError?.let { " ($it)" } ?: ""))
            }
            out.delete()
            exportProgress = null
        }
    }

    val dirty = index != savedIndex && history.size > 1
    fun exit() {
        if (dirty) confirmExit = true else onBack()
    }
    BackHandler(!confirmExit && exportProgress == null) {
        when {
            panel != Panel.None -> panel = Panel.None
            selectedId != null -> selectedId = null
            else -> exit()
        }
    }
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Box(Modifier.fillMaxSize().background(Studio.background)) {
        Column(Modifier.fillMaxSize().background(Studio.background).padding(top = top + 64.dp, bottom = bottom)) {
            // Preview
            Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .aspectRatio(project.aspect().coerceIn(0.3f, 3f), matchHeightConstraintsFirst = project.aspect() < 1.2f)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color.Black)
                        .clickable(interactionSource = null, indication = null) { if (player.isPlaying) player.pause() else player.play() }
                ) {
                    AndroidView(
                        factory = { ctx -> SurfaceView(ctx).also { player.setVideoSurfaceView(it) } },
                        modifier = Modifier.fillMaxSize()
                    )
                    playerError?.let {
                        BasicText("Preview error: $it", style = type.caption.copy(color = Color.White), modifier = Modifier.align(Alignment.Center).padding(16.dp))
                    }
                }
            }
            // Transport
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BasicText("${time(position)} / ${time(project.durationMs)}", style = type.label.copy(color = Studio.text))
                Spacer(Modifier.weight(1f))
                StudioIconButton(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "Pause" else "Play", {
                    if (player.isPlaying) player.pause() else {
                        if (position >= project.durationMs - 50) player.seekTo(0)
                        player.play()
                    }
                })
                Spacer(Modifier.weight(1f))
                BasicText(
                    "${project.clips.size} clip" + if (project.clips.size == 1) "" else "s",
                    style = type.caption.copy(color = Studio.textDim)
                )
            }
            // Timeline
            Timeline(
                project = project,
                scroll = scroll,
                pxPerSec = pxPerSec,
                onZoom = { pxPerSec = (pxPerSec * it).coerceIn(with(density) { 16.dp.toPx() }, with(density) { 400.dp.toPx() }) },
                selectedId = selectedId,
                onSelect = { id -> selectedId = if (selectedId == id) null else id; panel = Panel.None },
                onTrimLive = { updateClip(it, live = true) },
                onTrimDone = { commit() },
                onAdd = {
                    scope.launch {
                        val more = pickClips(context, pickers)
                        if (more.isNotEmpty()) commit(project.copy(clips = project.clips + more))
                    }
                }
            )
            Spacer(Modifier.height(212.dp))
        }

        // Top bar
        Row(
            Modifier.fillMaxWidth().padding(top = top + 10.dp, start = 12.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            StudioIconButton(Icons.Rounded.Close, "Close", ::exit)
            Spacer(Modifier.weight(1f))
            StudioIconButton(Icons.AutoMirrored.Rounded.Undo, "Undo", {
                if (index > 0) {
                    index--; project = history[index]
                }
            }, enabled = index > 0)
            StudioIconButton(Icons.AutoMirrored.Rounded.Redo, "Redo", {
                if (index < history.lastIndex) {
                    index++; project = history[index]
                }
            }, enabled = index < history.lastIndex)
            PillButton("Export", enabled = exportProgress == null && project.clips.isNotEmpty()) {
                panel = if (panel == Panel.Export) Panel.None else Panel.Export
            }
        }

        // Tools
        Panel(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(start = 10.dp, end = 10.dp, bottom = bottom + 10.dp)
                .widthIn(max = 680.dp)
                .fillMaxWidth(),
            cornerRadius = 30.dp
        ) {
            Column(Modifier.padding(vertical = 12.dp)) {
                Box(Modifier.height(112.dp).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    ToolPanel(
                        panel = panel,
                        project = project,
                        selected = selected,
                        adjustment = adjustment,
                        onAdjustment = { adjustment = it },
                        exportHeight = exportHeight,
                        onExportHeight = { exportHeight = it },
                        onExport = ::export,
                        onLive = { project = it },
                        onCommit = { commit(it) },
                        onClipLive = { updateClip(it, live = true) },
                        onClipCommit = { updateClip(it) },
                        onPickMusic = {
                            scope.launch {
                                val uri = pickers.openDocuments(arrayOf("audio/*"), false).firstOrNull() ?: return@launch
                                val name = runCatching { Docs.resolve(context, uri).name }.getOrDefault("Music")
                                commit(project.copy(music = Music(uri, name)))
                            }
                        }
                    )
                }
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 6.dp, start = 8.dp, end = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    fun toggle(p: Panel) {
                        panel = if (panel == p) Panel.None else p
                    }
                    if (selected == null) {
                        Tab(Icons.Rounded.Add, "Add", false) {
                            scope.launch {
                                val more = pickClips(context, pickers)
                                if (more.isNotEmpty()) commit(project.copy(clips = project.clips + more))
                            }
                        }
                        Tab(Icons.Rounded.AutoAwesome, "Filters", panel == Panel.Filters) { toggle(Panel.Filters) }
                        Tab(Icons.Rounded.Tune, "Adjust", panel == Panel.Adjust) { toggle(Panel.Adjust) }
                        Tab(Icons.Rounded.AspectRatio, "Ratio", panel == Panel.Ratio) { toggle(Panel.Ratio) }
                        Tab(Icons.Rounded.LibraryMusic, "Music", panel == Panel.Music) { toggle(Panel.Music) }
                        Tab(Icons.AutoMirrored.Rounded.VolumeUp, "Volume", panel == Panel.Volume) { toggle(Panel.Volume) }
                    } else {
                        Tab(Icons.Rounded.ContentCut, "Split", false) {
                            project.split(position, System.nanoTime())?.let { commit(it) } ?: toast(context, "Move the playhead inside the clip to split")
                        }
                        Tab(Icons.Rounded.Speed, "Speed", panel == Panel.Speed, enabled = !selected.isImage) { toggle(Panel.Speed) }
                        Tab(Icons.AutoMirrored.Rounded.VolumeUp, "Volume", panel == Panel.Volume, enabled = !selected.isImage) { toggle(Panel.Volume) }
                        Tab(Icons.Rounded.TextFields, "Text", panel == Panel.Text) { toggle(Panel.Text) }
                        Tab(Icons.Rounded.ContentCopy, "Duplicate", false) {
                            val i = project.clips.indexOfFirst { it.id == selected.id }
                            commit(project.copy(clips = project.clips.toMutableList().apply { add(i + 1, selected.copy(id = System.nanoTime())) }))
                        }
                        Tab(Icons.AutoMirrored.Rounded.ArrowBack, "Earlier", false, enabled = project.clips.first().id != selected.id) {
                            val i = project.clips.indexOfFirst { it.id == selected.id }
                            commit(project.copy(clips = project.clips.toMutableList().apply { add(i - 1, removeAt(i)) }))
                        }
                        Tab(Icons.AutoMirrored.Rounded.ArrowForward, "Later", false, enabled = project.clips.last().id != selected.id) {
                            val i = project.clips.indexOfFirst { it.id == selected.id }
                            commit(project.copy(clips = project.clips.toMutableList().apply { add(i + 1, removeAt(i)) }))
                        }
                        Tab(Icons.Rounded.Delete, "Delete", false, enabled = project.clips.size > 1) {
                            commit(project.copy(clips = project.clips.filterNot { it.id == selected.id }))
                            selectedId = null
                            panel = Panel.None
                        }
                    }
                }
            }
        }

        exportProgress?.let { p ->
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)).clickable(interactionSource = null, indication = null) {})
            Panel(Modifier.align(Alignment.Center).width(260.dp), cornerRadius = 28.dp) {
                Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    BasicText("Exporting $p%", style = type.title.copy(color = Studio.text))
                    Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(Studio.track)) {
                        Box(Modifier.fillMaxWidth(p / 100f).fillMaxHeight().clip(CircleShape).background(Studio.accent))
                    }
                    BasicText("Encoding on your device's hardware", style = type.caption.copy(color = Studio.textDim))
                    PillButton("Cancel", filled = false) {
                        exporter?.cancel()
                        exportProgress = null
                    }
                }
            }
        }
        UnsavedChangesPanel(
            confirmExit,
            onSave = { confirmExit = false; panel = Panel.Export },
            onDiscard = {
                confirmExit = false
                VideoProject.file(context).delete()
                onBack()
            },
            onCancel = { confirmExit = false }
        )
    }
}

@Composable
private fun Tab(icon: ImageVector, label: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    ToolTab(icon, label, selected, { if (enabled) onClick() }, Modifier.graphicsLayer { alpha = if (enabled) 1f else 0.35f })
}

@Composable
private fun ToolPanel(
    panel: Panel,
    project: VideoProject,
    selected: Clip?,
    adjustment: Adjustment,
    onAdjustment: (Adjustment) -> Unit,
    exportHeight: Int,
    onExportHeight: (Int) -> Unit,
    onExport: () -> Unit,
    onLive: (VideoProject) -> Unit,
    onCommit: (VideoProject) -> Unit,
    onClipLive: (Clip) -> Unit,
    onClipCommit: (Clip) -> Unit,
    onPickMusic: () -> Unit
) {
    val type = Lumen.type
    @Composable
    fun Label(text: String) = BasicText(text, style = type.caption.copy(color = Studio.textDim), modifier = Modifier.width(64.dp))

    when {
        panel == Panel.Export -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ChipRow {
                listOf(480 to "480p", 720 to "720p", 1080 to "1080p", 1440 to "1440p").forEach { (h, l) ->
                    Chip(l, exportHeight == h, { onExportHeight(h) })
                }
            }
            PillButton("Export ${time(project.durationMs)} video", onClick = onExport)
        }
        panel == Panel.Speed && selected != null -> ChipRow {
            listOf(0.25f, 0.5f, 0.75f, 1f, 1.5f, 2f, 3f, 4f).forEach { s ->
                Chip(if (s % 1f == 0f) "${s.toInt()}×" else "$s×", selected.speed == s, { onClipCommit(selected.copy(speed = s)) })
            }
        }
        panel == Panel.Volume && selected != null -> Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            StudioIconButton(
                if (selected.volume == 0f) Icons.AutoMirrored.Rounded.VolumeOff else Icons.AutoMirrored.Rounded.VolumeUp, "Mute",
                { onClipCommit(selected.copy(volume = if (selected.volume == 0f) 1f else 0f)) }
            )
            Spacer(Modifier.width(12.dp))
            StudioSlider(selected.volume, { onClipLive(selected.copy(volume = it)) }, { onClipCommit(selected) }, min = 0f, max = 2f, center = 1f)
        }
        panel == Panel.Volume -> Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Label("Clips")
            StudioSlider(project.clipVolume, { onLive(project.copy(clipVolume = it)) }, { onCommit(project) }, min = 0f, max = 2f, center = 1f)
        }
        panel == Panel.Text && selected != null -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            BasicTextField(
                value = selected.text,
                onValueChange = { onClipLive(selected.copy(text = it)) },
                singleLine = true,
                textStyle = type.body.copy(color = Studio.text),
                cursorBrush = SolidColor(Studio.accent),
                decorationBox = { inner ->
                    Box {
                        if (selected.text.isEmpty()) BasicText("Caption for this clip", style = type.body.copy(color = Studio.textDim))
                        inner()
                    }
                },
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.White.copy(alpha = 0.08f))
                    .padding(horizontal = 14.dp, vertical = 12.dp)
            )
            ColorDots(selected.textColor) { onClipCommit(selected.copy(textColor = it)) }
        }
        panel == Panel.Filters -> ChipRow {
            FilterPreset.entries.forEach { f ->
                Chip(f.label, project.look.filter == f, { onCommit(project.copy(look = project.look.copy(filter = f, filterStrength = 1f))) })
            }
        }
        panel == Panel.Adjust -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ChipRow {
                videoAdjustments.forEach { a -> Chip(a.label, a == adjustment, { onAdjustment(a) }, dot = project.look.get(a) != 0f) }
            }
            StudioSlider(
                project.look.get(adjustment),
                { onLive(project.copy(look = project.look.with(adjustment, it))) },
                { onCommit(project) },
                modifier = Modifier.padding(horizontal = 16.dp),
                min = if (adjustment.bipolar) -1f else 0f,
                center = if (adjustment.bipolar) 0f else null
            )
        }
        panel == Panel.Ratio -> ChipRow {
            Ratio.entries.forEach { r -> Chip(r.label, project.ratio == r, { onCommit(project.copy(ratio = r)) }) }
        }
        panel == Panel.Music -> {
            val m = project.music
            if (m == null) {
                PillButton("Choose a song", onClick = onPickMusic)
            } else Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    BasicText(m.name, style = type.label.copy(color = Studio.text), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Chip("Replace", false, onPickMusic)
                    Spacer(Modifier.width(6.dp))
                    Chip("Remove", false, { onCommit(project.copy(music = null)) })
                }
                Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Label("Volume")
                    StudioSlider(m.volume, { onLive(project.copy(music = m.copy(volume = it))) }, { onCommit(project) }, min = 0f, max = 1.5f, center = null)
                }
            }
        }
        selected != null -> BasicText(
            "Drag the white handles to trim · scroll to move the playhead",
            style = type.caption.copy(color = Studio.textDim)
        )
        else -> BasicText(
            "Tap a clip to edit it · pinch the timeline to zoom",
            style = type.caption.copy(color = Studio.textDim)
        )
    }
}

@Composable
private fun Timeline(
    project: VideoProject,
    scroll: androidx.compose.foundation.ScrollState,
    pxPerSec: Float,
    onZoom: (Float) -> Unit,
    selectedId: Long?,
    onSelect: (Long) -> Unit,
    onTrimLive: (Clip) -> Unit,
    onTrimDone: () -> Unit,
    onAdd: () -> Unit
) {
    val density = LocalDensity.current
    val zoom by rememberUpdatedState(onZoom)
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(96.dp)
            // Two-finger pinch zooms the timeline; one finger keeps scrolling.
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    while (true) {
                        val e = awaitPointerEvent(PointerEventPass.Initial)
                        val pressed = e.changes.count { it.pressed }
                        if (pressed == 0) break
                        if (pressed >= 2) {
                            val z = e.calculateZoom()
                            if (z != 1f) zoom(z)
                            e.changes.forEach { it.consume() }
                        }
                    }
                }
            }
    ) {
        val half = maxWidth / 2
        Row(
            Modifier
                .fillMaxSize()
                .horizontalScroll(scroll)
                .padding(horizontal = half),
            verticalAlignment = Alignment.CenterVertically
        ) {
            project.clips.forEach { clip ->
                val w = with(density) { (clip.outMs / 1000f * pxPerSec).toDp() }
                ClipStrip(clip, w, clip.id == selectedId, pxPerSec, { onSelect(clip.id) }, onTrimLive, onTrimDone)
                Spacer(Modifier.width(2.dp))
            }
            Box(
                Modifier
                    .padding(start = 8.dp)
                    .size(52.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White.copy(alpha = 0.1f))
                    .clickable(onClick = onAdd),
                contentAlignment = Alignment.Center
            ) {
                Image(Icons.Rounded.Add, "Add clips", Modifier.size(24.dp), colorFilter = ColorFilter.tint(Studio.text))
            }
        }
        // Playhead
        Box(
            Modifier
                .align(Alignment.Center)
                .width(2.dp)
                .fillMaxHeight()
                .clip(CircleShape)
                .background(Color.White)
        )
    }
}

@Composable
private fun ClipStrip(
    clip: Clip,
    width: Dp,
    selected: Boolean,
    pxPerSec: Float,
    onTap: () -> Unit,
    onTrimLive: (Clip) -> Unit,
    onTrimDone: () -> Unit
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val type = Lumen.type
    val heightPx = with(density) { 56.dp.roundToPx() }
    val frameW = with(density) { (56.dp * (clip.width.toFloat() / clip.height.coerceAtLeast(1)).coerceIn(0.5f, 2f)) }
    val count = (width / frameW).toInt().coerceIn(1, 60) + 1
    val latest by rememberUpdatedState(clip)
    Box(
        Modifier
            .width(width)
            .height(60.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Studio.panel)
            .then(if (selected) Modifier.border(2.dp, Color.White, RoundedCornerShape(10.dp)) else Modifier)
            .clickable(onClick = onTap)
    ) {
        Row(Modifier.fillMaxSize()) {
            for (i in 0 until count) {
                val t = clip.trimStartMs + ((clip.trimEndMs - clip.trimStartMs) * i / count.toFloat()).roundToLong()
                var bmp by remember(clip.uri, t / 250) { mutableStateOf(FilmStrip.cached(clip, t)) }
                LaunchedEffect(clip.uri, t / 250) {
                    if (bmp == null) bmp = FilmStrip.frame(context, clip, t, heightPx)
                }
                Box(Modifier.width(frameW).fillMaxHeight().background(Color.White.copy(alpha = 0.04f))) {
                    bmp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                }
            }
        }
        Row(
            Modifier.align(Alignment.BottomStart).padding(6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (clip.speed != 1f) Badge("${clip.speed}×")
            if (clip.volume == 0f && !clip.isImage) Badge("muted")
            if (clip.text.isNotBlank()) Badge("T")
        }
        BasicText(
            time(clip.outMs),
            style = type.caption.copy(color = Color.White),
            modifier = Modifier.align(Alignment.TopEnd).padding(horizontal = 18.dp, vertical = 4.dp)
        )
        if (selected) {
            TrimHandle(Modifier.align(Alignment.CenterStart), onTrimDone) { dxPx ->
                val c = latest
                val deltaMs = (dxPx / pxPerSec * 1000f * c.speed).roundToLong()
                val next = if (c.isImage) c.copy(trimEndMs = (c.trimEndMs - deltaMs).coerceIn(500, 60_000))
                else c.copy(trimStartMs = (c.trimStartMs + deltaMs).coerceIn(0, c.trimEndMs - 300))
                onTrimLive(next)
            }
            TrimHandle(Modifier.align(Alignment.CenterEnd), onTrimDone) { dxPx ->
                val c = latest
                val deltaMs = (dxPx / pxPerSec * 1000f * c.speed).roundToLong()
                val max = if (c.isImage) 60_000 else c.sourceMs
                onTrimLive(c.copy(trimEndMs = (c.trimEndMs + deltaMs).coerceIn(c.trimStartMs + 300, max)))
            }
        }
    }
}

@Composable
private fun TrimHandle(modifier: Modifier, onEnd: () -> Unit, onDrag: (Float) -> Unit) {
    Box(
        modifier
            .width(16.dp)
            .fillMaxHeight()
            .background(Color.White)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    var last = down.position.x
                    while (true) {
                        val e = awaitPointerEvent()
                        val c = e.changes.first()
                        if (!c.pressed) break
                        val dx = c.position.x - last
                        last = c.position.x
                        if (dx != 0f) onDrag(dx)
                        c.consume()
                    }
                    onEnd()
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Box(Modifier.width(3.dp).height(18.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.5f)))
    }
}

@Composable
private fun Badge(text: String) {
    BasicText(
        text,
        style = Lumen.type.caption.copy(color = Color.White),
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 5.dp, vertical = 1.dp)
    )
}

private fun time(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60) + if (ms < 10_000) ".%d".format((ms % 1000) / 100) else ""
}
