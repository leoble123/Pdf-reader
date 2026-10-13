package app.lumen.reader.edit

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.lumen.reader.core.Saver
import app.lumen.reader.ui.glass.GlassPanel
import app.lumen.reader.ui.theme.Lumen
import com.kyant.backdrop.Backdrop

/** "You have unsaved changes" sheet. Rendered in a scaffold overlay so it sits on glass. */
@Composable
fun BoxScope.UnsavedChangesPanel(
    backdrop: Backdrop,
    visible: Boolean,
    onSave: () -> Unit,
    onDiscard: () -> Unit,
    onCancel: () -> Unit
) {
    val colors = Lumen.colors
    val type = Lumen.type
    if (visible) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.3f))
                .clickable(interactionSource = null, indication = null, onClick = onCancel)
        )
    }
    AnimatedVisibility(
        visible,
        modifier = Modifier.align(Alignment.Center),
        enter = fadeIn() + scaleIn(initialScale = 0.9f),
        exit = fadeOut() + scaleOut(targetScale = 0.9f)
    ) {
        GlassPanel(backdrop, Modifier.width(300.dp), cornerRadius = 28.dp) {
            Column(Modifier.padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                BasicText("Save your changes?", style = type.title.copy(color = colors.content, textAlign = TextAlign.Center))
                BasicText(
                    "Leaving now will discard your edits.",
                    style = type.body.copy(color = colors.contentSecondary, textAlign = TextAlign.Center),
                    modifier = Modifier.padding(top = 6.dp, bottom = 18.dp)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    PillButton("Discard", Modifier.weight(1f), filled = false, onClick = onDiscard)
                    PillButton("Save", Modifier.weight(1f), filled = true, onClick = onSave)
                }
            }
        }
    }
    BackHandler(visible, onCancel)
}

@Composable
fun PillButton(label: String, modifier: Modifier = Modifier, filled: Boolean = true, enabled: Boolean = true, onClick: () -> Unit) {
    val colors = Lumen.colors
    Box(
        modifier
            .clip(CircleShape)
            .background(if (filled) colors.accent.copy(alpha = if (enabled) 1f else 0.4f) else colors.content.copy(alpha = 0.1f))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        BasicText(label, style = Lumen.type.label.copy(color = if (filled) colors.onAccent else colors.content))
    }
}

fun toast(context: android.content.Context, result: Saver.Result) {
    Saver.describe(result)?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
}

fun toast(context: android.content.Context, message: String) {
    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
}
