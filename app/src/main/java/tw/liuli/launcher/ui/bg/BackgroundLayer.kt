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
 * - 轉場中背景畫在較小的離屏圖層再放大，檔位由 [BackgroundEngine] 依幀時間自動調整，停下來逐檔升回原生解析度
 * - 不使用 Softlight 等進階混合模式（電視 GPU 上非常貴）
 * [blurred]（0～1）＝子頁面開啟時，用給玻璃的小張背景放大蓋上去，就是不吃效能的「模糊桌布」。
 */
@Composable
fun BackgroundLayer(engine: BackgroundEngine, blurred: () -> Float, modifier: Modifier = Modifier) {
    LaunchedEffect(engine) { engine.run() }
    LaunchedEffect(engine) { engine.runGlass() }
    BoxWithConstraints(modifier.fillMaxSize(), contentAlignment = Alignment.TopStart) {
        // 解析度由引擎依實測幀時間決定（1/8 一檔）；靜止時逐檔升回原生解析度
        val renderScale = engine.renderLevel / BackgroundEngine.FULL_LEVEL.toFloat()
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
                    @Suppress("UNUSED_VARIABLE") val gv = engine.glassVersion // 模糊桌布跟著玻璃圖更新
                    drawImage(engine.glass, dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()), alpha = b)
                    drawRect(Color.Black, alpha = 0.4f * b)
                }
            }
        }
    }
}
