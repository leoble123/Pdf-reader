package app.lumen.reader.pdf.tools

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.RotateRight
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.lumen.reader.core.DocRef
import app.lumen.reader.core.Docs
import app.lumen.reader.core.FileKind
import app.lumen.reader.core.LocalPickers
import app.lumen.reader.core.Saver
import app.lumen.reader.edit.toast
import app.lumen.reader.pdf.PdfDoc
import app.lumen.reader.pdf.engine.PdfAssembler
import app.lumen.reader.pdf.engine.PdfReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/** A document pages come from: a PDF (copied losslessly) or a single image (embedded as JPEG). */
private sealed interface Source {
    val ref: DocRef

    class Pdf(override val ref: DocRef, val reader: PdfReader, val preview: PdfDoc?) : Source
    class Image(override val ref: DocRef, val jpeg: ByteArray, val width: Int, val height: Int) : Source
}

private data class PageItem(val id: Long, val source: Int, val page: Int, val rotation: Int = 0)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfToolsScreen(refs: List<DocRef>, onBack: () -> Unit, onOpen: (DocRef) -> Unit) {
    val context = LocalContext.current
    val pickers = LocalPickers.current
    val scope = rememberCoroutineScope()
    val sources = remember { mutableStateListOf<Source>() }
    val items = remember { mutableStateListOf<PageItem>() }
    val selected = remember { mutableStateListOf<Long>() }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf<String?>(null) }
    var saveDialog by remember { mutableStateOf(false) }
    var addMenu by remember { mutableStateOf(false) }
    var dirty by remember { mutableStateOf(false) }
    var leaveDialog by remember { mutableStateOf(false) }
    var nextId by remember { mutableStateOf(0L) }

    suspend fun load(newRefs: List<DocRef>) {
        loading = true
        for (ref in newRefs) {
            val src = runCatching { openSource(context, ref) }.getOrElse {
                toast(context, "${ref.name}: ${it.message ?: "can't open"}"); null
            } ?: continue
            sources += src
            val index = sources.lastIndex
            val count = if (src is Source.Pdf) src.reader.pages.size else 1
            repeat(count) { p -> items += PageItem(nextId++, index, p) }
        }
        loading = false
    }

    LaunchedEffect(Unit) {
        if (refs.isNotEmpty()) load(refs) else {
            val picked = pickers.openDocuments(arrayOf("application/pdf", "image/*"), true)
            if (picked.isEmpty()) {
                onBack(); return@LaunchedEffect
            }
            load(picked.mapNotNull { runCatching { Docs.resolve(context, it) }.getOrNull() })
        }
    }
    DisposableEffect(Unit) { onDispose { sources.forEach { (it as? Source.Pdf)?.preview?.close() } } }

    fun addMore(mimes: Array<String>) {
        scope.launch {
            val picked = pickers.openDocuments(mimes, true)
            load(picked.mapNotNull { runCatching { Docs.resolve(context, it) }.getOrNull() })
            dirty = true
        }
    }

    fun build(only: Set<Long>?): ByteArray {
        val asm = PdfAssembler()
        for (item in items) {
            if (only != null && item.id !in only) continue
            when (val s = sources[item.source]) {
                is Source.Pdf -> asm.addPage(s.reader, s.reader.pages[item.page], item.rotation)
                is Source.Image -> asm.addJpegPage(s.jpeg, s.width, s.height, 595f, 595f * s.height / s.width, rotate = item.rotation)
            }
        }
        return ByteArrayOutputStream().also { asm.write(it) }.toByteArray()
    }

    fun save(replace: Boolean, only: Set<Long>? = null) {
        saveDialog = false
        scope.launch {
            busy = "Saving…"
            val bytes = runCatching { withContext(Dispatchers.Default) { build(only) } }.getOrElse {
                busy = null; toast(context, it.message ?: "Couldn't build the PDF"); return@launch
            }
            val first = sources.first().ref
            val result = if (replace) Saver.save(context, pickers, first) { it.write(bytes) }
            else {
                val base = first.name.substringBeforeLast('.')
                val name = when {
                    only != null -> "$base (pages).pdf"
                    sources.size > 1 -> "$base (merged).pdf"
                    else -> "$base (edited).pdf"
                }
                Saver.saveCopy(context, pickers, name, "application/pdf") { it.write(bytes) }
            }
            busy = null
            toast(context, result)
            when (result) {
                Saver.Result.Saved -> { dirty = false; onOpen(first) }
                is Saver.Result.SavedCopy -> {
                    dirty = false
                    if (only == null) runCatching { Docs.resolve(context, result.uri, "application/pdf") }.getOrNull()?.let(onOpen)
                }
                else -> Unit
            }
        }
    }

    fun moveSelection(delta: Int) {
        val sel = selected.toSet()
        val order = if (delta < 0) items.indices else items.indices.reversed()
        for (i in order) {
            val j = i + delta
            if (items[i].id in sel && j in items.indices && items[j].id !in sel) {
                val t = items[i]; items[i] = items[j]; items[j] = t
            }
        }
        dirty = true
    }

    fun leave() {
        if (dirty) leaveDialog = true else onBack()
    }
    BackHandler(selected.isNotEmpty() || dirty) { if (selected.isNotEmpty()) selected.clear() else leave() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(if (selected.isEmpty()) "Organise pages" else "${selected.size} selected")
                        Text(
                            "${items.size} pages" + if (sources.size > 1) " from ${sources.size} files" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton({ if (selected.isNotEmpty()) selected.clear() else leave() }) {
                        Icon(if (selected.isEmpty()) Icons.AutoMirrored.Rounded.ArrowBack else Icons.Rounded.Close, "Back")
                    }
                },
                actions = {
                    IconButton({
                        if (selected.size == items.size) selected.clear() else { selected.clear(); selected += items.map { it.id } }
                    }) { Icon(Icons.Rounded.SelectAll, "Select all") }
                    Box {
                        IconButton({ addMenu = true }) { Icon(Icons.Rounded.Add, "Add pages") }
                        DropdownMenu(addMenu, { addMenu = false }) {
                            DropdownMenuItem({ Text("Add PDF") }, { addMenu = false; addMore(arrayOf("application/pdf")) })
                            DropdownMenuItem({ Text("Add images") }, { addMenu = false; addMore(arrayOf("image/*")) })
                        }
                    }
                }
            )
        },
        bottomBar = {
            if (selected.isNotEmpty()) BottomAppBar {
                IconButton({
                    for (i in items.indices) if (items[i].id in selected) items[i] = items[i].copy(rotation = (items[i].rotation + 90) % 360)
                    dirty = true
                }) { Icon(Icons.Rounded.RotateRight, "Rotate") }
                IconButton({ moveSelection(-1) }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Move earlier") }
                IconButton({ moveSelection(1) }) { Icon(Icons.AutoMirrored.Rounded.ArrowForward, "Move later") }
                IconButton({
                    val sel = selected.toSet()
                    val keep = items.filter { it.id !in sel }
                    items.clear(); items += keep; selected.clear(); dirty = true
                }, enabled = selected.size < items.size) { Icon(Icons.Rounded.Delete, "Delete") }
                Box(Modifier.weight(1f))
                TextButton({ save(replace = false, only = selected.toSet()) }) {
                    Icon(Icons.Rounded.ContentCopy, null, Modifier.size(18.dp))
                    Text("  Extract")
                }
            }
        },
        floatingActionButton = {
            if (selected.isEmpty() && items.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = { if (sources.size == 1 && sources[0] is Source.Pdf) saveDialog = true else save(replace = false) },
                    icon = { Icon(Icons.Rounded.Save, null) },
                    text = { Text("Save PDF") }
                )
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (loading && items.isEmpty()) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(112.dp),
                    contentPadding = PaddingValues(12.dp, 12.dp, 12.dp, 96.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    itemsIndexed(items, key = { _, it -> it.id }) { index, item ->
                        val isSelected = item.id in selected
                        PageTile(
                            source = sources[item.source],
                            item = item,
                            number = index + 1,
                            showSource = sources.size > 1,
                            selected = isSelected,
                            modifier = Modifier.animateItem(),
                            onClick = { if (isSelected) selected.remove(item.id) else selected += item.id }
                        )
                    }
                }
            }
            busy?.let {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.3f)), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
        }
    }

    if (saveDialog) {
        AlertDialog(
            onDismissRequest = { saveDialog = false },
            title = { Text("Save changes") },
            text = { Text("Replace the original file, or keep it and save the result as a new PDF?") },
            confirmButton = { Button({ save(replace = true) }) { Text("Replace original") } },
            dismissButton = { TextButton({ save(replace = false) }) { Text("Save as copy") } }
        )
    }
    if (leaveDialog) {
        AlertDialog(
            onDismissRequest = { leaveDialog = false },
            title = { Text("Discard changes?") },
            text = { Text("Your page changes haven't been saved.") },
            confirmButton = { TextButton({ leaveDialog = false; onBack() }) { Text("Discard") } },
            dismissButton = { TextButton({ leaveDialog = false }) { Text("Keep editing") } }
        )
    }
}

@Composable
private fun PageTile(
    source: Source,
    item: PageItem,
    number: Int,
    showSource: Boolean,
    selected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit
) {
    var thumb by remember(item.source, item.page) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(item.source, item.page) {
        thumb = runCatching {
            when (source) {
                is Source.Pdf -> source.preview?.renderPreview(item.page)?.asImageBitmap()
                is Source.Image -> withContext(Dispatchers.IO) {
                    BitmapFactory.decodeByteArray(source.jpeg, 0, source.jpeg.size, BitmapFactory.Options().apply {
                        inSampleSize = (source.width / 240).coerceAtLeast(1)
                    })?.asImageBitmap()
                }
            }
        }.getOrNull()
    }
    val shape = RoundedCornerShape(10.dp)
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(0.75f)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .border(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, shape)
                .clickable(onClick = onClick)
                .padding(6.dp),
            contentAlignment = Alignment.Center
        ) {
            thumb?.let {
                Image(
                    it, "Page $number",
                    Modifier.fillMaxSize().graphicsLayer { rotationZ = item.rotation.toFloat() },
                    contentScale = ContentScale.Fit
                )
            } ?: CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            if (selected) {
                Icon(
                    Icons.Rounded.CheckCircle, null,
                    Modifier.align(Alignment.TopEnd).size(24.dp).background(MaterialTheme.colorScheme.surface, CircleShape),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
        Text(
            if (showSource) "$number · ${source.ref.name}" else "$number",
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

private suspend fun openSource(context: android.content.Context, ref: DocRef): Source = withContext(Dispatchers.IO) {
    if (ref.kind == FileKind.Image || ref.mime?.startsWith("image/") == true) {
        val raw = Docs.open(context, ref).use { it.readBytes() }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
        val isJpeg = bounds.outMimeType == "image/jpeg" && orientationIsNormal(raw)
        if (isJpeg) Source.Image(ref, raw, bounds.outWidth, bounds.outHeight)
        else {
            // Other formats (and rotated JPEGs) are decoded upright and re-encoded once.
            val bmp = app.lumen.reader.edit.photo.decode(context, ref, 3000)
            val jpeg = jpegOf(bmp)
            Source.Image(ref, jpeg, bmp.width, bmp.height)
        }
    } else {
        val bytes = Docs.open(context, ref).use { it.readBytes() }
        val reader = PdfReader(bytes)
        val preview = runCatching { PdfDoc.open(context, ref) }.getOrNull()
        Source.Pdf(ref, reader, preview)
    }
}

private fun orientationIsNormal(jpeg: ByteArray): Boolean = runCatching {
    android.media.ExifInterface(jpeg.inputStream()).getAttributeInt(
        android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL
    ) in setOf(android.media.ExifInterface.ORIENTATION_NORMAL, android.media.ExifInterface.ORIENTATION_UNDEFINED)
}.getOrDefault(true)

internal fun jpegOf(bitmap: Bitmap, quality: Int = 88): ByteArray {
    val opaque = if (bitmap.hasAlpha()) {
        Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888).also {
            val c = android.graphics.Canvas(it)
            c.drawColor(android.graphics.Color.WHITE)
            c.drawBitmap(bitmap, 0f, 0f, null)
        }
    } else bitmap
    return ByteArrayOutputStream().also { opaque.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()
}
