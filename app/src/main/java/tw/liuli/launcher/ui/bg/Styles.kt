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

// ───────────── 2. 光斑（連續景深：越近越大、越柔、越淡、視差越大） ─────────────

/**
 * 一顆光斑。[n]＝近度（0＝最遠、1＝最近）。照相機散景的物理關係：
 * - 離對焦面越近（n 越大），散景圓越大、邊緣越柔、單位面積越暗（同樣的光攤在更大的圓上）
 * - 鏡頭跟著焦點平移時，近的移動多、遠的移動少（視差 ∝ 近度）
 * 近度用偏向遠方的分佈抽樣：遠方小光點很多，近處大光斑很少，再加幾個極大、極淡的光暈。
 */
private class Bokeh(val x: Float, val y: Float, val n: Float, val r: Float, val h: Float, val a: Float)

private val bokehs: List<Bokeh> = Random(7).let { rnd ->
    val out = ArrayList<Bokeh>()
    repeat(78) {
        val n = rnd.nextFloat().pow(2.1f)
        val r = (1.3f + 46f * n * n + 6f * n) * (.75f + .5f * rnd.nextFloat())
        out += Bokeh(rnd.nextFloat() * W * 1.5f - W * .25f, rnd.nextFloat() * H * 1.1f - H * .05f, n, r, (rnd.nextFloat() - .5f) * 70, .5f + .5f * rnd.nextFloat())
    }
    // 極大、極淡的光暈：最近、最模糊，錯落在畫面各處
    repeat(5) {
        val n = .9f + .1f * rnd.nextFloat()
        out += Bokeh(rnd.nextFloat() * W * 1.6f - W * .3f, rnd.nextFloat() * H, n, 85f + 75f * rnd.nextFloat(), (rnd.nextFloat() - .5f) * 50, .7f + .3f * rnd.nextFloat())
    }
    out.sortedBy { it.n } // 由遠到近畫
}

private fun DrawScope.bokeh(st: BgState) {
    drawRect(Brush.linearGradient(listOf(st.c(-8f, .7f, .04f), st.c(14f, .8f, .1f)), Offset.Zero, Offset(W * .25f, H)), size = FULL)
    focusGlow(st, W * .55f, .09f)
    for (b in bokehs) {
        // 視差：近度的 1.4 次方（近處移動明顯比遠處多，不是線性）
        val k = .03f + .5f * b.n.pow(1.4f)
        val c = Offset(b.x - (st.fx - .5f) * W * k, b.y - (st.fy - .5f) * H * k * .45f)
        val r = b.r * (1 + st.pulse * (.08f + .2f * b.n))
        // 單位面積亮度隨大小下降；最遠的小點最「實」
        val a = b.a * (.62f / (1f + r * .055f))
        // 邊緣柔和度：遠的幾乎是實心點、近的邊緣很軟；光圈邊（散景特有的一圈稍亮）只在中近景
        val soft = .55f + .4f * b.n
        val rim = if (b.n > .15f && b.r < 80f) .35f * (1f - b.n * .6f) else 0f
        if (r < 2.2f) {
            drawCircle(st.c(b.h, .85f, .72f, a), r, c, blendMode = BlendMode.Plus)
        } else {
            drawCircle(
                Brush.radialGradient(
                    0f to st.c(b.h, .9f, .62f, a * .8f),
                    (1f - soft * .45f) to st.c(b.h, .9f, .6f, a * (.75f + rim * .4f)),
                    (1f - soft * .18f) to st.c(b.h, .9f, .66f, a * (.8f + rim)),
                    1f to st.c(b.h, .9f, .5f, 0f),
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

// ───────────── 5. 雨窗（擬真雨滴：毛玻璃後的模糊燈光、水滴像透鏡映出清晰而上下顛倒的背景） ─────────────
//
// 參考開源的擬真做法（Codrops RainEffect／Shadertoy「Heartfelt」）的幾個重點：
// 1. 玻璃後面是模糊的（霧面），水滴本身卻像一顆小透鏡，映出「清晰、上下顛倒」的背景——上半部是下方的顏色、下半部是上方的顏色
// 2. 水滴的明暗來自形狀：邊緣把光折走所以暗、底部聚光較亮、高光柔而小（不是貼一個白點）
// 3. 分層：靜止的細小水珠、慢慢匯聚長大的水滴、撐不住加速滑落的水滴
// 4. 滑過的地方擦掉了霧氣，留下一道較清透的水痕和沿途的小殘珠，之後霧氣慢慢回來
// 原作是 GPU 著色器（從背景貼圖取樣），這台電視不能用自訂著色器；但這裡的背景是算出來的光暈，
// 可以直接算出水滴上下方的背景顏色，用漸層畫出那個顛倒、更鮮明的小影像。

/** 由兩個整數得到 0～1 的固定亂數（同樣的輸入永遠同樣的值；主執行緒與背景執行緒畫出來一致）。 */
private fun hash01(a: Int, b: Int): Float {
    var x = a * 374761393 + b * 668265263
    x = (x xor (x ushr 13)) * 1274126177
    return ((x xor (x ushr 16)) and 0xFFFFFF) / 16777216f
}

private fun frac(v: Float) = v - kotlin.math.floor(v)

/** 窗外的模糊燈光（位置與顏色），雨滴取樣用。 */
private class RainGlow(val x: Float, val y: Float, val r: Float, val hOff: Float, val a: Float)

private fun rainGlows(st: BgState): Array<RainGlow> {
    val px = -(st.fx - .5f) * W * .05f
    return arrayOf(
        RainGlow(st.fx * W + px, st.fy * H, W * .4f, 0f, .4f),
        RainGlow(W * .15f + px, H * .25f, W * .22f, -30f, .3f),
        RainGlow(W * .85f + px, H * .7f, W * .26f, 28f, .3f),
        RainGlow(W * .55f + px, H * .05f, W * .2f, 45f, .22f),
        RainGlow(W * .32f + px, H * .85f, W * .18f, -12f, .26f),
        RainGlow(W * .7f + px, H * .3f, W * .12f, 60f, .22f),
    )
}

/** 某一點背景的亮度（0～1）與加權色相偏移：和畫背景用的同一組光暈。 */
private fun rainSample(gl: Array<RainGlow>, x: Float, y: Float, out: FloatArray) {
    var w = 0f; var hs = 0f
    for (g in gl) {
        val d = hypot(x - g.x, y - g.y) / g.r
        if (d >= 1f) continue
        val k = (1f - d) * g.a
        w += k; hs += k * g.hOff
    }
    out[0] = min(1f, w); out[1] = if (w > 0f) hs / w else 0f
}

private fun DrawScope.rainDrop(st: BgState, gl: Array<RainGlow>, buf: FloatArray, c: Offset, r: Float, a: Float, stretch: Float = 1f) {
    if (a <= .01f || r <= .25f) return
    val ry = r * stretch
    if (r < 1.2f) {
        // 細小水珠：只取一個顛倒的背景色畫實心點（不建漸層，一幀上百顆省 CPU）
        rainSample(gl, c.x, c.y + r * 7f, buf)
        drawCircle(st.c(buf[1], .75f, .14f + .5f * buf[0], a), r, c)
        return
    }
    // 透鏡：上半部映出下方的背景、下半部映出上方的背景（上下顛倒），比周圍更清晰、更鮮明
    rainSample(gl, c.x, c.y + r * 7f, buf); val lumTop = buf[0]; val hTop = buf[1]
    rainSample(gl, c.x, c.y - r * 7f, buf); val lumBot = buf[0]; val hBot = buf[1]
    drawOval(
        Brush.verticalGradient(
            0f to st.c(hTop, .75f, .1f + .45f * lumTop, a), 1f to st.c(hBot, .75f, .1f + .45f * lumBot, a),
            startY = c.y - ry, endY = c.y + ry,
        ),
        topLeft = Offset(c.x - r, c.y - ry), size = Size(r * 2, ry * 2),
    )
    // 形狀的明暗：邊緣暗（光被折走），中間透
    drawOval(
        Brush.radialGradient(0f to Color.Transparent, .62f to Color.Transparent, .9f to Color.Black.copy(alpha = .38f * a), 1f to Color.Black.copy(alpha = .18f * a), center = c, radius = max(r, ry)),
        topLeft = Offset(c.x - r, c.y - ry), size = Size(r * 2, ry * 2),
    )
    // 底部聚光：光穿過水滴在下緣聚成一彎亮
    drawOval(
        Brush.radialGradient(listOf(st.c(hBot, .4f, .8f, .28f * a), Color.Transparent), center = c + Offset(0f, ry * .55f), radius = r * .7f),
        topLeft = Offset(c.x - r * .7f, c.y + ry * .1f), size = Size(r * 1.4f, ry * .85f), blendMode = BlendMode.Plus,
    )
    // 高光：左上角一點柔和的反光（小而淡）
    drawCircle(
        Brush.radialGradient(listOf(Color.White.copy(alpha = .45f * a), Color.Transparent), center = c + Offset(-r * .35f, -ry * .45f), radius = r * .32f),
        r * .32f, c + Offset(-r * .35f, -ry * .45f),
    )
}

private fun DrawScope.rain(st: BgState) {
    val t = st.at
    val gl = rainGlows(st)
    val buf = scratch.get()!!.sampleA
    // 窗外：深色底＋模糊的燈光（跟著主色）
    drawRect(Brush.verticalGradient(listOf(st.c(-6f, .5f, .05f), st.c(10f, .55f, .1f)), 0f, H), size = FULL)
    for (g in gl) {
        drawRect(Brush.radialGradient(listOf(st.c(g.hOff, .85f, .5f, g.a), st.c(g.hOff, .85f, .45f, 0f)), center = Offset(g.x, g.y), radius = g.r), size = FULL)
    }
    // 霧面：整片淡白霧（玻璃上的水氣）
    val fog = .055f
    drawRect(Color.White.copy(alpha = fog), size = FULL)

    // 大水滴：在原地慢慢匯聚長大，撐不住就加速往下滑，身後擦出一道清透的水痕（之後霧氣慢慢回來）
    for (lane in 0 until 11) {
        val period = 7f + 7f * hash01(lane, 31)
        val ph = t / period + hash01(lane, 37)
        val cyc = kotlin.math.floor(ph).toInt()
        val u = ph - cyc
        val x0 = (.04f + .92f * hash01(lane, cyc * 3 + 1)) * W
        val y0 = (.02f + .5f * hash01(lane, cyc * 3 + 2)) * H
        val rMax = 3.2f + 3.6f * hash01(lane, cyc * 3 + 3)
        if (u < .5f) {
            val g = u / .5f
            rainDrop(st, gl, buf, Offset(x0, y0), rMax * (.4f + .6f * g * g), min(1f, u * 10f))
        } else {
            val s = (u - .5f) / .5f
            val y = y0 + (H - y0 + 14f) * s * s // 重力：越滑越快
            val x = x0 + 1.8f * sin(s * 6f + lane) * s
            // 水痕：被擦掉霧氣的地方（抵銷霧面），上面較淡（霧氣已慢慢回來）
            val w = rMax * 1.1f
            drawRect(
                Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0f), Color.Black.copy(alpha = fog * 1.6f)), y0, y),
                Offset(x0 - w / 2, y0), Size(w, max(0f, y - y0)),
            )
            // 沿途的小殘珠
            for (k in 0 until 6) {
                val f = hash01(lane * 7 + k, cyc)
                val ky = y0 + (y - y0) * f
                if (ky < y - rMax * 2.2f) rainDrop(st, gl, buf, Offset(x0 + (hash01(k, cyc + lane) - .5f) * w * .8f, ky), .7f + 1f * hash01(k, lane + 3), .9f)
            }
            // 滑動中的水滴稍微拉長
            rainDrop(st, gl, buf, Offset(x, y), rMax * (1f - .12f * s), 1f, stretch = 1f + .25f * s)
        }
    }
    // 靜止的細小水珠：很多、很小，慢慢浮現、停留、淡出，再出現在別處（雨一直在累積）
    for (i in 0 until 170) {
        val period = 10f + 10f * hash01(i, 7)
        val ph = t / period + hash01(i, 3)
        val cyc = kotlin.math.floor(ph).toInt()
        val u = ph - cyc
        val a = min(1f, u * 5f) * min(1f, (1f - u) * 4f)
        val r = .45f + 2.1f * hash01(i, cyc + 5).pow(3f)
        rainDrop(st, gl, buf, Offset(hash01(i, cyc * 2 + 11) * W, hash01(i, cyc * 2 + 12) * H), r, a)
    }
    vignette(.38f)
}

// ───────────── 6. 深海（只有光影：水面透下的體積光、晃動的光斑與陰影、懸浮微粒） ─────────────

private fun DrawScope.deepsea(st: BgState) {
    val sc = scratch.get()!!
    val pathL = sc.path
    val t = st.at
    // 水深：上方透光、往下越深越暗
    drawRect(
        Brush.verticalGradient(0f to st.c(-14f, .75f, .2f), .3f to st.c(-6f, .8f, .11f), .7f to st.c(4f, .85f, .045f), 1f to st.c(10f, .85f, .018f), startY = 0f, endY = H),
        size = FULL,
    )
    val par = -(st.fx - .5f)
    // 水面上的光源（太陽）在畫面上方很遠處：所有光束都從那裡呈扇形散開（有透視、不是平行的聚光燈）
    val sx = W * .5f + par * W * .25f + (st.fx - .5f) * W * .3f
    val sy = -H * .9f
    // 體積光：許多細光束，粗細與亮度各不相同；每一束隨水面波動各自明暗閃動；
    // 每束畫兩層（寬而淡＋窄而亮）讓邊緣柔和；越往深處越淡
    for (i in 0 until 26) {
        val h1 = hash01(i, 11); val h2 = hash01(i, 12); val h3 = hash01(i, 13)
        val ang = (-.62f + 1.24f * (i + h1 * .8f) / 26f) + .025f * sin(t * (.13f + .1f * h2) + i)
        // 這一束射到水面時的位置（y=0）與到底部的位置
        val x0 = sx + (0f - sy) * kotlin.math.tan(ang)
        val x1 = sx + (H - sy) * kotlin.math.tan(ang)
        val flicker = .5f + .5f * sin(t * (.6f + 1.1f * h3) + i * 2.7f) * sin(t * (.23f + .3f * h1) + i)
        val near = exp(-((x0 - st.fx * W) / (W * .35f)).pow(2))
        val a = (.025f + .07f * h2 * h2 + .04f * near) * (.35f + .65f * flicker)
        if (a < .01f) continue
        val wTop = 1.5f + 7f * h3
        val wBot = wTop * (3.5f + 3f * h1)
        for (layer in 0 until 2) {
            val k = if (layer == 0) 1f else .38f
            val la = if (layer == 0) a * .55f else a
            pathL.reset()
            pathL.moveTo(x0 - wTop * k, 0f); pathL.lineTo(x0 + wTop * k, 0f)
            pathL.lineTo(x1 + wBot * k, H); pathL.lineTo(x1 - wBot * k, H); pathL.close()
            drawPath(
                pathL,
                Brush.verticalGradient(
                    0f to st.c(-12f, .4f, .82f, la), .35f to st.c(-8f, .45f, .7f, la * .55f), .75f to st.c(-4f, .5f, .6f, la * .15f), 1f to st.c(0f, .5f, .5f, 0f),
                    startY = 0f, endY = H,
                ),
                blendMode = BlendMode.Plus,
            )
        }
    }
    // 水面附近晃動的光斑：很多柔和的亮塊各自漂移、明暗起伏（水面起伏把光聚散），沒有線條
    for (i in 0 until 12) {
        val cx = (hash01(i, 21) * 1.2f - .1f) * W + 22f * sin(t * (.17f + .12f * hash01(i, 22)) + i) + par * W * .06f
        val cy = H * (.02f + .2f * hash01(i, 23)) + 6f * sin(t * (.21f + .1f * hash01(i, 24)) + i * 1.3f)
        val r = 18f + 34f * hash01(i, 25)
        val a = .07f * (.5f + .5f * sin(t * (.5f + .6f * hash01(i, 26)) + i * 2f))
        drawOval(
            Brush.radialGradient(listOf(st.c(-12f, .35f, .85f, a), st.c(-10f, .4f, .7f, 0f)), center = Offset(cx, cy), radius = r),
            topLeft = Offset(cx - r * 1.6f, cy - r * .6f), size = Size(r * 3.2f, r * 1.2f), blendMode = BlendMode.Plus,
        )
    }
    // 緩慢飄過的陰影（水面的起伏遮住一部分光）
    for (i in 0 until 4) {
        val cx = frac(hash01(i, 31) + t * .006f * (1f + hash01(i, 32))) * W * 1.6f - W * .3f
        val cy = H * (.15f + .35f * hash01(i, 33))
        val r = W * (.18f + .12f * hash01(i, 34))
        drawRect(Brush.radialGradient(listOf(Color.Black.copy(alpha = .16f), Color.Transparent), center = Offset(cx, cy), radius = r), size = FULL)
    }
    // 懸浮微粒（海雪）：慢慢往下飄；近的大、亮、飄得快、視差大；落在光束裡的更亮
    for (p in 0 until 46) {
        val d = .2f + .8f * hash01(p, 5)
        val x = frac(hash01(p, 1) + t * .004f * (hash01(p, 6) - .5f)) * W * 1.2f - W * .1f + par * W * .12f * d
        val y = frac(hash01(p, 2) + t * (.006f + .014f * d)) * (H + 10f) - 5f
        val a = .1f + .28f * d * (1f - y / H * .6f)
        drawCircle(st.c(-8f, .3f, .8f, a), .4f + 1.1f * d, Offset(x, y))
    }
    vignette(.34f)
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

