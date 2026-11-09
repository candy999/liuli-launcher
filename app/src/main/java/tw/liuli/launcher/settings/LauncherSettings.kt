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

    /** 畫面黑框：粗細、內側圓角半徑、往內漸變範圍（dp；三個都是 0＝關閉；粗細 0＝不內縮只有圓角）。 */
    var frameThickness by mutableIntStateOf(prefs.getInt("frame_thickness", 0))
        private set
    var frameRadius by mutableIntStateOf(prefs.getInt("frame_radius", 0))
        private set
    var frameFeather by mutableIntStateOf(prefs.getInt("frame_feather", 0))
        private set

    /** 背景模糊等級（0＝關，1～6）。 */
    var bgBlur by mutableIntStateOf(prefs.getInt("bg_blur", 0))
        private set
    /** 背景紋理 id（空字串＝關，見 BgTexture）與不透明度（5～100%）。 */
    var texture by mutableStateOf(prefs.getString("texture", "") ?: "")
        private set
    var textureOpacity by mutableIntStateOf(prefs.getInt("texture_opacity", 35))
        private set

    fun updateBgBlur(level: Int) {
        bgBlur = level
        prefs.edit().putInt("bg_blur", level).apply()
    }

    fun updateTexture(id: String = texture, opacity: Int = textureOpacity) {
        texture = id; textureOpacity = opacity
        prefs.edit().putString("texture", id).putInt("texture_opacity", opacity).apply()
    }

    /** 螢幕小時鐘（看其他 App 時角落的時間）：開關、角落（0 左上 1 右上 2 左下 3 右下）、字級 sp、往內位移 dp、背景（0 陰影 1 半透明底）。 */
    var clockOn by mutableStateOf(prefs.getBoolean("clock_on", false))
        private set
    var clockCorner by mutableIntStateOf(prefs.getInt("clock_corner", 1))
        private set
    var clockSize by mutableIntStateOf(prefs.getInt("clock_size", 22))
        private set
    var clockX by mutableIntStateOf(prefs.getInt("clock_x", 40))
        private set
    var clockY by mutableIntStateOf(prefs.getInt("clock_y", 28))
        private set
    var clockBg by mutableIntStateOf(prefs.getInt("clock_bg", 0))
        private set

    fun updateClock(
        on: Boolean = clockOn, corner: Int = clockCorner, size: Int = clockSize,
        x: Int = clockX, y: Int = clockY, bg: Int = clockBg,
    ) {
        clockOn = on; clockCorner = corner; clockSize = size; clockX = x; clockY = y; clockBg = bg
        prefs.edit().putBoolean("clock_on", on).putInt("clock_corner", corner).putInt("clock_size", size)
            .putInt("clock_x", x).putInt("clock_y", y).putInt("clock_bg", bg).apply()
    }

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
