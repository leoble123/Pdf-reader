package app.lumen.reader.archive

import android.content.Context
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import app.lumen.reader.core.DocRef
import app.lumen.reader.core.Docs
import app.lumen.reader.core.FileKind
import app.lumen.reader.core.formatBytes
import app.lumen.reader.ui.theme.Lumen
import app.lumen.reader.viewer.CenteredMessage
import app.lumen.reader.viewer.LoadingIndicator
import app.lumen.reader.viewer.ViewerScaffold
import app.lumen.reader.viewer.kindAccent
import app.lumen.reader.viewer.kindIcon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipFile

private class Entry(val path: String, val size: Long, val compressed: Long, val isDir: Boolean)

@Composable
fun ZipScreen(ref: DocRef, onBack: () -> Unit, onOpen: (DocRef) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var entries by remember { mutableStateOf<List<Entry>?>(null) }
    var file by remember { mutableStateOf<File?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var dir by remember { mutableStateOf("") }
    var opening by remember { mutableStateOf(false) }

    LaunchedEffect(ref) {
        runCatching {
            withContext(Dispatchers.IO) {
                val f = Docs.localFile(context, ref)
                file = f
                ZipFile(f).use { z ->
                    val list = ArrayList<Entry>()
                    val dirs = HashSet<String>()
                    for (e in z.entries()) {
                        val name = e.name.trimStart('/')
                        if (name.contains("..")) continue
                        if (e.isDirectory) dirs += name.trimEnd('/') else list += Entry(name, e.size, e.compressedSize, false)
                        // Synthesize parent folders that aren't stored explicitly.
                        var parent = name.trimEnd('/').substringBeforeLast('/', "")
                        while (parent.isNotEmpty()) {
                            dirs += parent; parent = parent.substringBeforeLast('/', "")
                        }
                    }
                    list + dirs.map { Entry(it, 0, 0, true) }
                }
            }
        }.onSuccess { entries = it }.onFailure { error = it.message ?: "Not a readable archive" }
    }

    BackHandler(enabled = dir.isNotEmpty()) { dir = dir.substringBeforeLast('/', "") }

    val colors = Lumen.colors
    val type = Lumen.type
    val all = entries
    val visible = remember(all, dir) {
        all?.filter { it.path.substringBeforeLast('/', "") == dir }
            ?.sortedWith(compareBy({ !it.isDir }, { it.path.lowercase() }))
    }

    ViewerScaffold(
        ref = ref,
        title = if (dir.isEmpty()) ref.name else dir.substringAfterLast('/'),
        subtitle = if (dir.isEmpty()) all?.let { "${it.count { e -> !e.isDir }} files · ${formatBytes(ref.size)}" } else ref.name + "/" + dir,
        chromeVisible = true,
        onBack = { if (dir.isNotEmpty()) dir = dir.substringBeforeLast('/', "") else onBack() }
    ) { padding ->
        when {
            error != null -> CenteredMessage("Couldn't open archive", error)
            visible == null || opening -> LoadingIndicator()
            visible.isEmpty() -> CenteredMessage("Empty folder", null)
            else -> LazyColumn(contentPadding = padding, modifier = Modifier.padding(horizontal = 12.dp)) {
                items(visible, key = { it.path }) { e ->
                    val name = e.path.substringAfterLast('/')
                    val kind = if (e.isDir) null else FileKind.detect(name, null)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .clickable {
                                if (e.isDir) dir = e.path
                                else scope.launch {
                                    opening = true
                                    runCatching { extract(context, file!!, ref, e) }.onSuccess(onOpen)
                                    opening = false
                                }
                            }
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        val accent = if (kind == null) kindAccent(FileKind.Zip) else kindAccent(kind)
                        Box(
                            Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(accent.copy(alpha = 0.16f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Image(
                                if (kind == null) Icons.Rounded.Folder else kindIcon(kind), null,
                                Modifier.size(22.dp), colorFilter = ColorFilter.tint(accent)
                            )
                        }
                        Column(Modifier.weight(1f)) {
                            BasicText(name, style = type.label.copy(color = colors.content), maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                            BasicText(
                                if (e.isDir) "Folder" else "${kind?.label ?: "File"} · ${formatBytes(e.size)}",
                                style = type.caption.copy(color = colors.contentTertiary)
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Extracts one entry into the cache and exposes it through our FileProvider. */
private suspend fun extract(context: Context, zipFile: File, parent: DocRef, e: Entry): DocRef = withContext(Dispatchers.IO) {
    val dir = File(context.cacheDir, "unzipped/" + Docs.hash(parent.key + parent.size)).apply { mkdirs() }
    val name = e.path.substringAfterLast('/')
    val out = File(dir, Docs.hash(e.path) + "_" + name)
    if (!out.exists() || out.length() != e.size) {
        ZipFile(zipFile).use { z ->
            z.getInputStream(z.getEntry(e.path)).use { input -> out.outputStream().use { input.copyTo(it, 1 shl 16) } }
        }
    }
    val uri: Uri = FileProvider.getUriForFile(context, context.packageName + ".files", out)
    val kind = FileKind.detect(name, null).let { k ->
        if (k == FileKind.Zip) Docs.refineZipKind(out) else k
    }
    DocRef(uri, name, context.contentResolver.getType(uri), out.length(), kind)
}
