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
 * - 轉場中背景畫在 960×540 的離屏圖層再放大（GPU 只填 1/4 像素），停下來改用全解析度畫一次
 * - 不使用 Softlight 等進階混合模式（電視 GPU 上非常貴）
 * [blurred]（0～1）＝子頁面開啟時，用給玻璃的小張背景放大蓋上去，就是不吃效能的「模糊桌布」。
 */
@Composable
fun BackgroundLayer(engine: BackgroundEngine, blurred: () -> Float, modifier: Modifier = Modifier) {
    LaunchedEffect(engine) { engine.run() }
    BoxWithConstraints(modifier.fillMaxSize(), contentAlignment = Alignment.TopStart) {
        // 轉場中用 768×432 畫（在動，看不出差別），停下來再用全解析度畫一次（靜止畫面清楚）
        val renderScale = if (engine.moving) MOVING_SCALE else 1f
        Box(
            Modifier
                .size(maxWidth * renderScale, maxHeight * renderScale)
                .graphicsLayer {
                    compositingStrategy = CompositingStrategy.Offscreen
                    transformOrigin = TransformOrigin(0f, 0f)
                    scaleX = 1f / renderScale
                    scaleY = 1f / renderScale
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
                    drawRect(Color.Black, alpha = 0.4f * b)
                }
            }
        }
    }
}

/** 轉場期間背景的繪製解析度比例（1080p → 768×432；PowerVR BXE 等入門 GPU 實測需要這麼低）。 */
private const val MOVING_SCALE = 0.4f
