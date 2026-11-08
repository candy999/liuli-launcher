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
class KeepVisibleSpec(private val marginFraction: Float = 0.08f) : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val margin = containerSize * marginFraction
        val leading = offset - margin
        val trailing = offset + size - (containerSize - margin)
        return when {
            // 項目比可視範圍還大：對齊開頭
            size > containerSize - margin * 2 -> leading
            leading < 0f -> leading
            trailing > 0f -> trailing
            else -> 0f
        }
    }
}
