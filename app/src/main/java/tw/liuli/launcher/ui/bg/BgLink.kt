package tw.liuli.launcher.ui.bg

import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import kotlinx.coroutines.CoroutineScope

/** 讓任何可聚焦元件在取得焦點時通知背景：主題色＋位置＋移動方向。 */
class BgLink(
    val engine: BackgroundEngine,
    val scope: CoroutineScope,
    val screen: () -> Size,
    /** 最後一次左右鍵的方向（-1、0、1），給翻轉、擺動類樣式用。 */
    val lastDir: () -> Int,
) {
    fun focus(color: Int?, cx: Float, cy: Float, isApp: Boolean = false) {
        val s = screen()
        if (s.width <= 0f || s.height <= 0f) return
        engine.focus(scope, color, cx / s.width, cy / s.height, lastDir(), isApp)
    }
}

val LocalBgLink = staticCompositionLocalOf<BgLink?> { null }

/** 主題色常數（非 App 的焦點項目）。 */
object BgColors {
    const val CLOCK = 0xFFD9A03A.toInt()     // 時鐘／農民曆：金
    const val WEATHER = 0xFF3A64C8.toInt()   // 天氣：藍
    const val GEAR = 0xFF7A8194.toInt()      // 設定：中性灰
    const val TV = 0xFF2F6FE0.toInt()        // 電視直播
    const val HDMI = 0xFF6D5BD8.toInt()      // HDMI
    const val OTHER_INPUT = 0xFF5A6B8C.toInt()
    const val CALENDAR = 0xFFC9973A.toInt()
    const val NEUTRAL = 0xFF4F6BD0.toInt()   // 取不到 App 主題色時
}

/**
 * 放在 focusTarget 之前：記下元件中心位置，取得焦點時通知背景換色、移動焦點位置。
 */
fun Modifier.bgTarget(color: Int?, isApp: Boolean = false): Modifier = composed {
    val link = LocalBgLink.current
    val center = remember { FloatArray(2) }
    this
        .onGloballyPositioned { c ->
            val b = c.boundsInRoot()
            center[0] = b.center.x; center[1] = b.center.y
        }
        .onFocusChanged { if (it.isFocused) link?.focus(color ?: BgColors.NEUTRAL, center[0], center[1], isApp) }
}
