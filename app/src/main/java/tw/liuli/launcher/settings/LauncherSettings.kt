package tw.liuli.launcher.settings

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** 啟動器設定（Compose 可觀察，存在 SharedPreferences）。 */
class LauncherSettings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** 桌布配色：auto（依時間）或 [tw.liuli.launcher.ui.glass.WallpaperPalette] 的 id。 */
    var palette by mutableStateOf(prefs.getString("palette", AUTO) ?: AUTO)
        private set

    var cityId by mutableStateOf(prefs.getString("city", "taipei") ?: "taipei")
        private set

    fun updatePalette(id: String) {
        palette = id
        prefs.edit().putString("palette", id).apply()
    }

    fun updateCity(id: String) {
        cityId = id
        prefs.edit().putString("city", id).apply()
    }

    companion object {
        const val AUTO = "auto"
    }
}
