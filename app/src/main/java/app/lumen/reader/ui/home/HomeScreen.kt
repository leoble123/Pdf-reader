package app.lumen.reader.ui.home

import android.text.format.DateUtils
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.lumen.reader.core.FileKind
import app.lumen.reader.core.RecentEntry
import app.lumen.reader.core.Recents
import app.lumen.reader.core.Thumbs
import app.lumen.reader.core.formatBytes
import app.lumen.reader.ui.glass.GlassButton
import app.lumen.reader.ui.glass.GlassPanel
import app.lumen.reader.ui.theme.Lumen
import app.lumen.reader.ui.theme.LumenColors
import app.lumen.reader.viewer.kindAccent
import app.lumen.reader.viewer.kindIcon
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop

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

@Composable
fun HomeScreen(recents: Recents, onPick: (PickGroup) -> Unit, onOpen: (RecentEntry) -> Unit) {
    val colors = Lumen.colors
    val type = Lumen.type
    val backdrop = rememberLayerBackdrop()
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    var menuFor by remember { mutableStateOf<RecentEntry?>(null) }

    val entries = recents.entries
    val pinned = entries.filter { it.pinned }
    val rest = entries.filter { !it.pinned }

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .layerBackdrop(backdrop)
                .drawBehind { drawAmbient(colors) }
        ) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(156.dp),
                contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = top + 28.dp, bottom = bottom + 120.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "header") {
                    Column {
                        BasicText("Lumen", style = type.display.copy(color = colors.content))
                        Spacer(Modifier.height(4.dp))
                        BasicText(
                            "Every file, beautifully. Fully offline.",
                            style = type.body.copy(color = colors.contentSecondary)
                        )
                        Spacer(Modifier.height(22.dp))
                        Row(
                            Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            PickGroup.entries.drop(1).forEach { g ->
                                Chip(g.label) { onPick(g) }
                            }
                        }
                    }
                }
                if (entries.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "empty") { EmptyState() }
                }
                if (pinned.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "pinned-h") { SectionTitle("Pinned") }
                    items(pinned, key = { "p" + it.ref.key }) { e ->
                        RecentCard(e, Modifier.animateItem(), { onOpen(e) }, { menuFor = e })
                    }
                }
                if (rest.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "recent-h") { SectionTitle("Recent") }
                    items(rest, key = { "r" + it.ref.key }) { e ->
                        RecentCard(e, Modifier.animateItem(), { onOpen(e) }, { menuFor = e })
                    }
                }
            }
        }

        // Floating dock: the content scrolls beneath real refracting glass.
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = bottom + 18.dp)
        ) {
            GlassButton(backdrop, onClick = { onPick(PickGroup.Any) }, tint = colors.accent, height = 58.dp) {
                Image(Icons.Rounded.Add, null, Modifier.size(24.dp), colorFilter = ColorFilter.tint(Color.White))
                BasicText("Open a file", style = type.headline.copy(color = Color.White))
            }
        }

        if (menuFor != null) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.25f))
                    .clickable(interactionSource = null, indication = null) { menuFor = null }
            )
        }
        AnimatedVisibility(
            menuFor != null,
            modifier = Modifier.align(Alignment.Center),
            enter = fadeIn() + scaleIn(initialScale = 0.9f),
            exit = fadeOut() + scaleOut(targetScale = 0.9f)
        ) {
            val e = remember(menuFor) { menuFor } ?: return@AnimatedVisibility
            GlassPanel(backdrop, Modifier.width(280.dp), cornerRadius = 28.dp) {
                Column(Modifier.padding(vertical = 10.dp)) {
                    BasicText(
                        e.ref.name,
                        style = type.headline.copy(color = colors.content),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 22.dp, vertical = 10.dp)
                    )
                    SheetItem(if (e.pinned) "Unpin" else "Pin to top", if (e.pinned) Icons.Outlined.PushPin else Icons.Rounded.PushPin) {
                        recents.togglePin(e.ref); menuFor = null
                    }
                    SheetItem("Remove from recents", Icons.Rounded.Close) {
                        recents.remove(e.ref); menuFor = null
                    }
                }
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawAmbient(colors: LumenColors) {
    drawRect(colors.background)
    val w = size.width
    val h = size.height
    val a = if (colors.isDark) 0.55f else 0.45f
    drawCircle(
        Brush.radialGradient(listOf(colors.glow1.copy(alpha = a), Color.Transparent), Offset(w * 0.05f, h * 0.08f), w * 0.85f),
        w * 0.85f, Offset(w * 0.05f, h * 0.08f)
    )
    drawCircle(
        Brush.radialGradient(listOf(colors.glow2.copy(alpha = a * 0.8f), Color.Transparent), Offset(w * 1.0f, h * 0.35f), w * 0.8f),
        w * 0.8f, Offset(w * 1.0f, h * 0.35f)
    )
    drawCircle(
        Brush.radialGradient(listOf(colors.glow3.copy(alpha = a * 0.7f), Color.Transparent), Offset(w * 0.2f, h * 0.95f), w * 0.9f),
        w * 0.9f, Offset(w * 0.2f, h * 0.95f)
    )
}

@Composable
private fun Chip(label: String, onClick: () -> Unit) {
    val colors = Lumen.colors
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(colors.surface.copy(alpha = if (colors.isDark) 0.55f else 0.75f))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        BasicText(label, style = Lumen.type.label.copy(color = colors.content))
    }
}

@Composable
private fun SectionTitle(text: String) {
    BasicText(
        text,
        style = Lumen.type.headline.copy(color = Lumen.colors.contentSecondary),
        modifier = Modifier.padding(top = 10.dp, start = 4.dp)
    )
}

@Composable
private fun SheetItem(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    val colors = Lumen.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Image(icon, null, Modifier.size(20.dp), colorFilter = ColorFilter.tint(colors.content))
        BasicText(label, style = Lumen.type.label.copy(color = colors.content))
    }
}

@Composable
private fun RecentCard(entry: RecentEntry, modifier: Modifier, onClick: () -> Unit, onLongClick: () -> Unit) {
    val colors = Lumen.colors
    val type = Lumen.type
    val context = LocalContext.current
    val ref = entry.ref
    var thumb by remember(ref.key) { mutableStateOf(Thumbs.cached(ref)?.asImageBitmap()) }
    val fade = remember(ref.key) { Animatable(if (thumb != null) 1f else 0f) }
    LaunchedEffect(ref.key) {
        if (thumb == null) {
            Thumbs.load(context, ref)?.let {
                thumb = it.asImageBitmap()
                fade.animateTo(1f, tween(220))
            }
        }
    }
    val accent = kindAccent(ref.kind)
    Column(
        modifier
            .clip(RoundedCornerShape(22.dp))
            .background(colors.surface.copy(alpha = if (colors.isDark) 0.62f else 0.82f))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1.1f)
                .padding(8.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(
                    Brush.linearGradient(listOf(accent.copy(alpha = 0.28f), accent.copy(alpha = 0.10f)))
                ),
            contentAlignment = Alignment.Center
        ) {
            Image(kindIcon(ref.kind), null, Modifier.size(40.dp), colorFilter = ColorFilter.tint(accent))
            thumb?.let {
                Image(
                    it, null,
                    Modifier.fillMaxSize().graphicsLayer { alpha = fade.value },
                    contentScale = if (ref.kind == FileKind.Pdf) ContentScale.Crop else ContentScale.Crop,
                    alignment = Alignment.TopCenter
                )
            }
            if (entry.pinned) {
                Image(
                    Icons.Rounded.PushPin, null,
                    Modifier.align(Alignment.TopEnd).padding(8.dp).size(16.dp),
                    colorFilter = ColorFilter.tint(Color.White)
                )
            }
        }
        Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp, top = 2.dp)) {
            BasicText(
                ref.name,
                style = type.label.copy(color = colors.content),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(4.dp))
            BasicText(
                listOfNotNull(
                    ref.kind.label,
                    ref.size.takeIf { it >= 0 }?.let { formatBytes(it) },
                    if (System.currentTimeMillis() - entry.openedAt < 60_000) "Just now" else DateUtils.getRelativeTimeSpanString(entry.openedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
                ).joinToString(" · "),
                style = type.caption.copy(color = colors.contentTertiary),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun EmptyState() {
    val colors = Lumen.colors
    val type = Lumen.type
    Column(
        Modifier
            .padding(top = 8.dp)
            .widthIn(max = 560.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(colors.surface.copy(alpha = if (colors.isDark) 0.5f else 0.75f))
            .padding(22.dp)
    ) {
        BasicText("Open anything", style = type.title.copy(color = colors.content))
        Spacer(Modifier.height(6.dp))
        BasicText(
            "Tap “Open a file” or share a file to Lumen from any app. Everything renders on-device — nothing is ever uploaded.",
            style = type.body.copy(color = colors.contentSecondary)
        )
        Spacer(Modifier.height(18.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                FileKind.Pdf, FileKind.Docx, FileKind.Xlsx, FileKind.Pptx, FileKind.Epub, FileKind.Markdown,
                FileKind.Code, FileKind.Csv, FileKind.Image, FileKind.Svg, FileKind.Video, FileKind.Audio,
                FileKind.Html, FileKind.Zip, FileKind.Text
            ).forEach { k ->
                val accent = kindAccent(k)
                Row(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(accent.copy(alpha = 0.16f))
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Image(kindIcon(k), null, Modifier.size(15.dp), colorFilter = ColorFilter.tint(accent))
                    BasicText(k.label, style = type.caption.copy(color = colors.content))
                }
            }
        }
    }
}

