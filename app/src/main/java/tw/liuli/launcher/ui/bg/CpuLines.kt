package tw.liuli.launcher.ui.bg

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
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
 * 有明確線條的著色器樣式的 CPU 對照版（螺旋、特魯謝緞帶、漸層百葉）：形狀在 CPU 算成向量路徑，
 * 由 Compose 的畫布（硬體加速）填色——線條邊緣由路徑反鋸齒，任何解析度都銳利，不受半精度與網格內插影響。
 * 參數、顏色、動態和 GPU 版相同（[GlStyles]／[GlStyles2] 的 params），方便兩邊直接比較。
 * 座標是邏輯畫布 [w]×[h]（480×270，見 [BackgroundEngine.LOGICAL_W]），畫布會再縮放到實際解析度。
 *
 * 成本的做法：
 * - 螺旋：整條螺旋是一個固定的路徑，只有平移（中心）與旋轉（相位），路徑本身不重建。
 * - 特魯謝緞帶：所有圓弧合成四個路徑（底、亮面、兩條細線），只有捲過一整格才重建，其餘只平移。
 * - 漸層百葉：每片一個帶漸層的矩形，約三十片。
 */
internal object CpuLines {
    /** 每條執行緒各一份（主畫面在主執行緒畫、玻璃小圖在背景執行緒畫，路徑不能共用）。 */
    private class Scratch {
        var spiralPath: Path? = null
        var spiralUnit = 0f
        val tRibbon = Path(); val tInner = Path(); val tFineA = Path(); val tFineB = Path()
        var tKey = Long.MIN_VALUE
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

    // ───────────── 螺旋 ─────────────

    fun DrawScope.spiral(st: BgState, w: Float, h: Float) {
        val sc = scratch.get()!!
        val u = h / 5.6f   // 一圈的間距＝畫面高度 ÷ 5.6，和 GPU 版一樣
        drawRect(col(st, 0f, .85f, .09f), size = Size(w, h))
        // 阿基米德螺旋 r＝u·(−α／2π)：每轉一圈往外一個 u，線寬半個 u（GPU 版 stripe 的一半）。
        // 固定的路徑：相位＝整條旋轉、焦點＝平移，所以只在第一次（或畫布比例變了）建一次
        val path = sc.spiralPath.takeIf { sc.spiralUnit == u } ?: Path().also { p ->
            val turns = 14
            var first = true
            var a = 0.0
            val end = -2.0 * PI * turns
            while (a > end) {
                val r = (u * (-a / (2 * PI))).toFloat()
                val x = (r * cos(a)).toFloat(); val y = (r * sin(a)).toFloat()
                if (first) { p.moveTo(x, y); first = false } else p.lineTo(x, y)
                // 每段約 3 個邏輯單位（放大到 1080p 約 12 像素，曲線看不出折角）
                a -= min(.2, 3.0 / max(r.toDouble(), 1.0))
            }
            sc.spiralPath = p; sc.spiralUnit = u
        }
        val phase = ((st.at * .2 + st.ripple * .8) % (2 * PI)) / (2 * PI)
        withTransform({
            translate(st.fx * w, st.fy * h)
            rotate((phase * 360.0).toFloat(), Offset.Zero)
        }) {
            drawPath(path, col(st, 15f, .75f, .50f), style = Stroke(width = u * .5f, cap = StrokeCap.Butt))
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
        val tRibbon = sc.tRibbon; val tInner = sc.tInner; val tFineA = sc.tFineA; val tFineB = sc.tFineB
        val cell = h / 7f
        val t = (st.at * .055).toDouble()
        val width = .085f + (.35f + .4f * st.pulse) * .19f          // 緞帶半寬（格子單位）
        val bg = col(st, 0f, .75f, .13f); val rib = col(st, 40f, .85f, .48f)
        val edge = col(st, 20f, .8f, .38f); val fine = col(st, 0f, .8f, .24f)
        drawRect(bg, size = Size(w, h))
        val i0 = floor(t - w / 2 / cell).toInt() - 1
        val half = (h / 2 / cell).toInt() + 1
        // 路徑以「格子單位 × cell」畫，捲到下一格（i0 變）或緞帶寬度變了才重建；細線的半徑跟著寬度
        val key = i0.toLong() * 4096 + (width * 4000).toInt()
        if (key != sc.tKey) {
            sc.tKey = key
            tRibbon.reset(); tInner.reset(); tFineA.reset(); tFineB.reset()
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
            tInner.addPath(tRibbon)
        }
        val fineW = max(.012f, 1.5f * 7f / 1080f) * cell * 2f
        withTransform({ translate(w / 2 - ((t - i0) * cell).toFloat(), h / 2) }) {
            // 緞帶：外緣帶一點外緣色（GPU 版的 ridge 以外 ×0.38），中間是緞帶色
            drawPath(tRibbon, lerp(rib, edge, .38f), style = Stroke(width = 2f * width * cell, cap = StrokeCap.Butt))
            drawPath(tInner, rib, style = Stroke(width = 2f * .655f * width * cell, cap = StrokeCap.Butt))
            // 兩條細線（離中心 0.68 個半寬）
            val fc = lerp(rib, fine, .42f)
            drawPath(tFineA, fc, style = Stroke(width = fineW, cap = StrokeCap.Butt))
            drawPath(tFineB, fc, style = Stroke(width = fineW, cap = StrokeCap.Butt))
        }
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
