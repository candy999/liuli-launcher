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
    /** 絲綢每條布的取樣點（避免每幀配置 ArrayList<Float>）。 */
    val silkX = FloatArray(SILK_N)
    val silkTop = FloatArray(SILK_N)
    val silkBot = FloatArray(SILK_N)
}

private val scratch: ThreadLocal<Scratch> = ThreadLocal.withInitial { Scratch() }

private fun hsl(h: Float, s: Float, l: Float, a: Float = 1f): Color =
    Color.hsl(((h % 360f) + 360f) % 360f, s.coerceIn(0f, 1f), l.coerceIn(0f, 1f), a.coerceIn(0f, 1f))

/** 以目前主色為中心取色；飽和度乘上主色飽和度，背景永遠是暗、同色系。 */
private fun BgState.c(hOff: Float, sat: Float, l: Float, a: Float = 1f) = hsl(h + hOff, sat * s * 1.6f, l, a)

fun DrawScope.drawStyle(style: BgStyle, st: BgState) {
    // 所有漸層開抖色（見 DitherScope）：8 bit 面板上的大面積暗色漸層不再一圈一圈斷開
    val sc = scratch.get()!!
    DitherScope(this, sc.ditherPaint, sc.ditherNoise, sc.ditherM, sc.ditherInv).drawStyleRaw(style, st)
}

private fun DrawScope.drawStyleRaw(style: BgStyle, st: BgState) {
    when (style) {
        BgStyle.WAVES -> waves(st)
        BgStyle.BOKEH -> bokeh(st)
        BgStyle.TRIANGLES -> triangles(st)
        BgStyle.DOTS -> dots(st)
        BgStyle.FLIP -> flip(st)
        BgStyle.SILK -> silk(st)
        BgStyle.TRAILS -> trails(st)
        BgStyle.CONTOUR -> contour(st)
        BgStyle.PAPER -> paper(st)
        BgStyle.AURORA -> aurora(st)
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

// ───────────── 2. 光斑 ─────────────

private class Bokeh(val x: Float, val y: Float, val r: Float, val d: Float, val h: Float, val ring: Boolean)

private val bokehs = Random(7).let { rnd ->
    List(34) { i -> Bokeh(rnd.nextFloat() * W, rnd.nextFloat() * H, 6 + rnd.nextFloat() * 34, .25f + rnd.nextFloat() * .75f, (rnd.nextFloat() - .5f) * 50, i % 4 == 0) }
}

private fun DrawScope.bokeh(st: BgState) {
    base(st)
    for (b in bokehs) {
        val c = Offset(b.x + (st.fx - .5f) * W * .3f * b.d, b.y + (st.fy - .5f) * H * .1f * b.d)
        val r = b.r * (1 + st.pulse * .35f * b.d)
        if (b.ring) {
            drawCircle(st.c(b.h, .9f, .7f, .18f * b.d), r, c, style = Stroke(1f), blendMode = BlendMode.Plus)
        } else {
            drawCircle(
                Brush.radialGradient(
                    0f to st.c(b.h, .9f, .6f, .32f * b.d), .7f to st.c(b.h, .9f, .55f, .12f * b.d), 1f to st.c(b.h, .9f, .5f, 0f),
                    center = c, radius = r,
                ),
                r, c, blendMode = BlendMode.Plus,
            )
        }
    }
    vignette(.4f)
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

// ───────────── 5. 翻牌方陣（色彩波從焦點擴散） ─────────────


private fun DrawScope.flip(st: BgState) {
    val sc = scratch.get()!!
    val sampleAL = sc.sampleA
    val sampleBL = sc.sampleB
    drawRect(Color(0xFF04050A), size = FULL)
    val sz = 30f; val gap = 2.5f; val lx = st.fx * W; val ly = st.fy * H
    var iy = 0; var y = -4f
    while (y < H + sz) {
        var ix = 0; var x = -8f
        while (x < W + sz) {
            val cx = x + sz / 2; val cy = y + sz / 2
            val d = hypot(cx - lx, cy - ly) / W
            val delay = d * 1100
            st.sampleAt(delay, sampleAL); st.sampleAt(delay + 160, sampleBL)
            val dh = abs(((sampleAL[0] - sampleBL[0] + 540f) % 360f) - 180f) + abs(sampleAL[1] - sampleBL[1]) * 120
            val flip = min(1f, dh / 9f)
            val wScale = max(.06f, cos(flip * 1.35f))
            val spot = exp(-d * d * 6)
            val checker = if ((ix + iy) % 2 == 1) .012f else 0f
            val l = .06f + .13f * spot + checker + (1 - wScale) * .1f
            val w = (sz - gap) * wScale; val h = sz - gap
            val hue = sampleAL[0]; val sat = sampleAL[1]
            drawRect(
                Brush.horizontalGradient(listOf(hsl(hue - 6, .75f * sat * 1.6f, l * .75f), hsl(hue + 8, .8f * sat * 1.6f, l * 1.15f)), cx - w / 2, cx + w / 2),
                Offset(cx - w / 2, cy - h / 2), Size(w, h),
            )
            if (spot > .2f) drawRect(Color.White.copy(alpha = .07f * spot), Offset(cx - w / 2, cy - h / 2), Size(w, 1f))
            ix++; x += sz
        }
        iy++; y += sz
    }
    vignette(.45f)
}

// ───────────── 6. 絲綢 ─────────────

private const val SILK_N = 80

private fun DrawScope.silk(st: BgState) {
    val sc = scratch.get()!!
    val pathL = sc.path
    val xsL = sc.silkX; val topsL = sc.silkTop; val botsL = sc.silkBot
    drawRect(Brush.linearGradient(listOf(st.c(-10f, .45f, .12f), st.c(12f, .5f, .2f)), Offset.Zero, Offset(W * .3f, H)), size = FULL)
    val lx = st.fx * W
    rotate(-6.9f, pivot = Offset(W / 2, H / 2)) {
        for (i in 0 until 5) {
            val yc = H * (-.02f + i * .23f)
            val a = H * (.05f + .015f * sin(i * 2.1f))
            val ph = st.fx * (1.8f + i * .35f) + i * 1.7f + st.pulse * .35f * sin(i.toFloat())
            val thick = H * (.27f + .04f * cos(i * 1.3f))
            pathL.reset()
            var n = 0
            var x = -60f
            val g2 = 2 * (W * .24f) * (W * .24f)
            while (x <= W + 60 && n < SILK_N) {
                val gather = exp(-(x - lx) * (x - lx) / g2)
                val y = yc + a * sin(x / W * 3.4f + ph) - H * .045f * gather
                xsL[n] = x; topsL[n] = y; botsL[n] = y + thick * (1 - .2f * gather)
                n++
                x += 8f
            }
            pathL.moveTo(xsL[0], topsL[0])
            for (k in 0 until n) pathL.lineTo(xsL[k], topsL[k])
            for (k in n - 1 downTo 0) pathL.lineTo(xsL[k], botsL[k])
            pathL.close()
            // 柔和陰影：往下錯開兩層半透明黑
            translate(0f, 3.5f) { drawPath(pathL, Color.Black.copy(alpha = .16f)) }
            drawPath(
                pathL,
                Brush.verticalGradient(
                    0f to st.c(i * 4 - 8f, .42f, .13f), .4f to st.c(i * 4 - 4f, .45f, .22f + .02f * i),
                    .52f to st.c(i * 4f, .38f, .33f + .015f * i), .64f to st.c(i * 4 + 4f, .45f, .21f + .015f * i), 1f to st.c(i * 4 + 8f, .42f, .13f),
                    startY = yc - a, endY = yc + thick + a,
                ),
            )
        }
    }
    focusGlow(st, W * .5f, .1f); vignette(.25f)
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

// ───────────── 10. 極光 ─────────────

private fun DrawScope.aurora(st: BgState) {
    drawRect(Brush.verticalGradient(listOf(st.c(-10f, .6f, .04f), st.c(10f, .7f, .11f)), 0f, H), size = FULL)
    val cfgs = arrayOf(floatArrayOf(0f, .3f, 1f), floatArrayOf(-38f, .38f, .8f), floatArrayOf(42f, .24f, .65f))
    for ((c, cfg) in cfgs.withIndex()) {
        val hc = cfg[0]; val baseY = H * cfg[1]; val amp = H * .07f; val shift = st.fx * 2.4f + c * 1.3f
        val brush = Brush.verticalGradient(
            0f to st.c(hc, .95f, .75f, 0f), .05f to st.c(hc, .95f, .68f, .55f), .3f to st.c(hc + 22, .9f, .5f, .22f), 1f to st.c(hc + 30, .9f, .45f, 0f),
            startY = 0f, endY = 1f,
        )
        var x = 0f
        while (x < W) {
            val yTop = baseY + amp * sin(x / W * 3.2f + shift) + amp * .4f * sin(x / W * 7.1f + c)
            val hgt = H * (.24f + .1f * sin(x / W * 5 + c * 2 + st.fx * 3)) * (1 + st.pulse * .12f)
            val s01 = .5f + .5f * sin(x * .09f + c * 3 + shift * 2)
            val alpha = cfg[2] * (.45f + .55f * s01 * s01)
            withTransform({ translate(x, yTop); scale(1f, hgt, pivot = Offset.Zero) }) {
                drawRect(brush, size = Size(5.2f, 1f), alpha = alpha, blendMode = BlendMode.Plus)
            }
            x += 5f
        }
    }
    vignette(.35f)
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

