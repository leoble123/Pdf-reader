package app.lumen.reader.viewer

import android.content.ActivityNotFoundException
import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Share
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.lumen.reader.core.DocRef
import app.lumen.reader.core.formatBytes
import app.lumen.reader.ui.theme.Lumen

@Composable
fun LoadingIndicator(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        androidx.compose.material3.CircularProgressIndicator()
    }
}

@Composable
fun CenteredMessage(title: String, message: String?, icon: ImageVector? = null, content: @Composable () -> Unit = {}) {
    val colors = Lumen.colors
    val type = Lumen.type
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (icon != null) {
            Image(icon, null, Modifier.size(48.dp), colorFilter = ColorFilter.tint(colors.accent))
            Spacer(Modifier.height(16.dp))
        }
        BasicText(title, style = type.title.copy(color = colors.content, textAlign = TextAlign.Center))
        if (message != null) {
            Spacer(Modifier.height(8.dp))
            BasicText(message, style = type.body.copy(color = colors.contentSecondary, textAlign = TextAlign.Center))
        }
        Spacer(Modifier.height(20.dp))
        content()
    }
}

@Composable
internal fun ViewerMenu(ref: DocRef, open: Boolean, extra: List<MenuAction>, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var showInfo by remember { mutableStateOf(false) }
    androidx.compose.material3.DropdownMenu(expanded = open, onDismissRequest = onDismiss) {
        extra.forEach { a ->
            androidx.compose.material3.DropdownMenuItem(
                text = { androidx.compose.material3.Text(a.label) },
                leadingIcon = { androidx.compose.material3.Icon(a.icon, null) },
                onClick = { onDismiss(); a.onClick() }
            )
        }
        if (extra.isNotEmpty()) androidx.compose.material3.HorizontalDivider()
        androidx.compose.material3.DropdownMenuItem(
            text = { androidx.compose.material3.Text("Share") },
            leadingIcon = { androidx.compose.material3.Icon(Icons.Rounded.Share, null) },
            onClick = {
                onDismiss()
                launch(context, Intent.createChooser(app.lumen.reader.core.Docs.shareIntent(ref), "Share"))
            }
        )
        androidx.compose.material3.DropdownMenuItem(
            text = { androidx.compose.material3.Text("Open with…") },
            leadingIcon = { androidx.compose.material3.Icon(Icons.AutoMirrored.Rounded.OpenInNew, null) },
            onClick = {
                onDismiss()
                launch(context, Intent.createChooser(app.lumen.reader.core.Docs.openWithIntent(ref), "Open with"))
            }
        )
        androidx.compose.material3.DropdownMenuItem(
            text = { androidx.compose.material3.Text("Details") },
            leadingIcon = { androidx.compose.material3.Icon(Icons.Rounded.Info, null) },
            onClick = { onDismiss(); showInfo = true }
        )
    }
    if (showInfo) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showInfo = false },
            confirmButton = { androidx.compose.material3.TextButton({ showInfo = false }) { androidx.compose.material3.Text("Done") } },
            title = { androidx.compose.material3.Text(ref.name) },
            text = { InfoContent(ref) }
        )
    }
}

private fun launch(context: android.content.Context, intent: Intent) {
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "No app can handle this", Toast.LENGTH_SHORT).show()
    } catch (_: SecurityException) {
        Toast.makeText(context, "Permission to share this file has expired", Toast.LENGTH_SHORT).show()
    }
}

@Composable
private fun InfoContent(ref: DocRef) {
    val colors = Lumen.colors
    val type = Lumen.type
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        listOf(
            "Type" to ref.kind.label,
            "Size" to formatBytes(ref.size),
            "Format" to (ref.mime ?: ref.extension.uppercase().ifEmpty { "—" })
        ).forEach { (k, v) ->
            Row {
                BasicText(k, style = type.caption.copy(color = colors.contentTertiary), modifier = Modifier.width(64.dp))
                BasicText(v, style = type.caption.copy(color = colors.contentSecondary))
            }
        }
    }
}
