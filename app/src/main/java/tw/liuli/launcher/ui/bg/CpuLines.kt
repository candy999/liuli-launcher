package tw.liuli.launcher.ui.bg

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.asComposePath
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
 * 有明確線條的著色器樣式的 CPU 對照版（螺旋、特魯謝緞帶、漩渦、漸層百葉）：形狀在 CPU 算成向量路徑，
 * 由 Compose 的畫布（硬體加速）填色——線條邊緣由路徑反鋸齒，任何解析度都銳利，不受半精度與網格內插影響。
 * 參數、顏色、動態和 GPU 版相同（[GlStyles]／[GlStyles2] 的 params），方便兩邊直接比較。
 * 座標是邏輯畫布 [w]×[h]（480×270，見 [BackgroundEngine.LOGICAL_W]），畫布會再縮放到實際解析度。
 *
 * 成本的做法：
 * - 螺旋：整條螺旋是一個固定的填色外框（描邊只做一次），只有平移（中心）與旋轉（相位），路徑本身不重建。
 * - 特魯謝緞帶：所有圓弧合成四個填色外框（底、亮面、兩條細線），只有捲過一整格或寬度換檔才重建，其餘只平移。
 * - 漸層百葉：每片一個帶漸層的矩形，約三十片。
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
        val strokePaint = android.graphics.Paint().apply { style = android.graphics.Paint.Style.STROKE; strokeCap = android.graphics.Paint.Cap.BUTT }
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

    fun DrawScope.spiral(st: BgState, w: Float, h: Float) {
        val sc = scratch.get()!!
        val u = h / 5.6f   // 一圈的間距＝畫面高度 ÷ 5.6，和 GPU 版一樣
        drawRect(col(st, 0f, .85f, .09f), size = Size(w, h))
        val phase0 = ((st.at * .2 + st.ripple * .8) % (2 * PI)) / (2 * PI)
        if (forGlass()) {
            // 玻璃小圖：每段 12 個邏輯單位（小圖上約 3 像素）、直接描邊
            val gp = sc.spiralGlass.takeIf { sc.spiralGlassUnit == u } ?: Path().also { p ->
                var a = 0.0; var first = true
                while (a > -2.0 * PI * 14) {
                    val r = (u * (-a / (2 * PI))).toFloat()
                    val x = (r * cos(a)).toFloat(); val y = (r * sin(a)).toFloat()
                    if (first) { p.moveTo(x, y); first = false } else p.lineTo(x, y)
                    a -= min(.5, 12.0 / max(r.toDouble(), 1.0))
                }
                sc.spiralGlass = p; sc.spiralGlassUnit = u
            }
            withTransform({ translate(st.fx * w, st.fy * h); rotate((phase0 * 360.0).toFloat(), Offset.Zero) }) {
                drawPath(gp, col(st, 15f, .75f, .50f), style = Stroke(width = u * .5f, cap = StrokeCap.Butt))
            }
            return
        }
        // 阿基米德螺旋 r＝u·(−α／2π)：每轉一圈往外一個 u，線寬半個 u（GPU 版 stripe 的一半）。
        // 固定的路徑：相位＝整條旋轉、焦點＝平移，所以只在第一次（或畫布比例變了）建一次——而且直接存成描邊後的填色外框
        val path = sc.spiralPath.takeIf { sc.spiralUnit == u } ?: Path().let { p ->
            val turns = 14
            var first = true
            var a = 0.0
            val end = -2.0 * PI * turns
            while (a > end) {
                val r = (u * (-a / (2 * PI))).toFloat()
                val x = (r * cos(a)).toFloat(); val y = (r * sin(a)).toFloat()
                if (first) { p.moveTo(x, y); first = false } else p.lineTo(x, y)
                // 每段約 6 個邏輯單位：弦的誤差＝段長²／(8·半徑)，半徑 20 以上就不到 0.2 個邏輯單位（÷2 時 0.4 像素），
                // 點數比每段 3 少一半，畫布每格切三角形的工作也少一半
                a -= min(.3, 6.0 / max(r.toDouble(), 1.0))
            }
            sc.toFill(p, u * .5f).also { sc.spiralPath = it; sc.spiralUnit = u }
        }
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

    fun DrawScope.truchet(st: BgState, w: Float, h: Float) {
        val sc = scratch.get()!!
        val tRibbon = sc.tRibbon; val tFineA = sc.tFineA; val tFineB = sc.tFineB
        val cell = h / 7f
        val t = (st.at * .055).toDouble()
        // 緞帶半寬（格子單位）：分成 0.005 一檔（1080p 約 0.8 像素，看不出跳），焦點脈動時才不用每格重建外框
        val width = kotlin.math.round((.085f + (.35f + .4f * st.pulse) * .19f) * 200f) / 200f
        val bg = col(st, 0f, .75f, .13f); val rib = col(st, 40f, .85f, .48f)
        val edge = col(st, 20f, .8f, .38f); val fine = col(st, 0f, .8f, .24f)
        drawRect(bg, size = Size(w, h))
        val i0 = floor(t - w / 2 / cell).toInt() - 1
        val half = (h / 2 / cell).toInt() + 1
        // 路徑以「格子單位 × cell」畫，捲到下一格（i0 變）或緞帶寬度換檔才重建；細線的半徑跟著寬度。
        // 建好就轉成填色外框，之後每格只平移
        val fineW = max(.012f, 1.5f * 7f / 1080f) * cell * 2f
        val key = i0.toLong() * 4096 + (width * 4000).toInt()
        if (key != sc.tKey || sc.tRibbonFill == null) {
            sc.tKey = key
            tRibbon.reset(); tFineA.reset(); tFineB.reset()
            val cols = (w / cell).toInt() + 4
            for (i in i0 until i0 + cols) for (j in -half - 1..half) {
                val fl = flip(i, j)
                // 兩個四分之一圓（半徑 0.5 格）：不翻＝圓心在左上、右下角；翻＝右上、左下角
                val cx1 = if (fl) i + 1 else i; val cy1 = j
                val cx2 = if (fl) i else i + 1; val cy2 = j + 1
                val s1 = if (fl) 90f else 0f; val s2 = if (fl) 270f else 180f
                for ((cx, cy, s) in arrayOf(Triple(cx1, cy1, s1), Triple(cx2, cy2, s2))) {
                    fun arc(p: Path, r: Float) {
                        val x = (cx - i0) * cell; val y = cy * cell
                        p.arcTo(Rect(x - r * cell, y - r * cell, x + r * cell, y + r * cell), s, 90f, true)
                    }
                    arc(tRibbon, .5f); arc(tFineA, .5f - .68f * width); arc(tFineB, .5f + .68f * width)
                }
            }
            sc.tRibbonFill = sc.toFill(tRibbon, 2f * width * cell)
            sc.tInnerFill = sc.toFill(tRibbon, 2f * .655f * width * cell)
            sc.tFineAFill = sc.toFill(tFineA, fineW)
            sc.tFineBFill = sc.toFill(tFineB, fineW)
        }
        // 捲動的位移對齊到整數個繪製像素（捲得很慢，約每 7 格才跨一個像素）：位移沒變的格子，畫布可以沿用上一格切好的三角形
        val pxScale = size.width / w
        val sx = kotlin.math.round((w / 2 - ((t - i0) * cell).toFloat()) * pxScale) / pxScale
        withTransform({ translate(sx, h / 2) }) {
            // 玻璃小圖：只畫緞帶本體（亮面與細線在 120×68 上看不到，模糊後更看不出來）
            if (forGlass()) { drawPath(sc.tRibbonFill!!, lerp(rib, edge, .2f)); return@withTransform }
            // 緞帶：外緣帶一點外緣色（GPU 版的 ridge 以外 ×0.38），中間是緞帶色
            drawPath(sc.tRibbonFill!!, lerp(rib, edge, .38f))
            drawPath(sc.tInnerFill!!, rib)
            // 兩條細線（離中心 0.68 個半寬）
            val fc = lerp(rib, fine, .42f)
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
        // 瓣 i：交界 i（t＝m）到交界 i＋1（t＝m＋1）之間，顏色＝m 除以 3 的餘數（0 深、1 中、2 淺，同 GPU 版）
        for (i in 0 until GlStyles.Sauce.WEDGES) {
            val j = (i + 1) % GlStyles.Sauce.WEDGES
            val p = sc.swirlPaths[Math.floorMod(boundM[i], 3)]
            var o = i * SW_PTS * 2
            p.moveTo(cv[o], cv[o + 1])
            for (k in 1 until SW_PTS) { o += 2; p.lineTo(cv[o], cv[o + 1]) }
            o = (j * SW_PTS + SW_PTS - 1) * 2
            for (k in SW_PTS - 1 downTo 0) { p.lineTo(cv[o], cv[o + 1]); o -= 2 }
            p.close()
        }
        drawPath(sc.swirlPaths[0], col(tmp, 12))   // 深（巧克力）
        drawPath(sc.swirlPaths[1], col(tmp, 20))   // 中（焦糖）
        drawPath(sc.swirlPaths[2], col(tmp, 16))   // 淺（奶油）
    }

    // ───────────── 漸層百葉 ─────────────

    fun DrawScope.blinds(st: BgState, w: Float, h: Float) {
        val base = col(st, 0f, .6f, .025f); val blade = col(st, -12f, .95f, .38f); val hi = col(st, 14f, .6f, .86f)
        drawRect(base, size = Size(w, h))
        val n = 7f                                 // 每單位長度（畫面高度）幾片
        val slide = (st.ripple % 8f) * .07f        // 每次換焦點，葉片輕輕滑一點
        val ang = -.62f                            // 傾斜角（弧度）
        val spread = 2.2f - .8f * st.pulse
        fun shade(lum: Float): Color {
            val c1 = lerp(base, blade, (lum * 1.6f).coerceIn(0f, 1f))
            return lerp(c1, hi, (lum * lum * 1.3f).coerceIn(0f, 1f))
        }
        // 在「葉片座標」裡畫：原點在焦點、1 單位＝畫面高度、轉到葉片的方向（GPU 版 r＝旋轉(p)）
        withTransform({
            translate(st.fx * w, st.fy * h)
            scale(h, h, Offset.Zero)
            rotate((-ang * 180f / PI.toFloat()), Offset.Zero)
        }) {
            val reach = 2.2f
            val k0 = floor(-reach * n + slide).toInt(); val k1 = floor(reach * n + slide).toInt()
            val fxs = floatArrayOf(0f, .2f, .4f, .6f, .8f, .86f, .93f, 1f)
            for (kk in k0..k1) {
                val x0 = (kk - slide) / n; val x1 = (kk + 1 - slide) / n
                val cx = (kk + .5f - slide) / n
                val across = exp(-cx * cx * spread)
                if (across < .01f) continue
                // 葉片橫向的明暗（GPU 版 prof）：0～0.8 漸亮、0.86～1 收暗
                val stops = Array(fxs.size) { i ->
                    val f = fxs[i]
                    val prof = smoothF(0f, .8f, f) * (1f - smoothF(.86f, 1f, f))
                    f to shade(prof * across)
                }
                drawRect(Brush.horizontalGradient(*stops, startX = x0, endX = x1), Offset(x0, -reach), Size(x1 - x0, reach * 2))
            }
            // 沿著葉片離焦點越遠越暗（GPU 版 along＝exp(−r.y²·1.4)）：疊一層往兩端加深的底色
            val ys = floatArrayOf(-reach, -1.6f, -1.2f, -.8f, -.4f, 0f, .4f, .8f, 1.2f, 1.6f, reach)
            val fade = Array(ys.size) { i ->
                val y = ys[i]
                ((y + reach) / (2 * reach)) to base.copy(alpha = 1f - exp(-y * y * 1.4f))
            }
            drawRect(Brush.verticalGradient(*fade, startY = -reach, endY = reach), Offset(-reach, -reach), Size(reach * 2, reach * 2))
        }
    }

    private fun smoothF(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3 - 2 * t)
    }
}
