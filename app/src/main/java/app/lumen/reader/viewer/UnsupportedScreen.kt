package app.lumen.reader.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.lumen.reader.core.DocRef
import app.lumen.reader.core.Docs
import app.lumen.reader.core.FileKind
import app.lumen.reader.core.formatBytes
import app.lumen.reader.core.readHead
import app.lumen.reader.ui.theme.Lumen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** For formats Lumen doesn't know: file facts, a hex peek, and an escape hatch to view as text. */
@Composable
fun UnsupportedScreen(ref: DocRef, onBack: () -> Unit, onOpenAs: (DocRef) -> Unit) {
    val context = LocalContext.current
    val colors = Lumen.colors
    val type = Lumen.type
    var hex by remember { mutableStateOf<List<String>?>(null) }
    LaunchedEffect(ref) {
        hex = withContext(Dispatchers.IO) {
            runCatching {
                val bytes = Docs.open(context, ref).use { it.readHead(2048) }
                bytes.toList().chunked(8).mapIndexed { i, row ->
                    "%04x  ".format(i * 8) + row.joinToString(" ") { "%02x".format(it) }.padEnd(24) + "  " +
                        row.map { b -> val c = b.toInt() and 0xFF; if (c in 32..126) c.toChar() else '·' }.joinToString("")
                }
            }.getOrDefault(emptyList())
        }
    }
    ViewerScaffold(ref, ref.name, "${ref.kind.label} · ${formatBytes(ref.size)}", true, onBack) { padding ->
        LazyColumn(contentPadding = padding, modifier = Modifier.padding(horizontal = 20.dp)) {
            item {
                Column(Modifier.padding(vertical = 12.dp)) {
                    BasicText("No built-in preview", style = type.title.copy(color = colors.content))
                    Spacer(Modifier.height(6.dp))
                    BasicText(
                        "Lumen doesn't recognise this format${ref.mime?.let { " ($it)" } ?: ""}. You can still try one of these:",
                        style = type.body.copy(color = colors.contentSecondary)
                    )
                    Spacer(Modifier.height(16.dp))
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        listOf(FileKind.Text to "Open as text", FileKind.Zip to "Open as archive", FileKind.Image to "Open as image")
                            .forEach { (k, label) ->
                                BasicText(
                                    label,
                                    style = type.label.copy(color = colors.onAccent),
                                    modifier = Modifier
                                        .clip(CircleShape)
                                        .background(colors.accent)
                                        .clickable { onOpenAs(ref.copy(kind = k)) }
                                        .padding(horizontal = 16.dp, vertical = 10.dp)
                                )
                            }
                    }
                    Spacer(Modifier.height(24.dp))
                    BasicText("First bytes", style = type.caption.copy(color = colors.contentTertiary))
                    Spacer(Modifier.height(8.dp))
                }
            }
            item {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(colors.surface)
                        .horizontalScroll(rememberScrollState())
                        .padding(14.dp)
                ) {
                    hex?.forEach { BasicText(it, style = type.mono.copy(color = colors.contentSecondary, fontSize = type.mono.fontSize * 0.9f)) }
                }
            }
        }
    }
}
