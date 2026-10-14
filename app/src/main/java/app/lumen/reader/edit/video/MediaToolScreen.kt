package app.lumen.reader.edit.video

import androidx.annotation.OptIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import app.lumen.reader.core.DocRef
import app.lumen.reader.core.Docs
import app.lumen.reader.core.LocalPickers
import app.lumen.reader.core.Route
import app.lumen.reader.core.Saver
import app.lumen.reader.core.formatBytes
import app.lumen.reader.edit.toast
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume

/** One-shot video utilities: shrink a video, or pull its soundtrack out as M4A. */
@kotlin.OptIn(ExperimentalMaterial3Api::class)
@OptIn(UnstableApi::class)
@Composable
fun MediaToolScreen(route: Route.MediaTool, onBack: () -> Unit) {
    val context = LocalContext.current
    val pickers = LocalPickers.current
    val scope = rememberCoroutineScope()
    var ref by remember { mutableStateOf(route.ref) }
    var tool by remember { mutableStateOf(route.tool) }
    var height by remember { mutableIntStateOf(720) }
    var keepAudio by remember { mutableStateOf(true) }
    var progress by remember { mutableStateOf<Int?>(null) }
    var transformer by remember { mutableStateOf<Transformer?>(null) }
    var resultText by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        if (ref == null) {
            val uri = pickers.openDocuments(arrayOf("video/*"), false).firstOrNull()
            if (uri == null) onBack() else ref = runCatching { Docs.resolve(context, uri) }.getOrNull()
        }
    }

    fun run() {
        val src = ref ?: return
        resultText = null
        val audioOnly = tool == Route.MediaTool.Tool.ExtractAudio
        val out = File(context.cacheDir, if (audioOnly) "tool.m4a" else "tool.mp4").apply { delete() }
        val item = EditedMediaItem.Builder(MediaItem.fromUri(src.uri)).apply {
            if (audioOnly) setRemoveVideo(true)
            else {
                if (!keepAudio) setRemoveAudio(true)
                setEffects(Effects(emptyList(), listOf(Presentation.createForShortSide(height))))
            }
        }.build()
        progress = 0
        scope.launch {
            val t = Transformer.Builder(context).apply {
                if (audioOnly) setAudioMimeType(MimeTypes.AUDIO_AAC) else setVideoMimeType(MimeTypes.VIDEO_H264)
            }.build()
            transformer = t
            var failure: String? = null
            val ok = suspendCancellableCoroutine { cont ->
                t.addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        if (cont.isActive) cont.resume(true)
                    }

                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        failure = exportException.errorCodeName
                        if (cont.isActive) cont.resume(false)
                    }
                })
                t.start(item, out.absolutePath)
                cont.invokeOnCancellation { t.cancel() }
                scope.launch {
                    val h = ProgressHolder()
                    while (cont.isActive) {
                        if (t.getProgress(h) == Transformer.PROGRESS_STATE_AVAILABLE) progress = h.progress
                        delay(200)
                    }
                }
            }
            transformer = null
            progress = null
            if (!ok) {
                toast(context, "Failed" + (failure?.let { " ($it)" } ?: "")); return@launch
            }
            val base = src.name.substringBeforeLast('.')
            val name = if (audioOnly) "$base.m4a" else "$base ${height}p.mp4"
            val result = Saver.saveMedia(context, pickers, name, if (audioOnly) "audio/mp4" else "video/mp4") { o ->
                out.inputStream().use { it.copyTo(o) }
            }
            resultText = if (result is Saver.Result.SavedCopy) {
                val before = src.size
                "Saved to ${if (audioOnly) "Music" else "Movies"}/Lumen · ${formatBytes(out.length())}" +
                    if (!audioOnly && before > 0) " (${100 - out.length() * 100 / before}% smaller)" else ""
            } else Saver.describe(result)
            out.delete()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(tool.label) },
                navigationIcon = { IconButton({ transformer?.cancel(); onBack() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } }
            )
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                Route.MediaTool.Tool.entries.forEachIndexed { i, t ->
                    SegmentedButton(tool == t, { tool = t }, SegmentedButtonDefaults.itemShape(i, Route.MediaTool.Tool.entries.size)) { Text(t.label) }
                }
            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(ref?.name ?: "Choose a video", style = MaterialTheme.typography.titleMedium)
                    ref?.let { Text(formatBytes(it.size), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton({
                        scope.launch {
                            pickers.openDocuments(arrayOf("video/*"), false).firstOrNull()?.let { uri ->
                                ref = runCatching { Docs.resolve(context, uri) }.getOrNull()
                            }
                        }
                    }) { Text("Choose another") }
                }
            }
            if (tool == Route.MediaTool.Tool.Compress) {
                Text("Resolution", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(480, 720, 1080).forEach { h -> FilterChip(height == h, { height = h }, { Text("${h}p") }) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(keepAudio, { keepAudio = !keepAudio }, { Text(if (keepAudio) "Keep sound" else "No sound") })
                }
            }
            progress?.let { p ->
                LinearProgressIndicator({ p / 100f }, Modifier.fillMaxWidth())
                Text("Working… $p%", style = MaterialTheme.typography.bodyMedium)
            }
            resultText?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary) }
            Button(::run, enabled = ref != null && progress == null, modifier = Modifier.fillMaxWidth()) {
                Text(if (tool == Route.MediaTool.Tool.Compress) "Compress" else "Extract audio")
            }
        }
    }
}
