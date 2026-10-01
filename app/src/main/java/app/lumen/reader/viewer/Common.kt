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
import app.lumen.reader.ui.glass.GlassPanel
import app.lumen.reader.ui.theme.Lumen
import com.kyant.backdrop.Backdrop

@Composable
fun LoadingIndicator(modifier: Modifier = Modifier) {
    val colors = Lumen.colors
    val t = rememberInfiniteTransition(label = "spin")
    val angle by t.animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "a")
    val sweep by t.animateFloat(40f, 250f, infiniteRepeatable(tween(1100), RepeatMode.Reverse), label = "s")
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(36.dp)) {
            rotate(angle) {
                drawArc(
                    colors.accent, 0f, sweep, false,
                    topLeft = Offset(2.dp.toPx(), 2.dp.toPx()),
                    size = Size(size.width - 4.dp.toPx(), size.height - 4.dp.toPx()),
                    style = Stroke(3.dp.toPx(), cap = StrokeCap.Round)
                )
            }
        }
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
internal fun BoxScope.ViewerMenu(ref: DocRef, backdrop: Backdrop, open: Boolean, top: Dp, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var showInfo by remember { mutableStateOf(false) }
    if (open || showInfo) {
        Box(Modifier.fillMaxSize().clickable(interactionSource = null, indication = null) {
            onDismiss(); showInfo = false
        })
    }
    AnimatedVisibility(
        open || showInfo,
        modifier = Modifier.align(Alignment.TopEnd).padding(top = top + TopBarHeight + 16.dp, end = 12.dp),
        enter = fadeIn() + scaleIn(initialScale = 0.85f, transformOrigin = TransformOrigin(1f, 0f)),
        exit = fadeOut() + scaleOut(targetScale = 0.85f, transformOrigin = TransformOrigin(1f, 0f))
    ) {
        GlassPanel(backdrop, Modifier.width(240.dp), cornerRadius = 24.dp) {
            if (showInfo) {
                InfoContent(ref)
            } else {
                Column(Modifier.padding(vertical = 8.dp)) {
                    MenuItem(Icons.Rounded.Share, "Share") {
                        onDismiss()
                        launch(context, Intent.createChooser(app.lumen.reader.core.Docs.shareIntent(ref), "Share"))
                    }
                    MenuItem(Icons.AutoMirrored.Rounded.OpenInNew, "Open with…") {
                        onDismiss()
                        launch(context, Intent.createChooser(app.lumen.reader.core.Docs.openWithIntent(ref), "Open with"))
                    }
                    MenuItem(Icons.Rounded.Info, "Details") {
                        showInfo = true
                        onDismiss()
                    }
                }
            }
        }
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
private fun MenuItem(icon: ImageVector, label: String, onClick: () -> Unit) {
    val colors = Lumen.colors
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Image(icon, null, Modifier.size(20.dp), colorFilter = ColorFilter.tint(colors.content))
        BasicText(label, style = Lumen.type.label.copy(color = colors.content))
    }
}

@Composable
private fun InfoContent(ref: DocRef) {
    val colors = Lumen.colors
    val type = Lumen.type
    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        BasicText(ref.name, style = type.headline.copy(color = colors.content))
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
