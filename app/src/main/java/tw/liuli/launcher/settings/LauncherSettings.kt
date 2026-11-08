package tw.liuli.launcher.settings

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** 啟動器設定（Compose 可觀察，存在 SharedPreferences）。 */
class LauncherSettings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** 背景樣式 id（見 BgStyle）。 */
    var bgStyle by mutableStateOf(prefs.getString("bg_style", "waves") ?: "waves")
        private set

    /** 背景色系是否跟著焦點項目變化。 */
    var bgFollow by mutableStateOf(prefs.getBoolean("bg_follow", true))
        private set

    var cityId by mutableStateOf(prefs.getString("city", "taipei") ?: "taipei")
        private set

    /** 畫面黑框：粗細、內側圓角半徑、往內漸變範圍（dp；粗細與漸變都是 0＝關閉）。 */
    var frameThickness by mutableIntStateOf(prefs.getInt("frame_thickness", 0))
        private set
    var frameRadius by mutableIntStateOf(prefs.getInt("frame_radius", 32))
        private set
    var frameFeather by mutableIntStateOf(prefs.getInt("frame_feather", 0))
        private set

    fun updateFrame(thickness: Int = frameThickness, radius: Int = frameRadius, feather: Int = frameFeather) {
        frameThickness = thickness; frameRadius = radius; frameFeather = feather
        prefs.edit().putInt("frame_thickness", thickness).putInt("frame_radius", radius).putInt("frame_feather", feather).apply()
    }

    fun updateBgStyle(id: String) {
        bgStyle = id
        prefs.edit().putString("bg_style", id).apply()
    }

    fun updateBgFollow(on: Boolean) {
        bgFollow = on
        prefs.edit().putBoolean("bg_follow", on).apply()
    }

    fun updateCity(id: String) {
        cityId = id
        prefs.edit().putString("city", id).apply()
    }
}
