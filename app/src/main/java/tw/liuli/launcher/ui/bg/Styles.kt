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
    /** 星座網的星點位置（每幀重算，不配置新陣列）。 */
    val netX = FloatArray(NET_N)
    val netY = FloatArray(NET_N)
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
        BgStyle.GALAXY -> galaxy(st)
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

// ───────────── 5. 星空銀河（斜跨的銀河、三層星星、緩慢漂移的星點之間時連時斷的連線） ─────────────

/** 由兩個整數得到 0～1 的固定亂數（同樣的輸入永遠同樣的值；主執行緒與背景執行緒畫出來一致）。 */
private fun hash01(a: Int, b: Int): Float {
    var x = a * 374761393 + b * 668265263
    x = (x xor (x ushr 13)) * 1274126177
    return ((x xor (x ushr 16)) and 0xFFFFFF) / 16777216f
}

private fun frac(v: Float) = v - kotlin.math.floor(v)

private const val NET_N = 42

private fun DrawScope.galaxy(st: BgState) {
    val t = st.at
    val par = -(st.fx - .5f)
    val parY = -(st.fy - .5f)
    // 深空：幾乎全黑，帶一點主色
    drawRect(Brush.linearGradient(listOf(st.c(-10f, .5f, .02f), st.c(14f, .6f, .055f)), Offset.Zero, Offset(W * .3f, H)), size = FULL)

    // 銀河：從左下斜到右上的一條光帶——幾團拉長的柔光沿著帶子排列（核心偏暖、外圍是主色），再壓上幾道暗塵帶
    val gx = par * W * .05f
    withTransform({ rotate(-24f, pivot = Offset(W / 2 + gx, H / 2)) }) {
        for (k in 0 until 9) {
            val u = k / 8f
            val cx = W * (-.1f + 1.2f * u) + gx
            val cy = H * .5f + 10f * sin(k * 1.9f)
            val core = exp(-((u - .55f) / .28f).pow(2))
            val rw = W * (.16f + .06f * hash01(k, 1))
            val rh = H * (.13f + .05f * hash01(k, 2) + .08f * core)
            val hOff = -20f + 40f * hash01(k, 3) + 30f * core
            val a = .11f + .14f * core
            drawOval(
                Brush.radialGradient(listOf(st.c(hOff, .55f, .62f, a), st.c(hOff, .6f, .5f, a * .35f), st.c(hOff, .6f, .45f, 0f)), center = Offset(cx, cy), radius = rw),
                topLeft = Offset(cx - rw, cy - rh), size = Size(rw * 2, rh * 2), blendMode = BlendMode.Plus,
            )
        }
        // 暗塵帶：沿著銀河中線斷斷續續
        for (k in 0 until 6) {
            val cx = W * (.05f + .18f * k) + gx + 10f * hash01(k, 7)
            val cy = H * .5f + 6f * sin(k * 2.3f) - 2f
            val rw = W * (.07f + .05f * hash01(k, 8)); val rh = H * (.025f + .02f * hash01(k, 9))
            drawOval(
                Brush.radialGradient(listOf(Color.Black.copy(alpha = .35f), Color.Transparent), center = Offset(cx, cy), radius = rw),
                topLeft = Offset(cx - rw, cy - rh), size = Size(rw * 2, rh * 2),
            )
        }
    }

    // 星星：三層景深。遠的又小又多、幾乎不動；近的較大、帶一圈柔光、視差較大；各自緩慢閃爍
    for (i in 0 until 230) {
        val d = hash01(i, 21).pow(2.4f) // 0＝最遠
        val k = .02f + .1f * d
        // 銀河帶附近星星比較密：一部分星星往帶子中線靠
        var x = hash01(i, 22) * W * 1.1f - W * .05f
        var y = hash01(i, 23) * H
        if (i % 3 == 0) {
            val band = H * .5f - (x - W / 2) * .445f // 和銀河同一條斜線
            y = band + (hash01(i, 24) - .5f) * H * .32f
        }
        x += par * W * k; y += parY * H * k * .5f
        val tw = .55f + .45f * sin(t * (.6f + 2.2f * hash01(i, 25)) + i * 3.1f)
        val a = (.25f + .6f * hash01(i, 26)) * tw * (.5f + .5f * d)
        val r = .35f + .9f * d + .4f * hash01(i, 27)
        val warm = (hash01(i, 28) - .5f) * 70f
        drawCircle(st.c(warm, .25f, .85f, a), r, Offset(x, y), blendMode = BlendMode.Plus)
        if (d > .45f) {
            drawCircle(
                Brush.radialGradient(listOf(st.c(warm, .4f, .75f, a * .35f), Color.Transparent), center = Offset(x, y), radius = r * 4.5f),
                r * 4.5f, Offset(x, y), blendMode = BlendMode.Plus,
            )
        }
    }

    // 星座網：一群緩慢漂移的星點，彼此靠近時連出細線、遠離時線條淡去——網子一直在重組
    val sc = scratch.get()!!
    val nx = sc.netX; val ny = sc.netY
    for (i in 0 until NET_N) {
        val ax = hash01(i, 41); val ay = hash01(i, 42)
        nx[i] = (ax * 1.2f - .1f) * W + 22f * sin(t * (.05f + .07f * hash01(i, 43)) + i * 1.7f) + par * W * .07f
        ny[i] = ay * H + 16f * sin(t * (.04f + .06f * hash01(i, 44)) + i * 2.3f) + parY * H * .035f
    }
    val maxD = W * .17f
    val lineW = .6f
    for (i in 0 until NET_N) {
        for (j in i + 1 until NET_N) {
            val dx = nx[i] - nx[j]; val dy = ny[i] - ny[j]
            if (abs(dx) > maxD || abs(dy) > maxD) continue
            val dist = sqrt(dx * dx + dy * dy)
            if (dist > maxD) continue
            // 越近越亮；再乘一個緩慢的波，讓線條時隱時現
            val wave = .5f + .5f * sin(t * .3f + (i * 7 + j * 13) * .37f)
            val a = (1f - dist / maxD).pow(1.5f) * .3f * wave
            if (a < .02f) continue
            drawLine(st.c(-6f, .5f, .75f, a), Offset(nx[i], ny[i]), Offset(nx[j], ny[j]), lineW, blendMode = BlendMode.Plus)
        }
    }
    for (i in 0 until NET_N) {
        val tw = .6f + .4f * sin(t * (.8f + hash01(i, 45)) + i)
        drawCircle(st.c(-6f, .35f, .85f, .55f * tw), .9f, Offset(nx[i], ny[i]), blendMode = BlendMode.Plus)
    }

    // 流星：偶爾劃過一道（約每 9～15 秒一顆）
    val period = 12f
    val ph = t / period
    val cyc = kotlin.math.floor(ph).toInt()
    val u = (ph - cyc) * period // 這一輪開始後的秒數
    if (u < .9f && hash01(cyc, 51) > .25f) {
        val p = u / .9f
        val x0 = W * (.2f + .6f * hash01(cyc, 52)); val y0 = H * (.05f + .3f * hash01(cyc, 53))
        val dxs = W * .28f; val dys = H * .16f
        val hx = x0 + dxs * p; val hy = y0 + dys * p
        val tail = .22f
        val fade = sin(p * PI.toFloat())
        drawLine(
            Brush.linearGradient(listOf(Color.Transparent, st.c(-6f, .3f, .9f, .7f * fade)), Offset(hx - dxs * tail, hy - dys * tail), Offset(hx, hy)),
            Offset(hx - dxs * tail, hy - dys * tail), Offset(hx, hy), 1.1f, blendMode = BlendMode.Plus,
        )
    }
    vignette(.4f)
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
    // 耶穌光（體積光）：從水面上方很遠處斜斜灑下的寬光帶，彼此錯落重疊；
    // 每一道「橫向」與「縱向」都柔和漸淡（兩個漸層相乘），完全沒有邊界——像透過霧氣看到的光
    val sx = W * .5f + par * W * .2f + (st.fx - .5f) * W * .25f
    val sy = -H * 1.1f
    for (i in 0 until 16) {
        val h1 = hash01(i, 11); val h2 = hash01(i, 12); val h3 = hash01(i, 13); val h4 = hash01(i, 14)
        // 角度：以畫面中央為主散開，再隨水面緩慢搖擺
        val deg = (-26f + 52f * (i + h1) / 16f) + 2.2f * sin(t * (.07f + .06f * h2) + i * 1.3f)
        // 寬度差很多：大多是寬而淡的光帶，少數窄而亮
        val w = 18f + 110f * h2 * h2
        val flicker = .55f + .45f * sin(t * (.25f + .45f * h3) + i * 2.7f) * sin(t * (.11f + .2f * h4) + i)
        val near = exp(-((sx + (H * .5f - sy) * kotlin.math.tan(deg * PI.toFloat() / 180f) - st.fx * W) / (W * .4f)).pow(2))
        val a = (.07f + .17f * (1f - h2) * h3 + .07f * near) * flicker
        if (a < .012f) continue
        val len = (H - sy) * 1.25f
        val col = st.c(-10f + 14f * (h4 - .5f), .42f, .78f)
        withTransform({ rotate(deg, pivot = Offset(sx, sy)) }) {
            drawRect(SoftBeamBrush(sx, w, sy, len, col, a), Offset(sx - w / 2, sy), Size(w, len), blendMode = BlendMode.Plus)
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

/**
 * 柔和光帶的著色器：橫向「透明 → 亮 → 透明」的漸層，乘上縱向「水面最亮、越深越淡」的漸層（DST_IN：用後者的透明度遮前者）。
 * 兩個方向都漸淡，所以光帶沒有任何清楚的邊。座標是光帶自己的（呼叫端已經旋轉好）。
 */
private class SoftBeamBrush(
    private val cx: Float, private val w: Float, private val top: Float, private val len: Float,
    private val color: Color, private val alpha: Float,
) : androidx.compose.ui.graphics.ShaderBrush() {
    override fun createShader(size: Size): android.graphics.Shader {
        val c = android.graphics.Color.argb((alpha.coerceIn(0f, 1f) * 255).toInt(), (color.red * 255).toInt(), (color.green * 255).toInt(), (color.blue * 255).toInt())
        val clear = c and 0x00FFFFFF
        val across = android.graphics.LinearGradient(
            cx - w / 2, 0f, cx + w / 2, 0f,
            intArrayOf(clear, c, clear), floatArrayOf(0f, .5f, 1f), android.graphics.Shader.TileMode.CLAMP,
        )
        // 縱向遮罩：光源到水面那段（約 42%）透明，進到水裡最亮，往下慢慢淡掉
        val along = android.graphics.LinearGradient(
            0f, top, 0f, top + len,
            intArrayOf(0, 0, 0xFF000000.toInt(), 0x66000000, 0), floatArrayOf(0f, .42f, .5f, .75f, 1f), android.graphics.Shader.TileMode.CLAMP,
        )
        return android.graphics.ComposeShader(across, along, android.graphics.PorterDuff.Mode.DST_IN)
    }
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

