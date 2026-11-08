package tw.liuli.launcher.ui.bg

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** 背景樣式（與 design/home-v4.html 相同）。 */
enum class BgStyle(val id: String, val label: String, val note: String) {
    WAVES("waves", "流線浪潮", "波峰朝焦點聚集"),
    BOKEH("bokeh", "光斑", "前後景深視差，焦點時光斑脈動"),
    TRIANGLES("triangles", "幾何三角", "曲面隆起並跟著焦點移動，三角面拉伸變形"),
    DOTS("dots", "波點圓圈", "遠近兩層波點，焦點處聚光"),
    FLIP("flip", "翻牌方陣", "新顏色像波一樣擴散，方塊依序翻面"),
    SILK("silk", "絲綢", "柔光布料皺褶往焦點聚攏"),
    TRAILS("trails", "光軌", "長曝光光軌，兩端交錯開合"),
    CONTOUR("contour", "等高線", "焦點是山峰，等高線跟著流動"),
    PAPER("paper", "紙雕層疊", "多層山巒剪影，前景移動較多"),
    AURORA("aurora", "極光", "帶垂直紋理的極光簾幕"),
    SOLID("solid", "純色", "雙色漸層純色"),
    BLACK("black", "純黑（OLED）", "幾乎全黑，焦點下方一抹主色");

    companion object {
        fun byId(id: String?) = entries.firstOrNull { it.id == id } ?: WAVES
    }
}

/**
 * 每一幀繪圖需要的狀態（全部是普通欄位，不是 Compose state，繪圖時讀取零成本）。
 * 色相 [h]、飽和度 [s]、焦點位置 [fx]/[fy]（0～1）、脈動 [pulse]、移動方向 [dir]、漣漪相位 [ripple]。
 */
class BgState {
    var h = 228f
    var s = 0.5f
    var fx = 0.2f
    var fy = 0.7f
    var pulse = 0f
    var dir = 0f
    var ripple = 0f
    var nowMs = 0L

    // 色彩歷史：翻牌方陣用「過去某時間點的顏色」做出從焦點擴散的色彩波
    private val ht = LongArray(HISTORY)
    private val hh = FloatArray(HISTORY)
    private val hs = FloatArray(HISTORY)
    private var count = 0
    private var head = 0

    fun record(t: Long) {
        ht[head] = t; hh[head] = h; hs[head] = s
        head = (head + 1) % HISTORY
        if (count < HISTORY) count++
    }

    /** 取得 [agoMs] 毫秒前的色相與飽和度，寫進 [out]（0＝h、1＝s）。 */
    fun sampleAt(agoMs: Float, out: FloatArray) {
        out[0] = h; out[1] = s
        if (count == 0) return
        val target = nowMs - agoMs.toLong()
        var i = (head - 1 + HISTORY) % HISTORY
        repeat(count) {
            if (ht[i] <= target) { out[0] = hh[i]; out[1] = hs[i]; return }
            i = (i - 1 + HISTORY) % HISTORY
        }
        val oldest = (head - count + HISTORY) % HISTORY
        out[0] = hh[oldest]; out[1] = hs[oldest]
    }

    /** 整份複製（給背景執行緒畫玻璃用的快照，避免畫到一半被主執行緒改掉）。 */
    fun copyFrom(o: BgState) {
        h = o.h; s = o.s; fx = o.fx; fy = o.fy; pulse = o.pulse; dir = o.dir; ripple = o.ripple; nowMs = o.nowMs
        o.ht.copyInto(ht); o.hh.copyInto(hh); o.hs.copyInto(hs)
        count = o.count; head = o.head
    }

    private companion object {
        const val HISTORY = 256
    }
}

/**
 * 背景引擎：「電容式」緩衝（docs/ux-design.md、design/home-v4.html）。
 * 1. 停留延遲：焦點停留 [DWELL] 毫秒才開始反應，快速掃過不會每個都換色
 * 2. 兩級 RC：目標 → mid → cur，S 曲線起步慢、收尾柔、不過衝
 * 3. 脈動是充放電，漣漪相位連續前進
 * 只在轉場期間逐幀繪製，追上目標後停止。
 */
class BackgroundEngine {
    val st = BgState()

    var style by mutableStateOf(BgStyle.WAVES)
        private set
    var follow by mutableStateOf(true)
        private set

    /** 每畫一幀加一；全螢幕畫布與玻璃面板在繪製階段讀它，所以只有轉場時才重畫。 */
    var frame by mutableLongStateOf(0L)
        private set

    /** 轉場中＝true。 */
    var moving by mutableStateOf(false)
        private set

    /**
     * 背景的繪製解析度＝裝置原生解析度 ÷ [renderDiv]（1～5 的整數）。
     * 整數倍放大，每個低解析度像素剛好對到 d×d 個實體像素，和物理點陣的落差最小。
     * 1080p：÷1＝1920×1080、÷2＝960×540、÷3＝640×360、÷4＝480×270、÷5＝384×216。
     * 不能整除的螢幕由 [BackgroundLayer] 無條件進位到下一個整數像素再裁切多出來的邊。
     */
    var renderDiv by mutableIntStateOf(1)
        private set

    /** 每種樣式各自學到的背景更新率檔位（樣式之間的繪圖成本差很多），索引對應 [QUALITY_MS]。 */
    private val learnedQ = IntArray(BgStyle.entries.size) { DEFAULT_Q }
    /** 降過檔的樣式要再經過幾次轉場才允許試著升檔。 */
    private val cooldown = IntArray(BgStyle.entries.size)
    @Volatile private var yieldUntil = 0L
    private var glassDirty = false
    /** 目前播放速度（1＝正常；GPU 跟不上時 <1）。 */
    private var speed = 1f

    /** 給玻璃取樣的小張背景（240×135，已模糊＋過曝）。只在主執行緒寫入。 */
    val glass: ImageBitmap = ImageBitmap(GLASS_W, GLASS_H)
    private val glassBitmap = glass.asAndroidBitmap()

    /** [glass] 每更新一次加一；玻璃面板與模糊桌布在繪製階段讀它。 */
    var glassVersion by mutableLongStateOf(0L)
        private set

    // ── 背景執行緒專用：把樣式畫到這張小圖、模糊，結果放在 px ──
    private val workImage = ImageBitmap(GLASS_W, GLASS_H)
    private val workScope = CanvasDrawScope()
    private val workCanvas = Canvas(workImage)
    private val workBitmap = workImage.asAndroidBitmap()
    private val workState = BgState()
    private val px = IntArray(GLASS_W * GLASS_H)
    private val tmp = IntArray(GLASS_W * GLASS_H)
    private val glassReq = Channel<Unit>(Channel.CONFLATED)

    private var tH = 228f; private var tS = 0.5f; private var tFx = 0.2f; private var tFy = 0.7f
    private var mH = 228f; private var mS = 0.5f; private var mFx = 0.2f; private var mFy = 0.7f
    private var charge = 0f
    private var rippleTarget = 0f
    private var dirT = 0f
    private var lastT = 0L
    private var holdMs = 0L
    private var glassTick = 0
    private var holdUntil = 0L
    private var first = true
    private var dwellJob: Job? = null
    private val wake = Channel<Unit>(Channel.CONFLATED)

    fun applyStyle(s: BgStyle) {
        style = s
        charge = max(charge, 0.35f)
        rippleTarget += 1f
        wake.trySend(Unit)
    }

    fun applyFollow(f: Boolean) {
        follow = f
        if (!f) { tH = 228f; tS = 0.5f }
        wake.trySend(Unit)
    }

    /**
     * 焦點改變：主題色（ARGB，null＝不換色）、焦點中心在畫面上的位置（0～1）、移動方向（-1/0/1）。
     */
    fun focus(scope: CoroutineScope, color: Int?, fx: Float, fy: Float, dir: Int) {
        if (first) {
            // 開機第一次直接定位，不做轉場
            first = false
            if (follow && color != null) {
                val hs = colorToHs(color)
                tH = hs[0]; mH = tH; st.h = tH
                tS = hs[1]; mS = tS; st.s = tS
            }
            tFx = fx; mFx = fx; st.fx = fx
            tFy = fy; mFy = fy; st.fy = fy
            requestGlass()
            frame++
            return
        }
        dwellJob?.cancel()
        dwellJob = scope.launch {
            delay(DWELL)
            if (follow && color != null) {
                val hs = colorToHs(color)
                tH = hs[0]; tS = hs[1]
            }
            tFx = fx; tFy = fy; dirT = dir.toFloat()
            charge = min(0.45f, charge + 0.45f)
            rippleTarget = floor(rippleTarget) + 1f
            // 只有翻牌方陣需要讓色彩波傳完（其他樣式追上目標就停）
            if (style == BgStyle.FLIP) holdMs = 1600
            wake.trySend(Unit)
        }
    }

    /** 使用者按了鍵：接下來 [YIELD_MS] 內背景不更新，GPU 全部讓給 UI（焦點彈簧、捲動）。 */
    fun noteInput() {
        yieldUntil = android.os.SystemClock.uptimeMillis() + YIELD_MS
    }

    /**
     * 由 LaunchedEffect 執行：被喚醒後推進背景，追上目標就停。
     *
     * 排程原則（UI 順暢優先，背景固定原生 1080p）：
     * 1. 讓路：按鍵後 [YIELD_MS] 內不更新背景，之後以較慢速度緩緩接回。
     * 2. 背景更新率依時間排程（30／20／15fps），依樣式學習；量到主執行緒被 GPU 拖慢就降一檔，
     *    降過的樣式要隔幾次轉場、且連續夠快才會再試著升回去，避免忽快忽慢。
     * 3. 播放速度平滑：每格最多推進 [MAX_STEP_MS]（低於 15fps 時自然變慢）；
     *    速度要變慢可以立刻變，要變快則用 [TAU_SPEED] 慢慢加速，不會因為效能突然變好而突然變快。
     */
    suspend fun run() {
        var pending = false
        while (true) {
            if (!pending) wake.receive()
            pending = false
            lastT = 0L
            speed = 1f
            moving = true
            val styleIdx = style.ordinal
            if (cooldown[styleIdx] > 0) cooldown[styleIdx]--
            var q = learnedQ[styleIdx]
            var interval = QUALITY_MS[q]
            val forced = debugDiv in 1..MAX_DIV
            // 降解析度立即生效（在動，看不出切換）
            renderDiv = if (forced) debugDiv else QUALITY_DIV[q]
            if (forced) interval = 16.7f * debugEvery.coerceIn(1, 8)
            var done = false
            var prevT = 0L
            var lastBgT = 0L
            var yielded = false
            var bgTicks = 0
            var skipMeasure = 2
            var winSum = 0f
            var winN = 0
            var fastWins = 0
            while (!done) {
                withFrameMillis<Unit> { t ->
                    if (prevT != 0L) {
                        if (skipMeasure > 0) skipMeasure-- else { winSum += (t - prevT).toFloat(); winN++ }
                    }
                    prevT = t
                    // 讓路中：不畫背景（也不算進效能量測，這段時間的幀是 UI 的）
                    if (android.os.SystemClock.uptimeMillis() < yieldUntil) {
                        yielded = true; winSum = 0f; winN = 0; skipMeasure = 2
                        return@withFrameMillis
                    }
                    if (lastBgT != 0L && t - lastBgT < interval - 6f) return@withFrameMillis
                    if (yielded) {
                        // 讓路結束：當作只過了一格的時間，並從半速緩緩加速接回
                        lastT = t - interval.toLong()
                        speed = min(speed, 0.5f)
                        yielded = false
                    }
                    lastBgT = t
                    if (holdMs > 0) { holdUntil = t + holdMs; holdMs = 0 }
                    done = step(t) && t > holdUntil
                    if (done) snapToTarget()
                    st.nowMs = t
                    st.record(t)
                    // 玻璃（畫小張背景＋高斯模糊）在背景執行緒做，主執行緒只送出要求；忙的話自動合併成一次
                    if (done || bgTicks++ % 2 == 0) requestGlass()
                    if (glassDirty) { glassDirty = false; glassVersion++ }
                    frame++
                }
                if (!forced && winN >= ADAPT_WINDOW) {
                    // 主執行緒每格的平均間隔：GPU 跟不上時繪製佇列塞滿，主執行緒會被拖慢
                    val avg = winSum / winN
                    winSum = 0f; winN = 0
                    var nq = q
                    if (avg > SLOW_MS && q < QUALITY_MS.lastIndex) {
                        nq = q + 1
                        cooldown[styleIdx] = UPGRADE_COOLDOWN
                        fastWins = 0
                    } else if (avg < FAST_MS && q > 0 && cooldown[styleIdx] == 0) {
                        if (++fastWins >= 3) { nq = q - 1; fastWins = 0 }
                    } else {
                        fastWins = 0
                    }
                    if (nq != q) {
                        Log.i(TAG, "${style.id} ÷${QUALITY_DIV[q]}@${"%.0f".format(1000f / QUALITY_MS[q])}fps → ÷${QUALITY_DIV[nq]}@${"%.0f".format(1000f / QUALITY_MS[nq])}fps avg=${"%.1f".format(avg)}ms")
                        q = nq
                        learnedQ[styleIdx] = q
                        interval = QUALITY_MS[q]
                        renderDiv = QUALITY_DIV[q]
                        skipMeasure = 2
                    }
                }
            }
            moving = false
            if (glassDirty) { glassDirty = false; glassVersion++ }
            frame++
            // 靜止後慢慢拉回原生解析度：一次升一檔（÷4→÷3→÷2→÷1），不會一下子變清楚。
            // 新的焦點變化一來就中斷，直接開始下一段轉場。
            while (renderDiv > 1) {
                delay(RECOVER_STEP_MS)
                if (wake.tryReceive().isSuccess) { pending = true; break }
                renderDiv--
                frame++
            }
        }
    }

    private fun step(t: Long): Boolean {
        // 慢速播放：每格最多推進 1/15 秒；速度變慢立即生效、變快則慢慢加速
        val raw = if (lastT == 0L) 16.7f else (t - lastT).toFloat().coerceAtLeast(1f)
        lastT = t
        val targetSpeed = min(1f, MAX_STEP_MS / raw)
        speed = if (targetSpeed < speed) targetSpeed else speed + (targetSpeed - speed) * rc(TAU_SPEED, raw)
        val dt = raw * speed
        val a1 = rc(TAU1, dt)
        val a2 = rc(TAU2, dt)
        mH = towardHue(mH, tH, a1); st.h = towardHue(st.h, mH, a2)
        mS += (tS - mS) * a1; st.s += (mS - st.s) * a2
        mFx += (tFx - mFx) * a1; st.fx += (mFx - st.fx) * a2
        mFy += (tFy - mFy) * a1; st.fy += (mFy - st.fy) * a2
        charge *= exp(-dt / TAU_PULSE_OUT)
        st.pulse += (charge - st.pulse) * rc(TAU_PULSE_IN, dt)
        st.dir += (dirT - st.dir) * a1
        st.ripple += (rippleTarget - st.ripple) * rc(TAU_RIPPLE, dt)
        // 剩下的差距小到看不出來就停（色相 <1°、位置 <0.3%、脈動 <2%），停下時直接對齊目標
        val dh = abs(hueDelta(st.h, tH))
        return dh < 1f && abs(tS - st.s) < 0.006f && abs(tFx - st.fx) < 0.003f && abs(tFy - st.fy) < 0.003f &&
            charge < 0.02f && st.pulse < 0.02f && abs(rippleTarget - st.ripple) < 0.02f
    }

    private fun snapToTarget() {
        st.h = tH; mH = tH; st.s = tS; mS = tS
        st.fx = tFx; mFx = tFx; st.fy = tFy; mFy = tFy
        charge = 0f; st.pulse = 0f; st.ripple = rippleTarget; st.dir = dirT
    }

    /** 把目前狀態送給玻璃執行緒（只做快照排隊，主執行緒幾乎不花時間）。 */
    private fun requestGlass() {
        glassReq.trySend(Unit)
    }

    /**
     * 玻璃更新迴圈（由 LaunchedEffect 在主執行緒啟動）。
     * 在主執行緒拍快照 → 背景執行緒畫 240×135 小圖＋三次盒狀模糊（≈高斯）＋過曝 → 回主執行緒寫進 [glass]。
     * 電視 CPU 上這一套約 30～40ms，以前放在主執行緒會讓每次背景更新都卡一格；現在 UI 完全不等它。
     */
    suspend fun runGlass() {
        while (true) {
            glassReq.receive()
            workState.copyFrom(st)
            val s = style
            withContext(Dispatchers.Default) {
                workScope.draw(Density(1f), LayoutDirection.Ltr, workCanvas, Size(GLASS_W.toFloat(), GLASS_H.toFloat())) {
                    scale(GLASS_W / LOGICAL_W, GLASS_H / LOGICAL_H, pivot = androidx.compose.ui.geometry.Offset.Zero) {
                        drawStyle(s, workState)
                    }
                }
                workBitmap.getPixels(px, 0, GLASS_W, 0, 0, GLASS_W, GLASS_H)
                repeat(3) {
                    boxBlurH(px, tmp, GLASS_W, GLASS_H, BLUR_R)
                    boxBlurV(tmp, px, GLASS_W, GLASS_H, BLUR_R)
                }
                expose(px)
            }
            glassBitmap.setPixels(px, 0, GLASS_W, 0, 0, GLASS_W, GLASS_H)
            // 轉場中：等下一格背景一起重畫（省一次整個畫面的重繪）；靜止時立刻更新
            if (moving) glassDirty = true else glassVersion++
        }
    }

    companion object {
        const val LOGICAL_W = 480f
        const val LOGICAL_H = 270f
        const val GLASS_W = 240
        const val GLASS_H = 135
        private const val DWELL = 180L
        private const val TAU1 = 380f
        private const val TAU2 = 460f
        private const val TAU_PULSE_IN = 260f
        private const val TAU_PULSE_OUT = 600f
        private const val TAU_RIPPLE = 900f
        private const val BLUR_R = 5

        private const val TAG = "LiuliBg"

        /**
         * 量測用（adb）：強制背景解析度除數與更新間隔（幾個畫面一次），0＝自動。
         * adb shell am start -n tw.liuli.launcher/.MainActivity --ei bgDiv 2 --ei bgEvery 2
         */
        @Volatile var debugDiv = 0
        @Volatile var debugEvery = 2
        const val MAX_DIV = 5

        /**
         * 畫質階梯（由好到差）：解析度除數 ＋ 背景更新間隔。
         * 先保 30fps、依序降解析度到 ÷5；還不夠才把背景降到 20、15fps，再慢就靠慢速播放。
         */
        private val QUALITY_DIV = intArrayOf(1, 2, 3, 4, 5, 5, 5)
        private val QUALITY_MS = floatArrayOf(33.3f, 33.3f, 33.3f, 33.3f, 33.3f, 50f, 66.7f)
        /** AOC 電視實測：÷2 的背景一幀 GPU 約 18ms，可以穩定 30fps。 */
        private const val DEFAULT_Q = 1
        /** 靜止後每隔多久升一檔解析度。 */
        private const val RECOVER_STEP_MS = 220L
        private const val UPGRADE_COOLDOWN = 4
        /** 每一格背景最多推進的動畫時間（1/15 秒）。 */
        private const val MAX_STEP_MS = 66.7f
        /** 播放速度回升的時間常數（越大越慢）。 */
        private const val TAU_SPEED = 800f
        /** 按鍵後背景讓路的時間。 */
        private const val YIELD_MS = 320L
        private const val ADAPT_WINDOW = 10
        /** 主執行緒平均每格超過 22ms＝GPU 跟不上 → 降檔；低於 17.5ms 連續三次（且過了冷卻期）才升檔。 */
        private const val SLOW_MS = 22f
        private const val FAST_MS = 17.5f

        private fun rc(tau: Float, dt: Float) = 1f - exp(-dt / tau)
        private fun hueDelta(from: Float, to: Float) = ((to - from + 540f) % 360f) - 180f
        private fun towardHue(from: Float, to: Float, a: Float) = (from + hueDelta(from, to) * a + 360f) % 360f

        /** ARGB → HSL 的色相與飽和度；飽和度限制在 0.12～0.62，黃色偏移到琥珀色（深色上才不會變橄欖褐）。 */
        fun colorToHs(color: Int): FloatArray {
            val r = ((color shr 16) and 0xFF) / 255f
            val g = ((color shr 8) and 0xFF) / 255f
            val b = (color and 0xFF) / 255f
            val mx = max(r, max(g, b))
            val mn = min(r, min(g, b))
            val d = mx - mn
            val l = (mx + mn) / 2f
            val s = if (d == 0f) 0f else d / (1f - abs(2f * l - 1f))
            var h = when {
                d == 0f -> 0f
                mx == r -> ((g - b) / d) % 6f
                mx == g -> (b - r) / d + 2f
                else -> (r - g) / d + 4f
            }
            h = (h * 60f + 360f) % 360f
            if (h in 40f..78f) h = 34f + (h - 40f) * 0.25f
            return floatArrayOf(h, s.coerceIn(0.12f, 0.62f))
        }
    }
}

// ───────────── 玻璃背景的影像處理（CPU，小圖） ─────────────

/** 水平盒狀模糊：src → dst，半徑 r，邊緣延伸。 */
private fun boxBlurH(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
    val div = 2 * r + 1
    for (y in 0 until h) {
        val row = y * w
        var sr = 0; var sg = 0; var sb = 0
        for (k in -r..r) {
            val c = src[row + k.coerceIn(0, w - 1)]
            sr += (c shr 16) and 0xFF; sg += (c shr 8) and 0xFF; sb += c and 0xFF
        }
        for (x in 0 until w) {
            dst[row + x] = (0xFF shl 24) or ((sr / div) shl 16) or ((sg / div) shl 8) or (sb / div)
            val out = src[row + (x - r).coerceIn(0, w - 1)]
            val inn = src[row + (x + r + 1).coerceIn(0, w - 1)]
            sr += ((inn shr 16) and 0xFF) - ((out shr 16) and 0xFF)
            sg += ((inn shr 8) and 0xFF) - ((out shr 8) and 0xFF)
            sb += (inn and 0xFF) - (out and 0xFF)
        }
    }
}

/** 垂直盒狀模糊：src → dst。 */
private fun boxBlurV(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
    val div = 2 * r + 1
    for (x in 0 until w) {
        var sr = 0; var sg = 0; var sb = 0
        for (k in -r..r) {
            val c = src[k.coerceIn(0, h - 1) * w + x]
            sr += (c shr 16) and 0xFF; sg += (c shr 8) and 0xFF; sb += c and 0xFF
        }
        for (y in 0 until h) {
            dst[y * w + x] = (0xFF shl 24) or ((sr / div) shl 16) or ((sg / div) shl 8) or (sb / div)
            val out = src[(y - r).coerceIn(0, h - 1) * w + x]
            val inn = src[(y + r + 1).coerceIn(0, h - 1) * w + x]
            sr += ((inn shr 16) and 0xFF) - ((out shr 16) and 0xFF)
            sg += ((inn shr 8) and 0xFF) - ((out shr 8) and 0xFF)
            sb += (inn and 0xFF) - (out and 0xFF)
        }
    }
}

/**
 * 過曝：像相機多開半格光圈——先提高飽和度，再以「濾色」方式往白色推一點，
 * 暗部提亮多、亮部提亮少，看起來像霧面玻璃透光，而不是蓋一層白。
 */
private fun expose(px: IntArray) {
    val sat = 1.35f
    val lift = 0.22f
    for (i in px.indices) {
        val c = px[i]
        var r = ((c shr 16) and 0xFF) / 255f
        var g = ((c shr 8) and 0xFF) / 255f
        var b = (c and 0xFF) / 255f
        val l = 0.299f * r + 0.587f * g + 0.114f * b
        r = l + (r - l) * sat; g = l + (g - l) * sat; b = l + (b - l) * sat
        r = 1f - (1f - r.coerceIn(0f, 1f)) * (1f - lift)
        g = 1f - (1f - g.coerceIn(0f, 1f)) * (1f - lift)
        b = 1f - (1f - b.coerceIn(0f, 1f)) * (1f - lift)
        px[i] = (0xFF shl 24) or ((r * 255f).toInt() shl 16) or ((g * 255f).toInt() shl 8) or (b * 255f).toInt()
    }
}
