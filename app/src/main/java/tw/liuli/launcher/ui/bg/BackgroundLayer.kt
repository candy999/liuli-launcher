package tw.liuli.launcher.ui.bg

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt

/**
 * 全螢幕背景，只在引擎推進時重畫。
 *
 * 效能（AOC 電視實測：1080p 每幀 GPU 40ms 以上）：
 * - 背景先畫在 [RENDER_SCALE]（1280×720）的離屏圖層，再放大到螢幕，GPU 填的像素只剩 44%
 * - 不使用 Softlight 等進階混合模式（電視 GPU 上非常貴）
 * [blurred]（0～1）＝子頁面開啟時，用給玻璃的小張背景放大蓋上去，就是不吃效能的「模糊桌布」。
 */
@Composable
fun BackgroundLayer(engine: BackgroundEngine, blurred: () -> Float, modifier: Modifier = Modifier) {
    LaunchedEffect(engine) { engine.run() }
    BoxWithConstraints(modifier.fillMaxSize(), contentAlignment = Alignment.TopStart) {
        Box(
            Modifier
                .size(maxWidth * RENDER_SCALE, maxHeight * RENDER_SCALE)
                .graphicsLayer {
                    compositingStrategy = CompositingStrategy.Offscreen
                    transformOrigin = TransformOrigin(0f, 0f)
                    scaleX = 1f / RENDER_SCALE
                    scaleY = 1f / RENDER_SCALE
                },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                @Suppress("UNUSED_VARIABLE") val f = engine.frame // 訂閱：引擎每推進一幀就重畫
                scale(size.width / BackgroundEngine.LOGICAL_W, size.height / BackgroundEngine.LOGICAL_H, pivot = Offset.Zero) {
                    drawStyle(engine.style, engine.st)
                }
                val b = blurred()
                if (b > 0.001f) {
                    drawImage(engine.glass, dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()), alpha = b)
                    drawRect(Color.Black, alpha = 0.32f * b)
                }
            }
        }
    }
}

/** 背景實際繪製解析度相對螢幕的比例（1080p → 1280×720）。 */
private const val RENDER_SCALE = 0.6667f
