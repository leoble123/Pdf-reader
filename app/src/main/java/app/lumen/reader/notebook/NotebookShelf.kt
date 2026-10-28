package app.lumen.reader.notebook

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.lumen.reader.core.DocRef
import app.lumen.reader.pdf.InkController
import app.lumen.reader.pdf.drawMarks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Home-screen row: a "New" card followed by every notebook, newest first, with its first page. */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun NotebookShelf(onOpen: (DocRef) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var version by remember { mutableIntStateOf(0) }
    // A handful of tiny metadata files: read straight away so the shelf never pops in late.
    var books by remember { mutableStateOf(Notebooks.list(context)) }
    LaunchedEffect(version) { if (version > 0) books = withContext(Dispatchers.IO) { Notebooks.list(context) } }
    var picking by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<Notebooks.Info?>(null) }
    var renaming by remember { mutableStateOf<Notebooks.Info?>(null) }

    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        item(key = "new") {
            Column {
                Card(Modifier.combinedClickable(onClick = { picking = true })) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Add, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                }
                Caption("New notebook", null)
            }
        }
        items(books, key = { it.file.path }) { b ->
            Column {
                Box {
                    Card(Modifier.combinedClickable(onClick = { onOpen(b.ref()) }, onLongClick = { menuFor = b })) {
                        PagePreview(b)
                    }
                    DropdownMenu(menuFor == b, { menuFor = null }) {
                        DropdownMenuItem({ Text("Rename") }, { menuFor = null; renaming = b }, leadingIcon = { Icon(Icons.Rounded.Edit, null) })
                        DropdownMenuItem({ Text("Delete") }, {
                            menuFor = null; Notebooks.delete(context, b); version++
                        }, leadingIcon = { Icon(Icons.Rounded.Delete, null) })
                    }
                }
                Caption(b.name, "${b.pages} page" + if (b.pages == 1) "" else "s")
            }
        }
    }

    if (picking) {
        ModalBottomSheet(onDismissRequest = { picking = false }) {
            Text("New notebook", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 24.dp, bottom = 16.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Paper.entries.forEach { paper ->
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            tonalElevation = 2.dp,
                            shadowElevation = 2.dp,
                            modifier = Modifier.fillMaxWidth().aspectRatio(Notebooks.PAGE_W / Notebooks.PAGE_H.toFloat())
                                .clip(RoundedCornerShape(10.dp))
                                .combinedClickable(onClick = {
                                    picking = false
                                    scope.launch { onOpen(Notebooks.create(context, paper).ref()) }
                                })
                        ) { PaperCanvas(paper) }
                        Text(paper.label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                    }
                }
            }
            Text(
                "Pen draws, fingers scroll. Add pages from the toolbar; export as a PDF any time.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 32.dp)
            )
        }
    }

    renaming?.let { b ->
        var text by remember(b) { mutableStateOf(b.name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename notebook") },
            text = { OutlinedTextField(text, { text = it }, singleLine = true) },
            confirmButton = {
                TextButton({
                    renaming = null
                    scope.launch { Notebooks.rename(b, text); version++ }
                }) { Text("Rename") }
            },
            dismissButton = { TextButton({ renaming = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun Card(modifier: Modifier, content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 1.dp,
        shadowElevation = 1.dp,
        modifier = Modifier.width(104.dp).aspectRatio(Notebooks.PAGE_W / Notebooks.PAGE_H.toFloat()).clip(RoundedCornerShape(12.dp)).then(modifier)
    ) { content() }
}

@Composable
private fun Caption(title: String, sub: String?) {
    Column(Modifier.width(104.dp).padding(top = 6.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PaperCanvas(paper: Paper) {
    Canvas(Modifier.fillMaxSize()) {
        drawIntoCanvas { Notebooks.drawPaper(it.nativeCanvas, paper, size.width, size.height) }
    }
}

/** The first page as it looks now: paper plus ink. */
@Composable
private fun PagePreview(b: Notebooks.Info) {
    val context = LocalContext.current
    val ink = remember(b.file.path) { InkController.forDoc(context, b.ref()) }
    val marks = ink.pages[0].orEmpty()
    Canvas(Modifier.fillMaxSize()) {
        drawIntoCanvas { Notebooks.drawPaper(it.nativeCanvas, b.paper, size.width, size.height) }
        if (marks.isNotEmpty()) drawMarks(marks, null)
    }
}
