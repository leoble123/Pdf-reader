package app.lumen.reader.ui.settings

import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.lumen.reader.core.LocalSettings
import app.lumen.reader.core.ThemeMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val settings = LocalSettings.current
    val context = LocalContext.current
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                scrollBehavior = scroll
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            Header("Appearance")
            ListItem(
                headlineContent = { Text("Theme") },
                supportingContent = {
                    SingleChoiceSegmentedButtonRow(Modifier.padding(top = 8.dp)) {
                        ThemeMode.entries.forEachIndexed { i, mode ->
                            SegmentedButton(
                                selected = settings.themeMode == mode,
                                onClick = { settings.updateTheme(mode) },
                                shape = SegmentedButtonDefaults.itemShape(i, ThemeMode.entries.size)
                            ) { Text(mode.label) }
                        }
                    }
                }
            )
            Toggle(
                "Dynamic colour",
                if (Build.VERSION.SDK_INT >= 31) "Match your wallpaper (Material You)" else "Needs Android 12 or newer",
                settings.dynamicColor && Build.VERSION.SDK_INT >= 31,
                enabled = Build.VERSION.SDK_INT >= 31
            ) { settings.updateDynamic(it) }
            Toggle("Pure black", "True black backgrounds in dark theme — saves battery on OLED screens", settings.amoled) {
                settings.updateAmoled(it)
            }
            Toggle("Smooth motion", "Use the screen's highest refresh rate (90/120 Hz) for scrolling, zooming and ink", settings.highRefresh) {
                settings.updateHighRefresh(it)
            }
            Header("Pen")
            Toggle(
                "Draw with finger",
                if (settings.fingerDraws) "One finger draws in markup; two fingers scroll and zoom"
                else "Only a stylus draws; fingers scroll and zoom, and touches near the pen are ignored",
                settings.fingerDraws
            ) { settings.updateFingerDraws(it) }
            Header("Reading")
            Toggle("Keep screen on", "Don't let the screen sleep while Lumen is open", settings.keepScreenOn) {
                settings.updateKeepScreenOn(it)
            }
            Header("About")
            val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
            ListItem(
                headlineContent = { Text("Lumen ${version ?: ""}") },
                supportingContent = { Text("Fully offline: Lumen has no internet permission, so your files never leave this device.") }
            )
        }
    }
}

@Composable
private fun Header(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp)
    )
}

@Composable
private fun Toggle(title: String, subtitle: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        trailingContent = { Switch(checked, onChange, enabled = enabled) },
        modifier = Modifier.clickable(enabled = enabled) { onChange(!checked) }
    )
}
