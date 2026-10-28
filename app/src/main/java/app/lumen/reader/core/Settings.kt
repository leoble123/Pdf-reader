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

    /** Whether a finger draws in markup mode. Turned off automatically the first time a stylus touches. */
    var fingerDraws by mutableStateOf(prefs.getBoolean("finger_draws", true))
        private set
    var stylusSeen by mutableStateOf(prefs.getBoolean("stylus_seen", false))
        private set

    /** Run at the display's fastest refresh rate (90/120 Hz) instead of letting the system pick. */
    var highRefresh by mutableStateOf(prefs.getBoolean("high_refresh", true))
        private set

    fun updateHighRefresh(v: Boolean) { highRefresh = v; prefs.edit { putBoolean("high_refresh", v) } }

    fun updateFingerDraws(v: Boolean) { fingerDraws = v; prefs.edit { putBoolean("finger_draws", v) } }

    /** First stylus contact: from now on fingers navigate and the pen draws. Returns true the first time. */
    fun noteStylus(): Boolean {
        if (stylusSeen) return false
        stylusSeen = true
        fingerDraws = false
        prefs.edit { putBoolean("stylus_seen", true); putBoolean("finger_draws", false) }
        return true
    }

    fun updateTheme(v: ThemeMode) { themeMode = v; prefs.edit { putString("theme", v.name) } }
    fun updateDynamic(v: Boolean) { dynamicColor = v; prefs.edit { putBoolean("dynamic", v) } }
    fun updateKeepScreenOn(v: Boolean) { keepScreenOn = v; prefs.edit { putBoolean("screen_on", v) } }
    fun updateAmoled(v: Boolean) { amoled = v; prefs.edit { putBoolean("amoled", v) } }
}

val LocalSettings = staticCompositionLocalOf<Settings> { error("Settings not provided") }
