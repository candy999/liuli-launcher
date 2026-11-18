package tw.liuli.launcher.ui.glass

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

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
 * 做法：每一片玻璃在背景執行緒「烘」成一張小圖（[GlassBake]），GPU 每格只是把這張圖填進圓角矩形——一次貼圖填色，
 * 沒有即時模糊、沒有著色器折射，Android 9 的 Mali-470 也畫得動。
 * 烘出來的內容（全部在玻璃「裡面」，外框是乾淨的圓角，不往外暈）：
 * - 中間：霧化的背景（引擎給的 120×68 小圖放大）＋很淡的白霧與由上往下的淡光。
 * - 邊緣照物理算，沒有任何「畫上去」的顏色：邊緣是一圈圓弧形的斜面（磨圓的玻璃邊，底部最寬），
 *   每個像素依斜面的斜率用 Snell 定律（折射率 1.5）算視線偏折了多少，到霧化的背景上那個位置取樣——安靜、不搶眼。
 *   不做色散（試過折射清楚的背景、分色，邊緣會一格一格地跳，不如沒有）。
 * - 邊緣的亮線是反射（Fresnel）：越斜的地方反射越多，反射的是左上方一盞柔和的白光，所以左上亮、右下暗；反射掉的光不再透過來。
 * 只有背景小圖更新、玻璃的位置或大小改變時才重烘（背景在動時每秒最多十幾次，在背景執行緒）；
 * 焦點動畫只改圖層屬性，不會重烘、也不會重畫。
 */
fun Modifier.liquidGlass(
    shape: Shape = RoundedCornerShape(26.dp),
    tint: Float = 0.03f,
    /** 0～1：讓玻璃更亮。在繪製階段讀取。 */
    highlight: () -> Float = { 0f },
    /**
     * 滑入動畫的位移（像素）：玻璃取樣背景時扣掉，以「停下來的位置」為準。
     * 這樣滑動中玻璃的內容不變，整塊面板可以畫成一張圖只移動位置。
     */
    slide: () -> Offset = { Offset.Zero },
    /** true＝動畫中，不更新取樣位置。 */
    frozen: () -> Boolean = { false },
    /** 邊緣的折射帶、色散與亮線。 */
    edge: Boolean = true,
    /** 大片面板的受光：光的位置（0～1 的比例）；null＝不加。在繪製階段讀取。 */
    light: (() -> Offset)? = null,
    /** 第一張烘好時通知（面板等它烘好才開始滑入）。 */
    gate: GlassGate? = null,
): Modifier = composed {
    val backdrop = LocalGlassBackdrop.current
    var origin by remember { mutableStateOf(Offset.Zero) }
    val bake = remember(backdrop.image) { GlassBake(backdrop, edge) }
    bake.gate = gate
    LaunchedEffect(bake) { bake.run() }
    val matrix = remember { android.graphics.Matrix() }
    val rough = remember(backdrop.image) {
        backdrop.image?.let { img ->
            android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG).apply {
                shader = android.graphics.BitmapShader(img.asAndroidBitmap(), android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP)
            }
        }
    }

    this
        .onGloballyPositioned {
            if (frozen() && origin != Offset.Zero) return@onGloballyPositioned
            // 取整數像素：滑動中位置的浮點誤差不會讓 origin 每格都「改變」而觸發重畫
            val p = it.positionInRoot() - slide()
            val o = Offset(p.x.roundToInt().toFloat(), p.y.roundToInt().toFloat())
            if (o != origin) origin = o
        }
        .drawWithCache {
            val outline = shape.createOutline(size, layoutDirection, this)
            val path = Path().apply { addOutline(outline) }
            val androidPath = path.asAndroidPath()
            val rr = when (outline) {
                is Outline.Rounded -> outline.roundRect
                else -> RoundRect(0f, 0f, size.width, size.height, 0f, 0f)
            }
            val radii = floatArrayOf(rr.topLeftCornerRadius.x, rr.topRightCornerRadius.x, rr.bottomRightCornerRadius.x, rr.bottomLeftCornerRadius.x)
            val dens = density

            onDrawBehind {
                if (tw.liuli.launcher.ui.bg.PerfProbe.noGlass) return@onDrawBehind
                tw.liuli.launcher.ui.bg.UiTrace.hit("glassDraw")
                val v = backdrop.version() // 背景換色時跟著重烘
                @Suppress("UNUSED_VARIABLE") val t = bake.tick // 烘好一張就重畫
                val hl = highlight()
                if (backdrop.image == null || backdrop.screen.width <= 0f) {
                    drawPath(path, Color(0x99141A2E))
                    return@onDrawBehind
                }
                val lp = light?.invoke()
                // 面板滑動中（整塊是一張離屏圖在移動）：已經有烘好的就不再重烘——一重烘，整張離屏圖就得重畫一次
                if (!(frozen() && bake.baked && gate?.baked != false)) bake.request(origin, size, radii, tint, hl, v, dens, lp?.x ?: -1f, lp?.y ?: 0f)
                val paint = bake.paint(matrix)
                if (paint != null) drawIntoCanvas {
                    val c = it.nativeCanvas
                    if (size.width * size.height > 500_000f) {   // 只有側邊欄、抽屜這種大面板；時鐘、天氣的玻璃會整片淡入淡出（逐筆乘透明度），重疊的地方會多疊一層
                        // 大片面板（側邊欄、抽屜）：中間絕大部分是一個不透明、不用反鋸齒的矩形（GPU 不必混合、不必算邊緣覆蓋率），
                        // 只有上下兩條含圓角的窄帶才用反鋸齒的路徑。海信上抽屜的玻璃是整個畫面最貴的一筆填色
                        val top = kotlin.math.ceil(max(radii[0], radii[1]))
                        val bot = kotlin.math.ceil(max(radii[2], radii[3]))
                        // 兩條窄帶各往中間多畫 3 像素、先畫；中間的矩形最後蓋上去。
                        // 這一片玻璃在布丁變形的圖層裡會被非整數地縮放、位移：不重疊的話，矩形的邊（不反鋸齒、貼齊像素）和窄帶的裁切邊
                        // 可能落在不同的像素列，中間露出一條一個像素的縫（閃一格的橫線）
                        val lap = 3f
                        if (top > 0f) { c.save(); c.clipRect(0f, 0f, size.width, top + lap); c.drawPath(androidPath, paint); c.restore() }
                        if (bot > 0f) { c.save(); c.clipRect(0f, size.height - bot - lap, size.width, size.height); c.drawPath(androidPath, paint); c.restore() }
                        paint.isAntiAlias = false
                        c.drawRect(0f, top, size.width, size.height - bot, paint)
                        paint.isAntiAlias = true
                    } else c.drawPath(androidPath, paint)
                }
                // 第一張還沒烘好（剛出現的那一兩格）：直接貼霧化的背景小圖＋同樣的白霧——和烘好的中間幾乎一樣，只差邊緣那一圈，
                // 不會先看到一塊深色的板子再跳成玻璃
                else if (rough != null) drawIntoCanvas {
                    val img = backdrop.image!!
                    matrix.setScale(backdrop.screen.width / img.width, backdrop.screen.height / img.height)
                    matrix.postTranslate(-origin.x, -origin.y)
                    rough.shader.setLocalMatrix(matrix)
                    it.nativeCanvas.drawPath(androidPath, rough)
                    drawPath(path, Color.White.copy(alpha = (tint + .05f).coerceIn(0f, 1f)))
                }
            }
        }
}

/** 一片玻璃「第一張烘好了沒」（可觀察）。面板滑入之前先等它。 */
class GlassGate {
    var baked by mutableStateOf(false)
}

/** 給所有玻璃共用的背景小圖像素（每個版本只讀一次）。 */
private object BackdropPixels {
    var px = IntArray(0); var w = 0; var h = 0
    private var version = Long.MIN_VALUE
    fun sync(image: ImageBitmap, v: Long) {
        if (v == version && px.isNotEmpty()) return
        val b = image.asAndroidBitmap()
        if (px.size != b.width * b.height) px = IntArray(b.width * b.height)
        w = b.width; h = b.height
        b.getPixels(px, 0, w, 0, 0, w, h)
        version = v
    }
}

/** 一片玻璃的烘焙：參數改變時在背景執行緒重算，算好寫回點陣圖。 */
private class GlassBake(private val backdrop: GlassBackdrop, private val edge: Boolean) {
    var tick by mutableIntStateOf(0)
        private set
    var gate: GlassGate? = null
    val baked: Boolean get() = bakedFor != null
    private val wake = Channel<Unit>(Channel.CONFLATED)

    private class Req(
        val ox: Float, val oy: Float, val w: Float, val h: Float, val radii: FloatArray,
        val tint: Float, val hl: Float, val version: Long, val density: Float, val lx: Float, val ly: Float,
    ) {
        fun same(o: Req?) = o != null && ox == o.ox && oy == o.oy && w == o.w && h == o.h && tint == o.tint && hl == o.hl &&
            version == o.version && lx == o.lx && ly == o.ly && radii.contentEquals(o.radii)
    }

    private var wanted: Req? = null
    private var bitmap: android.graphics.Bitmap? = null
    private var paint: android.graphics.Paint? = null
    private var q = 1
    private var bakedFor: Req? = null
    private var buf = IntArray(0)

    fun request(origin: Offset, size: Size, radii: FloatArray, tint: Float, hl: Float, version: Long, density: Float, lx: Float, ly: Float) {
        val image = backdrop.image ?: return
        if (size.width < 2f || size.height < 2f) return
        val r = Req(origin.x, origin.y, size.width, size.height, radii, tint, hl, version, density, lx, ly)
        if (r.same(wanted)) {
            // 參數沒變、但有人在等「烘好了」的通知（面板重新打開）：叫醒一下，讓它回報
            if (gate?.baked == false) wake.trySend(Unit)
            return
        }
        BackdropPixels.sync(image, version)
        wanted = r
        wake.trySend(Unit)
    }

    /** 目前烘好的圖（給圓角矩形填色用）；大小對不上（剛改過大小）就先不用。 */
    fun paint(m: android.graphics.Matrix): android.graphics.Paint? {
        val b = bakedFor ?: return null
        val w = wanted ?: return null
        if (b.w != w.w || b.h != w.h) return null
        val p = paint ?: return null
        m.setScale(q.toFloat(), q.toFloat())
        p.shader.setLocalMatrix(m)
        return p
    }

    suspend fun run() {
        while (true) {
            wake.receive()
            val r = wanted ?: continue
            if (r.same(bakedFor)) { gate?.baked = true; continue }
            val area = r.w * r.h
            // 越大片烘得越粗（中間本來就是模糊的背景，粗一點看不出來）：抽屜 1880×976 用 ÷6，約 5 萬個像素
            val nq = when { area <= 40_000f -> 1; area <= 160_000f -> 2; area <= 500_000f -> 3; area <= 1_000_000f -> 4; else -> 6 }
            val bw = max(1, (r.w / nq + .999f).toInt()); val bh = max(1, (r.h / nq + .999f).toInt())
            if (buf.size != bw * bh) buf = IntArray(bw * bh)
            val out = buf
            val sx = BackdropPixels.w / backdrop.screen.width; val sy = BackdropPixels.h / backdrop.screen.height
            withContext(Dispatchers.Default) { bake(out, bw, bh, nq, r, sx, sy, edge) }
            var b = bitmap
            if (b == null || b.width != bw || b.height != bh) {
                b = android.graphics.Bitmap.createBitmap(bw, bh, android.graphics.Bitmap.Config.ARGB_8888)
                b.setHasAlpha(false)   // 不透明：GPU 填色時不用混合
                bitmap = b
                paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG).apply {
                    shader = android.graphics.BitmapShader(b, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP)
                }
            }
            b!!.setPixels(out, 0, bw, 0, 0, bw, bh)
            q = nq
            bakedFor = r
            tick++
            tw.liuli.launcher.ui.bg.UiTrace.hit("bake")
            gate?.baked = true
            // 背景在動時每秒最多烘十幾次：玻璃後面是模糊的背景，慢一兩格看不出來
            // 面板上的光正在移動：馬上接著烘下一張（不然光是一段一段跳過去的）
            val lightMoving = wanted?.let { it.lx != r.lx } == true
            delay(if (lightMoving) 1 else if (nq >= 3) 120 else 66)
        }
    }

    private companion object {
        // 玻璃的折射率（一般的玻璃）。色散看起來不自然、已經拿掉：三個顏色用同一個折射率，邊緣不會有色邊
        const val N_G = 1.5f
        /** 垂直入射的反射率 ((n−1)/(n+1))²。 */
        const val R0 = .04f

        /** 垂直入射的視線穿過斜率為 [slope] 的玻璃表面後，水平方向的偏移量（每單位厚度；正值＝往斜面升高的方向，也就是往玻璃裡面）。 */
        private fun refract(slope: Float, n: Float): Float {
            // 表面法線 N＝(-slope, 1)／len（朝外、朝觀看者），入射 I＝(0, -1)。Snell：T＝ηI＋(η·cosi − √(1 − η²(1 − cos²i)))·N
            val inv = 1f / sqrt(1f + slope * slope)
            val cosi = inv
            val eta = 1f / n
            val k = 1f - eta * eta * (1f - cosi * cosi)
            val coef = eta * cosi - sqrt(k)
            val tx = coef * (-slope * inv)
            val tz = -eta + coef * inv
            return tx / -tz
        }

        fun bake(out: IntArray, bw: Int, bh: Int, q: Int, r: Req, sx: Float, sy: Float, edge: Boolean) {
            val src = BackdropPixels.px; val sw = BackdropPixels.w; val sh = BackdropPixels.h
            if (src.isEmpty() || sw < 2 || sh < 2) return
            val hw = r.w / 2f; val hh = r.h / 2f
            val short = min(r.w, r.h)
            // 邊緣是一圈圓弧形的斜面（像磨圓的玻璃邊）：斜面的寬度＝短邊的 17%，夾在 5～16dp；中間是平的
            val bevel0 = if (edge) (short * .17f).coerceIn(5f * r.density, 16f * r.density) else 0f
            val mid = r.tint + .10f * r.hl
            val top = 1f - (1f - mid) * (1f - (.06f + .06f * r.hl))
            val maxX = sw - 1.001f; val maxY = sh - 1.001f
            // 一盞柔和的主光，在左上方、朝向觀看者
            val lxk = -.50f; val lyk = -.68f; val lzk = .54f
            for (y in 0 until bh) {
                val py = (y + .5f) * q
                val cy = py - hh
                // 由上往下的淡光：頂端 top → 0.6 高度處 mid
                val g = top + (mid - top) * (py / (r.h * .6f)).coerceIn(0f, 1f)
                val row = y * bw
                for (x in 0 until bw) {
                    val px = (x + .5f) * q
                    val cx = px - hw
                    val u = (r.ox + px) * sx - .5f
                    val v = (r.oy + py) * sy - .5f
                    var dG = 0f     // 取樣偏移（像素，沿著往外的法線；負的＝往裡面）
                    var nx = 0f; var ny = 0f
                    var fres = 0f; var env = 0f
                    if (edge) {
                        // 到圓角矩形邊緣的距離（裡面是負的）與往外的法線
                        val rad = if (cy < 0f) (if (cx < 0f) r.radii[0] else r.radii[1]) else (if (cx < 0f) r.radii[3] else r.radii[2])
                        val rc = min(rad, min(hw, hh))
                        val qx = abs(cx) - hw + rc; val qy = abs(cy) - hh + rc
                        val d: Float
                        if (qx > 0f && qy > 0f) {
                            val l = sqrt(qx * qx + qy * qy)
                            d = l - rc
                            nx = qx / l * (if (cx < 0f) -1f else 1f); ny = qy / l * (if (cy < 0f) -1f else 1f)
                        } else if (qx > qy) { d = qx - rc; nx = if (cx < 0f) -1f else 1f } else { d = qy - rc; ny = if (cy < 0f) -1f else 1f }
                        // 斜面的寬度：底部最寬、上緣次之、兩側最窄（玻璃的形狀本身下厚上薄）
                        val bevel = bevel0 * (.75f + .60f * max(ny, 0f) + .15f * max(-ny, 0f))
                        if (d > -bevel) {
                            // 斜面的剖面是四分之一圓：高度 h＝B·√(1−(1−t)²)，t＝離邊緣的距離／斜面寬度。越靠邊越陡
                            var t = -d / bevel
                            if (t < .04f) t = .04f
                            val w1 = 1f - t
                            val root = sqrt(1f - w1 * w1)
                            val slope = w1 / root
                            // 光線在玻璃裡走的厚度＝這一點的高度＋底下那一層平的玻璃
                            val thick = bevel * (root + 1.2f)
                            // 折射（Snell）：偏移的方向是往玻璃裡面
                            dG = -refract(slope, N_G) * thick
                            // 反射（Fresnel，Schlick 近似）：越斜的地方反射越多；反射進眼睛的是那盞主光加一點環境光。
                            // 最陡的那一圈比一個像素還窄，所以在這個像素涵蓋的範圍內取三點平均（大片面板烘得粗，一個像素涵蓋更寬）
                            val fp = max(q.toFloat(), 1.5f) / bevel
                            var fSum = 0f; var rSum = 0f
                            for (kk in -1..1) {
                                var tt = -d / bevel + kk * .4f * fp
                                if (tt < .01f) tt = .01f else if (tt > 1f) tt = 1f
                                val wk = 1f - tt
                                val sl = wk / sqrt(1f - wk * wk)
                                val inv = 1f / sqrt(1f + sl * sl)
                                val om = 1f - inv
                                val f = R0 + (1f - R0) * om * om * om * om * om
                                // 反射方向 R＝I＋2cosi·N，N＝(往外的法線·斜率, 1)·inv
                                val k2 = 2f * inv * inv
                                var sp = k2 * sl * (nx * lxk + ny * lyk) + (-1f + k2) * lzk
                                if (sp < 0f) sp = 0f
                                fSum += f; rSum += f * (.16f + .84f * sp * sp * sp)
                            }
                            fres = fSum / 3f
                            env = rSum / fSum
                        }
                    }
                    var cr: Int; var cg: Int; var cb: Int
                    run {
                        val u1 = u + nx * dG * sx; val v1 = v + ny * dG * sy
                        val uu = if (u1 < 0f) 0f else if (u1 > maxX) maxX else u1
                        val vv = if (v1 < 0f) 0f else if (v1 > maxY) maxY else v1
                        val x0 = uu.toInt(); val y0 = vv.toInt(); val fx = uu - x0; val fy = vv - y0
                        val i = y0 * sw + x0
                        val a = src[i]; val b = src[i + 1]; val c = src[i + sw]; val dd = src[i + sw + 1]
                        val w00 = (1f - fx) * (1f - fy); val w10 = fx * (1f - fy); val w01 = (1f - fx) * fy; val w11 = fx * fy
                        cr = (((a shr 16) and 255) * w00 + ((b shr 16) and 255) * w10 + ((c shr 16) and 255) * w01 + ((dd shr 16) and 255) * w11).toInt()
                        cg = (((a shr 8) and 255) * w00 + ((b shr 8) and 255) * w10 + ((c shr 8) and 255) * w01 + ((dd shr 8) and 255) * w11).toInt()
                        cb = ((a and 255) * w00 + (b and 255) * w10 + (c and 255) * w01 + (dd and 255) * w11).toInt()
                    }
                    // 玻璃本身的一點白霧：背景越亮加得越少（亮背景上不會整片發白）
                    val lum = (cr * 3 + cg * 6 + cb) / 2550f
                    var white = g * (1f - .5f * lum)
                    if (r.lx >= 0f) {
                        // 大片面板的受光：上緣一道光、一片柔光（跟著焦點那一欄）、往下漸暗
                        val ty = py / r.h
                        val tg = 1f - ty / .5f
                        if (tg > 0f) white += .09f * tg * tg
                        val ddx = (px / r.w - r.lx) / .34f; val ddy = (ty - r.ly) / .62f
                        val dd2 = ddx * ddx + ddy * ddy
                        if (dd2 < 4f) { val k = 1f - dd2 / 4f; white += .13f * k * k * k }
                        val shade = 1f - .22f * ty * sqrt(ty)
                        cr = (cr * shade).toInt(); cg = (cg * shade).toInt(); cb = (cb * shade).toInt()
                    }
                    if (white > 1f) white = 1f
                    // 白霧用 screen 疊上去：1-(1-c)(1-white)
                    val inv = 1f - white
                    var rr = 255f - (255 - cr) * inv; var gg = 255f - (255 - cg) * inv; var bb = 255f - (255 - cb) * inv
                    if (fres > 0f) {
                        // 能量守恆：反射掉的部分不再透過來；反射進來的是白光（沒有顏色）
                        val refl = 255f * fres * env; val tr = 1f - fres
                        rr = rr * tr + refl; gg = gg * tr + refl; bb = bb * tr + refl
                    }
                    // 抖色：寫成 8 位元之前加不到一階的規則噪點，大面積的緩漸層不會斷成一條一條
                    val dn = (((x * 5 + y * 11) and 15) + .5f) / 16f
                    rr += dn; gg += dn; bb += dn
                    val ri = if (rr < 0f) 0 else if (rr > 255f) 255 else rr.toInt()
                    val gi = if (gg < 0f) 0 else if (gg > 255f) 255 else gg.toInt()
                    val bi = if (bb < 0f) 0 else if (bb > 255f) 255 else bb.toInt()
                    out[row + x] = (255 shl 24) or (ri shl 16) or (gi shl 8) or bi
                }
            }
        }
    }
}
