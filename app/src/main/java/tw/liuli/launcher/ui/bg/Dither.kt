package tw.liuli.launcher.ui.bg

import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.DrawStyle
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.nativeCanvas
import kotlin.math.roundToInt

/**
 * 抖色（dither）：所有漸層在「量化成 8 bit 之前」加入不到一個色階的噪點，每個通道各自計算。
 *
 * 8 bit 面板一個通道只有 256 階，暗色的大面積漸層一階常常橫跨上百個像素（海信實測極光暗部：同一個值連續 80～130 像素），
 * 看起來就是一圈一圈的色帶。事後疊一張紋理只能遮，遮不掉；抖色是在漸層算出浮點顏色、四捨五入成整數的那一刻，
 * 讓「介於兩階之間」的位置依比例交錯成兩個相鄰的值——平均起來就是正確的中間色，色帶消失，噪點只有 ±½ 階、肉眼看不見。
 *
 * 做法：把樣式的 DrawScope 包一層，漸層（ShaderBrush）改用原生 Paint 畫，著色器＝漸層 ADD 一張噪點貼圖（[ComposeShader]）。
 * 兩者在 GPU 的片段著色器裡以浮點相加，最後寫入畫面時才四捨五入成 8 bit——噪點加在「量化之前」，這才是真正的抖色。
 * 噪點貼圖每個通道各自是 0 或 1 階（R、G、B 互相獨立），雙線性取樣、實體螢幕上 2 像素一格，取樣值就是 0～1 階之間的連續小數。
 * 噪點跟著焦點做小數像素的視差位移（[noiseParallax]，用背景已經平滑過的焦點位置），和背景一起滑動，
 * 不會像固定在螢幕上的紗窗；背景切換解析度時顆粒大小也不變。
 * 平均多 ½ 階，看不出來。純色、描邊等其他繪圖照舊交給原本的 DrawScope。
 *
 * 只設 Paint.DITHER_FLAG 不夠：海信（Android 9、Mali-470）實測開了旗標，像素和沒開完全一樣。
 */
internal class DitherScope(
    private val d: DrawScope,
    private val paint: android.graphics.Paint,
    private val noise: android.graphics.BitmapShader,
    private val m: android.graphics.Matrix,
    private val inv: android.graphics.Matrix,
    /** 噪點一格＝畫布上幾個像素（BackgroundLayer 傳 2／d：不管背景用哪一檔解析度，實體螢幕上永遠是 2 像素一格）。 */
    private val noisePx: Float,
    /** 噪點的視差位移（畫布像素，可以是小數）：跟著焦點平滑移動，不是固定在螢幕上。 */
    private val noiseShift: Offset,
) : DrawScope by d {

    /** 漸層＋一般填色＋（不透明度、SrcOver／Plus）→ 設好抖色 Paint；其他情況回傳 null 交給原本的 DrawScope。 */
    private fun prepare(brush: Brush, alpha: Float, style: DrawStyle, colorFilter: ColorFilter?, blendMode: BlendMode): android.graphics.Paint? {
        if (brush !is ShaderBrush || style != Fill || colorFilter != null) return null
        // 只抖一般疊加的漸層（底色、光暈、色塊）。加法混合的通常是極光光簾這種一幀上百個的小亮條：
        // 每個都多建一個合成著色器，弱電視上每幀多 15ms 以上；而且亮的加法光不是色帶的來源。
        if (blendMode != BlendMode.SrcOver) return null
        val mode: PorterDuffXfermode? = null
        val canvas = d.drawContext.canvas.nativeCanvas
        // 噪點貼圖對齊畫布像素：local＝目前矩陣的反矩陣 × 視差位移 × 一格的大小
        // （樣式在 480×270 的邏輯座標裡畫，還可能有拉伸；反矩陣把這些都抵銷掉）
        @Suppress("DEPRECATION") canvas.getMatrix(m)
        if (m.invert(inv)) {
            inv.preTranslate(noiseShift.x, noiseShift.y)
            inv.preScale(noisePx, noisePx)
            noise.setLocalMatrix(inv)
        }
        paint.shader = android.graphics.ComposeShader(brush.createShader(d.drawContext.size), noise, PorterDuff.Mode.ADD)
        paint.alpha = (alpha.coerceIn(0f, 1f) * 255f).roundToInt()
        paint.xfermode = mode
        return paint
    }

    override fun drawRect(
        brush: Brush, topLeft: Offset, size: Size, alpha: Float,
        style: DrawStyle, colorFilter: ColorFilter?, blendMode: BlendMode,
    ) {
        val p = prepare(brush, alpha, style, colorFilter, blendMode)
            ?: return d.drawRect(brush, topLeft, size, alpha, style, colorFilter, blendMode)
        d.drawContext.canvas.nativeCanvas.drawRect(topLeft.x, topLeft.y, topLeft.x + size.width, topLeft.y + size.height, p)
    }

    override fun drawCircle(
        brush: Brush, radius: Float, center: Offset, alpha: Float,
        style: DrawStyle, colorFilter: ColorFilter?, blendMode: BlendMode,
    ) {
        val p = prepare(brush, alpha, style, colorFilter, blendMode)
            ?: return d.drawCircle(brush, radius, center, alpha, style, colorFilter, blendMode)
        d.drawContext.canvas.nativeCanvas.drawCircle(center.x, center.y, radius, p)
    }

    override fun drawPath(
        path: Path, brush: Brush, alpha: Float,
        style: DrawStyle, colorFilter: ColorFilter?, blendMode: BlendMode,
    ) {
        val p = prepare(brush, alpha, style, colorFilter, blendMode)
            ?: return d.drawPath(path, brush, alpha, style, colorFilter, blendMode)
        d.drawContext.canvas.nativeCanvas.drawPath(path.asAndroidPath(), p)
    }

    companion object {
        /** 噪點一格在實體螢幕上的大小（像素）。 */
        const val NOISE_PX = 2f
        private const val NOISE_SIZE = 64

        fun newPaint() = android.graphics.Paint(
            android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.DITHER_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG,
        )

        /**
         * 噪點貼圖：每個像素 R、G、B 各自隨機 0 或 1 階。透明度是 1／255（預乘 alpha 的限制：顏色不能大於透明度），
         * 加到漸層上透明度只多 1／255，看不出來。
         */
        fun newNoise(): android.graphics.BitmapShader {
            val px = IntArray(NOISE_SIZE * NOISE_SIZE)
            var z = 0x1B873593
            for (i in px.indices) {
                z = z xor (z shl 13); z = z xor (z ushr 17); z = z xor (z shl 5)
                // 未預乘的顏色：透明度 1，通道 255＝預乘後 1／255（一階）、0＝不加
                val r = if (z and 1 != 0) 0xFF else 0
                val g = if (z and 2 != 0) 0xFF else 0
                val b = if (z and 4 != 0) 0xFF else 0
                px[i] = (1 shl 24) or (r shl 16) or (g shl 8) or b
            }
            val bmp = android.graphics.Bitmap.createBitmap(px, NOISE_SIZE, NOISE_SIZE, android.graphics.Bitmap.Config.ARGB_8888)
            return android.graphics.BitmapShader(bmp, android.graphics.Shader.TileMode.REPEAT, android.graphics.Shader.TileMode.REPEAT)
        }
    }
}

/** 噪點視差的最大位移（dp）：焦點從畫面一端到另一端，噪點反向滑動這麼多。 */
const val NOISE_PARALLAX_DP = 18f

/**
 * 噪點的視差位移（實體像素，小數）：焦點往右，噪點往左，像背景表面有一層細砂跟著輕輕滑動。
 * 用 [BgState.fx]／[BgState.fy]——引擎已經做過兩段平滑（有緩衝），不會一下跳過去。
 */
fun BgState.noiseParallax(rangePx: Float): Offset =
    Offset(-(fx - .5f) * 2f * rangePx, -(fy - .5f) * 2f * rangePx * .6f)
