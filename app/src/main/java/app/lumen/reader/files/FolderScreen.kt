package app.lumen.reader.files

import android.content.Context
import android.provider.DocumentsContract
import android.text.format.DateUtils
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.lumen.reader.core.DocRef
import app.lumen.reader.core.FileKind
import app.lumen.reader.core.Route
import app.lumen.reader.core.Thumbs
import app.lumen.reader.core.formatBytes
import app.lumen.reader.viewer.kindAccent
import app.lumen.reader.viewer.kindIcon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class Child(val id: String, val name: String, val mime: String, val size: Long, val modified: Long) {
    val isDir get() = mime == DocumentsContract.Document.MIME_TYPE_DIR
}

private enum class Sort(val label: String) { Name("Name"), Newest("Newest"), Largest("Largest"), Type("Type") }

/** Lists a folder the user granted through the system picker (no storage permission needed). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderScreen(
    route: Route.Folder,
    onBack: () -> Unit,
    onOpenFile: (DocRef, List<DocRef>) -> Unit,
    onOpenFolder: (Route) -> Unit
) {
    val context = LocalContext.current
    var children by remember { mutableStateOf<List<Child>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var sort by rememberSaveable { mutableStateOf(Sort.Name) }
    var sortMenu by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }

    LaunchedEffect(route) {
        runCatching { list(context, route) }.onSuccess { children = it }.onFailure { error = it.message ?: "This folder is no longer accessible" }
    }
    val shown = remember(children, sort, query) {
        children.orEmpty()
            .filter { query.isBlank() || it.name.contains(query, ignoreCase = true) }
            .sortedWith(
                compareBy<Child> { !it.isDir }.then(
                    when (sort) {
                        Sort.Name -> compareBy { it.name.lowercase() }
                        Sort.Newest -> compareByDescending { it.modified }
                        Sort.Largest -> compareByDescending { it.size }
                        Sort.Type -> compareBy<Child> { it.name.substringAfterLast('.', "").lowercase() }.thenBy { it.name.lowercase() }
                    }
                )
            )
    }
    fun refOf(c: Child): DocRef {
        val uri = DocumentsContract.buildDocumentUriUsingTree(route.tree, c.id)
        return DocRef(uri, c.name, c.mime, c.size, FileKind.detect(c.name, c.mime))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (searching) TextField(
                        value = query, onValueChange = { query = it }, singleLine = true,
                        placeholder = { Text("Filter") },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent
                        )
                    ) else Column {
                        Text(route.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        children?.let {
                            Text("${it.size} items", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                actions = {
                    IconButton({ searching = !searching; if (!searching) query = "" }) {
                        Icon(if (searching) Icons.Rounded.Close else Icons.Rounded.Search, "Filter")
                    }
                    Box {
                        IconButton({ sortMenu = true }) { Icon(Icons.AutoMirrored.Rounded.Sort, "Sort") }
                        DropdownMenu(sortMenu, { sortMenu = false }) {
                            Sort.entries.forEach { s ->
                                DropdownMenuItem({ Text(s.label + if (s == sort) "  ✓" else "") }, { sort = s; sortMenu = false })
                            }
                        }
                    }
                }
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                error != null -> Text(error!!, Modifier.align(Alignment.Center).padding(32.dp))
                children == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                shown.isEmpty() -> Text(if (query.isBlank()) "This folder is empty" else "No matches", Modifier.align(Alignment.Center))
                else -> LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                    items(shown, key = { it.id }) { c ->
                        val kind = if (c.isDir) null else FileKind.detect(c.name, c.mime)
                        ListItem(
                            headlineContent = { Text(c.name, maxLines = 1, overflow = TextOverflow.MiddleEllipsis) },
                            supportingContent = {
                                Text(
                                    if (c.isDir) "Folder" else listOfNotNull(
                                        kind?.label, formatBytes(c.size),
                                        c.modified.takeIf { it > 0 }?.let { DateUtils.getRelativeTimeSpanString(it).toString() }
                                    ).joinToString(" · ")
                                )
                            },
                            leadingContent = { Leading(c, kind, if (kind != null) refOf(c) else null) },
                            modifier = Modifier.clickable {
                                if (c.isDir) onOpenFolder(Route.Folder(route.tree, c.id, c.name))
                                else onOpenFile(refOf(c), if (kind == FileKind.Image) shown.filter { !it.isDir && FileKind.detect(it.name, it.mime) == FileKind.Image }.map(::refOf) else emptyList())
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Leading(c: Child, kind: FileKind?, ref: DocRef?) {
    val context = LocalContext.current
    var thumb by remember(c.id) { mutableStateOf<ImageBitmap?>(ref?.let { Thumbs.cached(it)?.asImageBitmap() }) }
    LaunchedEffect(c.id) {
        if (thumb == null && ref != null && kind in setOf(FileKind.Image, FileKind.Pdf, FileKind.Video)) {
            thumb = Thumbs.load(context, ref)?.asImageBitmap()
        }
    }
    val accent = kind?.let { kindAccent(it) } ?: MaterialTheme.colorScheme.primary
    Box(
        Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(accent.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center
    ) {
        thumb?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            ?: Icon(if (kind == null) Icons.Rounded.Folder else kindIcon(kind), null, Modifier.size(22.dp), tint = accent)
    }
}

private suspend fun list(context: Context, route: Route.Folder): List<Child> = withContext(Dispatchers.IO) {
    val uri = DocumentsContract.buildChildDocumentsUriUsingTree(route.tree, route.documentId)
    val out = ArrayList<Child>()
    context.contentResolver.query(
        uri,
        arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        ),
        null, null, null
    )?.use { c ->
        while (c.moveToNext()) {
            val name = c.getString(1) ?: continue
            if (name.startsWith(".")) continue
            out += Child(c.getString(0), name, c.getString(2) ?: "application/octet-stream", c.getLong(3), c.getLong(4))
        }
    } ?: error("This folder is no longer accessible")
    out
}
