package tw.liuli.launcher.ui.bg

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * 銀河的星雲貼圖：用「碎形雜訊」程序化生成一次（背景執行緒、約 0.2 秒），之後每格只是貼一張圖。
 *
 * 真實的銀河不是幾團光，而是：
 * - 沿著一條帶子、密度由中線往外遞減的星雲（裡面有大大小小、不規則的雲團——碎形雜訊）
 * - 被彎彎曲曲的暗塵帶切開（另一組雜訊在中線附近挖出暗紋）
 * - 核心偏暖（黃白）、外圍偏冷（藍白）、飽和度很低
 * - 一大堆暗淡的微小星點糊成的顆粒感
 * 生成後再輕微模糊一次，讓它「暈開」，最後以加法混合疊在深空上。
 * 座標：涵蓋邏輯畫面的 110%（視差移動時邊緣不會露空）；帶子與 Styles 的星點同一條斜線（-24°）。
 */
internal object MilkyWay {
    const val TW = 400
    const val TH = 225
    /** 貼圖涵蓋的邏輯範圍（比 480×270 大一圈）。 */
    const val MARGIN = .05f

    @Volatile var image: ImageBitmap? = null
        private set
    private val started = AtomicBoolean(false)

    /** 第一次要用時在背景執行緒生成；還沒好之前呼叫端先用簡易的柔光頂著。 */
    fun request() {
        if (!started.compareAndSet(false, true)) return
        Thread({ image = runCatching { build() }.getOrNull() }, "liuli-milkyway").apply {
            priority = Thread.MIN_PRIORITY
            start()
        }
    }

    // ── 值雜訊＋碎形 ──
    private fun hash(x: Int, y: Int, seed: Int): Float {
        var h = x * 374761393 + y * 668265263 + seed * 1442695041
        h = (h xor (h ushr 13)) * 1274126177
        return ((h xor (h ushr 16)) and 0xFFFFFF) / 16777216f
    }

    private fun noise(x: Float, y: Float, seed: Int): Float {
        val xi = floor(x).toInt(); val yi = floor(y).toInt()
        val fx = x - xi; val fy = y - yi
        val ux = fx * fx * (3 - 2 * fx); val uy = fy * fy * (3 - 2 * fy)
        val a = hash(xi, yi, seed); val b = hash(xi + 1, yi, seed)
        val c = hash(xi, yi + 1, seed); val d = hash(xi + 1, yi + 1, seed)
        return (a + (b - a) * ux) + ((c + (d - c) * ux) - (a + (b - a) * ux)) * uy
    }

    private fun fbm(x: Float, y: Float, octaves: Int, seed: Int): Float {
        var sum = 0f; var amp = .5f; var f = 1f; var norm = 0f
        for (o in 0 until octaves) {
            sum += amp * noise(x * f, y * f, seed + o * 17)
            norm += amp; amp *= .55f; f *= 2.03f
        }
        return sum / norm
    }

    private fun build(): ImageBitmap {
        val w = 480f; val h = 270f
        val ang = Math.toRadians(-24.0)
        val ca = cos(ang).toFloat(); val sa = sin(ang).toFloat()
        val lum = FloatArray(TW * TH)
        val warm = FloatArray(TW * TH)
        for (j in 0 until TH) for (i in 0 until TW) {
            // 這個像素在邏輯座標的位置
            val lx = -w * MARGIN + (i + .5f) / TW * w * (1 + 2 * MARGIN)
            val ly = -h * MARGIN + (j + .5f) / TH * h * (1 + 2 * MARGIN)
            // 轉到銀河自己的座標：u 沿著帶子（0～1）、v 是離中線的距離
            val dx = lx - w / 2; val dy = ly - h / 2
            val along = dx * ca + dy * sa
            val across = -dx * sa + dy * ca
            val u = (along / (w * 1.15f)) + .5f
            // 中線本身也彎彎的
            val bend = 7f * sin(u * 5.1f) + 4f * sin(u * 11.3f + 1f)
            val v = across - bend
            val core = exp(-((u - .55f) / .24f) * ((u - .55f) / .24f))
            val thick = h * (.075f + .07f * core)
            val prof = exp(-(v / thick) * (v / thick))
            // 雲團：大尺度＋細碎
            val cloud = fbm(along * .022f, across * .05f, 5, 11)
            val fine = fbm(along * .09f, across * .12f, 3, 29)
            var d = prof * (.25f + 1.15f * cloud * cloud) * (.75f + .5f * fine)
            // 外圍更淡、更散的一層暈
            d += .18f * exp(-(v / (thick * 2.6f)) * (v / (thick * 2.6f))) * (.6f + .8f * fbm(along * .015f, across * .03f, 3, 47))
            // 暗塵帶：中線附近，另一組雜訊挖出彎曲的暗紋
            val laneV = v - (5f * (fbm(along * .03f, 0f, 3, 61) - .5f) * 2f)
            val lane = exp(-(laneV / (thick * .35f)) * (laneV / (thick * .35f)))
            val dust = lane * max(0f, fbm(along * .045f, across * .1f, 4, 73) - .35f) * 2.4f
            d *= (1f - min(.9f, dust))
            lum[j * TW + i] = d
            warm[j * TW + i] = core * prof
        }
        // 輕微模糊兩次（3×3）：雲團暈開、沒有硬邊
        repeat(2) {
            val tmp = lum.copyOf()
            for (j in 1 until TH - 1) for (i in 1 until TW - 1) {
                var s = 0f
                for (dj in -1..1) for (di in -1..1) s += tmp[(j + dj) * TW + i + di] * (if (di == 0 && dj == 0) 4f else if (di == 0 || dj == 0) 2f else 1f)
                lum[j * TW + i] = s / 16f
            }
        }
        val px = IntArray(TW * TH)
        for (k in px.indices) {
            val l = min(1f, lum[k] * .55f)
            val wm = warm[k]
            // 核心偏暖（黃白）、外圍偏冷（藍白），飽和度很低
            val r = l * (.86f + .14f * wm)
            val g = l * (.86f + .04f * wm)
            val b = l * (.95f - .2f * wm)
            // 微小星點糊成的顆粒：亮處多一點細碎的亮點
            val grain = if (hash(k, 7, 91) > .985f) .25f * l + .04f else 0f
            fun c(v: Float) = (min(1f, v + grain) * 255f + .5f).toInt()
            px[k] = (0xFF shl 24) or (c(r) shl 16) or (c(g) shl 8) or c(b)
        }
        return Bitmap.createBitmap(px, TW, TH, Bitmap.Config.ARGB_8888).asImageBitmap()
    }
}
