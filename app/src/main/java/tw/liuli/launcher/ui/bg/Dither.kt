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
 * Compose 的繪圖沒有開抖色的參數，所以把樣式的 DrawScope 包一層：漸層（ShaderBrush）改用開了 DITHER 的原生 Paint 畫，
 * 純色、描邊等其他繪圖照舊交給原本的 DrawScope。GPU 上抖色只是著色器多幾個運算，幾乎不花成本。
 */
internal class DitherScope(private val d: DrawScope, private val paint: android.graphics.Paint) : DrawScope by d {

    /** 漸層＋一般填色＋（不透明度、SrcOver／Plus）→ 設好抖色 Paint；其他情況回傳 null 交給原本的 DrawScope。 */
    private fun prepare(brush: Brush, alpha: Float, style: DrawStyle, colorFilter: ColorFilter?, blendMode: BlendMode): android.graphics.Paint? {
        if (brush !is ShaderBrush || style != Fill || colorFilter != null) return null
        val mode = when (blendMode) {
            BlendMode.SrcOver -> null
            BlendMode.Plus -> PLUS
            else -> return null
        }
        paint.shader = brush.createShader(d.drawContext.size)
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
        private val PLUS = PorterDuffXfermode(PorterDuff.Mode.ADD)

        fun newPaint() = android.graphics.Paint(
            android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.DITHER_FLAG,
        )
    }
}
