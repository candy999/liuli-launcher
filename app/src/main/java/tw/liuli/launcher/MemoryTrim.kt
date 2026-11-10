package tw.liuli.launcher

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 記憶體策略：琉璃是主畫面，回來要快，但不能在背景佔著大量記憶體——系統記憶體吃緊時，
 * 佔得多的背景程序會先被殺，主畫面被殺掉反而要冷啟動、更慢。
 *
 * 離開琉璃（系統通知 TRIM_MEMORY_UI_HIDDEN）時，只保留「回來第一眼」需要的東西：
 * 主畫面本身、App 清單與圖示、背景引擎的狀態。其他為了加速而預先準備的都放掉，交給系統回收：
 * - 預先組合好的月曆、天氣頁、App 抽屜（整棵介面樹與文字排版）
 * - 背景模糊用的輪替圖
 * 回到琉璃後，等畫面穩定幾秒再在背後慢慢重建（不影響回來那一刻的速度）。
 */
object MemoryTrim {
    /** true＝琉璃目前不在前景，預先準備的東西已經（或應該）放掉。 */
    var hidden by mutableStateOf(false)
}
