package app.lumen.reader.viewer

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.lumen.reader.core.DocRef
import app.lumen.reader.ui.components.AppIconButton
import app.lumen.reader.ui.components.Panel
import app.lumen.reader.ui.theme.Lumen

val TopBarHeight = 64.dp

/** Extra entries a viewer adds to the overflow menu. */
class MenuAction(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector, val onClick: () -> Unit)

/**
 * Shared frame for every viewer: content runs edge-to-edge under a Material top bar that
 * slides away when [chromeVisible] is false (tap the content to toggle). Bars are plain
 * opaque-ish surfaces — no blur — so they cost nothing per frame.
 */
@Composable
fun ViewerScaffold(
    ref: DocRef?,
    title: String,
    subtitle: String?,
    chromeVisible: Boolean,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
    titleSlot: (@Composable () -> Unit)? = null,
    bottomBar: (@Composable BoxScope.() -> Unit)? = null,
    overlay: (@Composable BoxScope.() -> Unit)? = null,
    menu: List<MenuAction> = emptyList(),
    content: @Composable (PaddingValues) -> Unit
) {
    val colors = Lumen.colors
    val insets = WindowInsets.statusBars.union(WindowInsets.displayCutout)
    val top = insets.asPaddingValues().calculateTopPadding()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    var menuOpen by remember { mutableStateOf(false) }
    val chrome by animateFloatAsState(if (chromeVisible) 1f else 0f, tween(180), label = "chrome")

    Box(Modifier.fillMaxSize().background(colors.backgroundAlt)) {
        content(PaddingValues(top = top + TopBarHeight + 8.dp, bottom = bottom + 84.dp))

        overlay?.invoke(this)

        Surface(
            color = colors.bar.copy(alpha = 0.97f),
            contentColor = MaterialTheme.colorScheme.onSurface,
            tonalElevation = 2.dp,
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer {
                    translationY = -(1f - chrome) * size.height
                    alpha = chrome
                }
        ) {
            Row(
                Modifier
                    .windowInsetsPadding(insets)
                    .height(TopBarHeight)
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AppIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back", onBack)
                Box(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                    if (titleSlot != null) titleSlot() else TitleText(title, subtitle)
                }
                actions()
                if (ref != null) {
                    Box {
                        AppIconButton(Icons.Rounded.MoreVert, "More", { menuOpen = true })
                        ViewerMenu(ref, menuOpen, menu, onDismiss = { menuOpen = false })
                    }
                }
            }
        }

        if (bottomBar != null) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(bottom = bottom + 16.dp)
                    .graphicsLayer {
                        translationY = (1f - chrome) * (size.height + 48.dp.toPx())
                        alpha = chrome
                    },
                contentAlignment = Alignment.BottomCenter
            ) { bottomBar() }
        }
    }
}

@Composable
private fun TitleText(title: String, subtitle: String?) {
    Column(verticalArrangement = Arrangement.Center) {
        Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (subtitle != null) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun BottomPill(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Panel(modifier.height(52.dp)) {
        Row(
            Modifier.padding(horizontal = 6.dp).height(52.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            content = content
        )
    }
}
