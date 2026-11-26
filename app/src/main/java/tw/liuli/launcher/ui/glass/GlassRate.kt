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
 * - 只有背景真的照這個頻率在更新時才算（靜止的背景沒有負載，不能拿來當「付得起」）。
 * - 範圍每秒 [MIN]～[MAX] 次；頻率不必是背景格數的整數分之一：玻璃在兩次之間交叉淡化（[GlassBake]），任何頻率看起來都連續。
 */
object GlassRate {
    const val MIN = 8f
    const val MAX = 30f
    const val BUDGET = .8f

    /** 目前每秒幾次（背景引擎與 GL 照這個間隔出背景小圖）。 */
    @Volatile var perSec = 15f
        private set
    val intervalMs: Float get() = 1000f / perSec

    /** 交叉淡化（adb ACTION=glassfade:0 關掉做對照，不存）。 */
    @Volatile var fade = true

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
        // 背景沒有照目前的頻率更新（停下來、轉場剛結束）：這一窗不算
        if (n < perSec * el / 1000f * .7f) return
        val load = c / 1e6f / el   // 幾個核心
        val target = (perSec * BUDGET / max(load, .01f)).coerceIn(MIN, MAX)
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
