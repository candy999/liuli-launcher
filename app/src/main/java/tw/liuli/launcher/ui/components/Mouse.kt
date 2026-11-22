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

    /**
     * 陀螺儀飛鼠拿在手上，游標會一直微微抖動。按過遙控器鍵（或滾輪）之後，游標要離開按鍵當下的位置超過一小段距離，
     * 才算「改用游標」——不然用方向鍵移動焦點、列表捲動後，抖動的游標會把焦點搶回自己底下那一格。
     */
    private var ax = Float.NaN
    private var ay = Float.NaN
    private var armed = true

    /** 按下任何鍵（含滾輪換成的方向鍵）時呼叫：游標要再明顯移動才會帶焦點。 */
    fun onKey() {
        armed = false
        ax = lx; ay = ly
    }

    /**
     * 滾輪交給目前的頁面處理（月曆翻月、天氣換日子或時段）：回傳 true＝頁面處理了，不再換成上／下鍵。
     * [dir]：-1 往上、1 往下；[x]、[y]：游標在畫面上的位置（像素）。
     */
    @Volatile var wheel: ((dir: Int, x: Float, y: Float) -> Boolean)? = null

    /** 游標停了一陣子後又開始動就 +1（Compose 可觀察）：用來叫醒簡潔模式。 */
    var wakes by mutableIntStateOf(0)
        private set

    /** 由 Activity 收到游標移動（HOVER_MOVE）時呼叫；[slop]：按鍵後游標要移動多遠（像素）才重新帶焦點。 */
    fun onPointerMotion(e: MotionEvent, slop: Float) {
        if (e.x == lx && e.y == ly) return
        lx = e.x; ly = e.y
        if (!armed) {
            if (ax.isNaN() || kotlin.math.hypot(e.x - ax, e.y - ay) > slop) armed = true else return
        }
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
