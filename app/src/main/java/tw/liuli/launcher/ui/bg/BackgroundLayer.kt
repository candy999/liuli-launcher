package tw.liuli.launcher.ui.bg

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
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
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.draw.drawWithCache
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

        // 冷啟動：全黑（不畫背景）→ 第一個焦點出現、而且這個樣式的資源（山、星軌、銀河貼圖…）都準備好之後，才從焦點往外擴散。
        // 不然會先看到一片天空，山才突然冒出來。等太久（載入失敗）就不等了。
        val from = engine.revealFrom
        val style = engine.style
        var assetsReady by remember { mutableStateOf(false) }
        LaunchedEffect(style) {
            var t0 = android.os.SystemClock.uptimeMillis()
            var was = false
            while (true) {
                val r = style.prepareAssets()
                val now = android.os.SystemClock.uptimeMillis()
                if (!r && was) t0 = now   // 準備好之後又被放掉（離開琉璃時釋放記憶體）：重新計時
                was = r
                assetsReady = r || now - t0 > ASSET_WAIT_MS
                kotlinx.coroutines.delay(if (r) 300L else 40L)
            }
        }
        val reveal = remember { Animatable(0f) }
        LaunchedEffect(from, assetsReady) {
            if (from != null && assetsReady && reveal.value < 1f) reveal.animateTo(1f, tween(REVEAL_MS, easing = FastOutSlowInEasing))
        }
        // 已經顯示過、資源卻被放掉（離開琉璃回來）：先蓋黑，載入好再淡出，不讓山突然跳出來
        val cover by androidx.compose.animation.core.animateFloatAsState(if (assetsReady) 0f else 1f, tween(if (assetsReady) 450 else 0), label = "assetCover")
        if (from == null || (!assetsReady && reveal.value <= 0f) || PerfProbe.noBg) {
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
        // 山稜：山與霧另外以原生解析度畫（硬體點陣圖，GPU 直接取樣），低解析度的小圖只畫天空與星軌
        val ridgeNative = engine.style == BgStyle.RIDGE && YushanRidge.nativeReady
        val d = engine.renderDiv
        val w = (fullW + d - 1) / d
        // 山稜：小圖只畫到天空最低點（再往下一定被原生解析度的山蓋住），合成時少貼一大塊
        val h = if (ridgeNative) ((YushanRidge.skyBottom / BackgroundEngine.LOGICAL_H * fullH).toInt() + d - 1) / d
            else (fullH + d - 1) / d
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
                    drawStyle(
                        engine.style, engine.st, noisePx = DitherScope.NOISE_PX / d, noiseShift = engine.st.noiseParallax(parallaxPx) / d.toFloat(),
                        skipFront = ridgeNative,
                    )
                }
                // 設定面板開著時壓暗：畫在 ÷d 的小圖裡，成本只有全螢幕的 1／d²
                if (engine.dim > 0f) drawRect(Color.Black, alpha = engine.dim)
            }
        }
        if (ridgeNative) {
            val sx = fullW / BackgroundEngine.LOGICAL_W
            val sy = fullH / BackgroundEngine.LOGICAL_H
            // 星軌：原生解析度，每格旋轉（一張貼圖）
            Canvas(Modifier.fillMaxSize()) {
                @Suppress("UNUSED_VARIABLE") val f = engine.frame
                scale(sx, sy, pivot = Offset.Zero) { ridgeTrails(engine.st, engine.dim) }
            }
            // 山：動態解析度＋快取（焦點移動時降解析度、停下來畫一次原生解析度，之後每格只貼這張圖）
            RidgeMountains(engine, fullW, fullH) { 1f }
            // 霧：低解析度（÷3）的離屏圖層，每格流動；照前後順序畫，被前面的山擋住的部分用那層山的剪影擦掉
            val fd = maxOf(3, d)
            val fw = (fullW + fd - 1) / fd
            val fh = (fullH + fd - 1) / fd
            Box(
                Modifier
                    .layout { measurable, _ ->
                        val p = measurable.measure(Constraints.fixed(fw, fh))
                        layout(fw, fh) { p.place(0, 0) }
                    }
                    .graphicsLayer {
                        compositingStrategy = CompositingStrategy.Offscreen
                        transformOrigin = TransformOrigin(0f, 0f)
                        scaleX = fd.toFloat()
                        scaleY = fd.toFloat()
                    },
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    @Suppress("UNUSED_VARIABLE") val f = engine.frame
                    scale(fullW / fd.toFloat() / BackgroundEngine.LOGICAL_W, fullH / fd.toFloat() / BackgroundEngine.LOGICAL_H, pivot = Offset.Zero) {
                        ridgeFog(engine.st, engine.dim)
                    }
                }
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
        // 冷啟動的擴散：在所有背景圖層（低解析度小圖、原生解析度的山／星軌／霧、銀河星點）之上蓋一張全螢幕的黑罩，
        // 從焦點開一個柔邊的圓洞往外擴大——每一層一起揭開，中心一定在焦點上，也不會在圖層交界露出橫線
        if (reveal.value < 1f) Canvas(Modifier.fillMaxSize()) { revealMask(from, reveal.value) }
        if (cover > 0.001f) Canvas(Modifier.fillMaxSize()) { drawRect(Color.Black, alpha = cover) }
    }
}

/** 等樣式資源最久多久（毫秒）：載入失敗時不要永遠黑著。 */
private const val ASSET_WAIT_MS = 5000L

/**
 * 這個樣式要用的資源準備好了沒；沒有的話順便請它開始載入（在背景執行緒）。
 * 玉山星軌：七層山（含原生解析度的版本）與星軌貼圖；星空銀河：銀河貼圖；其他樣式即時畫，不用等。
 */
internal fun BgStyle.prepareAssets(): Boolean = when (this) {
    BgStyle.RIDGE -> {
        YushanRidge.request(); YushanRidge.requestTrails()
        YushanRidge.nativeReady && YushanRidge.trails != null && YushanRidge.trailsHi != null
    }
    BgStyle.GALAXY -> { MilkyWay.request(); MilkyWay.image != null }
    else -> true
}

private const val REVEAL_MS = 1400

/**
 * 玉山星軌的山（動態解析度＋快取）：
 * - 快取裡只有通道圖（受光／遠近／覆蓋），顏色（時段、App 色系、壓暗）在合成時用顏色矩陣套上——顏色怎麼變都不用重畫
 * - 只有山的視差位置變了才重畫：焦點移動中用引擎目前的解析度（至少 ÷3），停下來 0.35 秒以原生解析度重畫一次，之後每格只貼快取
 * - 簡潔模式的彈跳中山的視差固定（RidgePar），快取不動、一直是原生解析度
 */
@Composable
private fun RidgeMountains(engine: BackgroundEngine, fullW: Int, fullH: Int, alpha: () -> Float) {
    var key by remember { mutableIntStateOf(0) }
    var div by remember { mutableIntStateOf(2) }
    LaunchedEffect(engine) {
        var lx = Float.NaN; var ly = Float.NaN
        var stillSince = 0L
        while (true) {
            withFrameNanos { now ->
                RidgePar.update(engine.st, engine.bouncing)
                val moved = !(kotlin.math.abs(RidgePar.fx - lx) <= .0004f) || !(kotlin.math.abs(RidgePar.fy - ly) <= .0004f)
                if (moved) {
                    lx = RidgePar.fx; ly = RidgePar.fy
                    stillSince = now
                    div = maxOf(3, engine.renderDiv)   // 移動中看不出細節，至少 ÷3，GPU 留給焦點動畫
                    key++
                } else if (div != 1 && now - stillSince > 350_000_000L) {
                    div = 1; key++
                }
            }
        }
    }
    val d = div
    val w = (fullW + d - 1) / d
    val h = (fullH + d - 1) / d
    Box(
        Modifier
            .layout { measurable, _ ->
                val p = measurable.measure(Constraints.fixed(w, h))
                layout(w, h) { p.place(0, 0) }
            }
            .graphicsLayer {
                transformOrigin = TransformOrigin(0f, 0f)
                scaleX = d.toFloat()
                scaleY = d.toFloat()
                this.alpha = alpha()
            }
            .drawWithCache {
                // 快取的圖層：只在 key（視差、解析度）變了才重新記錄
                @Suppress("UNUSED_VARIABLE") val k = key
                val layer = obtainGraphicsLayer()
                // 離屏：內容畫成一張貼圖留著，之後只換顏色濾鏡、不重畫裡面的山
                layer.compositingStrategy = androidx.compose.ui.graphics.layer.CompositingStrategy.Offscreen
                layer.record {
                    val sx = fullW / d.toFloat() / BackgroundEngine.LOGICAL_W
                    val sy = fullH / d.toFloat() / BackgroundEngine.LOGICAL_H
                    scale(sx, sy, pivot = Offset.Zero) { ridgeMountains() }
                }
                onDrawBehind {
                    // 每格只換顏色矩陣、貼一次快取（時段、App 色系、壓暗都在這裡）
                    @Suppress("UNUSED_VARIABLE") val f = engine.frame
                    layer.colorFilter = ColorFilter.colorMatrix(ColorMatrix(ridgeTintMatrix(engine.st, engine.dim)))
                    drawLayer(layer)
                }
            },
    )
}

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
