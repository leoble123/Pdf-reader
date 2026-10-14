package app.lumen.reader.core

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.core.content.edit

enum class ThemeMode(val label: String) { System("System"), Light("Light"), Dark("Dark") }

/** App preferences as Compose state, persisted to SharedPreferences on every change. */
class Settings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var themeMode by mutableStateOf(runCatching { ThemeMode.valueOf(prefs.getString("theme", "System")!!) }.getOrDefault(ThemeMode.System))
        private set
    var dynamicColor by mutableStateOf(prefs.getBoolean("dynamic", true))
        private set
    var keepScreenOn by mutableStateOf(prefs.getBoolean("screen_on", true))
        private set
    var amoled by mutableStateOf(prefs.getBoolean("amoled", false))
        private set

    fun updateTheme(v: ThemeMode) { themeMode = v; prefs.edit { putString("theme", v.name) } }
    fun updateDynamic(v: Boolean) { dynamicColor = v; prefs.edit { putBoolean("dynamic", v) } }
    fun updateKeepScreenOn(v: Boolean) { keepScreenOn = v; prefs.edit { putBoolean("screen_on", v) } }
    fun updateAmoled(v: Boolean) { amoled = v; prefs.edit { putBoolean("amoled", v) } }
}

val LocalSettings = staticCompositionLocalOf<Settings> { error("Settings not provided") }
