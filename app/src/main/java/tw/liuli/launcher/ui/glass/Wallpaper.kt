package tw.liuli.launcher.ui.glass

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private data class Blob(
    val color: Color,
    val cx: Float, val cy: Float,   // 中心（畫面比例）
    val radius: Float,              // 半徑（寬度比例）
    val ax: Float, val ay: Float,   // 漂移幅度
    val seed: Float,
)

private val blobs = listOf(
    Blob(Color(0xFF2E5BFF), 0.22f, 0.28f, 0.55f, 0.10f, 0.08f, 0.0f),
    Blob(Color(0xFFB04BFF), 0.78f, 0.22f, 0.45f, 0.08f, 0.10f, 1.7f),
    Blob(Color(0xFF00C2B8), 0.62f, 0.82f, 0.50f, 0.10f, 0.07f, 3.1f),
    Blob(Color(0xFFFF7A59), 0.12f, 0.88f, 0.34f, 0.07f, 0.09f, 4.4f),
)

/** 一圈 60 秒；只有 HIGH 等級才動。 */
@Composable
fun rememberWallpaperPhase(animate: Boolean): State<Float> {
    if (!animate) return remember { mutableFloatStateOf(0.15f) }
    val transition = rememberInfiniteTransition(label = "wallpaper")
    return transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(60_000, easing = LinearEasing)),
        label = "phase",
    )
}

/**
 * 以 [canvas]（整個畫面）為座標系畫出流動的色塊桌布。
 * 玻璃面板會在平移後的座標系呼叫它，取得自己背後那一塊。
 */
fun DrawScope.drawLiquidWallpaper(phase: Float, canvas: Size) {
    if (canvas.width <= 0f || canvas.height <= 0f) return
    drawRect(
        Brush.linearGradient(
            listOf(Color(0xFF060A18), Color(0xFF0F1830), Color(0xFF0A0F22)),
            start = Offset.Zero,
            end = Offset(canvas.width, canvas.height),
        ),
        size = canvas,
    )
    val angle = phase * 2f * PI.toFloat()
    for (b in blobs) {
        // 倍頻皆為整數，phase 從 1 回到 0 時不會跳動
        val center = Offset(
            (b.cx + b.ax * sin(angle + b.seed)) * canvas.width,
            (b.cy + b.ay * cos(2f * angle + b.seed)) * canvas.height,
        )
        val r = b.radius * canvas.width
        drawCircle(
            Brush.radialGradient(
                listOf(b.color.copy(alpha = 0.55f), b.color.copy(alpha = 0.18f), Color.Transparent),
                center = center,
                radius = r,
            ),
            radius = r,
            center = center,
        )
    }
}
