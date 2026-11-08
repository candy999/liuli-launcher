package tw.liuli.launcher.ui.bg

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * 全螢幕背景。全解析度向量繪製（邏輯 480×270 → 實際像素），只在引擎推進時重畫。
 * [blurred]（0～1）＝子頁面開啟時，用給玻璃的小張背景放大蓋上去，就是不吃效能的「模糊桌布」。
 */
@Composable
fun BackgroundLayer(engine: BackgroundEngine, blurred: () -> Float, modifier: Modifier = Modifier) {
    LaunchedEffect(engine) { engine.run() }
    val grain = remember { ShaderBrush(ImageShader(makeGrain(), TileMode.Repeated, TileMode.Repeated)) }
    Canvas(modifier.fillMaxSize()) {
        @Suppress("UNUSED_VARIABLE") val f = engine.frame // 訂閱：引擎每推進一幀就重畫
        scale(size.width / BackgroundEngine.LOGICAL_W, size.height / BackgroundEngine.LOGICAL_H, pivot = Offset.Zero) {
            drawStyle(engine.style, engine.st)
        }
        // 極細抖動雜訊：避免深色漸層在電視上出現色帶
        drawRect(grain, alpha = 0.05f, blendMode = BlendMode.Softlight)
        val b = blurred()
        if (b > 0.001f) {
            drawImage(engine.glass, dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()), alpha = b)
            drawRect(Color.Black, alpha = 0.32f * b)
        }
    }
}

private fun makeGrain(): ImageBitmap {
    val n = 256
    val rnd = Random(3)
    val pixels = IntArray(n * n) {
        val v = rnd.nextInt(256)
        (0xFF shl 24) or (v shl 16) or (v shl 8) or v
    }
    return android.graphics.Bitmap.createBitmap(pixels, n, n, android.graphics.Bitmap.Config.ARGB_8888).asImageBitmap()
}
