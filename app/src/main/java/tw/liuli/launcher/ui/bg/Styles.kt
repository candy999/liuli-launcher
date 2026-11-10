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
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.nativeCanvas
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
    /** 批次畫星點用的畫筆（圓頭，約一個像素）。 */
    val starPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { strokeCap = android.graphics.Paint.Cap.ROUND }
    /** 星座網的星點位置（每幀重算，不配置新陣列）。 */
    val netX = FloatArray(NET_N)
    val netY = FloatArray(NET_N)
    val ditherNoise = DitherScope.newNoise()
    val ditherM = android.graphics.Matrix()
    val ditherInv = android.graphics.Matrix()
    val path = Path()
    /** 山稜：上色的畫筆與色彩矩陣、星軌的位置。 */
    val ridgePaint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG or android.graphics.Paint.ANTI_ALIAS_FLAG)
    val ridgeTint = RidgeTint()
    val ridgeRect = android.graphics.RectF()
    /** 雲頭的位置、半徑、高度（每格重算，不配置新陣列）。 */
    val billowX = FloatArray(64)
    val billowR = FloatArray(64)
    val billowY = FloatArray(64)
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
        BgStyle.RIDGE -> ridge(st)
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


/**
 * 高斯式的徑向漸層：透明度照 e^(-4.5·r²) 從中心往外衰減（不是線性），看起來是「暈開」的光，沒有圓的邊。
 * [color]：中心的顏色（含透明度）。
 */
private fun gaussBrush(color: Color, center: Offset, radius: Float): Brush {
    val stops = Array(7) { i ->
        val x = i / 6f
        x to color.copy(alpha = color.alpha * if (i == 6) 0f else exp(-4.5f * x * x))
    }
    return Brush.radialGradient(*stops, center = center, radius = radius)
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

// ───────────── 5. 星空銀河（星雲是無數細星點聚成的、暗塵帶切過；星星大多只有一個像素） ─────────────

/** 由兩個整數得到 0～1 的固定亂數（同樣的輸入永遠同樣的值；主執行緒與背景執行緒畫出來一致）。 */
private fun hash01(a: Int, b: Int): Float {
    var x = a * 374761393 + b * 668265263
    x = (x xor (x ushr 13)) * 1274126177
    return ((x xor (x ushr 16)) and 0xFFFFFF) / 16777216f
}

private fun frac(v: Float) = v - kotlin.math.floor(v)

private const val NET_N = 36
private const val BUCKETS = 4

/**
 * 預先算好的星點位置（只算一次）。
 * - [cloud]：銀河星雲，在「銀河自己的座標」裡（呼叫端旋轉），垂直方向呈常態分佈、越靠中線越密；分 4 個亮度組
 * - [field]：滿天的背景星，分 4 個亮度組
 * 用一次一組的批次畫點（drawPoints），兩千多顆星只要 8 次繪圖呼叫。
 */
private object StarField {
    val cloud: Array<FloatArray>
    val field: Array<FloatArray>

    init {
        val cl = Array(BUCKETS) { ArrayList<Float>() }
        for (i in 0 until 1900) {
            val x = hash01(i, 101) * W * 1.3f - W * .15f
            val g = hash01(i, 102) + hash01(i, 103) + hash01(i, 104) + hash01(i, 105) - 2f // 近似常態分佈
            // 帶子中段較厚、兩端較薄
            val u = (x + W * .15f) / (W * 1.3f)
            val thick = .1f + .07f * exp(-((u - .55f) / .25f).pow(2))
            // 和星雲貼圖同一條彎曲的中線（MilkyWay.build 的 bend）
            val ut = (x - W / 2) / (W * 1.15f) + .5f
            val y = H * .5f + 7f * sin(ut * 5.1f) + 4f * sin(ut * 11.3f + 1f) + g * H * thick
            // 越靠中線越可能是亮一點的星
            val b = (hash01(i, 106).pow(2.2f) * BUCKETS).toInt().coerceIn(0, BUCKETS - 1)
            cl[b] += x; cl[b] += y
        }
        cloud = Array(BUCKETS) { cl[it].toFloatArray() }
        val fl = Array(BUCKETS) { ArrayList<Float>() }
        for (i in 0 until 520) {
            val b = (hash01(i, 111).pow(2.6f) * BUCKETS).toInt().coerceIn(0, BUCKETS - 1)
            fl[b] += hash01(i, 112) * W * 1.1f - W * .05f
            fl[b] += hash01(i, 113) * H
        }
        field = Array(BUCKETS) { fl[it].toFloatArray() }
    }
}

/** 銀河星點各組的透明度：最暗那組不畫（太淡的點散在銀河裡像灰塵、像螢幕發霉），其餘稍微提亮。 */
private val CLOUD_ALPHA = floatArrayOf(0f, .24f, .36f, .56f)
private val FIELD_ALPHA = floatArrayOf(.18f, .3f, .48f, .75f)

private fun DrawScope.points(pts: FloatArray, paint: android.graphics.Paint) {
    drawContext.canvas.nativeCanvas.drawPoints(pts, paint)
}

private fun DrawScope.galaxy(st: BgState) {
    val t = st.at
    val sc = scratch.get()!!
    val paint = sc.starPaint
    val par = -(st.fx - .5f)
    val parY = -(st.fy - .5f)
    // 星點大小用「畫布像素」算：背景可能是 ÷1～÷5 的解析度，邏輯座標 1 單位可能是 1～4 個像素；
    // 星點固定約 1 個畫布像素，不會在高解析度時變成一顆顆大圓點
    val ppu = android.graphics.Matrix().let { m ->
        @Suppress("DEPRECATION") drawContext.canvas.nativeCanvas.getMatrix(m)
        m.mapRadius(1f).coerceAtLeast(.1f)
    }
    // 深空：幾乎全黑，帶一點主色
    drawRect(Brush.linearGradient(listOf(st.c(-10f, .5f, .018f), st.c(14f, .55f, .045f)), Offset.Zero, Offset(W * .3f, H)), size = FULL)

    // 銀河在很遠的地方：視差很小
    val gx = par * W * .02f
    val mw = MilkyWay.image
    if (mw != null) {
        // 程序化生成的星雲貼圖（碎形雲團＋暗塵帶，已經暈開）；顏色帶一點點主色
        val m = MilkyWay.MARGIN
        val tint = lerp(Color.White, st.c(0f, .5f, .72f), .3f)
        translate(gx, 0f) {
            drawImage(
                mw,
                dstOffset = IntOffset((-W * m).toInt(), (-H * m).toInt()),
                dstSize = IntSize((W * (1 + 2 * m)).toInt(), (H * (1 + 2 * m)).toInt()),
                blendMode = BlendMode.Plus,
                colorFilter = ColorFilter.tint(tint, BlendMode.Modulate),
            )
        }
    } else {
        // 貼圖還在生成（只有第一次、約 0.2 秒）：先用簡易的柔光頂著
        MilkyWay.request()
        withTransform({ rotate(-24f, pivot = Offset(W / 2 + gx, H / 2)) }) {
            translate(gx, 0f) {
                // 底光：低飽和、偏中性的寬柔光（中段核心微暖），只是淡淡地托住星雲
                for (k in 0 until 12) {
                    val u = k / 11f
                    val cx = W * (-.12f + 1.24f * u)
                    val core = exp(-((u - .55f) / .22f).pow(2))
                    val rw = W * (.16f + .05f * hash01(k, 1))
                    val a = .05f + .09f * core
                    withTransform({ scale(1f, .3f + .2f * core, pivot = Offset(cx, H * .5f)) }) {
                        drawRect(
                            gaussBrush(Color.hsl(32f + 10f * hash01(k, 3), .25f + .2f * core, .7f, a), Offset(cx, H * .5f), rw),
                            Offset(cx - rw, H * .5f - rw), Size(rw * 2, rw * 2), blendMode = BlendMode.Plus,
                        )
                    }
                }
                // 星雲的細星點在原生解析度另外畫（galaxyStars）：這裡只畫柔光與暗塵帶
                // 暗塵帶：沿著中線、彎彎曲曲斷斷續續的暗紋（高斯暈開）
                for (k in 0 until 14) {
                    val cx = W * (-.05f + .085f * k) + 6f * (hash01(k, 7) - .5f)
                    val cy = H * .5f + 7f * sin(k * 1.1f) + 3f * (hash01(k, 8) - .5f)
                    val rw = W * (.05f + .04f * hash01(k, 9))
                    withTransform({ scale(1f, .16f + .12f * hash01(k, 10), pivot = Offset(cx, cy)) }) {
                        drawRect(gaussBrush(Color.Black.copy(alpha = .5f), Offset(cx, cy), rw), Offset(cx - rw, cy - rw), Size(rw * 2, rw * 2))
                    }
                }
            }
        }


    }
    // 少數亮星的柔光（核心與所有星點在原生解析度另外畫）
    for (i in 0 until 14) {
        val x = hash01(i, 121) * W + par * W * .04f
        val y = hash01(i, 122) * H + parY * H * .02f
        val tw = .6f + .4f * sin(t * (.7f + 1.5f * hash01(i, 123)) + i * 2.3f)
        val warm = if (hash01(i, 124) > .6f) 30f else 215f
        drawRect(gaussBrush(Color.hsl(warm, .35f, .85f, .22f * tw), Offset(x, y), 2.6f), Offset(x - 2.6f, y - 2.6f), Size(5.2f, 5.2f), blendMode = BlendMode.Plus)
    }

    // 星座網：一群緩慢漂移的星點，彼此靠近時連出極細的線、遠離時淡去——網子一直在重組
    val nx = sc.netX; val ny = sc.netY
    for (i in 0 until NET_N) {
        nx[i] = (hash01(i, 41) * 1.2f - .1f) * W + 22f * sin(t * (.05f + .07f * hash01(i, 43)) + i * 1.7f) + par * W * .05f
        ny[i] = hash01(i, 42) * H + 16f * sin(t * (.04f + .06f * hash01(i, 44)) + i * 2.3f) + parY * H * .025f
    }
    val maxD = W * .17f
    for (i in 0 until NET_N) {
        for (j in i + 1 until NET_N) {
            val dx = nx[i] - nx[j]; val dy = ny[i] - ny[j]
            if (abs(dx) > maxD || abs(dy) > maxD) continue
            val dist = sqrt(dx * dx + dy * dy)
            if (dist > maxD) continue
            val wave = .5f + .5f * sin(t * .3f + (i * 7 + j * 13) * .37f)
            val a = (1f - dist / maxD).pow(1.6f) * .2f * wave
            if (a < .015f) continue
            drawLine(st.c(-6f, .35f, .8f, a), Offset(nx[i], ny[i]), Offset(nx[j], ny[j]), 1f / ppu, blendMode = BlendMode.Plus)
        }
    }
    for (i in 0 until NET_N) {
        val tw = .6f + .4f * sin(t * (.8f + hash01(i, 45)) + i)
        drawCircle(st.c(-6f, .25f, .9f, .6f * tw), .9f / ppu, Offset(nx[i], ny[i]), blendMode = BlendMode.Plus)
    }

    // 流星：偶爾劃過一道（約每 12 秒有四分之三的機會）
    val period = 12f
    val ph = t / period
    val cyc = kotlin.math.floor(ph).toInt()
    val u = (ph - cyc) * period
    if (u < .9f && hash01(cyc, 51) > .25f) {
        val p = u / .9f
        val x0 = W * (.2f + .6f * hash01(cyc, 52)); val y0 = H * (.05f + .3f * hash01(cyc, 53))
        val dxs = W * .28f; val dys = H * .16f
        val hx = x0 + dxs * p; val hy = y0 + dys * p
        val tail = .22f
        val fade = sin(p * PI.toFloat())
        drawLine(
            Brush.linearGradient(listOf(Color.Transparent, Color.White.copy(alpha = .7f * fade)), Offset(hx - dxs * tail, hy - dys * tail), Offset(hx, hy)),
            Offset(hx - dxs * tail, hy - dys * tail), Offset(hx, hy), .7f, blendMode = BlendMode.Plus,
        )
    }
    vignette(.35f)
}

/** 畫布上 1 個邏輯單位是幾個像素（星點要固定約 1 個像素，不隨背景解析度變大）。 */
private fun DrawScope.pixelsPerUnit(): Float {
    val m = android.graphics.Matrix()
    @Suppress("DEPRECATION") drawContext.canvas.nativeCanvas.getMatrix(m)
    return m.mapRadius(1f).coerceAtLeast(.1f)
}

/**
 * 星空銀河的星點，在「原生解析度」的畫布上畫（背景本身可能是 ÷2～÷5 再放大，一個像素的星會變成方塊）。
 * 星雲星點與滿天的星不會動、只有視差位移：呼叫端把它們畫成一張快取圖層、每格只移動圖層位置，
 * 不必每格重畫兩千多顆星（海信實測：每格重畫時按方向鍵中位數 32ms）。座標是 480×270 的邏輯座標。
 */
fun DrawScope.galaxyCloudStars() {
    val paint = scratch.get()!!.starPaint
    val ppu = pixelsPerUnit()
    withTransform({ rotate(-24f, pivot = Offset(W / 2, H / 2)) }) {
        paint.strokeWidth = 1.3f / ppu
        for (b in 0 until BUCKETS) {
            if (CLOUD_ALPHA[b] <= 0f) continue
            paint.color = Color.hsl(40f, .15f, .9f, CLOUD_ALPHA[b]).toArgb()
            points(StarField.cloud[b], paint)
        }
    }
}

fun DrawScope.galaxyFieldStars() {
    val paint = scratch.get()!!.starPaint
    paint.strokeWidth = 1.6f / pixelsPerUnit()
    for (b in 0 until BUCKETS) {
        paint.color = Color.hsl(220f, .1f, .92f, FIELD_ALPHA[b]).toArgb()
        points(StarField.field[b], paint)
    }
}

/** 會閃爍的亮星核心（只有十幾顆，每格重畫）。 */
fun DrawScope.galaxyBrightStars(st: BgState) {
    val t = st.at
    val par = -(st.fx - .5f)
    val parY = -(st.fy - .5f)
    val ppu = pixelsPerUnit()
    for (i in 0 until 14) {
        val x = hash01(i, 121) * W + par * W * .04f
        val y = hash01(i, 122) * H + parY * H * .02f
        val tw = .6f + .4f * sin(t * (.7f + 1.5f * hash01(i, 123)) + i * 2.3f)
        val warm = if (hash01(i, 124) > .6f) 30f else 215f
        drawCircle(Color.hsl(warm, .2f, .95f, .9f * tw), 1.4f / ppu, Offset(x, y), blendMode = BlendMode.Plus)
    }
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
    // 水面附近晃動的光：幾團很大、高斯暈開的柔光各自漂移、明暗起伏（水面起伏把光聚散），融在水裡、看不出形狀
    for (i in 0 until 7) {
        val cx = (hash01(i, 21) * 1.3f - .15f) * W + 30f * sin(t * (.11f + .08f * hash01(i, 22)) + i) + par * W * .06f
        val cy = H * (.0f + .14f * hash01(i, 23)) + 5f * sin(t * (.17f + .1f * hash01(i, 24)) + i * 1.3f)
        val r = 70f + 70f * hash01(i, 25)
        val a = .09f * (.45f + .55f * sin(t * (.35f + .4f * hash01(i, 26)) + i * 2f))
        if (a < .01f) continue
        withTransform({ scale(1f, .45f, pivot = Offset(cx, cy)) }) {
            drawRect(gaussBrush(st.c(-12f, .35f, .82f, a), Offset(cx, cy), r), Offset(cx - r, cy - r), Size(r * 2, r * 2), blendMode = BlendMode.Plus)
        }
    }
    // 緩慢飄過的陰影（水面的起伏遮住一部分光）
    for (i in 0 until 4) {
        val cx = frac(hash01(i, 31) + t * .006f * (1f + hash01(i, 32))) * W * 1.6f - W * .3f
        val cy = H * (.15f + .35f * hash01(i, 33))
        val r = W * (.18f + .12f * hash01(i, 34))
        drawRect(gaussBrush(Color.Black.copy(alpha = .18f), Offset(cx, cy), r), size = FULL)
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

// ───────────── 9. 山稜（玉山：向量分層、時光色彩、隨風流動的平塗雲海、深夜星軌） ─────────────

/** 天上幾顆亮星（黎明、暮色時才看得到；深夜改成星軌）。 */
private val ridgeStars = FloatArray(60 * 2).also { a ->
    for (i in 0 until 60) { a[i * 2] = hash01(i, 611) * W; a[i * 2 + 1] = hash01(i, 612).pow(1.4f) * H * .3f }
}

private fun fc(a: FloatArray, alpha: Float = 1f) = Color(a[0].coerceIn(0f, 1f), a[1].coerceIn(0f, 1f), a[2].coerceIn(0f, 1f), alpha.coerceIn(0f, 1f))
private fun mixF(a: FloatArray, b: FloatArray, s: Float) = FloatArray(3) { a[it] + (b[it] - a[it]) * s }

/** 山的色彩矩陣：A＝覆蓋、R＝受光、G＝遠近 → 陰影色＋（山嵐色－陰影色）×遠近＋光×受光。顏色沒變就沿用。 */
private class RidgeTint {
    private val last = FloatArray(9) { -1f }
    var filter: android.graphics.ColorFilter? = null
    fun get(body: FloatArray, haze: FloatArray, light: FloatArray): android.graphics.ColorFilter {
        val cur = floatArrayOf(body[0], body[1], body[2], haze[0], haze[1], haze[2], light[0], light[1], light[2])
        var same = filter != null
        for (i in 0 until 9) if (kotlin.math.abs(cur[i] - last[i]) > .002f) { same = false; break }
        if (same) return filter!!
        System.arraycopy(cur, 0, last, 0, 9)
        val m = floatArrayOf(
            light[0], haze[0] - body[0], 0f, 0f, 255f * body[0],
            light[1], haze[1] - body[1], 0f, 0f, 255f * body[1],
            light[2], haze[2] - body[2], 0f, 0f, 255f * body[2],
            0f, 0f, 0f, 1f, 0f,
        )
        return android.graphics.ColorMatrixColorFilter(m).also { filter = it }
    }
}

/**
 * 雲量場（0～1）：跟著風移動、也慢慢自己變化。濃的地方雲頭大、連成雲海；稀的地方雲頭縮到沒有、露出山谷。
 * [xw]＝隨風移動的座標（畫面座標減掉風吹過的距離）。
 */
private fun cloudDens(xw: Float, seed: Float, t: Float, amount: Float = .5f): Float {
    val f = .5f + .3f * sin(xw * .011f + seed * .7f + t * .013f) + .24f * sin(xw * .0201f + seed * 1.3f - t * .009f) + .14f * sin(xw * .041f + seed * 2.1f + t * .02f)
    val x = ((f - (1.02f - amount)) / .28f).coerceIn(0f, 1f)
    return x * x * (3 - 2 * x)
}

/**
 * 平塗雲海：一排隨風由左往右推移的雲頭（圓），三階平塗——雲頭向著太陽的那一邊最亮、中間階、下半部陰影。
 * [base]＝雲海平面的高度、[r0]＝雲頭的大小、[spd]＝風速（邏輯像素／秒）、[dk]＝這一層的視差、[fill]＝雲海一路填到畫面底
 * （夾在山層之間，被前面的稜線擋住＝雲穿過山脈）。
 */
private fun DrawScope.billowDeck(
    base: Float, r0: Float, spd: Float, dk: Float, seed: Int, fill: Boolean, small: Boolean,
    t: Float, par: Float, sunDir: Float, lit: Color, mid: Color, shade: Color, bx: FloatArray, br: FloatArray, by: FloatArray,
) {
    val span = W + 4 * r0; val sp = r0 * 1.15f
    val n = minOf(bx.size, kotlin.math.ceil(span / sp).toInt())
    val px = par * W * dk
    var m = 0
    for (i in 0 until n) {
        val ph = i * sp + t * spd + seed * 37.1f
        val wrap = kotlin.math.floor(ph / span).toInt()
        val x = ph - wrap * span - 2 * r0 + px
        val id = i + wrap * n
        val d = cloudDens(x - px - t * spd, seed.toFloat(), t)
        if (d < .04f) continue
        val r = r0 * (.55f + .45f * hash01(id, seed)) * (.3f + .7f * d)
        bx[m] = x; br[m] = r; by[m] = base - r * (.2f + .35f * hash01(id, seed + 7)) - 5f * d
        m++
    }
    val bottom = if (fill) H + 2f else base + r0 * .55f
    // 1. 雲海本體（雲頭下方）與下半部的陰影
    for (j in 0 until m) drawRect(mid, Offset(bx[j] - br[j] * 1.05f, by[j]), Size(br[j] * 2.1f, bottom - by[j]))
    if (fill) for (j in 0 until m) {
        val sy = base + r0 * .6f
        if (sy < bottom) drawRect(shade, Offset(bx[j] - br[j] * 1.05f, sy), Size(br[j] * 2.1f, bottom - sy))
    }
    // 2. 雲頭（中間階）
    for (j in 0 until m) {
        drawCircle(mid, br[j], Offset(bx[j], by[j]))
        if (small) {
            drawCircle(mid, br[j] * .45f, Offset(bx[j] - br[j] * .65f, by[j] + br[j] * .2f))
            drawCircle(mid, br[j] * .42f, Offset(bx[j] + br[j] * .62f, by[j] + br[j] * .22f))
        }
    }
    // 3. 向陽的受光面：比雲頭小一點、往太陽和上方偏，留下背光那一側一彎中間階
    for (j in 0 until m) {
        drawCircle(lit, br[j] * .8f, Offset(bx[j] + sunDir * br[j] * .18f, by[j] - br[j] * .16f))
        if (small) {
            drawCircle(lit, br[j] * .34f, Offset(bx[j] - br[j] * .65f + sunDir * br[j] * .08f, by[j] + br[j] * .12f))
            drawCircle(lit, br[j] * .32f, Offset(bx[j] + br[j] * .62f + sunDir * br[j] * .08f, by[j] + br[j] * .14f))
        }
    }
}

private fun DrawScope.ridge(st: BgState) {
    val sc = scratch.get()!!
    val t = st.at
    val par = -(st.fx - .5f)
    val parY = -(st.fy - .5f)
    val (sun, pal) = TimeOfDay.now(System.currentTimeMillis())
    val D = .92f
    fun dim(a: FloatArray) = FloatArray(3) { a[it] * D }
    val body = dim(pal.body); val haze = dim(pal.haze)
    val midTone = mixF(body, haze, .55f)
    val litC = dim(pal.lit)
    val light = FloatArray(3) { (litC[it] - midTone[it]) * pal.litK }
    val cLit = dim(pal.cLit); val cShade = dim(pal.cShade)
    val nightK = ((pal.stars - .5f) / .5f).coerceIn(0f, 1f)

    // 天空：頂 → 中 → 地平線，地平線以下接遠山的山嵐色
    drawRect(Brush.verticalGradient(0f to fc(dim(pal.skyTop)), .58f to fc(dim(pal.skyMid)), 1f to fc(dim(pal.skyHor)), startY = 0f, endY = H * .3f), size = Size(W, H * .3f))
    drawRect(Brush.verticalGradient(0f to fc(dim(pal.skyHor)), .15f to fc(haze), 1f to fc(haze), startY = H * .3f, endY = H), Offset(0f, H * .3f), Size(W, H * .7f))
    val canvas = drawContext.canvas.nativeCanvas
    // 深夜：同心圓星軌，繞著山後面的南天極慢慢轉（先畫好的一張圖，每格只旋轉）
    if (nightK > .01f) {
        val tr = YushanRidge.trails
        if (tr == null) YushanRidge.requestTrails()
        else {
            val tp = sc.ridgePaint
            tp.colorFilter = null; tp.alpha = (nightK * .95f * 255).toInt()
            canvas.save()
            canvas.translate(par * W * .004f, 0f)
            canvas.rotate(t * YushanRidge.TRAIL_DEG_PER_SEC, YushanRidge.POLE_X, YushanRidge.POLE_Y)
            YushanRidge.trailRect(sc.ridgeRect)
            canvas.drawBitmap(tr, null, sc.ridgeRect, tp)
            canvas.restore()
            tp.alpha = 255
        }
    }
    // 黎明、暮色：剩幾顆亮星
    val dotA = pal.stars * (1 - nightK)
    if (dotA > .01f) {
        val paint = sc.starPaint
        paint.strokeWidth = .7f
        paint.color = Color(.88f, .92f, 1f, (dotA * .8f).coerceIn(0f, 1f)).toArgb()
        translate(par * W * .003f, 0f) { points(ridgeStars, paint) }
    }
    // 日、月的光暈：上午在東（左）、下午在西（右）；越低越貼近地平線、越大越暖。夜裡是地平線一抹暖光
    val day = sun.elev > -6f
    val frac01 = ((sun.tst - 360f) / 720f).coerceIn(0f, 1f)
    val gx = if (day) W * (.06f + .88f * frac01) else W * .3f
    val gy = if (day) H * (.3f - .26f * (sun.elev / 50f).coerceIn(0f, 1f)) else H * .28f
    val glow = dim(pal.glow)
    withTransform({ scale(1f, if (day) .55f else .2f, pivot = Offset(gx, gy)) }) {
        val rr = W * (if (day) .75f else .6f)
        drawRect(gaussBrush(fc(glow, pal.glowA), Offset(gx, gy), rr), Offset(gx - rr, gy - rr), Size(rr * 2, rr * 2), blendMode = BlendMode.Plus)
    }
    withTransform({ scale(1f, .07f, pivot = Offset(gx, H * .29f)) }) {
        val rr = W * .9f
        drawRect(gaussBrush(fc(glow, pal.glowA * .8f), Offset(gx, H * .29f), rr), Offset(gx - rr, H * .29f - rr), Size(rr * 2, rr * 2), blendMode = BlendMode.Plus)
    }
    val sunDir = if (gx < W / 2) -1f else 1f
    val hiMid = fc(mixF(cLit, cShade, .3f), .55f); val hiLit = fc(cLit, .6f)
    // 高空的卷雲：平塗的細長流線（上半亮、下半略暗），各自緩慢往右飄
    for (i in 0 until 7) {
        val cx = frac(hash01(i, 901) + t * (.0015f + .0025f * hash01(i, 902))) * W * 1.6f - W * .3f + par * W * .003f
        val cy = H * (.02f + .2f * hash01(i, 903))
        val len = 40f + 90f * hash01(i, 905); val th = 1.4f + 2.2f * hash01(i, 906)
        drawOval(hiMid, Offset(cx - len, cy - th), Size(len * 2, th * 2))
        drawOval(hiLit, Offset(cx - len * .85f, cy - th * 1.15f), Size(len * 1.7f, th * 1.3f))
    }

    val layers = YushanRidge.layers
    if (layers == null) { YushanRidge.request(); vignette(.2f); return }
    fun topOf(name: String) = layers.firstOrNull { it.name == name }?.topY ?: H * .6f
    val lit = fc(cLit); val mid = fc(mixF(cLit, cShade, .35f)); val shade = fc(mixF(cLit, cShade, .8f))
    val farLit = fc(mixF(cLit, haze, .3f)); val farMid = fc(mixF(mixF(cLit, cShade, .35f), haze, .35f)); val farShade = fc(mixF(mixF(cLit, cShade, .8f), haze, .4f))
    val bx = sc.billowX; val br = sc.billowR; val by = sc.billowY
    // 地平線上、遠山背後的積雲
    billowDeck(topOf("遠山") - 4f, 7f, .5f, .004f, 3, false, true, t, par, sunDir, farLit, farMid, farShade, bx, br, by)
    val tp = sc.ridgePaint
    tp.colorFilter = sc.ridgeTint.get(body, haze, light)
    val ox = par * W; val oy = parY * H * .35f
    for (p in layers) {
        // 整層一起平移（視差），不變形
        p.dst.set(p.x0 + ox * p.k, p.y0 + oy * p.k, p.x0 + ox * p.k + p.w, p.y0 + oy * p.k + p.h)
        canvas.drawBitmap(p.image, null, p.dst, tp)
        // 雲海夾在山層之間：風由左往右吹，越近的雲流得越快、雲頭越大
        when (p.name) {
            "遠山" -> billowDeck(p.topY + 6f, 9f, 1f, .006f, 4, true, true, t, par, sunDir, farLit, farMid, farShade, bx, br, by)
            "主峰山體" -> billowDeck(topOf("右側稜") - 14f, 15f, 1.7f, .03f, 5, true, true, t, par, sunDir, lit, mid, shade, bx, br, by)
            "右側稜" -> billowDeck(topOf("近稜（北峰→風口）") - 8f, 14f, 2.2f, .045f, 6, true, true, t, par, sunDir, lit, mid, shade, bx, br, by)
            "近稜（北峰→風口）" -> billowDeck(topOf("近稜下方的稜") - 8f, 13f, 2.8f, .06f, 7, true, false, t, par, sunDir, lit, mid, shade, bx, br, by)
            "近稜下方的稜" -> billowDeck(topOf("最前面的稜") - 8f, 12f, 3.3f, .08f, 9, false, false, t, par, sunDir, lit, mid, shade, bx, br, by)
        }
        tp.colorFilter = sc.ridgeTint.get(body, haze, light)
    }
    vignette(.16f + .1f * nightK)
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

