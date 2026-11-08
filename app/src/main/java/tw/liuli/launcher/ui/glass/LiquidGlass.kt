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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * Liquid Glass 材質。
 *
 * 成本：玻璃後面只有靜態桌布，所以不做即時模糊，而是從「預先模糊好的桌布」取出
 * 自己背後那一塊（等於貼一張圖）。Android 13 以上再用 AGSL 著色器做邊緣的透鏡折射。
 * 只有在面板移動（捲動、動畫）時才重畫，畫面靜止時零成本。
 *
 * 疊層（由下而上）：模糊背景（含折射）→ 白色薄霧 → 上緣光澤 → 邊緣內光 → 高光邊。
 */
fun Modifier.liquidGlass(
    shape: Shape = RoundedCornerShape(26.dp),
    tint: Float = 0.07f,
    /** 0～1：讓玻璃更亮（例如焦點時）。在繪製階段讀取，不會觸發重新組合。 */
    highlight: () -> Float = { 0f },
): Modifier = composed {
    val backdrop = LocalGlassBackdrop.current
    var origin by remember { mutableStateOf(Offset.Zero) }
    val lens = remember(backdrop.images) {
        val images = backdrop.images
        if (backdrop.lensing && images != null && Build.VERSION.SDK_INT >= 33) LensShader.create(images) else null
    }

    this
        .onGloballyPositioned { origin = it.positionInRoot() }
        .drawWithCache {
            val outline = shape.createOutline(size, layoutDirection, this)
            val path = Path().apply { addOutline(outline) }
            val images = backdrop.images
            val blurred = images?.blurred?.asImageBitmap()
            val radiusPx = (shape as? RoundedCornerShape)?.topStart?.toPx(size, this) ?: 0f
            val rimWidth = 1.dp.toPx()
            val glowWidth = 14.dp.toPx()

            onDrawBehind {
                val hl = highlight()
                clipPath(path) {
                    // 1. 背景：預先模糊的桌布對應區域（含折射）
                    if (blurred != null && backdrop.screen.width > 0f) {
                        val sx = blurred.width / backdrop.screen.width
                        val sy = blurred.height / backdrop.screen.height
                        if (lens != null && Build.VERSION.SDK_INT >= 33) {
                            drawRect(lens.brush(origin, size.width, size.height, radiusPx, sx, sy))
                        } else {
                            drawImage(
                                blurred,
                                srcOffset = IntOffset((origin.x * sx).roundToInt(), (origin.y * sy).roundToInt()),
                                srcSize = IntSize(
                                    (size.width * sx).roundToInt().coerceAtLeast(1),
                                    (size.height * sy).roundToInt().coerceAtLeast(1),
                                ),
                                dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                            )
                        }
                    } else {
                        drawRect(Color(0x99141A2E))
                    }
                    // 2. 白色薄霧
                    drawRect(Color.White.copy(alpha = tint + 0.06f * hl))
                    // 3. 上緣光澤
                    drawRect(
                        Brush.verticalGradient(
                            0f to Color.White.copy(alpha = 0.10f + 0.08f * hl),
                            0.4f to Color.Transparent,
                        ),
                    )
                    // 4. 邊緣內光：模擬玻璃厚度把光往邊緣聚集
                    drawPath(path, Color.White.copy(alpha = 0.05f), style = Stroke(glowWidth))
                    drawPath(path, Color.White.copy(alpha = 0.05f), style = Stroke(glowWidth * 0.45f))
                }
                // 5. 高光邊：上緣亮、側邊暗、下緣有一點反光
                drawPath(
                    path,
                    Brush.verticalGradient(
                        0f to Color.White.copy(alpha = 0.55f + 0.30f * hl),
                        0.18f to Color.White.copy(alpha = 0.16f),
                        0.82f to Color.White.copy(alpha = 0.08f),
                        1f to Color.White.copy(alpha = 0.24f + 0.10f * hl),
                    ),
                    style = Stroke(rimWidth * 1.6f),
                )
            }
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

        fun create(images: WallpaperImages): LensShader? = runCatching {
            val s = RuntimeShader(SRC)
            s.setInputShader(
                "wall",
                BitmapShader(images.blurred, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP),
            )
            LensShader(s)
        }.getOrNull()
    }
}
