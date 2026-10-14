package app.lumen.reader.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Icon button used across the app. [tint] fills it (for the primary action of a bar);
 * otherwise it's a plain Material icon button.
 */
@Composable
fun AppIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Color.Unspecified,
    iconTint: Color = Color.Unspecified,
    enabled: Boolean = true,
    size: Dp = 48.dp
) {
    if (tint.isSpecified) {
        FilledIconButton(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier.size(size),
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            )
        ) { Icon(icon, contentDescription) }
    } else {
        IconButton(onClick = onClick, enabled = enabled, modifier = modifier.size(size)) {
            Icon(icon, contentDescription, tint = if (iconTint.isSpecified) iconTint else LocalContentColor.current)
        }
    }
}

/** A raised Material surface for floating panels, toolbars and sheets. */
@Composable
fun Panel(
    modifier: Modifier = Modifier,
    cornerRadius: Dp? = null,
    content: @Composable () -> Unit
) {
    Surface(
        modifier = modifier,
        shape = if (cornerRadius == null) CircleShape else RoundedCornerShape(cornerRadius),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 3.dp,
        shadowElevation = 6.dp
    ) { Box { content() } }
}
