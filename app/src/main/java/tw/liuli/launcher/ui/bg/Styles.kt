package tw.liuli.launcher.ui.bg

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/*
 * 12 種向量背景，全部在 480×270 的邏輯座標作畫（由呼叫端縮放到實際解析度）。
 * 每個樣式都讀 BgState 的色相、焦點位置、脈動來產生「跟著焦點的變化」。
 * 與 design/home-v4.html 的同名樣式一一對應。
 */

private const val W = 480f
private const val H = 270f
private val FULL = Size(W, H)

/**
 * 繪圖用的暫存物件（Path、三角網格頂點、取樣陣列）。每條執行緒各一份：
 * 主執行緒畫全螢幕背景的同時，背景執行緒也在畫給玻璃用的小張背景，共用同一份會互相覆蓋。
 */
private class Scratch {
    val ditherPaint = DitherScope.newPaint()
    val ditherNoise = DitherScope.newNoise()
    val ditherM = android.graphics.Matrix()
    val ditherInv = android.graphics.Matrix()
    val path = Path()
    val mesh = buildMesh()
    val sampleA = FloatArray(2)
    val sampleB = FloatArray(2)
    val field = FloatArray(CNX * CNY)
    /** 等高線的靜態地形（5 座固定的小山，不隨焦點變）：只算一次，每幀省下 5/6 的 exp。 */
    val bumpField = FloatArray(CNX * CNY).also { bf ->
        val cw = W / (CNX - 1); val ch = H / (CNY - 1)
        for (j in 0 until CNY) for (i in 0 until CNX) {
            val x = i * cw; val y = j * ch
            var f = 0f
            for (b in bumps) { val s2 = b[3] * W; val dx = x - b[0] * W; val dy = y - b[1] * H; f += b[2] * exp(-(dx * dx + dy * dy) / (s2 * s2)) }
            bf[j * CNX + i] = f
        }
    }
}

private val scratch: ThreadLocal<Scratch> = ThreadLocal.withInitial { Scratch() }

private fun hsl(h: Float, s: Float, l: Float, a: Float = 1f): Color =
    Color.hsl(((h % 360f) + 360f) % 360f, s.coerceIn(0f, 1f), l.coerceIn(0f, 1f), a.coerceIn(0f, 1f))

/** 以目前主色為中心取色；飽和度乘上主色飽和度，背景永遠是暗、同色系。 */
private fun BgState.c(hOff: Float, sat: Float, l: Float, a: Float = 1f) = hsl(h + hOff, sat * s * 1.6f, l, a)

fun DrawScope.drawStyle(
    style: BgStyle, st: BgState,
    noisePx: Float = 2f, noiseShift: Offset = Offset.Zero,
) {
    // 所有漸層開抖色（見 DitherScope）：8 bit 面板上的大面積暗色漸層不再一圈一圈斷開
    val sc = scratch.get()!!
    DitherScope(this, sc.ditherPaint, sc.ditherNoise, sc.ditherM, sc.ditherInv, noisePx, noiseShift).drawStyleRaw(style, st)
}

private fun DrawScope.drawStyleRaw(style: BgStyle, st: BgState) {
    when (style) {
        BgStyle.WAVES -> waves(st)
        BgStyle.BOKEH -> bokeh(st)
        BgStyle.TRIANGLES -> triangles(st)
        BgStyle.DOTS -> dots(st)
        BgStyle.RAIN -> rain(st)
        BgStyle.DEEPSEA -> deepsea(st)
        BgStyle.TRAILS -> trails(st)
        BgStyle.CONTOUR -> contour(st)
        BgStyle.PAPER -> paper(st)
        BgStyle.BEAMS -> beams(st)
        BgStyle.SOLID -> solid(st)
        BgStyle.BLACK -> black(st)
    }
}

// ───────────── 共用 ─────────────

private fun DrawScope.base(st: BgState) {
    drawRect(Brush.linearGradient(listOf(st.c(-8f, .7f, .06f), st.c(14f, .8f, .15f)), Offset.Zero, Offset(W * .25f, H)), size = FULL)
}

private fun DrawScope.vignette(a: Float) {
    drawRect(
        Brush.radialGradient(
            0f to Color.Transparent, 0.22f to Color.Transparent, 1f to Color.Black.copy(alpha = a),
            center = Offset(W / 2, H * .48f), radius = W * .75f,
        ),
        size = FULL,
    )
}

private fun DrawScope.focusGlow(st: BgState, r: Float, a: Float) {
    val c = Offset(st.fx * W, st.fy * H)
    drawRect(Brush.radialGradient(listOf(st.c(0f, .9f, .5f, a), st.c(0f, .9f, .5f, 0f)), center = c, radius = r), size = FULL)
}


// ───────────── 1. 流線浪潮 ─────────────

private fun DrawScope.waves(st: BgState) {
    val sc = scratch.get()!!
    val pathL = sc.path
    base(st)
    for (i in 0 until 3) {
        val y0 = H * (.56f + i * .13f)
        val a = H * (.07f - i * .012f) * (1 + st.pulse * .5f)
        val cx = st.fx * W
        val ph = st.fx * 4 * (i + 1)
        pathL.reset(); pathL.moveTo(0f, H)
        var x = 0f
        while (x <= W) {
            val y = y0 + a * sin(x / W * PI.toFloat() * 2 * (.7f + i * .25f) + ph) -
                a * 1.1f * exp(-(x - cx).pow(2) / (2 * (W * .16f).pow(2)))
            pathL.lineTo(x, y); x += 6f
        }
        pathL.lineTo(W, H); pathL.close()
        drawPath(pathL, Brush.horizontalGradient(listOf(st.c(-14f + i * 10, .9f, .3f + i * .06f, .62f), st.c(18f + i * 8, .85f, .4f + i * .04f, .5f)), 0f, W))
    }
    vignette(.35f)
}

// ───────────── 2. 光斑（三層景深：遠小近大、遠慢近快的視差） ─────────────

/**
 * 一顆光斑：[layer] 0＝遠（小、清楚、密）、1＝中、2＝近（大、很柔、稀疏、半透明）。
 * 大小用冪次分佈：大多數是小的、少數很大，看起來才有「大小錯落」。
 */
private class Bokeh(val x: Float, val y: Float, val r: Float, val layer: Int, val h: Float, val a: Float)

private val bokehs: List<Bokeh> = Random(7).let { rnd ->
    val out = ArrayList<Bokeh>()
    // 位置鋪得比畫面寬（視差移動時邊緣不會空掉）；遠景最多、近景最少
    repeat(54) { out += Bokeh(rnd.nextFloat() * W * 1.3f - W * .15f, rnd.nextFloat() * H, 1.1f + rnd.nextFloat().pow(2.2f) * 4.2f, 0, (rnd.nextFloat() - .5f) * 70, .35f + rnd.nextFloat() * .55f) }
    repeat(20) { out += Bokeh(rnd.nextFloat() * W * 1.4f - W * .2f, rnd.nextFloat() * H, 7f + rnd.nextFloat().pow(1.6f) * 13f, 1, (rnd.nextFloat() - .5f) * 60, .45f + rnd.nextFloat() * .5f) }
    repeat(7) { out += Bokeh(rnd.nextFloat() * W * 1.6f - W * .3f, rnd.nextFloat() * H, 26f + rnd.nextFloat() * 36f, 2, (rnd.nextFloat() - .5f) * 50, .5f + rnd.nextFloat() * .5f) }
    out
}

/** 每一層的視差量（焦點從畫面一端移到另一端時，這一層移動畫面寬度的幾成）：遠景幾乎不動、近景移動最多。 */
private val BOKEH_PARALLAX = floatArrayOf(.05f, .16f, .4f)

private fun DrawScope.bokeh(st: BgState) {
    drawRect(Brush.linearGradient(listOf(st.c(-8f, .7f, .045f), st.c(14f, .8f, .11f)), Offset.Zero, Offset(W * .25f, H)), size = FULL)
    focusGlow(st, W * .55f, .1f)
    for (b in bokehs) {
        val k = BOKEH_PARALLAX[b.layer]
        // 鏡頭跟著焦點：焦點往右，畫面往左帶——近的移得多、遠的移得少
        val c = Offset(b.x - (st.fx - .5f) * W * k, b.y - (st.fy - .5f) * H * k * .5f)
        val r = b.r * (1 + st.pulse * (.12f + .14f * b.layer))
        when (b.layer) {
            // 遠：小而清楚的光點
            0 -> drawCircle(st.c(b.h, .85f, .7f, .5f * b.a), r, c, blendMode = BlendMode.Plus)
            // 中：柔邊光斑，邊緣一圈稍亮（鏡頭散景的光圈邊）
            1 -> drawCircle(
                Brush.radialGradient(
                    0f to st.c(b.h, .9f, .6f, .26f * b.a), .74f to st.c(b.h, .9f, .58f, .22f * b.a),
                    .9f to st.c(b.h, .9f, .66f, .34f * b.a), 1f to st.c(b.h, .9f, .5f, 0f),
                    center = c, radius = r,
                ),
                r, c, blendMode = BlendMode.Plus,
            )
            // 近：很大、很淡、很柔（離焦最嚴重），只隱約一圈邊
            else -> drawCircle(
                Brush.radialGradient(
                    0f to st.c(b.h, .8f, .55f, .13f * b.a), .6f to st.c(b.h, .8f, .55f, .11f * b.a),
                    .86f to st.c(b.h, .8f, .62f, .16f * b.a), 1f to st.c(b.h, .8f, .5f, 0f),
                    center = c, radius = r,
                ),
                r, c, blendMode = BlendMode.Plus,
            )
        }
    }
    vignette(.42f)
}


// ───────────── 3. 幾何三角（頂點會移動的低多邊形曲面） ─────────────

private class MeshPoint(val x: Float, val y: Float, val z: Float) {
    var sx = 0f; var sy = 0f; var zz = 0f
}

private class Mesh(val points: List<List<MeshPoint>>, val tris: List<Array<MeshPoint>>)


/** 網格往四周多鋪一格（邊緣不露空），每個頂點有基礎高度 z。 */
private fun buildMesh(): Mesh {
    val cols = 12; val rows = 8
    val cw = W / cols; val ch = H / rows
    val rnd = Random(11)
    val points = (-1..rows + 1).map { y ->
        (-1..cols + 1).map { x ->
            MeshPoint((x + (if ((y and 1) == 1) .5f else 0f) + (rnd.nextFloat() - .5f) * .55f) * cw, (y + (rnd.nextFloat() - .5f) * .55f) * ch, rnd.nextFloat() * 14)
        }
    }
    val tris = ArrayList<Array<MeshPoint>>()
    for (y in 0 until points.size - 1) for (x in 0 until points[0].size - 1) {
        val p0 = points[y][x]; val p1 = points[y][x + 1]; val p2 = points[y + 1][x]; val p3 = points[y + 1][x + 1]
        if ((y and 1) == 1) { tris += arrayOf(p0, p1, p3); tris += arrayOf(p0, p3, p2) } else { tris += arrayOf(p0, p1, p2); tris += arrayOf(p1, p3, p2) }
    }
    return Mesh(points, tris)
}

private fun DrawScope.triangles(st: BgState) {
    val sc = scratch.get()!!
    val pathL = sc.path
    val meshL = sc.mesh
    val lx = st.fx * W; val ly = st.fy * H; val sig = W * .22f
    for (row in meshL.points) for (p in row) {
        val dx = p.x - lx; val dy = (p.y - ly) * 1.2f
        val e = exp(-(dx * dx + dy * dy) / (sig * sig))
        p.zz = p.z + (40 + 22 * st.pulse) * e
        val push = .42f * e * (1 + .6f * st.pulse)
        p.sx = p.x + dx * push + sin(p.y * .045f + st.fx * 9) * 5 * st.pulse
        p.sy = p.y + (p.y - ly) * push * .8f - p.zz * .5f + cos(p.x * .04f + st.fx * 7) * 4 * st.pulse
    }
    val lX = -.36f; val lY = -.55f; val lZ = .75f
    // 底色：反鋸齒的三角形邊緣會透出一點底下的顏色，鋪一層同色系的暗色，接縫就是淡淡的稜線而不是黑線
    drawRect(st.c(0f, .75f, .07f), size = FULL)
    for (t in meshL.tris) {
        val a = t[0]; val b = t[1]; val c = t[2]
        val ux = b.sx - a.sx; val uy = b.sy - a.sy; val uz = b.zz - a.zz
        val vx = c.sx - a.sx; val vy = c.sy - a.sy; val vz = c.zz - a.zz
        var nx = uy * vz - uz * vy; var ny = uz * vx - ux * vz; var nz = ux * vy - uy * vx
        if (nz < 0) { nx = -nx; ny = -ny; nz = -nz }
        val len = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-4f)
        val shade = max(0f, (nx * lX + ny * lY + nz * lZ) / len)
        val cx = (a.sx + b.sx + c.sx) / 3; val cy = (a.sy + b.sy + c.sy) / 3
        val d = hypot(cx - lx, (cy - ly) * 1.2f) / W
        val near = exp(-d * d * 8)
        val col = st.c((shade - .75f) * 24 + d * 10, .75f, .04f + .2f * shade.pow(3) * (.4f + .6f * near) + .04f * near)
        pathL.reset(); pathL.moveTo(a.sx, a.sy); pathL.lineTo(b.sx, b.sy); pathL.lineTo(c.sx, c.sy); pathL.close()
        drawPath(pathL, col)
    }
    vignette(.38f)
}

// ───────────── 4. 波點圓圈 ─────────────

private fun DrawScope.dots(st: BgState) {
    base(st); focusGlow(st, W * .45f, .2f)
    val lx = st.fx * W; val ly = st.fy * H; val ph = st.fx * 6
    val far = st.c(14f, .6f, .62f, .1f)
    var y = 6f
    while (y < H) {
        var x = 6f + ((y / 20).toInt() % 2) * 10f - (st.fx - .5f) * 10f
        while (x < W) { drawCircle(far, 1.1f, Offset(x, y)); x += 20f }
        y += 20f
    }
    val sp = 12f
    y = sp / 2
    while (y < H + sp) {
        var x = ((y / (sp * .866f)).toInt() % 2) * sp / 2
        while (x < W + sp) {
            val d = hypot(x - lx, (y - ly) * 1.15f) / W
            val spot = exp(-d * d * 5)
            val wave = .5f + .5f * sin(x * .045f + y * .03f - ph)
            val r = .35f + 4.4f * (.6f * spot + .4f * wave * (.35f + .65f * spot)) * (1 + st.pulse * .15f)
            drawCircle(st.c(d * 30 - 6, .8f, .55f + .12f * spot, .14f + .5f * spot), r, Offset(x, y))
            x += sp
        }
        y += sp * .866f
    }
    vignette(.4f)
}

// ───────────── 5. 雨窗（毛玻璃上的雨滴：小水珠累積、大水滴滑落留下水痕） ─────────────

/** 由兩個整數得到 0～1 的固定亂數（同樣的輸入永遠同樣的值；主執行緒與背景執行緒畫出來一致）。 */
private fun hash01(a: Int, b: Int): Float {
    var x = a * 374761393 + b * 668265263
    x = (x xor (x ushr 13)) * 1274126177
    return ((x xor (x ushr 16)) and 0xFFFFFF) / 16777216f
}

private fun frac(v: Float) = v - kotlin.math.floor(v)

/** 一顆水珠：底下一點暗影、主體是透過去更亮的背景色、左上角一點高光。 */
private fun DrawScope.drop(st: BgState, c: Offset, r: Float, a: Float, hOff: Float) {
    if (a <= .01f || r <= .2f) return
    drawCircle(Color.Black.copy(alpha = .22f * a), r * 1.05f, c + Offset(0f, r * .3f))
    drawCircle(st.c(hOff, .55f, .42f, .55f * a), r, c)
    drawCircle(st.c(hOff, .35f, .7f, .28f * a), r * .62f, c + Offset(0f, r * .15f))
    drawCircle(Color.White.copy(alpha = .55f * a), r * .28f, c + Offset(-r * .32f, -r * .38f))
}

private fun DrawScope.rain(st: BgState) {
    val t = st.at
    // 毛玻璃後面：深色底＋幾團柔和的光暈（像窗外的燈），跟著主色；視差很小（遠在窗外）
    drawRect(Brush.verticalGradient(listOf(st.c(-6f, .5f, .06f), st.c(10f, .55f, .11f)), 0f, H), size = FULL)
    val px = -(st.fx - .5f) * W * .06f
    val glows = arrayOf(
        floatArrayOf(st.fx * W, st.fy * H, W * .42f, 0f, .32f),
        floatArrayOf(W * .18f, H * .3f, W * .3f, -28f, .2f),
        floatArrayOf(W * .82f, H * .62f, W * .34f, 26f, .22f),
        floatArrayOf(W * .55f, H * .08f, W * .26f, 40f, .14f),
    )
    for (g in glows) {
        val c = Offset(g[0] + px, g[1])
        drawRect(Brush.radialGradient(listOf(st.c(g[3], .85f, .5f, g[4]), st.c(g[3], .85f, .45f, 0f)), center = c, radius = g[2]), size = FULL)
    }
    // 霧面：整片很淡的白霧，玻璃的感覺
    drawRect(Color.White.copy(alpha = .03f), size = FULL)

    // 小水珠：位置固定一陣子，慢慢浮現、停留、淡出，再換到別的地方（雨一直在累積的感覺）
    for (i in 0 until 150) {
        val period = 9f + 9f * hash01(i, 7)
        val ph = t / period + hash01(i, 3)
        val cyc = kotlin.math.floor(ph).toInt()
        val u = ph - cyc
        val a = (min(1f, u * 6f) * min(1f, (1f - u) * 4f))
        val x = hash01(i, cyc * 2 + 11) * W
        val y = hash01(i, cyc * 2 + 12) * H
        val r = .5f + 1.6f * hash01(i, cyc + 5).pow(2.5f)
        drop(st, Offset(x, y), r, a, (hash01(i, 9) - .5f) * 30)
    }

    // 大水滴：先在原地慢慢長大（小水珠匯聚），重到撐不住就加速往下滑，身後留下一道較清透的水痕
    for (lane in 0 until 13) {
        val period = 6f + 6f * hash01(lane, 31)
        val ph = t / period + hash01(lane, 37)
        val cyc = kotlin.math.floor(ph).toInt()
        val u = ph - cyc
        val x0 = (.04f + .92f * hash01(lane, cyc * 3 + 1)) * W
        val y0 = (.02f + .55f * hash01(lane, cyc * 3 + 2)) * H
        val rMax = 2.2f + 2.2f * hash01(lane, cyc * 3 + 3)
        val hOff = (hash01(lane, 41) - .5f) * 30
        if (u < .55f) {
            val g = u / .55f
            drop(st, Offset(x0, y0), rMax * (.35f + .65f * g), min(1f, u * 8f), hOff)
        } else {
            val s = (u - .55f) / .45f
            val y = y0 + (H - y0 + 12f) * s * s // 重力：越滑越快
            val x = x0 + 2.5f * sin(s * 7f + lane)
            // 水痕：被水滴擦過的地方比較清透（稍亮、稍飽和），上淡下濃
            val w = rMax * .9f
            drawRect(
                Brush.verticalGradient(listOf(st.c(hOff, .5f, .5f, 0f), st.c(hOff, .5f, .55f, .2f)), y0, y),
                Offset(x0 - w / 2, y0), Size(w, max(0f, y - y0)),
            )
            // 水痕上殘留的小水珠
            for (k in 0 until 4) {
                val ky = y0 + (y - y0) * hash01(lane * 7 + k, cyc)
                if (ky < y - rMax * 2) drop(st, Offset(x0 + (hash01(k, cyc + lane) - .5f) * w, ky), .6f + .6f * hash01(k, lane), .8f, hOff)
            }
            drop(st, Offset(x, y), rMax * (1f - .15f * s), 1f, hOff)
        }
    }
    vignette(.4f)
}

// ───────────── 6. 深海（水面波動的光紋、灑下的光束、緩緩飄落的微粒） ─────────────

private fun DrawScope.deepsea(st: BgState) {
    val sc = scratch.get()!!
    val pathL = sc.path
    val t = st.at
    // 水深：上方透光、往下越深越暗
    drawRect(
        Brush.verticalGradient(0f to st.c(-14f, .75f, .22f), .3f to st.c(-6f, .8f, .12f), .7f to st.c(4f, .85f, .05f), 1f to st.c(10f, .85f, .02f), startY = 0f, endY = H),
        size = FULL,
    )
    val lx = st.fx * W
    // 光束：從水面斜射下來，跟著水面的波浪慢慢搖擺、明暗起伏；焦點附近的光束較亮
    for (i in 0 until 7) {
        val bx = W * (-.08f + i * .18f) + 16f * sin(t * .21f + i * 1.7f) - (st.fx - .5f) * W * .1f
        val wt = 10f + 14f * hash01(i, 1)
        val wb = 50f + 50f * hash01(i, 2)
        val skew = 40f + 30f * hash01(i, 3)
        val near = exp(-((bx - lx) / (W * .22f)).pow(2))
        val a = (.07f + .05f * sin(t * .55f + i * 2.3f) + .1f * near).coerceAtLeast(0f)
        pathL.reset()
        pathL.moveTo(bx - wt / 2, -2f); pathL.lineTo(bx + wt / 2, -2f)
        pathL.lineTo(bx + skew + wb / 2, H); pathL.lineTo(bx + skew - wb / 2, H); pathL.close()
        drawPath(
            pathL,
            Brush.verticalGradient(0f to st.c(-10f, .45f, .78f, a), .55f to st.c(-6f, .5f, .6f, a * .3f), 1f to st.c(0f, .5f, .5f, 0f), startY = 0f, endY = H),
            blendMode = BlendMode.Plus,
        )
    }
    // 水面光紋（焦散）：上方交錯流動的亮紋，越深越淡、越寬越散
    val stroke = Stroke(1.1f)
    for (k in 0 until 10) {
        val yb = H * (.015f + k * .045f)
        val fade = (1f - k / 10f).pow(1.6f)
        pathL.reset()
        var x = -8f
        var first = true
        while (x <= W + 8f) {
            val y = yb + 3.2f * sin(x * .045f + t * 1.05f + k * 1.3f) + 2.4f * sin(x * .11f - t * 1.6f + k * .7f) +
                1.2f * sin(x * .23f + t * 2.3f + k)
            if (first) { pathL.moveTo(x, y); first = false } else pathL.lineTo(x, y)
            x += 6f
        }
        val glow = .14f * fade * (.75f + .25f * sin(t * .8f + k))
        drawPath(pathL, st.c(-12f, .35f, .82f, glow), style = stroke, blendMode = BlendMode.Plus)
    }
    // 懸浮微粒（海雪）：慢慢往下飄；近的大、亮、飄得快、視差大
    for (p in 0 until 46) {
        val d = .2f + .8f * hash01(p, 5)
        val x = frac(hash01(p, 1) + t * .004f * (hash01(p, 6) - .5f)) * W * 1.2f - W * .1f - (st.fx - .5f) * W * .12f * d
        val y = frac(hash01(p, 2) + t * (.006f + .014f * d)) * (H + 10f) - 5f
        val a = .1f + .28f * d * (1f - y / H * .6f)
        drawCircle(st.c(-8f, .3f, .8f, a), .4f + 1.1f * d, Offset(x, y))
    }
    vignette(.32f)
}

// ───────────── 7. 光軌 ─────────────

private const val TRAIL_SEG = 40

private fun DrawScope.trails(st: BgState) {
    val pts = ArrayList<Offset>(TRAIL_SEG + 1)
    drawRect(st.c(0f, .6f, .035f), size = FULL); focusGlow(st, W * .5f, .12f)
    val lx = st.fx * W; val ly = st.fy * H; val sx = st.fx - .5f; val sy = st.fy - .5f
    for (i in 0 until 14) {
        val yl = H * (.06f + i * .066f) + sx * H * .38f * sin(i * 1.1f + .5f) + sy * H * .2f
        val yr = H * (.02f + ((i * 7) % 14) * .068f) - sx * H * .38f * cos(i * .9f) + sy * H * .12f
        val wob = st.pulse * 8 * sin(i * 2.7f)
        val pull = .28f + .18f * sin(i * 1.7f)
        val c1x = W * .3f + (lx - W * .3f) * pull * .6f; val c1y = yl + (ly - yl) * pull + wob
        val c2x = W * .7f + (lx - W * .7f) * pull * .6f; val c2y = yr + (ly - yr) * pull - wob
        val hOff = sin(i * 1.9f) * 26; val thick = .7f + ((i * 5) % 7) * .28f
        val brush = Brush.horizontalGradient(
            0f to st.c(hOff, .9f, .6f, 0f), .5f to st.c(hOff, .95f, .7f, .55f + .35f * st.pulse), 1f to st.c(hOff + 15, .9f, .6f, 0f),
            startX = -40f, endX = W + 40f,
        )
        // 三次貝茲取樣成 TRAIL_SEG 段折線
        pts.clear()
        for (k in 0..TRAIL_SEG) {
            val t = k / TRAIL_SEG.toFloat(); val u = 1 - t
            val bx = u * u * u * -40f + 3 * u * u * t * c1x + 3 * u * t * t * c2x + t * t * t * (W + 40f)
            val by = u * u * u * yl + 3 * u * u * t * c1y + 3 * u * t * t * c2y + t * t * t * yr
            pts.add(Offset(bx, by))
        }
        drawPoints(pts, PointMode.Polygon, brush, strokeWidth = thick * 4f, alpha = .14f, blendMode = BlendMode.Plus)
        drawPoints(pts, PointMode.Polygon, brush, strokeWidth = thick, blendMode = BlendMode.Plus)
    }
    vignette(.4f)
}

// ───────────── 8. 等高線 ─────────────

private const val CNX = 80
private const val CNY = 45
private val bumps = arrayOf(floatArrayOf(.12f, .2f, .7f, .22f), floatArrayOf(.85f, .25f, .55f, .2f), floatArrayOf(.7f, .85f, .6f, .25f), floatArrayOf(.25f, .9f, .45f, .2f), floatArrayOf(.5f, .45f, -.35f, .3f))

private fun DrawScope.contour(st: BgState) {
    val sc = scratch.get()!!
    val pathL = sc.path
    val fieldL = sc.field
    val bumpL = sc.bumpField
    base(st); focusGlow(st, W * .5f, .16f)
    val cw = W / (CNX - 1); val ch = H / (CNY - 1)
    val lx = st.fx * W; val ly = st.fy * H
    val sf = W * (.17f + .02f * st.pulse)
    for (j in 0 until CNY) for (i in 0 until CNX) {
        val x = i * cw; val y = j * ch
        val dx = x - lx; val dy = (y - ly) * 1.15f
        fieldL[j * CNX + i] = 1.25f * exp(-(dx * dx + dy * dy) / (sf * sf)) + bumpL[j * CNX + i]
    }
    var lev = .08f; var li = 0
    val px = FloatArray(8)
    while (lev < 1.9f) {
        pathL.reset()
        for (j in 0 until CNY - 1) for (i in 0 until CNX - 1) {
            val a = fieldL[j * CNX + i]; val b = fieldL[j * CNX + i + 1]
            val c = fieldL[(j + 1) * CNX + i + 1]; val d = fieldL[(j + 1) * CNX + i]
            val x0 = i * cw; val y0 = j * ch
            var n = 0
            if ((a - lev) * (b - lev) < 0) { px[n++] = x0 + cw * (lev - a) / (b - a); px[n++] = y0 }
            if ((b - lev) * (c - lev) < 0) { px[n++] = x0 + cw; px[n++] = y0 + ch * (lev - b) / (c - b) }
            if ((d - lev) * (c - lev) < 0) { px[n++] = x0 + cw * (lev - d) / (c - d); px[n++] = y0 + ch }
            if ((a - lev) * (d - lev) < 0) { px[n++] = x0; px[n++] = y0 + ch * (lev - a) / (d - a) }
            if (n >= 4) { pathL.moveTo(px[0], px[1]); pathL.lineTo(px[2], px[3]) }
            if (n == 8) { pathL.moveTo(px[4], px[5]); pathL.lineTo(px[6], px[7]) }
        }
        drawPath(pathL, st.c(lev * 26, .85f, .38f + lev * .16f, .22f + min(.5f, lev * .25f)), style = Stroke(if (li % 4 == 0) 1.3f else .7f))
        lev += .12f; li++
    }
    vignette(.4f)
}

// ───────────── 9. 紙雕層疊 ─────────────

private val hills = Random(5).let { r -> List(5) { floatArrayOf(r.nextFloat() * 6, r.nextFloat() * 6, r.nextFloat() * 6, .5f + r.nextFloat()) } }

private fun DrawScope.paper(st: BgState) {
    val sc = scratch.get()!!
    val pathL = sc.path
    base(st); focusGlow(st, W * .5f, .25f)
    for (i in 0 until 5) {
        val hh = hills[i]
        val yb = H * (.42f + i * .12f)
        val off = (st.fx - .5f) * W * .05f * (i + 1)
        pathL.reset(); pathL.moveTo(0f, H)
        var x = -10f
        while (x <= W + 10) {
            val xx = x + off
            val y = yb - H * .05f * hh[3] * (sin(xx / W * 5 + hh[0]) + .6f * sin(xx / W * 11 + hh[1]) + .3f * sin(xx / W * 23 + hh[2]))
            pathL.lineTo(x, y); x += 8f
        }
        pathL.lineTo(W, H); pathL.close()
        // 紙張的陰影落在上一層：往上錯開
        translate(0f, -3f) { drawPath(pathL, Color.Black.copy(alpha = .26f)) }
        drawPath(pathL, st.c(i * 6 - 10f, .7f, .1f + i * .045f))
    }
    vignette(.3f)
}

// ───────────── 10. 光束（粗細不一的垂直光束，呼吸、流動，往焦點聚攏） ─────────────

private fun DrawScope.beams(st: BgState) {
    val t = st.at
    drawRect(Brush.verticalGradient(listOf(st.c(-10f, .6f, .025f), st.c(10f, .7f, .06f)), 0f, H), size = FULL)
    val lx = st.fx * W
    focusGlow(st, W * .45f, .08f)
    for (i in 0 until 56) {
        val h1 = hash01(i, 1); val h2 = hash01(i, 2); val h3 = hash01(i, 3); val h4 = hash01(i, 4)
        val base = h1 * W * 1.3f - W * .15f
        // 寬度：大多數是細線，少數是寬的光帶
        val w = .5f + 10f * h2.pow(3.2f)
        // 往焦點聚攏一些（越近聚得越多），再加上緩慢的左右飄移
        val pull = .22f * exp(-((base - lx) / (W * .3f)).pow(2))
        val x = base + (lx - base) * pull + 7f * sin(t * (.12f + .22f * h3) + i * 1.9f) - (st.fx - .5f) * W * .08f * (1f - h2)
        // 亮度：各自緩慢呼吸；焦點附近整體亮一些
        val breathe = .5f + .5f * sin(t * (.25f + .55f * h4) + i * 2.1f)
        val a = (.12f + .55f * breathe * breathe) * (.45f + .7f * exp(-((x - lx) / (W * .28f)).pow(2))) * (.6f + .4f * (1f - h2))
        // 每條光束最亮的位置上下緩慢游移，兩端淡出
        val yc = H * (.5f + .22f * sin(t * (.1f + .15f * h3) + i))
        val span = H * (.55f + .45f * h4)
        val hOff = (h3 - .5f) * 80f
        drawRect(
            Brush.verticalGradient(
                0f to st.c(hOff, .95f, .55f, 0f), .3f to st.c(hOff, .95f, .55f, a * .55f), .5f to st.c(hOff, .9f, .62f, a),
                .7f to st.c(hOff, .95f, .55f, a * .55f), 1f to st.c(hOff, .95f, .5f, 0f),
                startY = yc - span, endY = yc + span,
            ),
            Offset(x - w / 2, 0f), Size(w, H), blendMode = BlendMode.Plus,
        )
        // 寬的光帶中間加一條更亮的細芯
        if (w > 4f) {
            drawRect(
                Brush.verticalGradient(0f to Color.Transparent, .5f to st.c(hOff, .6f, .8f, a * .5f), 1f to Color.Transparent, startY = yc - span * .8f, endY = yc + span * .8f),
                Offset(x - w * .12f, 0f), Size(w * .24f, H), blendMode = BlendMode.Plus,
            )
        }
    }
    vignette(.3f)
}

// ───────────── 11. 純色、12. 純黑 ─────────────

private fun DrawScope.solid(st: BgState) {
    drawRect(Brush.linearGradient(listOf(st.c(-6f, .55f, .09f), st.c(10f, .6f, .16f)), Offset.Zero, Offset(W * .4f, H)), size = FULL)
    focusGlow(st, W * .6f, .14f); vignette(.28f)
}

private fun DrawScope.black(st: BgState) {
    drawRect(Color(0xFF020204), size = FULL)
    val c = Offset(st.fx * W, H * 1.1f)
    drawRect(Brush.radialGradient(listOf(st.c(0f, .9f, .45f, .35f), st.c(0f, .9f, .45f, 0f)), center = c, radius = W * .45f), size = FULL)
}

