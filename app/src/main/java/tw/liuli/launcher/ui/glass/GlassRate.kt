package tw.liuli.launcher.ui.glass

import android.os.SystemClock
import android.util.Log
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.min

/**
 * 毛玻璃每秒更新幾次：依實測負載自動調整，讓「玻璃」總共最多用掉一個 CPU 核心的 [BUDGET]（80%）。
 * 玻璃的負載＝背景小圖（畫樣式＋模糊）＋每一片玻璃重新取色，都在背景執行緒，用執行緒 CPU 時間量。
 * - 每約 1 秒算一次：超過預算立刻照比例降；有餘裕慢慢升（每秒最多多 3 次）；操作中只降不升（主執行緒留給按鍵）。
 * - 用每次的成本推估（趕不上時次數會變少，直接看用了多少會誤判成付得起）；背景靜止、一秒不到 3 次的那一窗不算。
 * - 範圍每秒 [MIN]～[MAX] 次。低於 [FADE_BELOW] 次時玻璃在兩次之間交叉淡化（[GlassBake]），看起來仍然連續；
 *   15 次以上直接換（間隔夠短看不出跳，淡化每格要畫兩層，海信上會把背景擠掉一兩檔解析度）。
 */
object GlassRate {
    const val MIN = 8f
    const val MAX = 30f
    const val BUDGET = .8f
    const val FADE_BELOW = 15f

    /** 目前每秒幾次（背景引擎與 GL 照這個間隔出背景小圖）。 */
    @Volatile var perSec = 15f
        private set
    val intervalMs: Float get() = 1000f / perSec

    /** 交叉淡化（adb ACTION=glassfade:0 關掉做對照，不存）。 */
    @Volatile var fade = true

    /** 量測用：adb ACTION=glassmax:N 把上限壓到每秒 N 次（0＝不限；不存）。 */
    @Volatile var debugMax = 0f
        set(v) { field = v; if (v > 0f) perSec = min(perSec, max(MIN, v)) }

    /** 操作中（按鍵後一小段時間、面板開著）：不升。主執行緒寫入。 */
    @Volatile var busy = false

    private val cpuNs = AtomicLong()
    private val updates = AtomicInteger()
    private var since = 0L

    /** 背景執行緒做完一段玻璃的工作（執行緒 CPU 時間，奈秒）。 */
    fun addCpu(ns: Long) { if (ns > 0) cpuNs.addAndGet(ns) }

    /** 出了一張新的背景小圖（主執行緒）。 */
    fun updated() {
        updates.incrementAndGet()
        val now = SystemClock.uptimeMillis()
        if (since == 0L) { since = now; return }
        val el = now - since
        if (el < 1000) return
        since = now
        val n = updates.getAndSet(0)
        val c = cpuNs.getAndSet(0)
        // 用「每次的成本」推估目前頻率下的負載（不直接看這一秒用了多少：趕不上時次數變少，負載看起來反而低；
        // 背景停下來時也一樣）。太少次（靜止）不算
        if (n < 3) return
        val costMs = c / 1e6f / n
        val load = costMs * perSec / 1000f   // 照目前頻率要幾個核心
        val target = (BUDGET * 1000f / max(costMs, .1f)).coerceIn(MIN, if (debugMax > 0f) max(MIN, debugMax) else MAX)
        val old = perSec
        perSec = when {
            target < perSec -> target
            busy -> perSec
            else -> min(target, perSec + 3f)
        }
        if (tw.liuli.launcher.ui.bg.GlBackground.verbose || (old - perSec) > 2f)
            Log.i("LiuliGlass", "rate %.1f/s → %.1f/s（負載 %.0f%% 核心、每次 %.1fms、%d 次）".format(old, perSec, load * 100, c / 1e6f / n, n))
    }
}
