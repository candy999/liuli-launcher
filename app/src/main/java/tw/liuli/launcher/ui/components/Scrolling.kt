package tw.liuli.launcher.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec

/**
 * 「只在需要時才捲動」的焦點捲動規則。
 *
 * Compose 1.7 在電視上預設使用 PivotBringIntoViewSpec：每次焦點改變都把焦點項目
 * 對齊到畫面約 30% 的位置，結果左右移動時整個清單會上下跳。這裡改成：
 * 項目已完整可見（含邊距）就完全不捲；只露出一部分時才捲到剛好看見。
 */
@OptIn(ExperimentalFoundationApi::class)
class KeepVisibleSpec(
    private val marginFraction: Float = 0.08f,
    /**
     * 容忍值：焦點項目放大（1.1 倍）時邊界會超出一點點，量測到的大小也會隨動畫變化。
     * 超出量小於這個比例就不捲，避免左右移動時清單被推來推去。
     */
    private val toleranceFraction: Float = 0.05f,
) : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val margin = containerSize * marginFraction
        val tolerance = containerSize * toleranceFraction
        val leading = offset - margin
        val trailing = offset + size - (containerSize - margin)
        return when {
            // 項目比可視範圍還大：對齊開頭
            size > containerSize - margin * 2 -> if (leading < -tolerance || leading > tolerance) leading else 0f
            leading < -tolerance -> leading
            trailing > tolerance -> trailing
            else -> 0f
        }
    }
}
