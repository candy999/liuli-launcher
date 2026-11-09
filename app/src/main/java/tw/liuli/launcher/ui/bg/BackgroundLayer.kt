package tw.liuli.launcher.ui.bg

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.layout.layout
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntSize

/**
 * 全螢幕背景，只在引擎推進時重畫。
 *
 * 效能（AOC 電視實測：1080p 每幀 GPU 40ms 以上；海信 Mali-470 更慢）：
 * - 轉場中背景畫在「原生 ÷ d」的離屏圖層再整數倍放大，d 由 [BackgroundEngine] 依每幀實際耗時自動調整，停下來逐檔升回原生解析度
 * - 不使用 Softlight 等進階混合模式（電視 GPU 上非常貴）
 * - 背景模糊開啟時：只畫引擎在背景執行緒算好的小圖（雙線性放大），GPU 幾乎不花力氣
 * [blurred]（0～1）＝子頁面開啟時，用給玻璃的小張背景放大蓋上去，就是不吃效能的「模糊桌布」。
 */
@Composable
fun BackgroundLayer(engine: BackgroundEngine, blurred: () -> Float, modifier: Modifier = Modifier) {
    LaunchedEffect(engine) { engine.run() }
    LaunchedEffect(engine) { engine.runGlass() }
    LaunchedEffect(engine) { engine.runBlur() }
    BoxWithConstraints(modifier.fillMaxSize().clipToBounds(), contentAlignment = Alignment.TopStart) {
        val fullW = constraints.maxWidth
        val fullH = constraints.maxHeight
        SideEffect { engine.setViewport(fullW, fullH) }

        if (engine.blurLevel > 0) {
            Canvas(Modifier.fillMaxSize()) {
                @Suppress("UNUSED_VARIABLE") val v = engine.blurVersion // 訂閱：模糊小圖每更新一次就重畫
                val img = engine.blurImage
                if (img != null) {
                    drawImage(img, dstSize = IntSize(fullW, fullH), filterQuality = FilterQuality.Low)
                } else {
                    // 第一張模糊圖還沒算好：先用玻璃的小圖（同樣是模糊過的背景）頂著
                    @Suppress("UNUSED_VARIABLE") val gv = engine.glassVersion
                    drawImage(engine.glass, dstSize = IntSize(fullW, fullH), filterQuality = FilterQuality.Low)
                }
                pageBlur(engine, blurred(), IntSize(fullW, fullH))
            }
            return@BoxWithConstraints
        }

        // 解析度＝原生 ÷ d（d 由引擎依實測幀時間決定）。不能整除時無條件進位一個像素，
        // 放大 d 倍後多出來的邊由 clipToBounds 裁掉——每個低解析度像素都剛好對齊 d×d 個實體像素。
        val d = engine.renderDiv
        val w = (fullW + d - 1) / d
        val h = (fullH + d - 1) / d
        Box(
            Modifier
                .layout { measurable, _ ->
                    val p = measurable.measure(Constraints.fixed(w, h))
                    layout(w, h) { p.place(0, 0) }
                }
                .graphicsLayer {
                    compositingStrategy = CompositingStrategy.Offscreen
                    transformOrigin = TransformOrigin(0f, 0f)
                    scaleX = d.toFloat()
                    scaleY = d.toFloat()
                },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                @Suppress("UNUSED_VARIABLE") val f = engine.frame // 訂閱：引擎每推進一幀就重畫
                // 以「原生 ÷ d」的精確比例縮放（不是進位後的 w×h），進位多出的一點點只是延伸畫面、會被裁掉
                val sx = fullW / d.toFloat() / BackgroundEngine.LOGICAL_W
                val sy = fullH / d.toFloat() / BackgroundEngine.LOGICAL_H
                scale(sx, sy, pivot = Offset.Zero) {
                    drawStyle(engine.style, engine.st)
                }
                pageBlur(engine, blurred(), IntSize(w, h))
            }
        }
    }
}

/** 子頁面開啟時的模糊桌布：玻璃小圖放大蓋上去再壓暗。 */
private fun DrawScope.pageBlur(engine: BackgroundEngine, b: Float, size: IntSize) {
    if (b <= 0.001f) return
    @Suppress("UNUSED_VARIABLE") val gv = engine.glassVersion // 模糊桌布跟著玻璃圖更新
    drawImage(engine.glass, dstSize = size, alpha = b)
    drawRect(Color.Black, alpha = 0.4f * b)
}
