package tw.liuli.launcher.ui.bg

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
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
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
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

    /**
     * 每種樣式各自學到的兩個旋鈕（樣式之間的繪圖成本差很多）：
     * 解析度除數 1～5，以及背景更新間隔檔位（索引對應 [INTERVALS]：30／20／15fps）。
     */
    private val learnedDiv = IntArray(BgStyle.entries.size) { DEFAULT_DIV }
    private val learnedIv = IntArray(BgStyle.entries.size)
    /** 這個樣式降解析度沒有用（瓶頸在 CPU 不在 GPU）：只調更新率，解析度保持原生。 */
    private val cpuBound = BooleanArray(BgStyle.entries.size)
    /** 升檔退避：降檔後要等這麼久才允許再試著升檔；試探失敗就加倍。 */
    private var upBackoff = BASE_BACKOFF_MS
    private var upBlockedUntil = 0L
    private var lastUpgradeAt = 0L
    @Volatile private var yieldUntil = 0L
    private var glassDirty = false
    /** 目前播放速度（1＝正常；GPU 跟不上時 <1）。 */
    private var speed = 1f
    /** 最後一次按鍵的時間（靜止後補畫昂貴的原生解析度幀要避開操作）。 */
    @Volatile private var inputAt = 0L

    // ── 每種樣式最近量到的「每幀總耗時」與當時的解析度除數（推估付不付得起更清楚的檔位） ──
    private val costLat = FloatArray(BgStyle.entries.size) { -1f }
    private val costDiv = IntArray(BgStyle.entries.size) { 1 }

    // ── FrameMetrics：背景格的 vsync 時間 ↔ 回報的總耗時 ──
    private val metricLock = Any()
    private val tickNs = LongArray(TICKS)
    private val tickOk = BooleanArray(TICKS)
    private var tickHead = 0
    private val mVsync = LongArray(M_CAP)
    private val mTotal = LongArray(M_CAP)
    private var mCount = 0
    private val latBuf = FloatArray(16)
    private var latN = 0

    // ── 背景模糊 ──
    /** 背景模糊等級（0＝關，見 [BLUR_SIGMAS]）。 */
    var blurLevel by mutableIntStateOf(0)
        private set
    private val blurOn get() = blurLevel > 0
    /** 模糊後的小圖（GPU 雙線性放大到全螢幕）；尺寸隨等級改變。 */
    var blurImage by mutableStateOf<ImageBitmap?>(null)
        private set
    /** [blurImage] 每更新一次加一。 */
    var blurVersion by mutableLongStateOf(0L)
        private set
    /** 上一張模糊圖：背景執行緒趕不上 30fps 時，GPU 在兩張之間交叉淡化（模糊的畫面內插起來很自然）。 */
    var blurPrev: ImageBitmap? = null
        private set
    /** 交叉淡化的時間（＝最近兩張模糊圖的平均間隔；0＝不淡化）。 */
    @Volatile var blurFadeMs = 0
        private set
    private val blurReq = Channel<Unit>(Channel.CONFLATED)
    private var viewW = 1920
    private var viewH = 1080

    init {
        active = this
    }

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
    private var pendingSince = 0L
    private val wake = Channel<Unit>(Channel.CONFLATED)

    /**
     * 冷啟動：背景先全黑、完全不畫，把 CPU／GPU 留給介面載入。
     * 第一次有焦點（預設是第一個 App）時才開始，從焦點位置往外擴散（[revealFrom]，畫面比例 0～1）；
     * 等太久沒有焦點（例如沒有任何 App）就從畫面中央開始。null＝還沒開始。
     */
    var revealFrom by mutableStateOf<androidx.compose.ui.geometry.Offset?>(null)
        private set
    private val started = kotlinx.coroutines.CompletableDeferred<Unit>()

    private fun startReveal(fx: Float, fy: Float) {
        if (started.isCompleted) return
        revealFrom = androidx.compose.ui.geometry.Offset(fx, fy)
        started.complete(Unit)
        // 從焦點盪出一圈漣漪，和擴散一起出現
        charge = max(charge, 0.35f)
        rippleTarget += 1f
        wake.trySend(Unit)
        Log.i(TAG, "reveal from ${"%.2f".format(fx)},${"%.2f".format(fy)}")
    }

    /** 等到冷啟動擴散開始（最多 [REVEAL_FALLBACK_MS]）。 */
    private suspend fun awaitStart() {
        // 等太久都沒有 App 取得焦點（例如常用列是空的）：從最後的焦點位置開始；連焦點都沒有就從中央
        if (withTimeoutOrNull(REVEAL_FALLBACK_MS) { started.await() } == null) {
            if (first) startReveal(0.5f, 0.5f) else startReveal(tFx, tFy)
        }
    }

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
    fun focus(scope: CoroutineScope, color: Int?, fx: Float, fy: Float, dir: Int, isApp: Boolean = false) {
        if (!started.isCompleted) {
            // 冷啟動、擴散開始之前：直接定位、不做轉場（背景還是黑的）。
            // App 清單載入前焦點可能先停在時鐘上——等焦點落到 App 磚（預設第一個）才從那裡擴散，顏色也是那個 App 的
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
            if (isApp) startReveal(fx, fy)
            return
        }
        dwellJob?.cancel()
        // 停留延遲是「去抖動」：快速掃過不會每個都換色。但連續按著方向時不能一直不動——
        // 從第一次變化起最多等 MAX_WAIT 就套用目前的焦點，背景跟著走（約每 0.45 秒更新一次目標）。
        val now = android.os.SystemClock.uptimeMillis()
        if (pendingSince == 0L) pendingSince = now
        val wait = if (now - pendingSince >= MAX_WAIT) 0L else DWELL
        dwellJob = scope.launch {
            delay(wait)
            pendingSince = 0L
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

    /** 使用者按了鍵：接下來 [YIELD_MS] 內背景改用省力模式（÷3、15fps），GPU 留給 UI（焦點彈簧、捲動）。 */
    fun noteInput() {
        val now = android.os.SystemClock.uptimeMillis()
        inputAt = now
        yieldUntil = now + YIELD_MS
    }

    /**
     * 由 LaunchedEffect 執行：被喚醒後推進背景，追上目標就停。
     *
     * 排程原則（UI 順暢優先）：
     * 1. 操作中（按鍵後 [YIELD_MS]）：背景照常動，但降到 ÷3、15fps，GPU 留給 UI（焦點彈簧、捲動）。
     * 2. 畫質階梯（解析度 ÷1～÷5 ＋ 更新率）依「每一幀的實際總耗時」（FrameMetrics）調整。
     *    GPU 跟不上時會把幀排隊：只看幀與幀的間隔會嚴重低估（海信實測：間隔 50ms、實際延遲 130ms，按鍵也跟著慢 0.1 秒）。
     *    總耗時超過更新間隔＝開始排隊，連續兩窗就降一檔，超過 1.8 倍立刻降。
     *    升檔前先用目前的成本推估下一檔（約 70% 成本跟像素數成正比），推估付得起才升，不會為了試探讓 UI 卡一下。
     * 3. 提前對焦：依剩餘動畫時間逐檔升解析度，但只升到「這台機器付得起」的檔位；
     *    更清楚的檔位（例如弱機上的原生解析度）等停下來、遙控器閒置一下再補畫——只有一幀，不影響操作。
     * 4. 播放速度平滑：每格最多推進 [MAX_STEP_MS]；變慢立即、變快緩慢。
     * 5. 背景模糊開啟時：GPU 不畫向量，改由背景執行緒畫小圖＋模糊（[runBlur]），GPU 只放大一張圖。
     */
    suspend fun run() {
        awaitStart()
        var pending = false
        while (true) {
            if (!pending) wake.receive()
            pending = false
            lastT = 0L
            speed = 1f
            moving = true
            val si = style.ordinal
            var div = learnedDiv[si]
            var iv = learnedIv[si]
            var interval = INTERVALS[iv]
            val forced = debugDiv in 1..MAX_DIV
            if (!blurOn) renderDiv = if (forced) debugDiv else div // 降解析度立即生效（在動，看不出切換）
            if (forced) interval = 16.7f * debugEvery.coerceIn(1, 8)
            // 剛降過解析度：下一個量測窗要確認有沒有變快（沒變快＝CPU 瓶頸）
            var checkDivFrom = 0f
            var done = false
            var lastBgT = 0L
            var wasBusy = false
            var nextRestoreT = 0L
            var bgTicks = 0
            var skipMeasure = 2
            var winSum = 0f
            var winN = 0
            latN = 0
            var fastWins = 0
            var slowWins = 0
            var ramping = false
            var rampTarget = 1
            var nextRampT = 0L
            synchronized(metricLock) { mCount = 0 }
            while (!done) {
                withFrameNanos<Unit> { ns ->
                    val t = ns / 1_000_000L
                    val blur = blurOn
                    // 操作中（按鍵後一小段時間）：背景照常動，但降到 ÷3、15fps，把 GPU 留給 UI。
                    // 速度不變（每格推進 1/15 秒），所以不會停住、也不會變慢。
                    val busy = android.os.SystemClock.uptimeMillis() < yieldUntil
                    val due = if (busy) max(interval, BUSY_INTERVAL) else interval
                    if (lastBgT != 0L && t - lastBgT < due - 6f) return@withFrameNanos
                    val divBefore = renderDiv
                    var measure = false
                    if (busy) {
                        wasBusy = true
                        if (!forced && !blur && renderDiv < BUSY_DIV) renderDiv = BUSY_DIV // 降解析度立即生效
                    } else if (wasBusy) {
                        // 操作結束：重新量測，解析度之後逐檔拉回
                        wasBusy = false
                        skipMeasure = 2
                        nextRestoreT = t + RAMP_STEP_MS
                    } else if (lastBgT != 0L) {
                        // 量測：兩格背景之間實際隔多久（備援指標；主要看 FrameMetrics 的總耗時）
                        if (skipMeasure > 0) skipMeasure-- else { winSum += (t - lastBgT).toFloat(); winN++; measure = true }
                    }
                    lastBgT = t
                    if (holdMs > 0) { holdUntil = t + holdMs; holdMs = 0 }
                    done = step(t) && t > holdUntil
                    if (done) snapToTarget()
                    st.nowMs = t
                    st.record(t)

                    if (!blur && !forced) {
                        // 操作結束後：解析度逐檔拉回到這段轉場的檔位（不一下子變清楚）
                        if (!busy && !ramping && renderDiv > div && t >= nextRestoreT) {
                            renderDiv--
                            nextRestoreT = t + RAMP_STEP_MS
                            skipMeasure = 2
                        }
                        // 提前對焦：剩餘時間只夠「每 RAMP_STEP_MS 升一檔」升到付得起的檔位時，就開始逐檔升
                        if (!busy && !done) {
                            if (!ramping) {
                                val target = affordableDiv(si, interval)
                                if (renderDiv > target) {
                                    val rem = max(remainingMs() / max(speed, 0.2f), (holdUntil - t).toFloat())
                                    if (rem < (renderDiv - target) * RAMP_STEP_MS + RAMP_LEAD_MS) {
                                        ramping = true
                                        rampTarget = target
                                        nextRampT = t
                                        Log.i(TAG, "${style.id} early sharpen: ${rem.toInt()}ms left, div $renderDiv -> $target")
                                    }
                                }
                            }
                            if (ramping && renderDiv > rampTarget && t >= nextRampT) {
                                renderDiv--
                                nextRampT = t + RAMP_STEP_MS
                            }
                        }
                    }

                    // 玻璃（畫小張背景＋高斯模糊）在背景執行緒做，主執行緒只送出要求；忙的話自動合併成一次
                    if (done || bgTicks++ % 2 == 0) requestGlass()
                    if (blur) blurReq.trySend(Unit)
                    if (glassDirty) { glassDirty = false; glassVersion++ }
                    // 這一格的實際耗時稍後由 FrameMetrics 送回；只採計「穩定在同一檔、不在操作中」的格
                    noteTick(ns, measure && !blur && !forced && !ramping && renderDiv == div && renderDiv == divBefore)
                    frame++
                }
                drainLatency()
                if (!forced && !blurOn && !ramping && renderDiv == div && (latN >= LAT_WINDOW || winN >= ADAPT_WINDOW * 2)) {
                    // 總耗時（中位數）：沒有排隊時就是這一幀真正的成本；超過更新間隔＝開始排隊
                    val lat = if (latN > 0) median(latBuf, latN) else -1f
                    val avg = if (winN > 0) winSum / winN else interval
                    winSum = 0f; winN = 0; latN = 0
                    if (lat >= 0f) { costLat[si] = lat; costDiv[si] = div }
                    val cost = if (lat >= 0f) lat else avg
                    val slow = avg > interval * SLOW_RATIO || lat > interval * LAT_SLOW
                    val verySlow = avg > interval * VERY_SLOW_RATIO || lat > interval * LAT_VERY_SLOW
                    // 升檔要「推估」付得起：先加回更新率，再加解析度
                    val canUp = avg < interval * FAST_RATIO && when {
                        iv > 0 -> lat < 0f || lat <= INTERVALS[iv - 1] * LAT_UP
                        div > 1 -> lat < 0f || lat * scaleCost(div, div - 1) <= interval * LAT_UP
                        else -> false
                    }
                    val now = android.os.SystemClock.uptimeMillis()
                    val oldDiv = div
                    val oldIv = iv
                    if (checkDivFrom > 0f) {
                        // 降解析度後幾乎沒變快：這個樣式卡在 CPU，解析度還原、改降更新率
                        if (cost > checkDivFrom * 0.85f && cost > interval * LAT_UP) {
                            cpuBound[si] = true
                            div = max(1, div - 1)
                            if (slow && iv < INTERVALS.lastIndex) iv++
                            Log.i(TAG, "${style.id} cpu-bound (${checkDivFrom.toInt()}ms -> ${cost.toInt()}ms), use fps instead")
                        }
                        checkDivFrom = 0f
                    } else {
                        when {
                            slow -> {
                                fastWins = 0
                                if (++slowWins >= 2 || verySlow) {
                                    slowWins = 0
                                    if (!cpuBound[si] && div < MAX_DIV) {
                                        div++
                                        checkDivFrom = cost
                                    } else if (iv < INTERVALS.lastIndex) {
                                        iv++
                                    }
                                    // 剛升檔就變慢＝試探失敗：退避時間加倍
                                    upBackoff = if (now - lastUpgradeAt < 1500) min(upBackoff * 2, MAX_BACKOFF_MS) else BASE_BACKOFF_MS
                                    upBlockedUntil = now + upBackoff
                                }
                            }
                            canUp -> {
                                slowWins = 0
                                if (++fastWins >= 2 && now >= upBlockedUntil) {
                                    fastWins = 0
                                    if (iv > 0) iv-- else div--
                                    lastUpgradeAt = now
                                }
                            }
                            else -> { fastWins = 0; slowWins = 0 }
                        }
                    }
                    if (div != oldDiv || iv != oldIv) {
                        Log.i(TAG, "${style.id} div $oldDiv->$div, ${(1000f / INTERVALS[oldIv]).toInt()}->${(1000f / INTERVALS[iv]).toInt()}fps lat=${lat.toInt()}ms gap=${avg.toInt()}ms")
                        learnedDiv[si] = div
                        learnedIv[si] = iv
                        interval = INTERVALS[iv]
                        renderDiv = div
                        skipMeasure = 2
                    }
                }
            }
            moving = false
            if (blurOn) blurReq.trySend(Unit)
            if (glassDirty) { glassDirty = false; glassVersion++ }
            frame++
            if (blurOn) continue
            // 靜止後逐檔升回原生解析度。這台機器付不起的檔位（轉場中用不到的）要等遙控器閒置
            // [STATIC_IDLE_MS] 才畫——只畫一幀，但弱機上這一幀可能要 0.1 秒，不能剛好擋到按鍵。新的焦點變化一來就中斷。
            val afford = if (forced) 1 else affordableDiv(si, interval)
            val stoppedAt = android.os.SystemClock.uptimeMillis()
            while (renderDiv > 1) {
                val expensive = renderDiv - 1 < afford
                var wait = RAMP_STEP_MS
                if (expensive) wait = max(wait, max(inputAt, stoppedAt) + STATIC_IDLE_MS - android.os.SystemClock.uptimeMillis())
                if (withTimeoutOrNull(wait) { wake.receive() } != null) { pending = true; break }
                if (expensive && android.os.SystemClock.uptimeMillis() - inputAt < STATIC_IDLE_MS) continue
                renderDiv--
                frame++
            }
        }
    }

    /** 目前這個樣式在轉場中付得起的最清楚解析度（由最近量到的總耗時推估）；還沒量過＝原生。 */
    private fun affordableDiv(si: Int, interval: Float): Int {
        val lat = costLat[si]
        if (lat < 0f) return 1
        val d0 = costDiv[si]
        var d = d0
        while (d > 1 && lat * scaleCost(d0, d - 1) <= interval * LAT_UP) d--
        // 量到的檔位本身就付不起：至少維持在量到的檔位（降檔由控制器處理）
        return d
    }

    /** 記下這一格背景的 vsync 時間；FrameMetrics 回報時用它對上是哪一格。 */
    private fun noteTick(ns: Long, ok: Boolean) {
        synchronized(metricLock) {
            tickNs[tickHead] = ns
            tickOk[tickHead] = ok
            tickHead = (tickHead + 1) % TICKS
        }
    }

    /** 把 FrameMetrics 回報的總耗時對到背景格，放進 [latBuf]。 */
    private fun drainLatency() {
        synchronized(metricLock) {
            for (k in 0 until mCount) {
                val v = mVsync[k]
                for (j in 0 until TICKS) {
                    if (tickOk[j] && abs(tickNs[j] - v) < 1_500_000L) {
                        tickOk[j] = false
                        if (latN < latBuf.size) latBuf[latN++] = mTotal[k] / 1e6f
                        break
                    }
                }
            }
            mCount = 0
        }
    }

    /** 由 Activity 的 FrameMetrics 監聽器（另一條執行緒）呼叫：每一幀的 vsync 時間與總耗時（奈秒）。 */
    fun onFrameMetrics(vsyncNs: Long, totalNs: Long, uiNs: Long) {
        // 開面板、第一次組合新畫面時，主執行緒會忙上幾十～幾百毫秒；那是介面的成本，不能讓背景降檔（更不能被判成 CPU 瓶頸）
        if (uiNs > UI_NOISE_NS) return
        synchronized(metricLock) {
            if (mCount < M_CAP) { mVsync[mCount] = vsyncNs; mTotal[mCount] = totalNs; mCount++ }
        }
    }

    /**
     * 估計動畫還要多久停（毫秒）：各通道以指數收斂估「降到停止門檻」所需時間，取最大。
     * 兩級 RC 的尾巴大約以 TAU2 收斂，中間級的差距也算進去。
     */
    private fun remainingMs(): Float {
        fun tail(res: Float, thr: Float, tau: Float) = if (res <= thr) 0f else tau * ln(res / thr)
        val hue = abs(hueDelta(st.h, tH)) + abs(hueDelta(mH, tH))
        val pos = max(abs(tFx - st.fx) + abs(tFx - mFx), abs(tFy - st.fy) + abs(tFy - mFy))
        val sat = abs(tS - st.s) + abs(tS - mS)
        return maxOf(
            maxOf(tail(hue, 1f, TAU2), tail(pos, 0.003f, TAU2), tail(sat, 0.006f, TAU2)),
            maxOf(tail(charge, 0.02f, TAU_PULSE_OUT), tail(abs(rippleTarget - st.ripple), 0.02f, TAU_RIPPLE)),
        )
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

    /**
     * 背景模糊（[blurLevel] > 0）：在背景執行緒把樣式畫在小圖上、三次盒狀模糊（半徑 2，≈高斯 σ 2.45 個小圖像素），
     * GPU 只負責把小圖雙線性放大到全螢幕——向量完全不上 GPU。
     *
     * 為什麼可以畫這麼小（docs/blur-shortcut.md 的實驗）：小圖上的模糊 σ ≥ 2 個小圖像素時，
     * 「小圖＋模糊＋放大」和「原生解析度畫完再做高斯模糊」差異 ≤ 3／255，看不出來，也不會有鋸齒；
     * 低於 2 個像素誤差會跳到 15～40 階（出現格子與鋸齒）。所以縮小倍數 f ＝ 目標 σ ÷ 2.5，模糊越強、小圖越小、越省。
     */
    suspend fun runBlur() {
        awaitStart()
        val scope = CanvasDrawScope()
        val state = BgState()
        var work: ImageBitmap? = null
        var canvas: Canvas? = null
        var bpx = IntArray(0)
        var btmp = IntArray(0)
        var n = 0
        var spentDraw = 0L
        var spentBlur = 0L
        // 三張輪替：一張正在顯示、一張是淡化的起點、一張給這次寫入
        val ring = arrayOfNulls<ImageBitmap>(3)
        var ri = 0
        var lastOutAt = 0L
        var gapAvg = 0f
        while (true) {
            blurReq.receive()
            val lvl = blurLevel
            if (lvl <= 0) { blurImage = null; blurPrev = null; continue }
            val f = blurFactor(lvl)
            val bw = max(8, ceil(viewW / f).toInt())
            val bh = max(8, ceil(viewH / f).toInt())
            if (work == null || work.width != bw || work.height != bh) {
                work = ImageBitmap(bw, bh)
                canvas = Canvas(work)
                bpx = IntArray(bw * bh)
                btmp = IntArray(bw * bh)
            }
            state.copyFrom(st)
            val s = style
            val wImg = work!!
            val c = canvas!!
            val px = bpx
            val tmp = btmp
            var tDraw = 0L
            val t0 = android.os.SystemClock.uptimeMillis()
            withContext(Dispatchers.Default) {
                val bmp = wImg.asAndroidBitmap()
                bmp.eraseColor(0xFF000000.toInt())
                scope.draw(Density(1f), LayoutDirection.Ltr, c, Size(bw.toFloat(), bh.toFloat())) {
                    scale(bw / LOGICAL_W, bh / LOGICAL_H, pivot = androidx.compose.ui.geometry.Offset.Zero) {
                        drawStyle(s, state)
                    }
                }
                bmp.getPixels(px, 0, bw, 0, 0, bw, bh)
                tDraw = android.os.SystemClock.uptimeMillis()
                repeat(3) { pass ->
                    boxBlurH(px, tmp, bw, bh, BG_BLUR_R)
                    // 最後一次：抖色（每個通道各自加不到一階的噪點再取整數），模糊後的大面積漸層不會斷成色帶
                    boxBlurV(tmp, px, bw, bh, BG_BLUR_R, dither = pass == 2)
                }
            }
            val t1 = android.os.SystemClock.uptimeMillis()
            // 輸出圖：三張輪替（在主執行緒、兩幀之間寫入，RenderThread 不會讀到一半；正在顯示與淡化起點的那兩張不動）
            ri = (ri + 1) % ring.size
            var out = ring[ri]
            if (out == null || out.width != bw || out.height != bh) { out = ImageBitmap(bw, bh); ring[ri] = out }
            out.asAndroidBitmap().setPixels(px, 0, bw, 0, 0, bw, bh)
            val cur = blurImage
            blurPrev = if (cur != null && cur.width == bw && cur.height == bh) cur else null
            // 交叉淡化時間＝輸出間隔的平均；隔很久才來一張（靜止後第一張）就直接換
            val gap = (t1 - lastOutAt).toFloat()
            gapAvg = if (lastOutAt == 0L || gap > 600f) 0f else if (gapAvg == 0f) gap else gapAvg + (gap - gapAvg) * 0.3f
            lastOutAt = t1
            blurFadeMs = if (gapAvg > 45f && blurPrev != null) min(gapAvg, 300f).toInt() else 0
            blurImage = out
            blurVersion++
            spentDraw += tDraw - t0
            spentBlur += t1 - tDraw
            if (++n % 60 == 0) {
                Log.i(TAG, "blur L$lvl ${bw}x$bh (f=${"%.1f".format(f)}): draw ${spentDraw / 60}ms + blur ${spentBlur / 60}ms, fade ${blurFadeMs}ms")
                spentDraw = 0; spentBlur = 0
            }
        }
    }

    /** 模糊等級 → 縮小倍數：σ（以 1080p 為準，依螢幕高度等比）÷ 2.5（三次半徑 2 盒狀模糊＋取樣＋放大的總 σ ≈ 2.5f）。 */
    private fun blurFactor(level: Int): Float {
        val sigma = BLUR_SIGMAS[level.coerceIn(0, BLUR_SIGMAS.lastIndex)] * viewH / 1080f
        return max(1.5f, sigma / 2.5f)
    }

    /** 背景模糊等級：0＝關。 */
    fun applyBlur(level: Int) {
        blurLevel = level.coerceIn(0, BLUR_SIGMAS.lastIndex)
        blurReq.trySend(Unit)
        frame++
    }

    /** 由背景圖層告知實際的畫面像素尺寸（模糊小圖依此計算）。 */
    fun setViewport(w: Int, h: Int) {
        if (w > 0 && h > 0 && (w != viewW || h != viewH)) {
            viewW = w; viewH = h
            blurReq.trySend(Unit)
        }
    }

    companion object {
        const val LOGICAL_W = 480f
        const val LOGICAL_H = 270f
        const val GLASS_W = 240
        const val GLASS_H = 135
        /** 冷啟動等第一個焦點最多多久（毫秒），之後從中央開始擴散。 */
        private const val REVEAL_FALLBACK_MS = 4000L
        private const val DWELL = 180L
        private const val MAX_WAIT = 450L
        private const val TAU1 = 380f
        private const val TAU2 = 460f
        private const val TAU_PULSE_IN = 260f
        private const val TAU_PULSE_OUT = 600f
        private const val TAU_RIPPLE = 900f
        private const val BLUR_R = 5
        /** 背景模糊在小圖上的盒狀模糊半徑（三次 ≈ 高斯 σ 2.45 個小圖像素，≥2 才不會有格子與鋸齒）。 */
        private const val BG_BLUR_R = 2
        /** 背景模糊等級對應的 σ（1080p 像素）。 */
        val BLUR_SIGMAS = floatArrayOf(0f, 8f, 12f, 18f, 24f, 36f, 48f)

        /** 目前的引擎（給 Activity 的 FrameMetrics 監聽器）。 */
        @Volatile var active: BackgroundEngine? = null
        private const val TICKS = 32
        private const val M_CAP = 64
        /** 每幾格（有 FrameMetrics 的）背景評估一次。 */
        private const val LAT_WINDOW = 4
        /** 總耗時超過更新間隔的倍數：1.0＝開始排隊（連續兩窗降檔）；1.8＝立刻降。 */
        private const val LAT_SLOW = 1.0f
        private const val LAT_VERY_SLOW = 1.8f
        /** 推估的下一檔總耗時要在更新間隔的 85% 以內才升檔。 */
        private const val LAT_UP = 0.85f
        /** 靜止後，付不起的清楚檔位要等遙控器閒置這麼久才補畫。 */
        private const val STATIC_IDLE_MS = 700L
        /** 這一幀之前主執行緒的延遲＋重新組合超過 8ms＝被介面拖慢，不採計。 */
        private const val UI_NOISE_NS = 8_000_000L

        /** 解析度除數 from → to 的成本倍數：約 30% 固定（CPU、合成）＋70% 跟像素數成正比。 */
        private fun scaleCost(from: Int, to: Int) = 0.3f + 0.7f * (from * from).toFloat() / (to * to).toFloat()

        private fun median(a: FloatArray, n: Int): Float {
            val c = a.copyOf(n); c.sort(); return c[n / 2]
        }

        private const val TAG = "LiuliBg"

        /**
         * 量測用（adb）：強制背景解析度除數與更新間隔（幾個畫面一次），0＝自動。
         * adb shell am start -n tw.liuli.launcher/.MainActivity --ei bgDiv 2 --ei bgEvery 2
         */
        @Volatile var debugDiv = 0
        @Volatile var debugEvery = 2
        const val MAX_DIV = 5

        /**
         * 背景更新間隔檔位：30／20／15fps。
         * 太慢時先降解析度（GPU 瓶頸）；降解析度沒有用的樣式（CPU 瓶頸）改降更新率；再慢就靠慢速播放。
         */
        private val INTERVALS = floatArrayOf(33.3f, 50f, 66.7f)
        /** 起始解析度除數（AOC 電視實測：÷2 的背景一幀 GPU 約 18ms，可以穩定 30fps）。 */
        private const val DEFAULT_DIV = 2
        /** 提前對焦／靜止後：每隔多久升一檔解析度。 */
        private const val RAMP_STEP_MS = 200L
        /** 提前對焦多留一點時間，讓最後一檔在停下之前就到位。 */
        private const val RAMP_LEAD_MS = 150L
        private const val BASE_BACKOFF_MS = 800L
        private const val MAX_BACKOFF_MS = 6000L
        /** 每一格背景最多推進的動畫時間（1/15 秒）。 */
        private const val MAX_STEP_MS = 66.7f
        /** 播放速度回升的時間常數（越大越慢）。 */
        private const val TAU_SPEED = 800f
        /** 按鍵後背景省力的時間。 */
        private const val YIELD_MS = 320L
        /** 操作中的背景：至少 ÷3、15fps（動作照常，只是比較粗、比較少格）。 */
        private const val BUSY_DIV = 3
        private const val BUSY_INTERVAL = 66.7f
        /** 每幾格背景評估一次（30fps 約 0.27 秒）。 */
        private const val ADAPT_WINDOW = 8
        /** 背景格間隔超過排定的 1.25 倍＝跟不上（連續兩窗才降）；超過 1.8 倍直接降；在 1.1 倍內＝準時，可以試著升。 */
        private const val SLOW_RATIO = 1.25f
        private const val VERY_SLOW_RATIO = 1.8f
        private const val FAST_RATIO = 1.1f

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

/**
 * 水平盒狀模糊：src → dst，半徑 r，邊緣延伸。
 * 弱 CPU 上的寫法：除法換成「乘倒數再右移 16 位」，夾邊界只在左右兩端做（中間每個像素不呼叫 coerceIn）。
 */
private fun boxBlurH(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
    val mul = (65536 + 2 * r) / (2 * r + 1) // 無條件進位：255 不會變 254
    val last = w - 1
    for (y in 0 until h) {
        val row = y * w
        var sr = 0; var sg = 0; var sb = 0
        for (k in -r..r) {
            val c = src[row + (if (k < 0) 0 else if (k > last) last else k)]
            sr += (c shr 16) and 0xFF; sg += (c shr 8) and 0xFF; sb += c and 0xFF
        }
        for (x in 0 until w) {
            dst[row + x] = (0xFF shl 24) or (((sr * mul) ushr 16) shl 16) or (((sg * mul) ushr 16) shl 8) or ((sb * mul) ushr 16)
            val xo = x - r
            val xi = x + r + 1
            val out = src[row + (if (xo < 0) 0 else xo)]
            val inn = src[row + (if (xi > last) last else xi)]
            sr += ((inn shr 16) and 0xFF) - ((out shr 16) and 0xFF)
            sg += ((inn shr 8) and 0xFF) - ((out shr 8) and 0xFF)
            sb += (inn and 0xFF) - (out and 0xFF)
        }
    }
}

/**
 * 垂直盒狀模糊：src → dst（同上的寫法）。
 * [dither]：取整數前每個通道各自加 [0,1) 階的均勻噪點（floor(x+u) 的平均正好是 x），
 * 介於兩階之間的顏色依比例交錯成相鄰兩階——平均是正確的中間色，不會出現一圈一圈的色帶。
 */
private fun boxBlurV(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, dither: Boolean = false) {
    val mul = (65536 + 2 * r) / (2 * r + 1) // 無條件進位：255 不會變 254
    val last = h - 1
    if (dither) { boxBlurVDither(src, dst, w, h, r, mul); return }
    for (x in 0 until w) {
        var sr = 0; var sg = 0; var sb = 0
        for (k in -r..r) {
            val c = src[(if (k < 0) 0 else if (k > last) last else k) * w + x]
            sr += (c shr 16) and 0xFF; sg += (c shr 8) and 0xFF; sb += c and 0xFF
        }
        var idx = x
        for (y in 0 until h) {
            dst[idx] = (0xFF shl 24) or (((sr * mul) ushr 16) shl 16) or (((sg * mul) ushr 16) shl 8) or ((sb * mul) ushr 16)
            val yo = y - r
            val yi = y + r + 1
            val out = src[(if (yo < 0) 0 else yo) * w + x]
            val inn = src[(if (yi > last) last else yi) * w + x]
            sr += ((inn shr 16) and 0xFF) - ((out shr 16) and 0xFF)
            sg += ((inn shr 8) and 0xFF) - ((out shr 8) and 0xFF)
            sb += (inn and 0xFF) - (out and 0xFF)
            idx += w
        }
    }
}

private var ditherSeed = 0x2545F491

private fun boxBlurVDither(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, mul: Int) {
    val last = h - 1
    var z = ditherSeed
    for (x in 0 until w) {
        var sr = 0; var sg = 0; var sb = 0
        for (k in -r..r) {
            val c = src[(if (k < 0) 0 else if (k > last) last else k) * w + x]
            sr += (c shr 16) and 0xFF; sg += (c shr 8) and 0xFF; sb += c and 0xFF
        }
        var idx = x
        for (y in 0 until h) {
            // xorshift32：兩個 32 位元亂數切成三個 16 位元噪點（R、G、B 各自獨立）
            z = z xor (z shl 13); z = z xor (z ushr 17); z = z xor (z shl 5)
            val n1 = z
            z = z xor (z shl 13); z = z xor (z ushr 17); z = z xor (z shl 5)
            val n2 = z
            val vr = ((sr * mul + (n1 and 0xFFFF)) ushr 16).coerceAtMost(255)
            val vg = ((sg * mul + (n1 ushr 16)) ushr 16).coerceAtMost(255)
            val vb = ((sb * mul + (n2 and 0xFFFF)) ushr 16).coerceAtMost(255)
            dst[idx] = (0xFF shl 24) or (vr shl 16) or (vg shl 8) or vb
            val yo = y - r
            val yi = y + r + 1
            val out = src[(if (yo < 0) 0 else yo) * w + x]
            val inn = src[(if (yi > last) last else yi) * w + x]
            sr += ((inn shr 16) and 0xFF) - ((out shr 16) and 0xFF)
            sg += ((inn shr 8) and 0xFF) - ((out shr 8) and 0xFF)
            sb += (inn and 0xFF) - (out and 0xFF)
            idx += w
        }
    }
    ditherSeed = z
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
