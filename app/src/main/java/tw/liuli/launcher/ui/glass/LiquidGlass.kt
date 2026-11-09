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

    this
        .onGloballyPositioned { origin = it.positionInRoot() }
        .drawWithCache {
            val outline = shape.createOutline(size, layoutDirection, this)
            val path = Path().apply { addOutline(outline) }
            val androidPath = path.asAndroidPath()
            val blurred = backdrop.image
            val radiusPx = (shape as? RoundedCornerShape)?.topStart?.toPx(size, this) ?: 0f

            onDrawBehind {
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
                        drawIntoCanvas { it.nativeCanvas.drawPath(androidPath, wall) }
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
