package app.lumen.reader.ui.home

import android.text.format.DateUtils
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.NoteAdd
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Compress
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.DocumentScanner
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.VideoSettings
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.lumen.reader.core.Docs
import app.lumen.reader.core.FileKind
import app.lumen.reader.core.Folders
import app.lumen.reader.core.LocalPickers
import app.lumen.reader.core.RecentEntry
import app.lumen.reader.core.Recents
import app.lumen.reader.core.Route
import app.lumen.reader.core.Saver
import app.lumen.reader.core.Thumbs
import app.lumen.reader.core.formatBytes
import app.lumen.reader.viewer.kindAccent
import app.lumen.reader.viewer.kindIcon
import kotlinx.coroutines.launch

/** MIME groups for the quick-open chips. */
enum class PickGroup(val label: String, val mimes: Array<String>) {
    Any("Any file", arrayOf("*/*")),
    Pdf("PDF", arrayOf("application/pdf")),
    Docs(
        "Documents", arrayOf(
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "application/vnd.oasis.opendocument.text",
            "application/epub+zip", "text/*", "application/json", "application/xml"
        )
    ),
    Media("Photos & media", arrayOf("image/*", "video/*", "audio/*")),
    Archives("Archives", arrayOf("application/zip", "application/x-zip-compressed", "application/java-archive"))
}

private class Tool(val label: String, val icon: ImageVector, val run: () -> Unit)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    recents: Recents,
    folders: Folders,
    onPick: (PickGroup) -> Unit,
    onOpen: (RecentEntry) -> Unit,
    onCreate: (Route) -> Unit,
    onAddFolder: () -> Unit
) {
    val context = LocalContext.current
    val pickers = LocalPickers.current
    val scope = rememberCoroutineScope()
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    var menuFor by remember { mutableStateOf<RecentEntry?>(null) }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val grid = rememberLazyGridState()
    val fabExpanded by remember { derivedStateOf { grid.firstVisibleItemIndex == 0 } }

    val entries = recents.entries.filter { query.isBlank() || it.ref.name.contains(query, ignoreCase = true) }
    val pinned = entries.filter { it.pinned }
    val rest = entries.filter { !it.pinned }

    val tools = listOf(
        Tool("Scan", Icons.Rounded.DocumentScanner) { onCreate(Route.Scanner) },
        Tool("PDF tools", Icons.Rounded.PictureAsPdf) { onCreate(Route.PdfTools(emptyList())) },
        Tool("Photo editor", Icons.Rounded.AutoFixHigh) { onCreate(Route.PhotoEdit(null)) },
        Tool("Video editor", Icons.Rounded.VideoSettings) { onCreate(Route.VideoEdit(emptyList())) },
        Tool("Compress", Icons.Rounded.Compress) { onCreate(Route.MediaTool(Route.MediaTool.Tool.Compress, null)) },
        Tool("New note", Icons.AutoMirrored.Rounded.NoteAdd) {
            scope.launch {
                val uri = pickers.createDocument("Note.md", "text/markdown") ?: return@launch
                if (!Saver.overwrite(context, uri) { it.write("# New note\n\n".toByteArray()) }) return@launch
                val ref = Docs.resolve(context, uri, "text/markdown")
                    .let { if (it.kind == FileKind.Markdown) it else it.copy(kind = FileKind.Markdown) }
                recents.record(ref)
                onCreate(Route.Doc(ref))
            }
        }
    )

    androidx.compose.material3.Surface(Modifier.fillMaxSize()) { Box(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            state = grid,
            columns = GridCells.Adaptive(160.dp),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = top + 8.dp, bottom = bottom + 104.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            full("header") {
                Row(Modifier.padding(start = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (searching) {
                        TextField(
                            value = query,
                            onValueChange = { query = it },
                            singleLine = true,
                            placeholder = { Text("Search recent files") },
                            leadingIcon = { Icon(Icons.Rounded.Search, null) },
                            trailingIcon = {
                                IconButton({ query = ""; searching = false }) { Icon(Icons.Rounded.Close, "Close search") }
                            },
                            shape = RoundedCornerShape(28.dp),
                            colors = TextFieldDefaults.colors(
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent
                            ),
                            modifier = Modifier.weight(1f)
                        )
                    } else {
                        Text("Lumen", style = MaterialTheme.typography.displaySmall, modifier = Modifier.weight(1f))
                        IconButton({ searching = true }) { Icon(Icons.Rounded.Search, "Search") }
                        IconButton({ onCreate(Route.Settings) }) { Icon(Icons.Rounded.Settings, "Settings") }
                    }
                }
            }
            if (!searching) {
                full("chips") {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PickGroup.entries.drop(1).forEach { g ->
                            AssistChip(onClick = { onPick(g) }, label = { Text(g.label) })
                        }
                    }
                }
                full("tools-h") { SectionTitle("Tools") }
                full("tools") {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        maxItemsInEachRow = 3
                    ) {
                        tools.forEach { t -> ToolCard(t, Modifier.weight(1f)) }
                    }
                }
                full("notebooks-h") { SectionTitle("Notebooks") }
                full("notebooks") { app.lumen.reader.notebook.NotebookShelf(onOpen = { ref -> onCreate(Route.Doc(ref)) }) }
                full("folders-h") { SectionTitle("Folders") }
                full("folders") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        folders.entries.forEach { f ->
                            InputChip(
                                selected = false,
                                onClick = { onCreate(Route.Folder(f.tree, f.rootId, f.name)) },
                                label = { Text(f.name) },
                                leadingIcon = { Icon(Icons.Rounded.Folder, null, Modifier.size(18.dp)) },
                                trailingIcon = {
                                    Icon(
                                        Icons.Rounded.Close, "Remove ${f.name}",
                                        Modifier.size(18.dp).clip(CircleShape).clickable { folders.remove(f) }
                                    )
                                }
                            )
                        }
                        AssistChip(
                            onClick = onAddFolder,
                            label = { Text("Add folder") },
                            leadingIcon = { Icon(Icons.Rounded.CreateNewFolder, null, Modifier.size(18.dp)) }
                        )
                    }
                }
            }
            if (recents.entries.isEmpty()) {
                full("empty") { EmptyState() }
            }
            if (pinned.isNotEmpty()) {
                full("pinned-h") { SectionTitle("Pinned") }
                items(pinned, key = { "p" + it.ref.key }) { e -> RecentCard(e, Modifier.animateItem(), { onOpen(e) }, { menuFor = e }) }
            }
            if (rest.isNotEmpty()) {
                full("recent-h") { SectionTitle("Recent") }
                items(rest, key = { "r" + it.ref.key }) { e -> RecentCard(e, Modifier.animateItem(), { onOpen(e) }, { menuFor = e }) }
            }
        }

        ExtendedFloatingActionButton(
            onClick = { onPick(PickGroup.Any) },
            expanded = fabExpanded,
            icon = { Icon(Icons.Rounded.Add, null) },
            text = { Text("Open file") },
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = bottom + 16.dp)
        )

        menuFor?.let { e ->
            ModalBottomSheet(onDismissRequest = { menuFor = null }) {
                Text(
                    e.ref.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                )
                SheetItem(if (e.pinned) "Unpin" else "Pin to top", if (e.pinned) Icons.Outlined.PushPin else Icons.Rounded.PushPin) {
                    recents.togglePin(e.ref); menuFor = null
                }
                if (e.ref.kind == FileKind.Pdf) {
                    SheetItem("Organise pages", Icons.Rounded.PictureAsPdf) {
                        menuFor = null; onCreate(Route.PdfTools(listOf(e.ref)))
                    }
                }
                SheetItem("Remove from recents", Icons.Rounded.Close) {
                    recents.remove(e.ref); menuFor = null
                }
                Spacer(Modifier.height(bottom + 16.dp))
            }
        }
    } }
}

private fun LazyGridScope.full(key: String, content: @Composable () -> Unit) {
    item(key = key, span = { GridItemSpan(maxLineSpan) }) { content() }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, top = 12.dp)
    )
}

@Composable
private fun ToolCard(tool: Tool, modifier: Modifier) {
    Card(
        onClick = tool.run,
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Column(Modifier.padding(vertical = 14.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(tool.icon, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
            Spacer(Modifier.height(6.dp))
            Text(tool.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSecondaryContainer, maxLines = 1)
        }
    }
}

@Composable
private fun SheetItem(label: String, icon: ImageVector, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(label) },
        leadingContent = { Icon(icon, null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick)
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecentCard(entry: RecentEntry, modifier: Modifier, onClick: () -> Unit, onLongClick: () -> Unit) {
    val context = LocalContext.current
    val ref = entry.ref
    var thumb by remember(ref.key) { mutableStateOf(Thumbs.cached(ref)?.asImageBitmap()) }
    val fade = remember(ref.key) { Animatable(if (thumb != null) 1f else 0f) }
    LaunchedEffect(ref.key) {
        if (thumb == null) Thumbs.load(context, ref)?.let {
            thumb = it.asImageBitmap()
            fade.animateTo(1f, tween(180))
        }
    }
    val accent = kindAccent(ref.kind)
    Card(
        modifier = modifier.clip(CardDefaults.shape).combinedClickable(onClick = onClick, onLongClick = onLongClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1.15f)
                .padding(6.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(accent.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center
        ) {
            Image(kindIcon(ref.kind), null, Modifier.size(36.dp), colorFilter = ColorFilter.tint(accent))
            thumb?.let {
                Image(
                    it, null,
                    Modifier.fillMaxSize().graphicsLayer { alpha = fade.value },
                    contentScale = ContentScale.Crop,
                    alignment = Alignment.TopCenter
                )
            }
            if (entry.pinned) {
                Icon(
                    Icons.Rounded.PushPin, null,
                    Modifier.align(Alignment.TopEnd).padding(6.dp).size(16.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
        Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp, top = 2.dp)) {
            Text(ref.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(
                    ref.kind.label,
                    ref.size.takeIf { it >= 0 }?.let { formatBytes(it) },
                    if (System.currentTimeMillis() - entry.openedAt < 60_000) "Just now"
                    else DateUtils.getRelativeTimeSpanString(entry.openedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun EmptyState() {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(20.dp)) {
            Text("Open anything", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(6.dp))
            Text(
                "Tap “Open file”, add a folder, or share a file to Lumen from any app. Everything renders on-device — nothing is ever uploaded.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(16.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    FileKind.Pdf, FileKind.Docx, FileKind.Xlsx, FileKind.Pptx, FileKind.Epub, FileKind.Markdown,
                    FileKind.Code, FileKind.Csv, FileKind.Image, FileKind.Svg, FileKind.Video, FileKind.Audio,
                    FileKind.Html, FileKind.Zip, FileKind.Text
                ).forEach { k ->
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(50))
                            .background(kindAccent(k).copy(alpha = 0.14f))
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(kindIcon(k), null, Modifier.size(15.dp), tint = kindAccent(k))
                        Text(k.label, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}
