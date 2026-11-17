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
 * - 邊緣往內一圈是厚玻璃的折射帶：越靠近邊緣，取樣的位置越往玻璃「外面」偏——周圍的顏色被彎進來、沿著邊緣繞；
 *   底部的折射帶最厚，上緣次之，兩側最薄（像一顆上凸下厚的透鏡）。
 * - 折射帶裡紅、藍兩個通道偏移的量不一樣（色散）：有明暗、顏色變化的地方會分出很細的粉紅／青色邊。
 * - 最外緣一條受光的細亮線（左上、右下較亮）。
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
): Modifier = composed {
    val backdrop = LocalGlassBackdrop.current
    var origin by remember { mutableStateOf(Offset.Zero) }
    val bake = remember(backdrop.image) { GlassBake(backdrop, edge) }
    LaunchedEffect(bake) { bake.run() }
    val matrix = remember { android.graphics.Matrix() }

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
                val v = backdrop.version() // 背景換色時跟著重烘
                @Suppress("UNUSED_VARIABLE") val t = bake.tick // 烘好一張就重畫
                val hl = highlight()
                if (backdrop.image == null || backdrop.screen.width <= 0f) {
                    drawPath(path, Color(0x99141A2E))
                    return@onDrawBehind
                }
                bake.request(origin, size, radii, tint, hl, v, dens)
                val paint = bake.paint(matrix)
                if (paint != null) drawIntoCanvas { it.nativeCanvas.drawPath(androidPath, paint) }
                // 第一張還沒烘好（剛出現的那一兩格）：先用半透明的深色頂著
                else drawPath(path, Color(0x66202432))
            }
        }
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
    private val wake = Channel<Unit>(Channel.CONFLATED)

    private class Req(
        val ox: Float, val oy: Float, val w: Float, val h: Float, val radii: FloatArray,
        val tint: Float, val hl: Float, val version: Long, val density: Float,
    ) {
        fun same(o: Req?) = o != null && ox == o.ox && oy == o.oy && w == o.w && h == o.h && tint == o.tint && hl == o.hl &&
            version == o.version && radii.contentEquals(o.radii)
    }

    private var wanted: Req? = null
    private var bitmap: android.graphics.Bitmap? = null
    private var paint: android.graphics.Paint? = null
    private var q = 1
    private var bakedFor: Req? = null
    private var buf = IntArray(0)

    fun request(origin: Offset, size: Size, radii: FloatArray, tint: Float, hl: Float, version: Long, density: Float) {
        val image = backdrop.image ?: return
        if (size.width < 2f || size.height < 2f) return
        val r = Req(origin.x, origin.y, size.width, size.height, radii, tint, hl, version, density)
        if (r.same(wanted)) return
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
            if (r.same(bakedFor)) continue
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
                bitmap = b
                paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG).apply {
                    shader = android.graphics.BitmapShader(b, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP)
                }
            }
            b!!.setPixels(out, 0, bw, 0, 0, bw, bh)
            q = nq
            bakedFor = r
            tick++
            // 背景在動時每秒最多烘十幾次：玻璃後面是模糊的背景，慢一兩格看不出來
            delay(if (nq >= 3) 120 else 66)
        }
    }

    private companion object {
        fun bake(out: IntArray, bw: Int, bh: Int, q: Int, r: Req, sx: Float, sy: Float, edge: Boolean) {
            val src = BackdropPixels.px; val sw = BackdropPixels.w; val sh = BackdropPixels.h
            if (src.isEmpty() || sw < 2 || sh < 2) return
            val hw = r.w / 2f; val hh = r.h / 2f
            val short = min(r.w, r.h)
            // 折射帶的基本厚度：短邊的 17%，夾在 5～16dp
            val band0 = if (edge) (short * .17f).coerceIn(5f * r.density, 16f * r.density) else 0f
            val lineW = 1.3f * r.density
            val mid = r.tint + .10f * r.hl
            val top = 1f - (1f - mid) * (1f - (.06f + .06f * r.hl))
            val maxX = sw - 1.001f; val maxY = sh - 1.001f
            for (y in 0 until bh) {
                val py = (y + .5f) * q
                val cy = py - hh
                // 由上往下的淡光：頂端 top → 0.6 高度處 mid
                val g = top + (mid - top) * (py / (r.h * .6f)).coerceIn(0f, 1f)
                val row = y * bw
                for (x in 0 until bw) {
                    val px = (x + .5f) * q
                    val cx = px - hw
                    var u = (r.ox + px) * sx - .5f
                    var v = (r.oy + py) * sy - .5f
                    var du = 0f; var dv = 0f
                    var e = 0f; var nx = 0f; var ny = 0f; var d = -1e9f
                    if (edge) {
                        // 到圓角矩形邊緣的距離（裡面是負的）與往外的法線
                        val rad = if (cy < 0f) (if (cx < 0f) r.radii[0] else r.radii[1]) else (if (cx < 0f) r.radii[3] else r.radii[2])
                        val rc = min(rad, min(hw, hh))
                        val qx = abs(cx) - hw + rc; val qy = abs(cy) - hh + rc
                        if (qx > 0f && qy > 0f) {
                            val l = sqrt(qx * qx + qy * qy)
                            d = l - rc
                            nx = qx / l * (if (cx < 0f) -1f else 1f); ny = qy / l * (if (cy < 0f) -1f else 1f)
                        } else if (qx > qy) { d = qx - rc; nx = if (cx < 0f) -1f else 1f } else { d = qy - rc; ny = if (cy < 0f) -1f else 1f }
                        // 底部最厚、上緣次之、兩側最薄
                        val band = band0 * (.62f + 1.05f * max(ny, 0f) + .38f * max(-ny, 0f))
                        if (d > -band) {
                            e = 1f + d / band
                            if (e > 1f) e = 1f
                            e *= e
                            // 取樣位置往玻璃外面偏：周圍的顏色被彎進來
                            val shift = e * band * 5.5f
                            du = nx * shift * sx; dv = ny * shift * sy
                            u += du; v += dv
                        }
                    }
                    var cr: Int; var cg: Int; var cb: Int
                    run {
                        val uu = if (u < 0f) 0f else if (u > maxX) maxX else u
                        val vv = if (v < 0f) 0f else if (v > maxY) maxY else v
                        val x0 = uu.toInt(); val y0 = vv.toInt(); val fx = uu - x0; val fy = vv - y0
                        val i = y0 * sw + x0
                        val a = src[i]; val b = src[i + 1]; val c = src[i + sw]; val dd = src[i + sw + 1]
                        val w00 = (1f - fx) * (1f - fy); val w10 = fx * (1f - fy); val w01 = (1f - fx) * fy; val w11 = fx * fy
                        cr = (((a shr 16) and 255) * w00 + ((b shr 16) and 255) * w10 + ((c shr 16) and 255) * w01 + ((dd shr 16) and 255) * w11).toInt()
                        cg = (((a shr 8) and 255) * w00 + ((b shr 8) and 255) * w10 + ((c shr 8) and 255) * w01 + ((dd shr 8) and 255) * w11).toInt()
                        cb = ((a and 255) * w00 + (b and 255) * w10 + (c and 255) * w01 + (dd and 255) * w11).toInt()
                    }
                    var white = g
                    if (e > 0f) {
                        // 色散：紅色偏得更遠、藍色偏得少一點
                        val ur = u + du * .9f; val vr = v + dv * .9f
                        val ub = u - du * .9f; val vb = v - dv * .9f
                        run {
                            val uu = if (ur < 0f) 0f else if (ur > maxX) maxX else ur
                            val vv = if (vr < 0f) 0f else if (vr > maxY) maxY else vr
                            val x0 = uu.toInt(); val y0 = vv.toInt(); val fx = uu - x0; val fy = vv - y0
                            val i = y0 * sw + x0
                            cr = (((src[i] shr 16) and 255) * (1f - fx) * (1f - fy) + ((src[i + 1] shr 16) and 255) * fx * (1f - fy) +
                                ((src[i + sw] shr 16) and 255) * (1f - fx) * fy + ((src[i + sw + 1] shr 16) and 255) * fx * fy).toInt()
                        }
                        run {
                            val uu = if (ub < 0f) 0f else if (ub > maxX) maxX else ub
                            val vv = if (vb < 0f) 0f else if (vb > maxY) maxY else vb
                            val x0 = uu.toInt(); val y0 = vv.toInt(); val fx = uu - x0; val fy = vv - y0
                            val i = y0 * sw + x0
                            cb = ((src[i] and 255) * (1f - fx) * (1f - fy) + (src[i + 1] and 255) * fx * (1f - fy) +
                                (src[i + sw] and 255) * (1f - fx) * fy + (src[i + sw + 1] and 255) * fx * fy).toInt()
                        }
                        // 折射帶比中間亮一點、顏色濃一點（厚玻璃邊緣把光聚起來）
                        val gray = (cr * 3 + cg * 6 + cb) / 10
                        val el = sqrt(e)   // 線性的邊緣程度：顏色、亮度的變化比位移寬一點，折射帶看得出是一整圈
                        val sat = 1f + 1.0f * el
                        cr = (gray + (cr - gray) * sat).toInt(); cg = (gray + (cg - gray) * sat).toInt(); cb = (gray + (cb - gray) * sat).toInt()
                        white += .06f * el
                        // 最外緣的受光亮線：左上最亮、右下次之
                        if (d > -lineW * 1.6f) {
                            val k = (1f + d / (lineW * 1.6f)).coerceIn(0f, 1f)
                            val lit = max(0f, -(nx * .55f + ny * .83f)); val lit2 = max(0f, nx * .55f + ny * .83f)
                            white += k * k * (.16f + .50f * lit * lit + .26f * lit2 * lit2 * lit2)
                        }
                    }
                    if (white > 1f) white = 1f
                    // 白霧用 screen 疊上去：1-(1-c)(1-white)
                    val inv = 1f - white
                    var rr = 255 - ((255 - cr) * inv).toInt(); var gg = 255 - ((255 - cg) * inv).toInt(); var bb = 255 - ((255 - cb) * inv).toInt()
                    if (rr < 0) rr = 0 else if (rr > 255) rr = 255
                    if (gg < 0) gg = 0 else if (gg > 255) gg = 255
                    if (bb < 0) bb = 0 else if (bb > 255) bb = 255
                    out[row + x] = (255 shl 24) or (rr shl 16) or (gg shl 8) or bb
                }
            }
        }
    }
}
