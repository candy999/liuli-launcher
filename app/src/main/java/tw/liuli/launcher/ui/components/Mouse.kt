package tw.liuli.launcher.ui.components

import android.os.SystemClock
import android.view.MotionEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput

/**
 * 滑鼠／飛鼠模式：游標移到哪裡焦點就跟到哪裡、點一下＝確定、滾輪＝上下鍵（滾輪在 MainActivity 換成方向鍵）。
 *
 * 只有「游標真的在動」時才讓焦點跟著游標：用遙控器移動焦點時列表會捲動，內容從靜止的游標底下滑過，
 * 不擋的話焦點會被游標底下那一格搶走。
 */
object MouseInput {
    /** 游標最後一次真的移動的時間（uptimeMillis）。 */
    @Volatile var lastMoveAt = 0L
        private set
    private var lx = Float.NaN
    private var ly = Float.NaN

    /** 游標停了一陣子後又開始動就 +1（Compose 可觀察）：用來叫醒簡潔模式。 */
    var wakes by mutableIntStateOf(0)
        private set

    /** 由 Activity 收到游標移動（HOVER_MOVE）時呼叫。 */
    fun onPointerMotion(e: MotionEvent) {
        if (e.x == lx && e.y == ly) return
        lx = e.x; ly = e.y
        val now = SystemClock.uptimeMillis()
        if (now - lastMoveAt > 300) wakes++
        lastMoveAt = now
    }

    /** 游標剛剛（200ms 內）有在動。 */
    val recentlyMoved: Boolean get() = SystemClock.uptimeMillis() - lastMoveAt < 200
}

/**
 * 擋住游標：蓋在主畫面上的層（抽屜、設定、子頁面）用它，游標經過空白處時不會穿過去，
 * 把焦點帶到被蓋住的主畫面元件上。只吃游標事件，不影響遙控器。
 */
fun Modifier.blockPointer(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope { while (true) awaitPointerEvent() }
}
