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
import app.lumen.reader.ui.components.Panel
import app.lumen.reader.ui.theme.Lumen

/** "You have unsaved changes" dialog. Kept in BoxScope so call sites can place it in overlays. */
@Suppress("UnusedReceiverParameter")
@Composable
fun BoxScope.UnsavedChangesPanel(
    visible: Boolean,
    onSave: () -> Unit,
    onDiscard: () -> Unit,
    onCancel: () -> Unit
) {
    if (!visible) return
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onCancel,
        title = { androidx.compose.material3.Text("Save your changes?") },
        text = { androidx.compose.material3.Text("Leaving now will discard your edits.") },
        confirmButton = { androidx.compose.material3.Button(onSave) { androidx.compose.material3.Text("Save") } },
        dismissButton = { androidx.compose.material3.TextButton(onDiscard) { androidx.compose.material3.Text("Discard") } }
    )
}

@Composable
fun PillButton(label: String, modifier: Modifier = Modifier, filled: Boolean = true, enabled: Boolean = true, onClick: () -> Unit) {
    if (filled) androidx.compose.material3.Button(onClick, modifier, enabled = enabled) { androidx.compose.material3.Text(label) }
    else androidx.compose.material3.FilledTonalButton(onClick, modifier, enabled = enabled) { androidx.compose.material3.Text(label) }
}

fun toast(context: android.content.Context, result: Saver.Result) {
    Saver.describe(result)?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
}

fun toast(context: android.content.Context, message: String) {
    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
}
