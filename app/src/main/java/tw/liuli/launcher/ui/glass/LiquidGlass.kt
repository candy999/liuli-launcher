package tw.liuli.launcher.ui.glass

import android.graphics.BitmapShader
import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** 玻璃需要的背景：給玻璃取樣的小張背景、它的版本（換色時遞增）、整個畫面的大小。 */
@Immutable
class GlassBackdrop(
    val image: ImageBitmap?,
    val version: () -> Long,
    val screen: Size,
    val lensing: Boolean,
)

val LocalGlassBackdrop = staticCompositionLocalOf { GlassBackdrop(null, { 0L }, Size.Zero, false) }

/**
 * Liquid Glass 材質。
 *
 * 成本：不做即時模糊。背景引擎每畫一幀，會順便畫一張 120×68 的小背景；
 * 玻璃取出自己背後那一塊放大（雙線性放大＝自然的模糊），等於貼一張圖。
 * Android 13 以上再用 AGSL 著色器做邊緣的透鏡折射。背景靜止時零成本。
 *
 * 疊層（由下而上）：高斯模糊＋過曝的背景（含折射）→ 很淡的白霧 → 由上往下的淡光。
 * 不畫描邊、不畫內光線條（看起來像塑膠框，俗氣）。
 */
fun Modifier.liquidGlass(
    shape: Shape = RoundedCornerShape(26.dp),
    tint: Float = 0.03f,
    /** 0～1：讓玻璃更亮（例如焦點時）。在繪製階段讀取，不會觸發重新組合。 */
    highlight: () -> Float = { 0f },
    /**
     * 滑入動畫的位移（像素）：玻璃取樣背景時扣掉，以「停下來的位置」為準。
     * 這樣滑動中玻璃的內容不變，整塊面板可以畫成一張圖只移動位置（見呼叫端的離屏圖層），
     * 不用每格重畫整片玻璃與裡面所有元件。背景很模糊，滑動中取樣位置差一點看不出來。
     */
    slide: () -> Offset = { Offset.Zero },
    /** true＝動畫中，不更新取樣位置（面板同時在縮放時，位置每格都在變；背景很模糊，凍結在停下時的位置看不出差別）。 */
    frozen: () -> Boolean = { false },
    /** 邊緣的折射與色散（見 [EdgeGlass]）。純資訊用的小面板可以關掉省一點繪圖。 */
    edge: Boolean = true,
): Modifier = composed {
    val backdrop = LocalGlassBackdrop.current
    var origin by remember { mutableStateOf(Offset.Zero) }
    val lens = remember(backdrop.image) {
        val image = backdrop.image
        if (backdrop.lensing && image != null && Build.VERSION.SDK_INT >= 33) LensShader.create(image) else null
    }

    // 背景小圖的貼圖著色器：用 local matrix 對齊到玻璃所在的位置，直接「填」圓角矩形，不用裁切。
    // 弱 GPU（OpenGL ES 2.0）上，反鋸齒的路徑裁切常常要由 CPU 每幀畫一張遮罩（面板 680×1000 像素，RenderThread 40ms）；
    // 圓角矩形填色則由 GPU 直接算，沒有遮罩。
    val wall = remember(backdrop.image) {
        backdrop.image?.let { img ->
            android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG).apply {
                shader = android.graphics.BitmapShader(
                    img.asAndroidBitmap(),
                    android.graphics.Shader.TileMode.CLAMP,
                    android.graphics.Shader.TileMode.CLAMP,
                )
            }
        }
    }
    val matrix = remember { android.graphics.Matrix() }
    // 背景＋白霧＋淡光合成一個著色器、一次填色（大面積玻璃如抽屜，弱 GPU 少填一整層）。
    // 只在位置、大小或亮度改變時重建；靜止時不配置任何物件。
    val oneShot = remember(backdrop.image) { OneShotGlass() }
    val edgeGlass = remember(backdrop.image, edge) { if (edge) EdgeGlass(backdrop.image) else null }

    this
        .onGloballyPositioned {
            if (frozen() && origin != Offset.Zero) return@onGloballyPositioned
            // 取整數像素：滑動中位置的浮點誤差不會讓 origin 每格都「改變」而觸發重畫
            val p = it.positionInRoot() - slide()
            val o = Offset(p.x.roundToInt().toFloat(), p.y.roundToInt().toFloat())
            if (o != origin) origin = o
        }
        .drawWithCache {
            val outline = shape.createOutline(size, layoutDirection, this)
            val path = Path().apply { addOutline(outline) }
            val androidPath = path.asAndroidPath()
            val blurred = backdrop.image
            val radiusPx = (shape as? RoundedCornerShape)?.topStart?.toPx(size, this) ?: 0f
            edgeGlass?.layout(outline, size, density)

            onDrawBehind {
                if (tw.liuli.launcher.ui.bg.PerfProbe.noGlass) return@onDrawBehind
                @Suppress("UNUSED_VARIABLE") val v = backdrop.version() // 背景換色時跟著重畫
                val hl = highlight()
                // 1. 背景：預先模糊的桌布對應區域（含折射）
                if (blurred != null && backdrop.screen.width > 0f) {
                    val sx = blurred.width / backdrop.screen.width
                    val sy = blurred.height / backdrop.screen.height
                    if (lens != null && Build.VERSION.SDK_INT >= 33) {
                        drawPath(path, lens.brush(origin, size.width, size.height, radiusPx, sx, sy))
                    } else if (wall != null) {
                        // 貼圖座標＝（玻璃在畫面上的位置＋本地座標）× 小圖縮放；白霧與淡光用 SCREEN 合成進同一個著色器：
                        // 白色 α 疊在 c 上＝1-(1-c)(1-α)＝screen(c, α)，結果和分開畫兩層相同
                        oneShot.prepare(wall, matrix, origin, size, sx, sy, mistTop(tint, hl), mistOf(tint, hl))
                        drawIntoCanvas {
                            it.nativeCanvas.drawPath(androidPath, wall)
                            edgeGlass?.draw(it.nativeCanvas, origin, size, sx, sy, refract = true)
                        }
                        return@onDrawBehind
                    }
                } else {
                    drawPath(path, Color(0x99141A2E))
                }
                // 2＋3. 很淡的白色薄霧（焦點時再亮一點＝更過曝）＋由上往下非常淡的光，合成一次填色（弱 GPU 少填一層）。
                // 兩層半透明白疊起來的透明度＝1-(1-a)(1-b)，結果和分開畫相同。沒有任何描邊或線條。
                drawPath(
                    path,
                    Brush.verticalGradient(
                        0f to Color.White.copy(alpha = mistTop(tint, hl)),
                        0.6f to Color.White.copy(alpha = mistOf(tint, hl)),
                    ),
                )
                // 有著色器折射的機器（Android 13+）折射已經在本體裡，這裡只補色散與邊線
                if (blurred != null && backdrop.screen.width > 0f) drawIntoCanvas {
                    edgeGlass?.draw(it.nativeCanvas, origin, size, blurred.width / backdrop.screen.width, blurred.height / backdrop.screen.height, refract = lens == null)
                }
            }
        }
}

/** 白色薄霧（焦點時再亮一點＝更過曝）。 */
private fun mistOf(tint: Float, hl: Float) = tint + 0.10f * hl

/** 頂端：薄霧＋由上往下非常淡的光，兩層半透明白合起來的透明度＝1-(1-a)(1-b)。 */
private fun mistTop(tint: Float, hl: Float): Float {
    val glow = 0.06f + 0.06f * hl
    return 1f - (1f - mistOf(tint, hl)) * (1f - glow)
}

/**
 * 液態玻璃的邊緣（不靠 RuntimeShader，Android 9 的弱 GPU 也畫得動）：
 * - 折射：沿著邊緣往內一圈，貼的是「往玻璃中心偏移過」的背景（像厚玻璃邊緣把後面的東西彎進來），再亮一點點；
 *   內側再疊一圈半透明的過渡，不會是一條硬邊。
 * - 色散：最外緣兩條很細的色邊——偏紅的往左上錯開一點、偏藍的往右下錯開一點（紅藍分離）。
 * - 最上面一條受光的細白線。
 * 全部是幾條圓角矩形的描邊，跟著玻璃本體一起畫在同一份顯示清單裡：只有背景小圖更新或大小改變時才重畫，
 * 焦點動畫（只改圖層屬性）期間不會重畫。
 */
private class EdgeGlass(image: ImageBitmap?) {
    private val band = image?.let { img ->
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE
            shader = BitmapShader(img.asAndroidBitmap(), android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP)
        }
    }
    private val m = android.graphics.Matrix()
    private val stroke = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { style = android.graphics.Paint.Style.STROKE }
    private val gm = android.graphics.Matrix()
    // 漸層建在 0～1 的單位正方形（左上→右下），用矩陣拉到玻璃大小
    private fun grad(vararg c: Int) = android.graphics.LinearGradient(
        0f, 0f, 1f, 1f, c, null, android.graphics.Shader.TileMode.CLAMP,
    )
    private val red = grad(0xC0FF5A46.toInt(), 0x30FF8A5A, 0x66FF6A9A)
    private val blue = grad(0x6652C8FF, 0x303C8CFF, 0xC03C7CFF.toInt())
    private val white = grad(0xB8FFFFFF.toInt(), 0x24FFFFFF, 0x66FFFFFF)
    private val glow = grad(0x2EFFFFFF, 0x0CFFFFFF, 0x1EFFFFFF)

    private val pBand = android.graphics.Path(); private val pSoft = android.graphics.Path()
    private val pRed = android.graphics.Path(); private val pBlue = android.graphics.Path(); private val pHair = android.graphics.Path()
    private var bw = 0f; private var cw = 0f; private var hw = 0f

    fun layout(outline: androidx.compose.ui.graphics.Outline, size: Size, density: Float) {
        val rr = when (outline) {
            is androidx.compose.ui.graphics.Outline.Rounded -> outline.roundRect
            else -> androidx.compose.ui.geometry.RoundRect(0f, 0f, size.width, size.height, 0f, 0f)
        }
        val short = minOf(size.width, size.height)
        bw = (short * .15f).coerceIn(4.5f * density, 11f * density)
        cw = 1.5f * density; hw = 1f * density
        val d = .9f * density
        fun path(p: android.graphics.Path, inset: Float, dx: Float, dy: Float) {
            fun r(v: Float) = (v - inset).coerceAtLeast(0f)
            p.rewind()
            p.addRoundRect(
                android.graphics.RectF(inset + dx, inset + dy, size.width - inset + dx, size.height - inset + dy),
                floatArrayOf(
                    r(rr.topLeftCornerRadius.x), r(rr.topLeftCornerRadius.y), r(rr.topRightCornerRadius.x), r(rr.topRightCornerRadius.y),
                    r(rr.bottomRightCornerRadius.x), r(rr.bottomRightCornerRadius.y), r(rr.bottomLeftCornerRadius.x), r(rr.bottomLeftCornerRadius.y),
                ),
                android.graphics.Path.Direction.CW,
            )
        }
        path(pBand, bw / 2f, 0f, 0f)
        path(pSoft, bw * 1.3f, 0f, 0f)
        path(pRed, cw / 2f, -d, -d)
        path(pBlue, cw / 2f, d, d)
        path(pHair, hw / 2f, 0f, 0f)
    }

    fun draw(c: android.graphics.Canvas, origin: Offset, size: Size, sx: Float, sy: Float, refract: Boolean) {
        if (bw <= 0f) return
        gm.setScale(size.width, size.height)
        if (refract && band != null) {
            // 邊緣那一圈取樣的位置往中心偏移 shift（越厚的邊偏越多）：等於把背景以玻璃中心為準放大一點再貼上去
            val shift = bw * 2.6f
            val kx = 1f / (1f - (shift / (size.width / 2f)).coerceAtMost(.45f))
            val ky = 1f / (1f - (shift / (size.height / 2f)).coerceAtMost(.45f))
            m.setScale(1f / sx, 1f / sy)
            m.postTranslate(-origin.x, -origin.y)
            m.postScale(kx, ky, size.width / 2f, size.height / 2f)
            band.shader.setLocalMatrix(m)
            band.strokeWidth = bw * .6f; band.alpha = 120
            c.drawPath(pSoft, band)
            band.strokeWidth = bw; band.alpha = 255
            c.drawPath(pBand, band)
            glow.setLocalMatrix(gm); stroke.shader = glow; stroke.strokeWidth = bw
            c.drawPath(pBand, stroke)
        }
        red.setLocalMatrix(gm); stroke.shader = red; stroke.strokeWidth = cw
        c.drawPath(pRed, stroke)
        blue.setLocalMatrix(gm); stroke.shader = blue
        c.drawPath(pBlue, stroke)
        white.setLocalMatrix(gm); stroke.shader = white; stroke.strokeWidth = hw
        c.drawPath(pHair, stroke)
    }
}

/**
 * 玻璃的單次填色著色器：背景小圖（BitmapShader）SCREEN 一條白色漸層（頂端 [top] → 0.6 高度處 [mid]）。
 * 只有位置、大小、亮度改變時重建（例如面板滑入的那幾幀）。
 */
private class OneShotGlass {
    private var key = FloatArray(6) { Float.NaN }
    private var bitmapShader: android.graphics.Shader? = null

    fun prepare(
        paint: android.graphics.Paint, matrix: android.graphics.Matrix,
        origin: Offset, size: Size, sx: Float, sy: Float, top: Float, mid: Float,
    ) {
        if (bitmapShader == null) bitmapShader = paint.shader
        val bmp = bitmapShader ?: return
        val k = floatArrayOf(origin.x, origin.y, size.width, size.height, top, mid)
        if (k.contentEquals(key)) return
        key = k
        matrix.setScale(1f / sx, 1f / sy)
        matrix.postTranslate(-origin.x, -origin.y)
        bmp.setLocalMatrix(matrix)
        fun white(a: Float) = android.graphics.Color.argb((a.coerceIn(0f, 1f) * 255f + 0.5f).toInt(), 255, 255, 255)
        val glow = android.graphics.LinearGradient(
            0f, 0f, 0f, size.height * 0.6f, white(top), white(mid), android.graphics.Shader.TileMode.CLAMP,
        )
        // shaderA＝dst（背景）、shaderB＝src（白光）；子著色器的矩陣先設好再組合，原生物件一次建好
        paint.shader = android.graphics.ComposeShader(bmp, glow, android.graphics.PorterDuff.Mode.SCREEN)
    }
}

/**
 * 邊緣透鏡折射（Android 13+）。靠近玻璃邊緣的像素往內取樣，背景看起來被曲面玻璃彎折；
 * 紅藍通道再錯開一點點，做出細微色散。中心區域維持原樣。
 */
@RequiresApi(33)
private class LensShader(private val shader: RuntimeShader) {

    private val brush = ShaderBrush(shader)

    fun brush(origin: Offset, w: Float, h: Float, radius: Float, sx: Float, sy: Float): Brush {
        shader.setFloatUniform("origin", origin.x, origin.y)
        shader.setFloatUniform("size", w, h)
        shader.setFloatUniform("radius", radius.coerceAtMost(minOf(w, h) / 2f))
        shader.setFloatUniform("imgScale", sx, sy)
        shader.setFloatUniform("band", minOf(26f, minOf(w, h) * 0.25f))
        return brush
    }

    companion object {
        private const val SRC = """
            uniform shader wall;
            uniform float2 origin;
            uniform float2 size;
            uniform float radius;
            uniform float2 imgScale;
            uniform float band;

            float sdRound(float2 p, float2 b, float r) {
                float2 q = abs(p) - b + r;
                return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
            }

            half4 main(float2 coord) {
                float2 hs = size * 0.5;
                float2 p = coord - hs;
                float d = sdRound(p, hs, radius);
                // 0＝離邊緣超過 band，1＝貼著邊緣
                float edge = clamp(1.0 + d / band, 0.0, 1.0);
                edge = edge * edge;
                float2 e = float2(1.0, 0.0);
                float2 n = float2(
                    sdRound(p + e.xy, hs, radius) - sdRound(p - e.xy, hs, radius),
                    sdRound(p + e.yx, hs, radius) - sdRound(p - e.yx, hs, radius));
                n = n / max(length(n), 0.0001);
                float2 shift = -n * edge * band * 0.7;
                float2 s = origin + coord + shift;
                half4 c = wall.eval(s * imgScale);
                half r = wall.eval((s + shift * 0.12) * imgScale).r;
                half b = wall.eval((s - shift * 0.12) * imgScale).b;
                return half4(r, c.g, b, 1.0);
            }
        """

        fun create(image: ImageBitmap): LensShader? = runCatching {
            val s = RuntimeShader(SRC)
            s.setInputShader(
                "wall",
                BitmapShader(image.asAndroidBitmap(), android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP),
            )
            LensShader(s)
        }.getOrNull()
    }
}
