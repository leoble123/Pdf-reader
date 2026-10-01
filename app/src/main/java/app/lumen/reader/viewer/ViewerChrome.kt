package app.lumen.reader.viewer

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material.icons.rounded.MoreHoriz
import app.lumen.reader.core.DocRef
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.lumen.reader.ui.glass.GlassIconButton
import app.lumen.reader.ui.glass.GlassPanel
import app.lumen.reader.ui.theme.Lumen
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop

val TopBarHeight = 46.dp

/**
 * Shared frame for every viewer: content runs edge-to-edge underneath floating glass chrome.
 * The chrome slides away when [chromeVisible] is false (tap content to toggle).
 */
@Composable
fun ViewerScaffold(
    ref: DocRef?,
    title: String,
    subtitle: String?,
    chromeVisible: Boolean,
    onBack: () -> Unit,
    actions: @Composable RowScope.(Backdrop) -> Unit = {},
    titleSlot: (@Composable (Backdrop) -> Unit)? = null,
    bottomBar: (@Composable BoxScope.(Backdrop) -> Unit)? = null,
    overlay: (@Composable BoxScope.(Backdrop) -> Unit)? = null,
    content: @Composable (PaddingValues) -> Unit
) {
    val colors = Lumen.colors
    val backdrop: LayerBackdrop = rememberLayerBackdrop()
    val density = LocalDensity.current
    val insets = WindowInsets.statusBars.union(WindowInsets.displayCutout)
    val top = insets.asPaddingValues().calculateTopPadding()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    var menuOpen by remember { mutableStateOf(false) }
    val chrome by animateFloatAsState(if (chromeVisible) 1f else 0f, spring(0.85f, 380f), label = "chrome")

    Box(Modifier.fillMaxSize().background(colors.backgroundAlt)) {
        Box(Modifier.fillMaxSize().layerBackdrop(backdrop)) {
            content(PaddingValues(top = top + TopBarHeight + 20.dp, bottom = bottom + 84.dp))
        }

        overlay?.invoke(this, backdrop)

        Row(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(insets)
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .graphicsLayer {
                    translationY = -(1f - chrome) * (size.height + top.toPx())
                    alpha = chrome
                },
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            GlassIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back", backdrop, onBack)
            Box(Modifier.weight(1f)) {
                if (titleSlot != null) {
                    titleSlot(backdrop)
                } else {
                    TitlePill(backdrop, title, subtitle)
                }
            }
            actions(backdrop)
            if (ref != null) {
                GlassIconButton(Icons.Rounded.MoreHoriz, "More", backdrop, { menuOpen = !menuOpen })
            }
        }

        if (ref != null) {
            ViewerMenu(ref, backdrop, menuOpen, top, onDismiss = { menuOpen = false })
        }

        if (bottomBar != null) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(bottom = bottom + 14.dp)
                    .graphicsLayer {
                        translationY = (1f - chrome) * (size.height + with(density) { bottom.toPx() } + 40f)
                        alpha = chrome
                    },
                contentAlignment = Alignment.BottomCenter
            ) { bottomBar(backdrop) }
        }
    }
}

@Composable
fun TitlePill(backdrop: Backdrop, title: String, subtitle: String?) {
    val colors = Lumen.colors
    val type = Lumen.type
    GlassPanel(backdrop, Modifier.height(TopBarHeight).widthIn(min = 0.dp)) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.Center
        ) {
            BasicText(
                title,
                style = type.label.copy(color = colors.content),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (subtitle != null) {
                BasicText(
                    subtitle,
                    style = type.caption.copy(color = colors.contentSecondary),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
fun BottomPill(backdrop: Backdrop, modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    GlassPanel(backdrop, modifier.height(50.dp)) {
        Row(
            Modifier.padding(horizontal = 6.dp).height(50.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            content = content
        )
    }
}
