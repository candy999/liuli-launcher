package tw.liuli.launcher.ui.bg

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.layout.layout
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Color
import androidx.compose.animation.core.FastOutSlowInEasing
import kotlin.math.hypot
import kotlin.math.floor
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
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
    BoxWithConstraints(modifier.fillMaxSize().coverClip(engine).clipToBounds(), contentAlignment = Alignment.TopStart) {
        val parallaxPx = NOISE_PARALLAX_DP * LocalDensity.current.density
        val fullW = constraints.maxWidth
        val fullH = constraints.maxHeight
        SideEffect { engine.setViewport(fullW, fullH) }

        // 冷啟動：全黑（不畫背景）→ 第一個焦點出現後，從焦點往外擴散
        val from = engine.revealFrom
        val reveal = remember { Animatable(0f) }
        LaunchedEffect(from) {
            if (from != null && reveal.value < 1f) reveal.animateTo(1f, tween(REVEAL_MS, easing = FastOutSlowInEasing))
        }
        if (from == null || PerfProbe.noBg) {
            Canvas(Modifier.fillMaxSize()) { drawRect(Color.Black) }
            return@BoxWithConstraints
        }

        if (engine.blurLevel > 0) {
            // 背景執行緒每送來一張新圖，就從上一張交叉淡化過去（趕不上 30fps 時看起來仍然連續）
            val version = engine.blurVersion
            val fade = remember { Animatable(1f) }
            LaunchedEffect(version) {
                val ms = engine.blurFadeMs
                if (ms > 0) { fade.snapTo(0f); fade.animateTo(1f, tween(ms, easing = LinearEasing)) } else fade.snapTo(1f)
            }
            Canvas(Modifier.fillMaxSize()) {
                @Suppress("UNUSED_VARIABLE") val v = engine.blurVersion // 訂閱：模糊小圖每更新一次就重畫
                val img = engine.blurImage
                val prev = engine.blurPrev
                val a = fade.value
                val full = IntSize(fullW, fullH)
                if (img != null) {
                    if (prev != null && a < 0.999f) {
                        drawImage(prev, dstSize = full, filterQuality = FilterQuality.Low)
                        drawImage(img, dstSize = full, alpha = a, filterQuality = FilterQuality.Low)
                    } else {
                        drawImage(img, dstSize = full, filterQuality = FilterQuality.Low)
                    }
                } else {
                    // 第一張模糊圖還沒算好：先用玻璃的小圖（同樣是模糊過的背景）頂著
                    @Suppress("UNUSED_VARIABLE") val gv = engine.glassVersion
                    drawImage(engine.glass, dstSize = IntSize(fullW, fullH), filterQuality = FilterQuality.Low)
                }
                pageBlur(engine, blurred(), IntSize(fullW, fullH))
                if (engine.dim > 0f) drawRect(Color.Black, alpha = engine.dim)
                revealMask(from, reveal.value)
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
                    // 抖色噪點：實體螢幕上固定 2 像素一格（畫布是 ÷d，所以一格＝2／d 個畫布像素），跟著焦點平滑視差
                    drawStyle(engine.style, engine.st, noisePx = DitherScope.NOISE_PX / d, noiseShift = engine.st.noiseParallax(parallaxPx) / d.toFloat())
                }
                // 設定面板開著時壓暗：畫在 ÷d 的小圖裡，成本只有全螢幕的 1／d²
                if (engine.dim > 0f) drawRect(Color.Black, alpha = engine.dim)
                revealMask(from, reveal.value)
            }
        }
        // 星空銀河的星點：以原生解析度另外畫在背景之上。不會動的星點畫成快取圖層、每格只移動位置（視差）；
        // 只有十幾顆會閃爍的亮星每格重畫
        if (engine.style == BgStyle.GALAXY) {
            val sx = fullW / BackgroundEngine.LOGICAL_W
            val sy = fullH / BackgroundEngine.LOGICAL_H
            Canvas(
                Modifier.fillMaxSize().graphicsLayer {
                    @Suppress("UNUSED_VARIABLE") val f = engine.frame
                    compositingStrategy = CompositingStrategy.Offscreen
                    translationX = -(engine.st.fx - .5f) * fullW * .02f
                },
            ) { scale(sx, sy, pivot = Offset.Zero) { galaxyCloudStars() } }
            Canvas(
                Modifier.fillMaxSize().graphicsLayer {
                    @Suppress("UNUSED_VARIABLE") val f = engine.frame
                    compositingStrategy = CompositingStrategy.Offscreen
                    translationX = -(engine.st.fx - .5f) * fullW * .035f
                    translationY = -(engine.st.fy - .5f) * fullH * .015f
                },
            ) { scale(sx, sy, pivot = Offset.Zero) { galaxyFieldStars() } }
            Canvas(Modifier.fillMaxSize()) {
                @Suppress("UNUSED_VARIABLE") val f = engine.frame
                scale(sx, sy, pivot = Offset.Zero) { galaxyBrightStars(engine.st) }
            }
        }
        // 子頁面的模糊桌布畫在背景圖層「外面」：淡入淡出時每格只多貼一張小圖，背景本身維持快取不用重畫
        // （以前畫在裡面：背景靜止時已升到原生解析度，過場的每一格都要把整個背景重畫一次）
        Canvas(Modifier.fillMaxSize()) { pageBlur(engine, blurred(), IntSize(fullW, fullH)) }
    }
}

private const val REVEAL_MS = 1400

/**
 * 不透明面板（App 抽屜）蓋住的那一塊背景不畫：分成上方、左邊、右邊三個矩形各畫一次背景（矩形裁切由 GPU 直接限制範圍，
 * 被蓋住的像素完全不算）。抽屜滑動中範圍每格改變，只有這一層重新記錄，背景本身的圖層不用重畫。
 */
private fun Modifier.coverClip(engine: BackgroundEngine): Modifier = drawWithContent {
    val c = engine.cover
    val vis = if (c == null) 0f else floor((1f - c.slide()) * c.height - c.corner)
    if (c == null || vis < 2f) { drawContent(); return@drawWithContent }
    val top = size.height - vis
    clipRect(0f, 0f, size.width, top) { this@drawWithContent.drawContent() }
    clipRect(0f, top, c.inset, size.height) { this@drawWithContent.drawContent() }
    clipRect(size.width - c.inset, top, size.width, size.height) { this@drawWithContent.drawContent() }
}

/**
 * 蓋在背景上的不透明面板：從畫面底部往上 [height] 像素、左右各留 [inset] 像素、頂端圓角 [corner]（圓角處透出背景）。
 * [slide]：0＝完全打開、1＝完全收起（在繪製階段讀取）。
 */
class PanelCover(val height: Float, val inset: Float, val corner: Float, val slide: () -> Float)

/**
 * 冷啟動擴散：黑色蓋在背景上，中間挖一個從 [from]（畫面比例）往外長大的圓洞，邊緣柔和漸變。
 * [t]＝0～1；1 之後不畫。圓的最終半徑＝到最遠角落的距離＋柔邊，保證整個畫面都露出來。
 */
private fun DrawScope.revealMask(from: Offset, t: Float) {
    if (t >= 1f) return
    val c = Offset(from.x * size.width, from.y * size.height)
    val far = maxOf(
        hypot(c.x, c.y), hypot(size.width - c.x, c.y),
        hypot(c.x, size.height - c.y), hypot(size.width - c.x, size.height - c.y),
    )
    val feather = 0.45f // 柔邊佔半徑的比例
    val r = (far / (1f - feather) * t).coerceAtLeast(1f)
    drawRect(
        Brush.radialGradient(
            0f to Color.Transparent, (1f - feather) to Color.Transparent, 1f to Color.Black,
            center = c, radius = r,
        ),
    )
}

/** 子頁面開啟時的模糊桌布：玻璃小圖放大蓋上去再壓暗。 */
private fun DrawScope.pageBlur(engine: BackgroundEngine, b: Float, size: IntSize) {
    if (b <= 0.001f) return
    @Suppress("UNUSED_VARIABLE") val gv = engine.glassVersion // 模糊桌布跟著玻璃圖更新
    drawImage(engine.glass, dstSize = size, alpha = b)
    drawRect(Color.Black, alpha = 0.4f * b)
}
