package tw.liuli.launcher.ui.bg

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 用 CPU 算形狀、畫成向量路徑的樣式：螺旋、特魯謝緞帶、三色濃醬（只有這個版本，GPU 版已拿掉）。
 * 形狀在 CPU 算成向量路徑，由 Compose 的畫布（硬體加速）填色——邊緣由路徑反鋸齒，任何解析度都銳利，不受半精度影響。
 * 座標是邏輯畫布 [w]×[h]（480×270，見 [BackgroundEngine.LOGICAL_W]），畫布會再縮放到實際解析度。
 * 特魯謝緞帶的外觀（緞帶的外緣、亮面與兩條細線）參照 Subframe 的 truchet-ribbon（MIT License，全文見 assets/licenses/subframe-LICENSE.txt）。
 *
 * 成本的做法：
 * - 螺旋：整條畫成一張圓形遮罩（背景執行緒），每格只旋轉、平移。
 * - 特魯謝緞帶：整片畫成三張遮罩（背景執行緒），每格只平移疊上。
 * - 三色濃醬：九條交界線每格重建（約七百個點），停穩之後引擎不再重畫。
 * - 玻璃小圖（120×68、之後還要模糊）一律畫簡化版。
 */
internal object CpuLines {
    /** 每條執行緒各一份（主畫面在主執行緒畫、玻璃小圖在背景執行緒畫，路徑不能共用）。 */
    private class Scratch {
        var spiralPath: Path? = null
        var spiralUnit = 0f
        var spiralGlass: Path? = null
        var spiralGlassUnit = 0f
        val tRibbon = Path(); val tFineA = Path(); val tFineB = Path()
        var tKey = Long.MIN_VALUE
        // 特魯謝緞帶描邊後的填色外框（捲過一格或寬度換檔才重建）
        var tRibbonFill: Path? = null; var tInnerFill: Path? = null; var tFineAFill: Path? = null; var tFineBFill: Path? = null
        val strokePaint = android.graphics.Paint().apply { style = android.graphics.Paint.Style.STROKE; strokeCap = android.graphics.Paint.Cap.BUTT; strokeJoin = android.graphics.Paint.Join.ROUND }
        /** 緞帶整片畫好的遮罩（見 [TMask]）；背景執行緒畫好才換上。 */
        @Volatile var tMask: TMask? = null
        @Volatile var tBuilding = false
        /** 螺旋整條畫好的圓形遮罩（見 [SMask]）。 */
        @Volatile var sMask: SMask? = null
        @Volatile var sBuilding = false
        val tmp = FloatArray(64)
        val swirlPaths = arrayOf(Path(), Path(), Path())
        val curve = FloatArray(GlStyles.Sauce.WEDGES * SW_PTS * 2)
    }
    private val scratch: ThreadLocal<Scratch> = ThreadLocal.withInitial { Scratch() }

    private fun Color.toned(): Color {
        // 和 GPU 版的高光壓縮一樣（[GlBackground.TONE]）：亮度超過 0.55 的部分壓扁
        val tl = .299f * red + .587f * green + .114f * blue
        if (tl <= .55f) return this
        val k = (.55f + (tl - .55f) * .45f) / tl
        return Color(red * k, green * k, blue * k, alpha)
    }

    private fun col(st: BgState, dh: Float, sat: Float, l: Float) = GlStyles.hsl(st, dh, sat, l).toned()
    private fun col(a: FloatArray, i: Int) = Color(a[i], a[i + 1], a[i + 2]).toned()

    /**
     * 把線描邊後的形狀轉成「填色的外框」（只在建路徑時做一次）。
     * 描邊每一格都要重新算外框（線越長越貴：螺旋八千個點、緞帶兩百段圓弧，海信上畫面執行緒每秒忙 0.8 秒）；
     * 轉成填色外框後，路徑本身不變、每格只平移旋轉，畫布可以沿用快取。
     */
    private fun Scratch.toFill(path: Path, width: Float): Path {
        strokePaint.strokeWidth = width
        val out = android.graphics.Path()
        strokePaint.getFillPath(path.asAndroidPath(), out)
        return out.asComposePath()
    }

    // ───────────── 螺旋 ─────────────

    /**
     * 這一次是在畫玻璃小圖（120×68，之後還要模糊）：CPU 版的樣式用簡化的畫法——軟體畫布上畫完整細節很貴
     * （螺旋一次 47ms，玻璃只剩每秒 17 次、背景執行緒吃掉 0.85 個核心），小圖模糊後也看不出差別。
     */
    private fun DrawScope.forGlass() = size.width < 200f

    /**
     * 螺旋整條畫成一張圓形遮罩（只有透明度），之後每格只旋轉、平移：以前每格把上萬個點的外框重新塗一次
     * （畫布的工作執行緒每秒忙 0.75 秒）。遮罩半徑涵蓋畫面對角線（焦點在哪裡都夠），解析度上限每個邏輯單位 2.5 像素
     * （約 2.8K 見方、7.6MB）：原生解析度時邊緣比直接畫稍軟，仍比 GPU 版的 960 銳利。在背景執行緒畫，畫好才換上。
     */
    private class SMask(val u: Float, val scale: Float, val r: Float, val img: ImageBitmap)

    /**
     * 螺旋條紋的實際形狀：中心線 r＝u·s／2π（角度 −s），條紋是中心線內外各 ¼ 圈（u／4）圍成的帶——
     * 外緣 r＝u(s／2π＋¼)（從 s＝−π／2 的中心開始）、內緣 r＝u(s／2π−¼)（到 s＝π／2 回到中心）：兩條邊在中心接成一條平滑的 S 形，
     * 和 GPU 版的條紋（stripe 的一半）一模一樣。以前外緣從 s＝0（離中心 ¼ 圈）開始、用直線連回中心，精簡模式看得到那條直線；
     * 更早用粗線沿中心線描，中心那圈的半徑比線寬小，線自己疊在一起。
     * 每段弧長最多 [seg] 個邏輯單位、角度一步最多 0.04 弧度（中心轉得急，靠角度限制取樣夠密）。
     */
    private fun spiralBand(u: Float, rMax: Float, seg: Double): android.graphics.Path {
        val p = android.graphics.Path()
        val sEnd = 2 * PI * (rMax / u + .25)
        val q = u / (2 * PI)
        p.moveTo(0f, 0f)
        var t = -PI / 2
        while (true) {
            val r = u * .25 + q * t
            p.lineTo((r * cos(-t)).toFloat(), (r * sin(-t)).toFloat())
            if (t >= sEnd) break
            t = min(sEnd, t + min(.04, seg / max(r, 1.0)))
        }
        t = sEnd
        while (t > PI / 2) {
            val r = q * t - u * .25
            p.lineTo((r * cos(-t)).toFloat(), (r * sin(-t)).toFloat())
            t -= min(.04, seg / max(r, 1.0))
        }
        p.close()
        return p
    }

    private fun buildSpiralMask(sc: Scratch, u: Float, r: Float, scale: Float) {
        sc.sBuilding = true
        maskExec.execute {
            try {
                val size = kotlin.math.ceil(2 * r * scale).toInt()
                val b = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ALPHA_8)
                val c = android.graphics.Canvas(b)
                c.scale(scale, scale); c.translate(r, r)
                c.drawPath(spiralBand(u, r, 1.0), android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG))
                sc.sMask = SMask(u, scale, r, b.asImageBitmap())
            } catch (_: Throwable) {
            } finally { sc.sBuilding = false }
        }
    }

    fun DrawScope.spiral(st: BgState, w: Float, h: Float) {
        val sc = scratch.get()!!
        val u = h / 5.6f   // 一圈的間距＝畫面高度 ÷ 5.6，和 GPU 版一樣
        drawRect(col(st, 0f, .85f, .09f), size = Size(w, h))
        val phase0 = ((st.at * .2 + st.ripple * .8) % (2 * PI)) / (2 * PI)
        if (!forGlass()) {
            val scale = min(size.width / w, 2.5f)
            val m = sc.sMask
            if ((m == null || m.u != u || kotlin.math.abs(m.scale - scale) > scale * .05f) && !sc.sBuilding)
                buildSpiralMask(sc, u, kotlin.math.hypot(w, h), scale)
            if (m != null && m.u == u) {
                withTransform({
                    translate(st.fx * w, st.fy * h)
                    rotate((phase0 * 360.0).toFloat(), Offset.Zero)
                    scale(1f / m.scale, 1f / m.scale, Offset.Zero)
                }) {
                    drawImage(m.img, topLeft = Offset(-m.r * m.scale, -m.r * m.scale), colorFilter = ColorFilter.tint(col(st, 15f, .75f, .50f), BlendMode.SrcIn))
                }
                return
            }
        }
        if (forGlass()) {
            // 玻璃小圖：每段 12 個邏輯單位（小圖上約 3 像素）、直接描邊
            val gp = sc.spiralGlass.takeIf { sc.spiralGlassUnit == u }
                ?: spiralBand(u, kotlin.math.hypot(w, h), 12.0).asComposePath().also { sc.spiralGlass = it; sc.spiralGlassUnit = u }
            withTransform({ translate(st.fx * w, st.fy * h); rotate((phase0 * 360.0).toFloat(), Offset.Zero) }) {
                drawPath(gp, col(st, 15f, .75f, .50f))
            }
            return
        }
        // 阿基米德螺旋 r＝u·(−α／2π)：每轉一圈往外一個 u，線寬半個 u（GPU 版 stripe 的一半）。
        // 固定的路徑：相位＝整條旋轉、焦點＝平移，所以只在第一次（或畫布比例變了）建一次——而且直接存成描邊後的填色外框
        // 遮罩還沒畫好（剛切過來）：直接填條紋的形狀
        val path = sc.spiralPath.takeIf { sc.spiralUnit == u }
            ?: spiralBand(u, kotlin.math.hypot(w, h), 3.0).asComposePath().also { sc.spiralPath = it; sc.spiralUnit = u }
        val phase = ((st.at * .2 + st.ripple * .8) % (2 * PI)) / (2 * PI)
        withTransform({
            translate(st.fx * w, st.fy * h)
            rotate((phase * 360.0).toFloat(), Offset.Zero)
        }) {
            drawPath(path, col(st, 15f, .75f, .50f))
        }
    }

    // ───────────── 特魯謝緞帶 ─────────────

    /** 每格的翻轉（固定的雜湊，64 格一循環，和 GPU 版的雜訊貼圖一樣會重複）。 */
    private fun flip(i: Int, j: Int): Boolean {
        val x = Math.floorMod(i, 64); val y = Math.floorMod(j, 64)
        var n = x * 374761393 + y * 668265263
        n = (n xor (n ushr 13)) * 1274126177
        return (n xor (n ushr 16)) and 1 == 1
    }

    /**
     * 緞帶整片畫成遮罩（只有透明度），之後每格只平移：緞帶整片只是往旁邊捲，形狀不變。
     * 以前每格把兩百段圓弧的外框重新塗一次（畫布的工作執行緒每秒忙 1.2 秒）；現在每格只是疊三張圖。
     * 三張：緞帶外緣、緞帶本體、兩條細線（顏色在畫的時候才上，主色變了不用重畫）。左右各多留 [T_MARGIN] 格，
     * 捲超過一半就在背景執行緒畫下一份，畫好才換上，主執行緒不等。
     */
    private class TMask(val iStart: Int, val cols: Int, val scale: Float, val cell: Float, val masks: Array<ImageBitmap>)

    private const val T_MARGIN = 4
    /** CPU 版的緞帶半寬（格子單位）：不跟焦點脈動變粗（一變遮罩就要重畫）。 */
    private const val T_WIDTH = .085f + .35f * .19f
    private val maskExec = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "liuli-mask").apply { isDaemon = true } }

    /** 緞帶的圓弧：iStart 那一格的左邊是 x＝0、畫面正中是 y＝0，cols 格寬、上下 half 格。回傳四條中心線（緞帶、細線內、細線外）。 */
    private fun truchetArcs(iStart: Int, cols: Int, half: Int, cell: Float, width: Float): Array<Path> {
        val rib = Path(); val fa = Path(); val fb = Path()
        for (i in iStart until iStart + cols) for (j in -half - 1..half) {
            val fl = flip(i, j)
            // 兩個四分之一圓（半徑 0.5 格）：不翻＝圓心在左上、右下角；翻＝右上、左下角
            val cx1 = if (fl) i + 1 else i; val cy1 = j
            val cx2 = if (fl) i else i + 1; val cy2 = j + 1
            val s1 = if (fl) 90f else 0f; val s2 = if (fl) 270f else 180f
            for ((cx, cy, st) in arrayOf(Triple(cx1, cy1, s1), Triple(cx2, cy2, s2))) {
                fun arc(p: Path, r: Float) {
                    val x = (cx - iStart) * cell; val y = cy * cell
                    p.arcTo(Rect(x - r * cell, y - r * cell, x + r * cell, y + r * cell), st, 90f, true)
                }
                arc(rib, .5f); arc(fa, .5f - .68f * width); arc(fb, .5f + .68f * width)
            }
        }
        return arrayOf(rib, fa, fb)
    }

    /** 在背景執行緒畫遮罩（ALPHA_8）。寬度上限 4000 像素（Mali-400 的貼圖最大 4096）。 */
    private fun buildMask(sc: Scratch, iStart: Int, cols: Int, half: Int, cell: Float, h: Float, scale0: Float) {
        sc.tBuilding = true
        maskExec.execute {
            try {
                val scale = min(scale0, 4000f / (cols * cell))
                val bw = kotlin.math.ceil(cols * cell * scale).toInt(); val bh = kotlin.math.ceil(h * scale).toInt()
                val arcs = truchetArcs(iStart, cols, half, cell, T_WIDTH)
                val fineW = max(.012f, 1.5f * 7f / 1080f) * cell * 2f
                val stroke = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    style = android.graphics.Paint.Style.STROKE; strokeCap = android.graphics.Paint.Cap.BUTT
                }
                fun mask(vararg lines: Pair<Path, Float>): ImageBitmap {
                    val b = android.graphics.Bitmap.createBitmap(bw, bh, android.graphics.Bitmap.Config.ALPHA_8)
                    val c = android.graphics.Canvas(b)
                    c.scale(scale, scale); c.translate(0f, h / 2)
                    for ((line, wd) in lines) { stroke.strokeWidth = wd; c.drawPath(line.asAndroidPath(), stroke) }
                    return b.asImageBitmap()
                }
                val m = TMask(iStart, cols, scale, cell, arrayOf(
                    mask(arcs[0] to 2f * T_WIDTH * cell),
                    mask(arcs[0] to 2f * .655f * T_WIDTH * cell),
                    mask(arcs[1] to fineW, arcs[2] to fineW),
                ))
                sc.tMask = m
            } catch (_: Throwable) {
            } finally { sc.tBuilding = false }
        }
    }

    fun DrawScope.truchet(st: BgState, w: Float, h: Float) {
        val sc = scratch.get()!!
        val tRibbon = sc.tRibbon; val tFineA = sc.tFineA; val tFineB = sc.tFineB
        val cell = h / 7f
        val t = (st.at * .055).toDouble()
        val width = T_WIDTH
        val bg = col(st, 0f, .75f, .13f); val rib = col(st, 40f, .85f, .48f)
        val edge = col(st, 20f, .8f, .38f); val fine = col(st, 0f, .8f, .24f)
        drawRect(bg, size = Size(w, h))
        val i0 = floor(t - w / 2 / cell).toInt() - 1
        val half = (h / 2 / cell).toInt() + 1
        val visCols = (w / cell).toInt() + 4
        val fc = lerp(rib, fine, .42f)
        if (!forGlass()) {
            // 遮罩：涵蓋畫面就直接平移疊上；捲超過一半、解析度變了就在背景畫下一份
            val scale = size.width / w
            val m = sc.tMask
            val off = if (m == null) -1 else i0 - m.iStart
            val stale = m == null || m.cell != cell || kotlin.math.abs(m.scale - min(scale, 4000f / (m.cols * cell))) > m.scale * .05f ||
                off < T_MARGIN / 2 || off > T_MARGIN + T_MARGIN / 2
            if (stale && !sc.tBuilding) buildMask(sc, i0 - T_MARGIN, visCols + 2 * T_MARGIN, half, cell, h, scale)
            if (m != null && m.cell == cell && off >= 0 && off + visCols <= m.cols) {
                val ox = w / 2 + ((m.iStart - t) * cell).toFloat()
                withTransform({ translate(ox, 0f); scale(1f / m.scale, 1f / m.scale, Offset.Zero) }) {
                    drawImage(m.masks[0], colorFilter = ColorFilter.tint(lerp(rib, edge, .38f), BlendMode.SrcIn))
                    drawImage(m.masks[1], colorFilter = ColorFilter.tint(rib, BlendMode.SrcIn))
                    drawImage(m.masks[2], colorFilter = ColorFilter.tint(fc, BlendMode.SrcIn))
                }
                return
            }
        }
        // 還沒有遮罩（剛切過來）或玻璃小圖：直接畫外框（捲到下一格才重建）
        val fineW = max(.012f, 1.5f * 7f / 1080f) * cell * 2f
        val key = i0.toLong() * 4096 + (width * 4000).toInt()
        if (key != sc.tKey || sc.tRibbonFill == null) {
            sc.tKey = key
            val arcs = truchetArcs(i0, visCols, half, cell, width)
            tRibbon.reset(); tFineA.reset(); tFineB.reset()
            tRibbon.addPath(arcs[0]); tFineA.addPath(arcs[1]); tFineB.addPath(arcs[2])
            sc.tRibbonFill = sc.toFill(tRibbon, 2f * width * cell)
            sc.tInnerFill = sc.toFill(tRibbon, 2f * .655f * width * cell)
            sc.tFineAFill = sc.toFill(tFineA, fineW)
            sc.tFineBFill = sc.toFill(tFineB, fineW)
        }
        withTransform({ translate(w / 2 - ((t - i0) * cell).toFloat(), h / 2) }) {
            // 玻璃小圖：只畫緞帶本體（亮面與細線在 120×68 上看不到，模糊後更看不出來）
            if (forGlass()) { drawPath(sc.tRibbonFill!!, lerp(rib, edge, .2f)); return@withTransform }
            // 緞帶：外緣帶一點外緣色（GPU 版的 ridge 以外 ×0.38），中間是緞帶色
            drawPath(sc.tRibbonFill!!, lerp(rib, edge, .38f))
            drawPath(sc.tInnerFill!!, rib)
            // 兩條細線（離中心 0.68 個半寬）
            drawPath(sc.tFineAFill!!, fc)
            drawPath(sc.tFineBFill!!, fc)
        }
    }

    // ───────────── 漩渦（三色九瓣被攪拌捲進中心） ─────────────

    /** 每條交界線取幾個點（中心附近密、外面疏）。 */
    private const val SW_PTS = 40

    /**
     * 九條交界線各自的「原始角度」（流體沒被捲之前，以攪拌中心為圓心的角度）：
     * GPU 版的瓣座標 t＝(9θ＋1.5·sin(2θ＋0.7))／2π，交界在 t 為整數的地方；t 對 θ 單調遞增，用牛頓法各解一次。
     * [boundM] 是那條交界線的 t（決定兩側的顏色）。
     */
    private val boundTheta = DoubleArray(GlStyles.Sauce.WEDGES)
    private val boundM = IntArray(GlStyles.Sauce.WEDGES)

    init {
        val n = GlStyles.Sauce.WEDGES.toDouble(); val wv = GlStyles.Sauce.WAVE.toDouble()
        fun t(th: Double) = (n * th + wv * sin(2 * th + .7)) / (2 * PI)
        fun dt(th: Double) = (n + 2 * wv * cos(2 * th + .7)) / (2 * PI)
        val m0 = kotlin.math.ceil(t(-PI)).toInt()
        for (i in 0 until GlStyles.Sauce.WEDGES) {
            val m = m0 + i
            var th = 2 * PI * m / n
            repeat(30) { th -= (t(th) - m) / dt(th) }
            boundTheta[i] = th; boundM[i] = m
        }
    }

    /**
     * 漩渦的 CPU 版：和 GPU 版同一份狀態（[GlStyles.Sauce.params]：攪拌中心的彈簧、外圈落後、捲緊程度），
     * 每條交界線在畫面上是「角度＝原始角度 − a(r)」的曲線（a(r)＝A·exp(−r²／σ²)，往中心吸只改變距離、不改變角度），
     * 中心依半徑在攪拌中心與外圈中心之間內插（同 GPU 版）。相鄰兩條圍成一瓣，同色的三瓣合成一個路徑填色——
     * 邊緣由路徑反鋸齒，任何解析度都銳利。移動時的尾巴（GPU 版取樣時的小偏移）這裡不畫。
     */
    fun DrawScope.swirl(st: BgState, w: Float, h: Float) {
        val sc = scratch.get()!!
        val tmp = sc.tmp; val cv = sc.curve
        GlStyles.Sauce.params(st, tmp)
        val unit = h / .8f                                   // GPU 版 v_q 的 1 單位＝畫面高度 ÷ 0.8
        val cix = tmp[4]; val ciy = tmp[5]; val cox = tmp[6]; val coy = tmp[7]
        val amp = tmp[0].toDouble(); val s2 = tmp[1].toDouble()
        val sig = sqrt(s2)
        val rMax = 3.0                                       // 畫面對角線約 1.63，取 3 確保瓣的外緣在畫面外
        // 每條交界線的點：r 從 0 到 3σ 密（a(r) 在這裡變化），3σ 到 rMax 疏
        val nIn = SW_PTS - 8
        for (b in 0 until GlStyles.Sauce.WEDGES) {
            val base = boundTheta[b]
            for (k in 0 until SW_PTS) {
                val r = if (k < nIn) 3 * sig * k / (nIn - 1) else 3 * sig + (rMax - 3 * sig) * (k - nIn + 1) / 8.0
                val th = base - amp * exp(-r * r / s2)
                val dx = (r * cos(th)).toFloat(); val dy = (r * sin(th)).toFloat()
                val wg = smoothF(.05f, .9f, r.toFloat())
                val cx = cix + (cox - cix) * wg; val cy = ciy + (coy - ciy) * wg
                val o = (b * SW_PTS + k) * 2
                cv[o] = (cx + dx) * unit; cv[o + 1] = (cy + dy) * unit
            }
        }
        for (p in sc.swirlPaths) p.reset()
        // 瓣 i：交界 i（t＝m）到交界 i＋1（t＝m＋1）之間，顏色＝m 除以 3 的餘數（0 深、1 中、2 淺），依序循環。
        // 堆疊：整面先鋪深色；中間色的瓣延伸到它後面那一瓣（淺色）的範圍；最後鋪淺色。
        // 以前三色各自一塊、互相貼齊：交界兩邊的反鋸齒各透一半，把最底下的底色透出來，變成一條深色線（綠色主題特別明顯）。
        // 現在每條交界只有上面那一層是半透明邊，底下墊的是正確的顏色
        fun wedge(p: Path, from: Int, to: Int) {
            var o = from * SW_PTS * 2
            p.moveTo(cv[o], cv[o + 1])
            for (k in 1 until SW_PTS) { o += 2; p.lineTo(cv[o], cv[o + 1]) }
            o = (to * SW_PTS + SW_PTS - 1) * 2
            for (k in SW_PTS - 1 downTo 0) { p.lineTo(cv[o], cv[o + 1]); o -= 2 }
            p.close()
        }
        for (i in 0 until GlStyles.Sauce.WEDGES) {
            when (Math.floorMod(boundM[i], 3)) {
                1 -> wedge(sc.swirlPaths[1], i, (i + 2) % GlStyles.Sauce.WEDGES)   // 中間色＋後面的淺色瓣
                2 -> wedge(sc.swirlPaths[2], i, (i + 1) % GlStyles.Sauce.WEDGES)
            }
        }
        drawRect(col(tmp, 12), size = Size(w, h))  // 深（巧克力）鋪底
        drawPath(sc.swirlPaths[1], col(tmp, 20))   // 中（焦糖）
        drawPath(sc.swirlPaths[2], col(tmp, 16))   // 淺（奶油）
    }

    private fun smoothF(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3 - 2 * t)
    }
}
