package app.lumen.reader.pdf

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.BorderColor
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Gesture
import androidx.compose.material.icons.rounded.PanTool
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import app.lumen.reader.core.LocalSettings
import app.lumen.reader.ui.components.HueStrip
import app.lumen.reader.ui.components.Panel
import app.lumen.reader.ui.components.StrokePreview

/**
 * Markup tools. Row one picks the tool (plus whether fingers draw or scroll); row two adapts:
 * size with a true-to-scale preview for pens, mode for the eraser, actions for a lasso selection.
 */
@Composable
internal fun InkToolbar(ink: InkController) {
    val settings = LocalSettings.current
    val scheme = MaterialTheme.colorScheme
    var customOpen by remember { mutableStateOf(false) }
    Panel(Modifier.padding(horizontal = 12.dp).widthIn(max = 600.dp).fillMaxWidth(), cornerRadius = 28.dp) {
        Column(
            Modifier.padding(horizontal = 10.dp, vertical = 10.dp).animateContentSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                listOf(
                    InkTool.Pen to Icons.Rounded.Edit,
                    InkTool.Highlighter to Icons.Rounded.BorderColor,
                    InkTool.Eraser to Icons.Rounded.CleaningServices,
                    InkTool.Lasso to Icons.Rounded.Gesture,
                    InkTool.Text to Icons.Rounded.TextFields
                ).forEach { (tool, icon) ->
                    val sel = ink.tool == tool
                    ToolButton(icon, tool.name, sel) {
                        if (sel && (tool == InkTool.Pen || tool == InkTool.Highlighter)) {
                            // Tapping the active pen cycles fine → medium → bold.
                            ink.width = when {
                                ink.width < 0.003f -> 0.004f
                                ink.width < 0.006f -> 0.008f
                                else -> 0.0022f
                            }
                        }
                        ink.selectTool(tool)
                    }
                }
                VerticalDivider(Modifier.height(28.dp))
                ToolButton(
                    if (settings.fingerDraws) Icons.Rounded.TouchApp else Icons.Rounded.PanTool,
                    if (settings.fingerDraws) "Fingers draw" else "Fingers scroll",
                    settings.fingerDraws
                ) { settings.updateFingerDraws(!settings.fingerDraws) }
            }

            when (ink.tool) {
                InkTool.Pen, InkTool.Highlighter -> {
                    val screenW = LocalWindowInfo.current.containerSize.width
                    val highlighter = ink.tool == InkTool.Highlighter
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StrokePreview(Color(ink.color), ink.width * (if (highlighter) 4 else 1) * screenW, highlighter)
                        Slider(
                            value = ink.width,
                            onValueChange = { ink.width = it },
                            valueRange = 0.0012f..0.014f,
                            modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                        )
                    }
                }
                InkTool.Eraser -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SingleChoiceSegmentedButtonRow(Modifier.weight(1f)) {
                            SegmentedButton(ink.eraseWhole, { ink.eraseWhole = true }, SegmentedButtonDefaults.itemShape(0, 2)) { Text("Whole stroke") }
                            SegmentedButton(!ink.eraseWhole, { ink.eraseWhole = false }, SegmentedButtonDefaults.itemShape(1, 2)) { Text("Partial") }
                        }
                        TextButton(onClick = { ink.clearAll() }, enabled = !ink.isEmpty) { Text("Clear all") }
                    }
                    val screenW = LocalWindowInfo.current.containerSize.width
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // The eraser's real footprint at 1× zoom.
                        androidx.compose.foundation.Canvas(Modifier.size(56.dp, 32.dp)) {
                            val r = (ink.eraserSize * screenW).coerceIn(2f, size.height / 2)
                            drawCircle(scheme.surfaceVariant, r)
                            drawCircle(scheme.outline, r, style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
                        }
                        Slider(
                            value = ink.eraserSize,
                            onValueChange = { ink.eraserSize = it },
                            valueRange = 0.006f..0.05f,
                            modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                        )
                    }
                }
                InkTool.Lasso -> Row(
                    Modifier.fillMaxWidth().height(40.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (ink.selection == null) {
                        Text(
                            "Circle marks to select them, then drag to move",
                            style = MaterialTheme.typography.bodyMedium,
                            color = scheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp)
                        )
                    } else {
                        FilterChip(false, { ink.deleteSelection() }, { Text("Delete") }, leadingIcon = { Icon(Icons.Rounded.Delete) })
                        FilterChip(false, { ink.duplicateSelection() }, { Text("Duplicate") }, leadingIcon = { Icon(Icons.Rounded.ContentCopy) })
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { ink.clearSelection() }) { Text("Done") }
                    }
                }
                InkTool.Text -> Text(
                    "Tap the page to add text",
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }

            if (ink.tool != InkTool.Eraser) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    (InkController.Palette + ink.recentColors).forEach { c ->
                        val sel = ink.color == c
                        Box(
                            Modifier
                                .size(if (sel) 30.dp else 24.dp)
                                .clip(CircleShape)
                                .background(Color(c))
                                .border(2.dp, if (sel) scheme.primary else scheme.outlineVariant, CircleShape)
                                .clickable { ink.pickColor(c) }
                        )
                    }
                    Box(
                        Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(if (customOpen) scheme.primaryContainer else scheme.surfaceContainerHighest)
                            .clickable { customOpen = !customOpen },
                        contentAlignment = Alignment.Center
                    ) { Icon(Icons.Rounded.Add) }
                }
                if (customOpen) HueStrip({ ink.pickColor(it) }, Modifier.padding(horizontal = 6.dp))
            }
        }
    }
}

@Composable
private fun ToolButton(icon: ImageVector, desc: String, selected: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(if (selected) scheme.primary else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Image(icon, desc, Modifier.size(22.dp), colorFilter = ColorFilter.tint(if (selected) scheme.onPrimary else scheme.onSurface))
    }
}

@Composable
private fun Icon(icon: ImageVector) {
    Image(icon, null, Modifier.size(18.dp).width(18.dp), colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSurface))
}
