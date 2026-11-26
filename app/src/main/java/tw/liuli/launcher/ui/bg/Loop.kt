package tw.liuli.launcher.ui.bg

import kotlin.math.PI
import kotlin.math.round
import kotlin.math.sign

/**
 * 持續動畫的循環：[BgState.at] 每 [P] 秒回到 0，背景可以永遠動下去，不會跳。
 *
 * 以前 at 一直往上加：單精度的 at 開了幾天之後每一格加的量被捨入吃掉，動畫一頓一頓的；各樣式又各自把時間取模
 * （64 或 2π）傳給著色器，取模的週期和動畫本身的週期對不上，每隔幾十秒到幾十分鐘整片突然跳一下。
 *
 * 規則：凡是跟著 at 走的週期性動作，一個 P 裡都要剛好走完整數圈——
 * - sin／cos 的角速度（弧度／秒）用 [w] 對齊（改變量在 0.1% 上下，看不出來）
 * - 平移（雜訊、貼圖、循環的位置）的速度用 [v] 對齊到「P 秒剛好平移週期的整數倍」
 * - 角度（度／秒）用 [v]（週期 360）
 * 逐格累加的狀態（流體、水彩的顏料、攪拌）不用 at 的絕對值，只用兩格之間的差（at 歸零那一格差是負的，當 0）。
 */
object Loop {
    /** 一個循環幾秒（at 的秒，跟不上時播放變慢，實際時間更長）。3600 秒內單精度的時間誤差不到 0.25 毫秒。 */
    const val P = 3600.0
    private const val TAU = 2 * PI

    /** 角速度 [k]（弧度／秒）對齊到 P 秒整數圈（至少一圈；0 還是 0）。 */
    fun w(k: Double): Double {
        if (k == 0.0) return 0.0
        val n = round(k * P / TAU)
        return (if (n == 0.0) sign(k) else n) * TAU / P
    }
    fun w(k: Float): Float = w(k.toDouble()).toFloat()

    /** 速度 [s]（每秒幾個單位）對齊到 P 秒剛好走完週期 [period] 的整數倍（至少一個週期；0 還是 0）。 */
    fun v(s: Double, period: Double): Double {
        if (s == 0.0) return 0.0
        val n = round(s * P / period)
        return (if (n == 0.0) sign(s) else n) * period / P
    }
    fun v(s: Float, period: Float): Float = v(s.toDouble(), period.toDouble()).toFloat()

    /** 從 [last] 到 [at] 經過的 at（跨過歸零也算對；往回撥＝負的）。 */
    fun diff(at: Float, last: Float): Float {
        if (last.isNaN()) return 0f
        var d = at - last
        if (d < -P / 2) d += P.toFloat() else if (d > P / 2) d -= P.toFloat()
        return d
    }

    /** 兩格之間經過的 at（0～[max] 秒；往回撥當 0）。 */
    fun dt(at: Float, last: Float, max: Float = .25f): Float = diff(at, last).coerceIn(0f, max)
}
