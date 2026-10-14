package app.lumen.reader.scan

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AddAPhoto
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Crop
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DocumentScanner
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.RotateRight
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import app.lumen.reader.core.DocRef
import app.lumen.reader.core.Docs
import app.lumen.reader.core.FileKind
import app.lumen.reader.core.LocalPickers
import app.lumen.reader.core.Saver
import app.lumen.reader.edit.toast
import app.lumen.reader.pdf.engine.PdfAssembler
import app.lumen.reader.pdf.tools.jpegOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

private class ScanPage(
    val id: Long,
    val source: Bitmap,
    val corners: List<Offset>,
    val rotation: Int = 0,
    val filter: ScanFilter = ScanFilter.Enhance,
    val result: Bitmap? = null
) {
    fun copy(corners: List<Offset> = this.corners, rotation: Int = this.rotation, filter: ScanFilter = this.filter, result: Bitmap? = null) =
        ScanPage(id, source, corners, rotation, filter, result)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScannerScreen(onBack: () -> Unit, onOpen: (DocRef) -> Unit) {
    val context = LocalContext.current
    val pickers = LocalPickers.current
    val scope = rememberCoroutineScope()
    val pages = remember { mutableStateListOf<ScanPage>() }
    var current by remember { mutableIntStateOf(0) }
    var adjusting by remember { mutableStateOf<Long?>(null) }
    var busy by remember { mutableStateOf(false) }
    var pendingPhoto by remember { mutableStateOf<File?>(null) }

    suspend fun process(page: ScanPage): ScanPage = withContext(Dispatchers.Default) {
        val flat = ScanProcessing.warp(page.source, page.corners)
        val turned = ScanProcessing.rotate(flat, page.rotation)
        page.copy(result = ScanProcessing.filter(turned, page.filter))
    }

    fun addImage(ref: DocRef) {
        scope.launch {
            busy = true
            runCatching {
                val bmp = withContext(Dispatchers.IO) { app.lumen.reader.edit.photo.decode(context, ref, 3000) }
                val corners = withContext(Dispatchers.Default) { ScanProcessing.detect(bmp) }
                val page = process(ScanPage(System.nanoTime(), bmp, corners))
                pages += page
                current = pages.lastIndex
            }.onFailure { toast(context, "Couldn't read that image") }
            busy = false
        }
    }

    fun update(index: Int, page: ScanPage) {
        pages[index] = page
        scope.launch {
            val done = process(page)
            val i = pages.indexOfFirst { it.id == page.id }
            if (i >= 0) pages[i] = done
        }
    }

    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val file = pendingPhoto
        if (ok && file != null && file.length() > 0) {
            val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
            addImage(DocRef(uri, file.name, "image/jpeg", file.length(), FileKind.Image))
        }
    }

    fun takePhoto() {
        val dir = File(context.cacheDir, "scan").apply { mkdirs() }
        val file = File(dir, "page_${System.currentTimeMillis()}.jpg")
        pendingPhoto = file
        val uri: Uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
        runCatching { camera.launch(uri) }.onFailure { toast(context, "No camera app available") }
    }

    fun importPhotos() {
        scope.launch {
            pickers.openDocuments(arrayOf("image/*"), true).forEach { uri ->
                runCatching { Docs.resolve(context, uri) }.getOrNull()?.let { addImage(it) }
            }
        }
    }

    fun savePdf() {
        if (pages.isEmpty() || busy) return
        scope.launch {
            busy = true
            val bytes = withContext(Dispatchers.Default) {
                val asm = PdfAssembler()
                for (p in pages) {
                    val bmp = p.result ?: process(p).result!!
                    val jpeg = jpegOf(bmp, if (p.filter == ScanFilter.Document) 80 else 85)
                    // A4 width; height follows the page's own proportions.
                    asm.addJpegPage(jpeg, bmp.width, bmp.height, 595f, 595f * bmp.height / bmp.width)
                }
                java.io.ByteArrayOutputStream().also { asm.write(it, "Scan") }.toByteArray()
            }
            val name = "Scan " + SimpleDateFormat("yyyy-MM-dd HH.mm", Locale.US).format(Date()) + ".pdf"
            val result = Saver.saveCopy(context, pickers, name, "application/pdf") { it.write(bytes) }
            busy = false
            toast(context, result)
            if (result is Saver.Result.SavedCopy) {
                File(context.cacheDir, "scan").deleteRecursively()
                runCatching { Docs.resolve(context, result.uri, "application/pdf") }.getOrNull()?.let(onOpen)
            }
        }
    }

    BackHandler(adjusting != null) { adjusting = null }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (pages.isEmpty()) "Scan" else "Scan · ${pages.size} page" + if (pages.size == 1) "" else "s") },
                navigationIcon = { IconButton(if (adjusting != null) ({ adjusting = null }) else onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                actions = {
                    if (adjusting == null && pages.isNotEmpty()) {
                        Button(::savePdf, enabled = !busy, modifier = Modifier.padding(end = 8.dp)) { Text("Save PDF") }
                    }
                }
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            val adjustingPage = pages.firstOrNull { it.id == adjusting }
            when {
                adjustingPage != null -> CornerEditor(adjustingPage) { corners ->
                    val i = pages.indexOfFirst { it.id == adjustingPage.id }
                    if (i >= 0) update(i, adjustingPage.copy(corners = corners))
                    adjusting = null
                }
                pages.isEmpty() -> EmptyScan(::takePhoto, ::importPhotos)
                else -> Column(Modifier.fillMaxSize()) {
                    val page = pages[current.coerceIn(0, pages.lastIndex)]
                    Box(Modifier.weight(1f).fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                        val result = page.result
                        if (result != null) {
                            Image(
                                result.asImageBitmap(), "Scanned page",
                                Modifier.clip(RoundedCornerShape(8.dp)).border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp)),
                                contentScale = ContentScale.Fit
                            )
                        } else CircularProgressIndicator()
                    }
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ScanFilter.entries.forEach { f ->
                            FilterChip(page.filter == f, { update(current, page.copy(filter = f)) }, { Text(f.label) })
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                        TextButton({ adjusting = page.id }) { Icon(Icons.Rounded.Crop, null); Text("  Corners") }
                        TextButton({ update(current, page.copy(rotation = (page.rotation + 1) % 4)) }) { Icon(Icons.Rounded.RotateRight, null); Text("  Rotate") }
                        TextButton({
                            pages.removeAt(current)
                            current = current.coerceAtMost(pages.lastIndex).coerceAtLeast(0)
                        }) { Icon(Icons.Rounded.Delete, null); Text("  Delete") }
                    }
                    LazyRow(
                        Modifier.fillMaxWidth().height(112.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        itemsIndexed(pages, key = { _, p -> p.id }) { i, p ->
                            Box(
                                Modifier
                                    .size(width = 72.dp, height = 96.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .border(
                                        if (i == current) 3.dp else 1.dp,
                                        if (i == current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                        RoundedCornerShape(8.dp)
                                    )
                                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                    .clickable { current = i },
                                contentAlignment = Alignment.Center
                            ) {
                                p.result?.let { Image(it.asImageBitmap(), "Page ${i + 1}", Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                            }
                        }
                        item {
                            Column(Modifier.height(96.dp), verticalArrangement = Arrangement.SpaceEvenly) {
                                FilledTonalButton(::takePhoto) { Icon(Icons.Rounded.AddAPhoto, "Take another photo") }
                                FilledTonalButton(::importPhotos) { Icon(Icons.Rounded.PhotoLibrary, "Import photos") }
                            }
                        }
                    }
                }
            }
            if (busy) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.25f)), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
        }
    }
}

@Composable
private fun EmptyScan(onCamera: () -> Unit, onImport: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(Icons.Rounded.DocumentScanner, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text("Scan documents", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            "Photograph a page on a darker surface. Lumen finds the edges, flattens the page and cleans it up — all on your phone.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        Spacer(Modifier.height(24.dp))
        Button(onCamera) { Icon(Icons.Rounded.AddAPhoto, null); Text("  Take photo") }
        Spacer(Modifier.height(8.dp))
        FilledTonalButton(onImport) { Icon(Icons.Rounded.PhotoLibrary, null); Text("  Import from gallery") }
    }
}

/** Shows the original photo with the detected page outline; drag the corners to fix it. */
@Composable
private fun CornerEditor(page: ScanPage, onDone: (List<Offset>) -> Unit) {
    val corners = remember(page.id) { mutableStateListOf(*page.corners.toTypedArray()) }
    val primary = MaterialTheme.colorScheme.primary
    val density = LocalDensity.current
    Column(Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
            val bw = constraints.maxWidth.toFloat()
            val bh = constraints.maxHeight.toFloat()
            val s = min(bw / page.source.width, bh / page.source.height)
            val w = page.source.width * s
            val h = page.source.height * s
            val image = remember(page.id) { page.source.asImageBitmap() }
            val frame = Modifier
                .offset { IntOffset(((bw - w) / 2).roundToInt(), ((bh - h) / 2).roundToInt()) }
                .size(with(density) { w.toDp() }, with(density) { h.toDp() })
            Box(Modifier.fillMaxSize()) {
                Image(image, null, frame, contentScale = ContentScale.FillBounds)
                val latest by rememberUpdatedState(corners.toList())
                Canvas(
                    frame.pointerInput(page.id) {
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            val pos = Offset(down.position.x / size.width, down.position.y / size.height)
                            val idx = latest.indices.minBy { hypot(latest[it].x - pos.x, latest[it].y - pos.y) }
                            while (true) {
                                val e = awaitPointerEvent()
                                val c = e.changes.first()
                                if (!c.pressed) break
                                corners[idx] = Offset((c.position.x / size.width).coerceIn(0f, 1f), (c.position.y / size.height).coerceIn(0f, 1f))
                                c.consume()
                            }
                        }
                    }
                ) {
                    val pts = corners.map { Offset(it.x * size.width, it.y * size.height) }
                    val path = Path().apply {
                        moveTo(pts[0].x, pts[0].y); pts.drop(1).forEach { lineTo(it.x, it.y) }; close()
                    }
                    drawPath(path, primary.copy(alpha = 0.18f))
                    drawPath(path, primary, style = Stroke(2.dp.toPx()))
                    pts.forEach {
                        drawCircle(Color.White, 14.dp.toPx(), it)
                        drawCircle(primary, 14.dp.toPx(), it, style = Stroke(3.dp.toPx()))
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
            TextButton({
                corners.clear(); corners.addAll(listOf(Offset(0f, 0f), Offset(1f, 0f), Offset(1f, 1f), Offset(0f, 1f)))
            }) { Text("Full image") }
            Button({ onDone(corners.toList()) }) { Icon(Icons.Rounded.Check, null); Text("  Apply") }
        }
    }
}
