package tw.liuli.launcher.ui.bg

import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * OpenGL ES 2.0 背景層：著色器樣式（[BgStyle.gl]）在自己的執行緒、離屏（pbuffer）畫成一張低解析度小圖，
 * 讀回 CPU 後交給原本的繪圖流程——全螢幕背景、玻璃取樣、背景模糊都只是「貼一張圖」，不用另外接一條路。
 *
 * 為什麼畫小圖：這些樣式全是低頻的漸層（沒有細線），480×270 放大到 1080p 看不出差別；
 * 海信 Mali-470 只有 GLES 2.0、半精度浮點、填充率低，全解析度跑著色器付不起。
 *
 * 半精度（Mali-470 的片段著色器只有 mediump）的規則：
 * - 時間相位在 CPU 端（雙精度）取模後才傳進來，著色器裡的數字永遠很小
 * - 不用 fract(sin(x)·43758) 這類雜湊（半精度下會變成條紋），要亂數就查 [noiseTex]（64×64 預先算好的雜訊貼圖）
 * - 迴圈全部手動展開、陣列只用常數索引
 *
 * 一次只有一格在算：主執行緒收到上一格的結果之後才排下一格，忙不過來時中間的要求自動合併。
 * 輸出圖三張輪替：一張正在顯示、一張可能還在 GPU 上傳，另一張給這次寫入。
 */
object GlBackground {
    private const val TAG = "LiuliGl"
    /**
     * 樣式參數的數量（著色器裡是 vec4 u_p[PARAMS／4]）。u_p[14]／u_p[15] 固定給時間相位的整數／小數部分（[putTimes]）。
     * ES 2.0 只保證 16 個 vec4，實際上 Mali-400 系列有 256 個；不夠的機器連結失敗，自動改用 CPU 備援。
     */
    const val PARAMS = 64

    /** EGL 或著色器編譯失敗：改用 CPU 的備援畫法，不再嘗試。 */
    @Volatile var failed = false
        private set

    /** 著色器編譯／連結失敗的樣式（例如超過這顆 GPU 的指令數上限）：只有這些樣式改用 CPU 備援，其他著色器樣式照常。 */
    private val badStyles = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<BgStyle, Boolean>())

    /** 這個樣式要用 CPU 備援（GL 整個失敗，或這個樣式的著色器編不起來）。 */
    fun failedFor(style: BgStyle) = failed || style in badStyles

    /** 最新的一張結果（主執行緒寫入）。 */
    @Volatile var current: Bitmap? = null
        private set

    /** [current] 是哪個樣式畫的（剛切換樣式時還是上一個的，玻璃先不要用）。 */
    @Volatile var currentStyle: BgStyle? = null
        private set

    /** [current] 每換一次加一；全螢幕背景在繪製階段讀它（著色器樣式不跟著引擎的 frame 重畫，等新圖來才畫）。 */
    var version by mutableLongStateOf(0L)
        private set

    /**
     * 動態解析度（規則和 CPU 樣式的背景引擎一樣，見 BackgroundEngine.run 的排程原則）：
     * - 每個樣式記住一個「學到的檔位」（[SIZES] 的索引＝原生 ÷1～÷5 的除數−1，和 CPU 樣式一樣），起點見 [startLevel]
     * - 每約 1 秒一窗（每 3 張量一次 GPU 時間、取 10 次的中位數）：超過 [SLOW_MS] 連續兩窗（或超過 [VERY_SLOW_MS] 一窗）降一檔；
     *   升檔前先推估上一檔的成本（約 70% 跟像素數成正比），推估在 [UP_MS] 以內、連續兩窗才升；剛升就變慢＝試探失敗，退避時間加倍。
     *   上限是原生解析度（電視實際的畫面大小）：GPU 夠強的電視會一路升到原生
     * - 操作中、面板／抽屜開著：降到原生 ÷3（[BUSY_LEVEL]，1080p＝640×360），每秒 30 格不變；便宜的樣式（一張 [CHEAP_MS] 以內）不降。
     *   操作結束每 [RAMP_STEP_MS] 拉回一檔
     * - 只在轉場時動的樣式（漸層百葉）停下來、遙控器閒置一下之後，補畫一張原生解析度（[request] 的 still）
     * [debugLevel]（adb ACTION=glres:N）強制檔位，-1＝自動。
     */
    private val levels = IntArray(BgStyle.entries.size) { -1 }
    @Volatile var debugLevel = -1
    /**
     * 片段著色器支援全精度（highp，例如 PowerVR）：扭曲色帶改成逐像素算（[GlStyles2.Warp.SHADER_HP]），沒有網格折角。
     * Mali-470 只有半精度，照舊在頂點算。GL 執行緒初始化時設定。
     */
    @Volatile var fragHighp = false
        private set
    /** 量測用：adb ACTION=glgrid:N 把扭曲色帶（頂點版）的網格改成 N×(N·9／16)；0＝預設。glhp:0 強制用頂點版。 */
    @Volatile var debugGrid = 0
    @Volatile var debugNoHighp = false
    fun debugWarp(grid: Int = debugGrid, noHighp: Boolean = debugNoHighp) {
        debugGrid = grid; debugNoHighp = noHighp
        handler?.post {
            if (noHighp && fragHighp) { fragHighp = false; programs.clear(); dropProg = null }
            programs.remove(BgStyle.WARP)
        }
    }
    /** 每個樣式最近在學到的檔位量到的 GPU 時間（毫秒；-1＝還沒量）：判斷「便宜的樣式不降」。 */
    private val lastCost = FloatArray(BgStyle.entries.size) { -1f }
    /** 操作中、面板／抽屜開著降到的檔位：原生 ÷3（和 CPU 樣式的 BackgroundEngine.BUSY_DIV 一樣；1080p＝640×360） */
    private const val BUSY_LEVEL = 2
    private const val CHEAP_MS = 12f
    // 閒置（沒在操作）時盡量升到最高畫質：每秒 20 格以上就可以（跟不上 30 格的部分，引擎把動畫等比放慢，看起來不卡）。
    // 學到的檔位只在閒置時量；操作中、面板開著另外降到 ÷3、每秒 30 格（[BUSY_LEVEL]），所以這裡的預算是閒置的預算。
    // 以前照每秒 30 格的預算（降 24ms、升 16ms），150 這種 GPU 也停在 960，閒置時白白浪費畫質
    private const val UP_MS = 36f
    private const val VERY_SLOW_MS = 66f
    private const val RAMP_STEP_MS = 200L
    // 主執行緒：目前顯示的檔位（操作中降下去、結束後逐檔拉回）
    private var shownLevel = -1
    private var shownStyle: BgStyle? = null
    private var nextRampMs = 0L
    // GL 執行緒：升降檔的判斷
    private var fastWins = 0
    private var lastUpMs = 0L
    private var upBlockedUntil = 0L
    private var upBackoff = 3000L
    private val winBuf = FloatArray(10)

    /** 實際交出畫面的間隔（毫秒，平滑過）：引擎拿來決定動畫速度（跟不上每秒 30 格時放慢，每格的變化量才不會變大）。 */
    @Volatile var frameMs = 33.3f
        private set
    private var lastShowMs = 0L
    private var lastLevelLog = 0L

    /**
     * 起始檔位（原生 ÷ 幾，換成 [SIZES] 的索引＝除數−1）。
     * 全部每秒 30 格（以前有些樣式每秒 15 格換解析度，流動看得出卡），解析度照 30 格的 GPU 預算（一張 20ms 以內）挑。
     * 海信 Mali-470 實測（一張的 GPU 時間）：螺旋 960 約 9ms、特魯謝緞帶 14ms、液態以太 13ms、漩渦 960 要 21ms → 800；
     * 色塊雜訊、扭曲色帶、水彩（每滴只畫自己的範圍）、柔極光（帶子的形狀在頂點算）640 約 14～16ms；極光簾 320 約 18ms。
     */
    private fun startLevel(style: BgStyle) = when (style) {
        BgStyle.SPIRAL, BgStyle.TRUCHET, BgStyle.BLINDS, BgStyle.LIQUID -> 2
        BgStyle.SAUCE, BgStyle.SIMPLEX, BgStyle.WARP, BgStyle.SOFTAURORA, BgStyle.WATERCOLOR -> 3
        BgStyle.CURTAIN -> 5
        else -> 4
    } - 1

    /** 每幾次要求才真的畫一張：現在全部每秒 30 格（保留這個介面給量測與儀表）。 */
    @Suppress("UNUSED_PARAMETER")
    fun every(style: BgStyle) = 1

    private var reqN = 0
    private var measuredStyle: BgStyle? = null
    private var slowWins = 0
    /**
     * 解析度檔位＝原生 ÷1～÷5（索引＝除數−1），和 CPU 樣式的動態解析度（BackgroundEngine.renderDiv，1～MAX_DIV）同一套：
     * 面板狀態（操作中、抽屜開著降到 ÷3）兩邊對得上；整數倍放大，每個低解析度像素剛好對齊 d×d 個實體像素。
     * 原生＝背景實際的畫面大小（TextureView 一好就換成它；1080p＝1920／960／640／480／384，介面 720p 的機上盒＝1280／640／427／320／256）。
     */
    private const val MAX_DIV = 5
    private fun sizesFor(w: Int, h: Int) = Array(MAX_DIV) { i -> val d = i + 1; intArrayOf((w + d - 1) / d, (h + d - 1) / d) }
    @Volatile private var SIZES = sizesFor(1920, 1080)

    /** 背景實際的畫面大小（主執行緒）：和目前的原生不同就重建檔位，各樣式重新從起點學。 */
    private fun setNative(w: Int, h: Int) {
        if (w < 320 || h < 180) return
        if (SIZES[0][0] == w && SIZES[0][1] == h) return
        SIZES = sizesFor(w, h)
        levels.fill(-1); lastCost.fill(-1f); shownLevel = -1
        Log.i(TAG, "原生 ${w}x$h，檔位 ÷1～÷$MAX_DIV")
    }
    private const val SLOW_MS = 46f

    private val main = Handler(Looper.getMainLooper())
    private var handler: Handler? = null

    // ── 主執行緒 → GL 執行緒的要求（加鎖） ──
    private val lock = Any()
    private val pend = FloatArray(PARAMS)
    private var pendStyle: BgStyle? = null
    private var hasPending = false
    private var inFlight = false

    // ── GL 執行緒專用 ──
    private var dpy: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var ctx: EGLContext = EGL14.EGL_NO_CONTEXT
    private var surf: EGLSurface = EGL14.EGL_NO_SURFACE
    private var config: EGLConfig? = null
    private var surfW = 0
    private var surfH = 0
    private val programs = HashMap<BgStyle, Program>()
    private var noiseTex = 0
    private var noiseTexL = 0
    private var noiseTexQ = 0
    private var readBuf: ByteBuffer? = null
    private val ring = arrayOfNulls<Bitmap>(3)
    private var ringI = 0
    private val work = FloatArray(PARAMS)
    // 直接顯示（TextureView）：GL 畫進它的 SurfaceTexture，系統合成時當貼圖用——不用讀回 CPU、也不用再上傳一次
    private var tex: android.graphics.SurfaceTexture? = null
    private var texSurface: android.view.Surface? = null
    private var win: EGLSurface = EGL14.EGL_NO_SURFACE
    private var winLevel = -1
    private var windowOk = true
    private var workStyle: BgStyle? = null
    private var pendLevel = 4
    private var workLevel = 4
    private var pendStable = false
    private var pendSplit = true
    private var workSplit = true
    private var workStable = false
    private var lastGlassNs = 0L

    // 量測
    private var nFrames = 0
    private var sumGpu = 0L
    private var sumCopy = 0L
    private var sumRead = 0L
    private var warm = 0
    private var frameNo = 0L

    @Volatile private var releasePending = false

    /** 量測用（adb ACTION=gllog:1）：一直印 GL 耗時與玻璃小圖的 CPU 成本；平常只印剛開始的前三次。 */
    @Volatile var verbose = false
    private var reports = 0

    /**
     * 直接顯示可以用（EGL 設定支援 window surface，而且還沒失敗過）：全螢幕背景放一個 TextureView 讓 GL 直接畫進去。
     * 不行就退回「畫小圖、讀回、當點陣圖貼」。
     */
    @Volatile var directOk = true
        private set

    /** TextureView 已經收到第一張（主執行緒寫入；在那之前背景先用 CPU 備援墊著）。 */
    var directShown by androidx.compose.runtime.mutableStateOf(false)
        private set

    /** 給 TextureView 用的監聽器：SurfaceTexture 準備好就交給 GL 執行緒，銷毀時等 GL 放掉才回傳。 */
    val textureListener = object : android.view.TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(st: android.graphics.SurfaceTexture, w: Int, h: Int) {
            setNative(w, h)
            ensureThread().post { destroyWindow(); tex = st; winLevel = -1 }
            // 只在轉場時動的樣式（百葉）：背景停著就不會再來要求，新的 TextureView 要用最後一次的參數補畫一張
            val go = synchronized(lock) {
                if (pendStyle == null || inFlight) false else { hasPending = true; inFlight = true; true }
            }
            if (go) ensureThread().post(render)
        }
        override fun onSurfaceTextureDestroyed(st: android.graphics.SurfaceTexture): Boolean {
            directShown = false
            val h = handler ?: return true
            val latch = java.util.concurrent.CountDownLatch(1)
            h.post { if (tex === st) { destroyWindow(); tex = null }; latch.countDown() }
            latch.await(500, java.util.concurrent.TimeUnit.MILLISECONDS)
            return true
        }
        override fun onSurfaceTextureSizeChanged(st: android.graphics.SurfaceTexture, w: Int, h: Int) { setNative(w, h) }
        override fun onSurfaceTextureUpdated(st: android.graphics.SurfaceTexture) {}
    }

    /** 這個樣式付得出畫面了（第一張圖好了，或 GL 失敗改用 CPU 備援）。會順便觸發第一張。 */
    fun ready(style: BgStyle, st: BgState): Boolean {
        if (failedFor(style)) return true
        if (current == null) request(style, st)
        return current != null
    }

    /**
     * 背景推進了一格：用目前的狀態排一張新圖（主執行緒呼叫）。
     * [busy]＝操作中或面板／抽屜開著：降到 640×360、每秒 30 格不變（便宜的樣式不降）；結束後逐檔拉回。
     * [still]＝停下來閒置之後補一張原生解析度（只在轉場時動的樣式）。
     */
    fun request(style: BgStyle, st: BgState, busy: Boolean = false, still: Boolean = false) {
        if (failedFor(style)) return
        if (every(style) > 1 && reqN++ % every(style) != 0 && currentStyle == style && current != null) return
        val si = style.ordinal
        if (levels[si] < 0) levels[si] = startLevel(style)
        val learned = levels[si].coerceIn(0, SIZES.size - 1)
        val cheap = lastCost[si] in 0f..CHEAP_MS
        val target = if (busy && !cheap) maxOf(learned, BUSY_LEVEL) else learned
        val now = SystemClock.uptimeMillis()
        if (style != shownStyle || shownLevel < 0) { shownStyle = style; shownLevel = target; frameMs = 33.3f; lastShowMs = 0L }
        if (target > shownLevel) shownLevel = target   // 降解析度立即生效（在動，看不出切換）
        else if (target < shownLevel && now >= nextRampMs) { shownLevel--; nextRampMs = now + RAMP_STEP_MS }
        val lv = when {
            debugLevel in SIZES.indices -> debugLevel
            still -> 0
            else -> shownLevel
        }
        if (verbose && now - lastLevelLog > 2000) {
            lastLevelLog = now
            Log.i(TAG, "${style.id} 顯示 ${SIZES[lv][0]}x${SIZES[lv][1]}（學到 ${SIZES[learned][0]}）${if (busy) " 操作中" else ""}、%.1f fps".format(1000f / frameMs))
        }
        synchronized(lock) {
            style.glParams(st, pend)
            pendLevel = lv
            pendStable = !still && debugLevel < 0 && lv == learned
            // 操作中、面板開著（降到 ÷3 讓路的時候）只畫一層，GPU 讓給介面；平常才分兩段（見 [SPLIT]）
            pendSplit = !(busy && !cheap)
            pendStyle = style
            hasPending = true
            if (inFlight) return
            inFlight = true
        }
        ensureThread().post(render)
    }

    /**
     * 量測用（adb ACTION=glbench）：目前的樣式在離屏 pbuffer 畫 480×270、960×540、1920×1080 各 8 張（每張 glFinish），印出中位數。
     * 平常量到的 GPU 時間混了「排在介面後面等」的時間，這個比較接近著色器本身的成本。
     */
    fun bench() {
        ensureThread().post {
            val style = workStyle ?: return@post
            try {
                val res = StringBuilder()
                for (sz in arrayOf(intArrayOf(480, 270), intArrayOf(960, 540), intArrayOf(1920, 1080))) {
                    ensureGl(sz[0], sz[1])
                    if (!EGL14.eglMakeCurrent(dpy, surf, surf, ctx)) continue
                    val prog = programs.getOrPut(style) { Program(style) }
                    pass(prog, sz[0], sz[1], false); GLES20.glFinish()
                    val ts = LongArray(8)
                    for (i in ts.indices) {
                        val t0 = SystemClock.elapsedRealtimeNanos()
                        pass(prog, sz[0], sz[1], false); GLES20.glFinish()
                        ts[i] = SystemClock.elapsedRealtimeNanos() - t0
                    }
                    ts.sort()
                    res.append(" ${sz[0]}x${sz[1]}=%.1fms".format(ts[3] / 1e6f))
                }
                ensureGl(BackgroundEngine.GLASS_W, BackgroundEngine.GLASS_H)
                Log.i(TAG, "bench ${style.id}:$res")
            } catch (t: Throwable) { Log.w(TAG, "bench", t) }
        }
    }

    /** 離開琉璃：GL 資源與輸出圖全部放掉，下一次要求時重建。 */
    fun release() {
        val h = handler ?: return
        releasePending = true
        h.post {
            if (!releasePending) return@post
            releasePending = false
            destroyGl()
            ring.fill(null)
            main.post { current = null }
        }
    }

    private fun ensureThread(): Handler {
        handler?.let { return it }
        val t = HandlerThread("liuli-gl", android.os.Process.THREAD_PRIORITY_DISPLAY)
        t.start()
        return Handler(t.looper).also { handler = it }
    }

    private val render: Runnable = Runnable {
        releasePending = false
        synchronized(lock) {
            pend.copyInto(work)
            if (pendStyle == BgStyle.LIQUID) LiquidEther.snapshot()
            workStyle = pendStyle
            workLevel = pendLevel
            workStable = pendStable
            workSplit = pendSplit
            hasPending = false
        }
        val style = workStyle
        var out: Bitmap? = null
        var shown = false
        if (style != null) {
            try {
                out = draw(style)
                shown = win != EGL14.EGL_NO_SURFACE
            } catch (t: Throwable) {
                val msg = t.message ?: ""
                if (t is IllegalStateException && (msg.startsWith("compile") || msg.startsWith("link"))) {
                    Log.w(TAG, "${style.id} 的著色器編不起來，這個樣式改用 CPU 畫法：$msg")
                    badStyles.add(style)
                } else {
                    Log.w(TAG, "GL 背景失敗，改用 CPU 畫法", t)
                    failed = true
                    runCatching { destroyGl() }
                }
            }
        }
        main.post {
            // 連續交出畫面的間隔（停下來之後的第一張不算）
            val nowMs = SystemClock.uptimeMillis()
            val d = nowMs - lastShowMs
            if (lastShowMs != 0L && d in 1L..150L) frameMs += (d - frameMs) * .15f
            lastShowMs = nowMs
            if (out != null) { current = out; currentStyle = style; version++ }
            if (shown && !directShown) directShown = true
            if (failed || (style != null && style in badStyles)) version++
            val again = synchronized(lock) { if (hasPending && !failed && pendStyle !in badStyles) true else { inFlight = false; false } }
            if (again) handler?.post(render)
        }
    }

    private fun pass(prog: Program, w: Int, h: Int, flipY: Boolean) {
        GLES20.glViewport(0, 0, w, h)
        GLES20.glUseProgram(prog.id)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE3)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, noiseTexQ)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, noiseTexL)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, noiseTex)
        // 玻璃小圖（120×68）用粗網格：密網格的頂點計算跟畫面大小無關，小圖照樣要算一整張（扭曲色帶 96×54 光這一步就 7ms）
        val g = if (w * h <= 200 * 120) prog.small else prog.full
        prog.bind(w, h, work, g.grid, flipY)
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, g.n, GLES20.GL_UNSIGNED_SHORT, g.idx)
        if (workStyle == BgStyle.WATERCOLOR) drawDrops(w, h, flipY)
    }

    private var dropProg: Program? = null

    // ── 扭曲色帶的輕微高斯模糊：先畫進一張同樣大小的貼圖，再用 3×3 高斯（1-2-1）輸出到畫面 ──
    // 細的深色帶在低解析度下只有一兩個像素寬，放大後是階梯；柔化交界（fwidth）不夠，要真的模糊一層。
    // 四次雙線性取樣（各偏半個像素）＝剛好是 [1 2 1]⊗[1 2 1]／16，多一次全畫面的貼圖（海信 640×360 約 1～2ms）
    private var blurFbo = 0
    private var blurTex = 0
    private var blurW = 0
    private var blurH = 0
    private var blurProg: Program? = null
    private var blurFailed = false

    /** 離屏貼圖（模糊、分兩段共用；兩種樣式不會同時出現）：大小不對就重配。不完整回傳 false。 */
    private fun offscreen(w: Int, h: Int): Boolean {
        if (blurTex == 0) {
            val ids = IntArray(1)
            GLES20.glGenTextures(1, ids, 0); blurTex = ids[0]
            GLES20.glGenFramebuffers(1, ids, 0); blurFbo = ids[0]
            blurW = 0; blurH = 0
        }
        if (blurW != w || blurH != h) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, blurTex)
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
            for (pn in intArrayOf(GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_TEXTURE_MAG_FILTER)) GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, pn, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, blurFbo)
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, blurTex, 0)
            val st = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            if (st != GLES20.GL_FRAMEBUFFER_COMPLETE) {
                Log.w(TAG, "離屏緩衝不完整 0x${Integer.toHexString(st)}，改回一層直接畫")
                return false
            }
            blurW = w; blurH = h
        }
        return true
    }

    private fun passBlurred(prog: Program, w: Int, h: Int) {
        if (!offscreen(w, h)) { blurFailed = true; pass(prog, w, h, flipY = true); return }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, blurFbo)
        pass(prog, w, h, flipY = false)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        val bp = blurProg ?: Program(VERTEX, BLUR, 1, 1).also { blurProg = it }
        GLES20.glViewport(0, 0, w, h)
        GLES20.glUseProgram(bp.id)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, blurTex)
        bp.bind(w, h, work, bp.grid, flipY = true)
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, bp.gridN, GLES20.GL_UNSIGNED_SHORT, bp.gridIdx)
    }

    // ── B 方案（分兩段）：有明確邊緣的樣式，形狀在低解析度算成一張平滑的「場」（離屏貼圖，16 位元拆兩個通道），
    // 再用較高的解析度（[edgeLevel]，最高原生）讀這張場、在那個解析度的像素上判斷邊緣與反鋸齒（fwidth）。
    // 場是平滑的（相位的 cos、到圓弧的距離、雜訊值），雙線性放大不會有鋸齒；最貴的計算還是在低解析度做，
    // 第二段每個像素只讀一次貼圖、做幾次比較。海信（Mali-470）原生走一遍要 10ms 以上付不起，所以邊緣是形狀的兩倍解析度
    /** 分兩段畫的樣式（有明確邊緣的）。 */
    private val SPLIT = setOf(BgStyle.SPIRAL, BgStyle.TRUCHET, BgStyle.SIMPLEX, BgStyle.SAUCE)
    private var splitFailed = false
    private val fieldProgs = HashMap<BgStyle, Program>()
    private val edgeProgs = HashMap<BgStyle, Program>()

    /** 形狀在 ÷(lv+1) 算時，邊緣在幾檔切：解析度兩倍（÷2→原生、÷3／÷4→÷2、÷5→÷3）。 */
    private fun edgeLevel(lv: Int) = (lv + 2) / 2 - 1

    /** 分兩段畫到畫面（fw×fh 的場、w×h 的邊緣）。編譯失敗、離屏緩衝不能用就回傳 false（照舊一層畫）。 */
    private fun passSplit(style: BgStyle, fw: Int, fh: Int, w: Int, h: Int): Boolean {
        val fp: Program
        val ep: Program
        try {
            fp = fieldProgs.getOrPut(style) { Program(style.glVertex(), style.splitField(), gridSize(style).first, gridSize(style).second) }
            ep = edgeProgs.getOrPut(style) { Program(VERTEX, style.splitEdge(), 1, 1) }
        } catch (e: Exception) {
            Log.w(TAG, "分兩段的著色器不能用，改回一層：${e.message}")
            splitFailed = true
            return false
        }
        if (!offscreen(fw, fh)) { splitFailed = true; return false }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, blurFbo)
        pass(fp, fw, fh, flipY = false)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, w, h)
        GLES20.glUseProgram(ep.id)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE4)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, blurTex)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, noiseTex)
        ep.bind(w, h, work, ep.grid, flipY = true)
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, ep.gridN, GLES20.GL_UNSIGNED_SHORT, ep.gridIdx)
        return true
    }

    /** 3×3 高斯（1-2-1）：四次雙線性取樣，各往對角偏半個像素。u_noise 這裡是要模糊的那張圖（第 0 個貼圖單元）。 */
    private const val BLUR = """
precision mediump float;
uniform sampler2D u_noise;
uniform vec2 u_res;
varying vec2 v_uv;
void main() {
  vec2 d = 0.5 / u_res;
  gl_FragColor = 0.25 * (texture2D(u_noise, v_uv - d) + texture2D(u_noise, v_uv + d)
    + texture2D(u_noise, v_uv + vec2(d.x, -d.y)) + texture2D(u_noise, v_uv + vec2(-d.x, d.y)));
}
"""
    private var dropLoc = IntArray(3)

    /** 水彩：每一滴顏料各畫一塊只蓋住自己的網格，乘法混色疊在紙上（見 [Watercolor]）。 */
    private fun drawDrops(w: Int, h: Int, flipY: Boolean) {
        val p = dropProg ?: Program(Watercolor.DROP_VERTEX, Watercolor.DROP_SHADER, 8, 8).also { pr ->
            dropProg = pr
            dropLoc = intArrayOf(
                GLES20.glGetUniformLocation(pr.id, "u_va"), GLES20.glGetUniformLocation(pr.id, "u_a"), GLES20.glGetUniformLocation(pr.id, "u_b"),
            )
        }
        GLES20.glUseProgram(p.id)
        p.bind(w, h, work, p.grid, flipY)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_DST_COLOR, GLES20.GL_ZERO)
        for (i in 0 until 6) {
            val o = 4 + i * 8
            if (work[o + 3] < .004f) continue
            GLES20.glUniform4fv(dropLoc[0], 1, work, o)
            GLES20.glUniform4fv(dropLoc[1], 1, work, o)
            GLES20.glUniform4fv(dropLoc[2], 1, work, o + 4)
            GLES20.glDrawElements(GLES20.GL_TRIANGLES, p.gridN, GLES20.GL_UNSIGNED_SHORT, p.gridIdx)
        }
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    private var dataTex = 0

    /** 液態以太：CPU 模擬的結果（80×45 RGBA）上傳到第 2 個貼圖單元。 */
    private fun uploadData(style: BgStyle) {
        if (style != BgStyle.LIQUID) return
        if (dataTex == 0) {
            val ids = IntArray(1)
            GLES20.glGenTextures(1, ids, 0)
            dataTex = ids[0]
            GLES20.glActiveTexture(GLES20.GL_TEXTURE2)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, dataTex)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        }
        GLES20.glActiveTexture(GLES20.GL_TEXTURE2)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, dataTex)
        LiquidEther.glData.position(0)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, LiquidEther.W, LiquidEther.H, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, LiquidEther.glData)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
    }

    private fun draw(style: BgStyle): Bitmap? {
        if (levels[style.ordinal] < 0) levels[style.ordinal] = startLevel(style)
        val sz = SIZES
        val lv = workLevel.coerceIn(0, sz.size - 1)
        // 分兩段（B 方案）：形狀在 lv 算，邊緣在 [edgeLevel] 切；畫面緩衝是切邊緣的大小
        val split = workSplit && lv > 0 && style in SPLIT && !splitFailed
        val el = if (split) edgeLevel(lv) else lv
        var w = sz[el][0]
        var h = sz[el][1]
        val t = tex
        // 液態以太：流體模擬在這條執行緒推進（不佔主執行緒），算完上傳
        if (style == BgStyle.LIQUID) LiquidEther.simulate()
        if (ctx != EGL14.EGL_NO_CONTEXT) uploadData(style)
        if (t != null && windowOk) {
            // 直接顯示：全螢幕那張畫進 TextureView（交換緩衝），玻璃另外畫一張 120×68 的小圖讀回
            ensureGl(BackgroundEngine.GLASS_W, BackgroundEngine.GLASS_H)
            if (win == EGL14.EGL_NO_SURFACE) {
                val sf = android.view.Surface(t)
                win = EGL14.eglCreateWindowSurface(dpy, config, sf, intArrayOf(EGL14.EGL_NONE), 0)
                if (win == EGL14.EGL_NO_SURFACE) {
                    sf.release()
                    windowOk = false; directOk = false
                    Log.w(TAG, "eglCreateWindowSurface 0x${Integer.toHexString(EGL14.eglGetError())}，改用讀回")
                } else {
                    texSurface = sf
                    EGL14.eglMakeCurrent(dpy, win, win, ctx)
                    EGL14.eglSwapInterval(dpy, 0)
                }
            }
            if (win != EGL14.EGL_NO_SURFACE) {
                if (winLevel != w * 10000 + h) { t.setDefaultBufferSize(w, h); winLevel = w * 10000 + h }
                if (!EGL14.eglMakeCurrent(dpy, win, win, ctx)) throw IllegalStateException("eglMakeCurrent(window)")
                val prog = programs.getOrPut(style) { Program(style) }
                // 每 3 張量一次 GPU 耗時（glFinish 會讓這條執行緒等 GPU，平常不用）；只量「穩定在學到的檔位」的張
                val n = frameNo++
                val timed = workStable && n % 3 == 0L
                val t0 = SystemClock.elapsedRealtimeNanos()
                when {
                    split && passSplit(style, sz[lv][0], sz[lv][1], w, h) -> {}
                    style == BgStyle.WARP && !blurFailed -> passBlurred(prog, w, h)
                    else -> pass(prog, w, h, flipY = true)
                }
                if (timed) GLES20.glFinish()
                val tg = SystemClock.elapsedRealtimeNanos()
                if (!EGL14.eglSwapBuffers(dpy, win)) {
                    Log.w(TAG, "eglSwapBuffers 0x${Integer.toHexString(EGL14.eglGetError())}")
                    destroyWindow()
                }
                val t1 = SystemClock.elapsedRealtimeNanos()
                var bmp: Bitmap? = null
                // 玻璃小圖照 GlassRate 的間隔（依負載每秒 8～30 張；照時間，不照張數：操作中、每秒畫幾張都一樣；容許早 8ms），
                // 引擎每收到一張就更新玻璃（玻璃在兩張之間交叉淡化）
                val now = SystemClock.elapsedRealtimeNanos()
                if (now - lastGlassNs >= ((tw.liuli.launcher.ui.glass.GlassRate.intervalMs - 8f) * 1e6f).toLong() || current == null) {
                    lastGlassNs = now
                    if (!EGL14.eglMakeCurrent(dpy, surf, surf, ctx)) throw IllegalStateException("eglMakeCurrent(pbuffer)")
                    pass(prog, BackgroundEngine.GLASS_W, BackgroundEngine.GLASS_H, flipY = false)
                    bmp = readBack(BackgroundEngine.GLASS_W, BackgroundEngine.GLASS_H)
                }
                if (timed && split == (style in SPLIT && !splitFailed && lv > 0)) measure(style, lv, w, h, tg - t0, t1 - tg, SystemClock.elapsedRealtimeNanos() - t1, direct = true)
                return bmp
            }
        }
        // 讀回模式（沒有 TextureView）：大圖讀回付不起，最多 480×270
        val rb = minOf(3, sz.size - 1)   // ÷4
        if (lv < rb) { w = sz[rb][0]; h = sz[rb][1] }
        if (!ensureGl(w, h)) return null
        if (!EGL14.eglMakeCurrent(dpy, surf, surf, ctx)) throw IllegalStateException("eglMakeCurrent")
        val prog = programs.getOrPut(style) { Program(style) }
        val t0 = SystemClock.elapsedRealtimeNanos()
        pass(prog, w, h, flipY = false)
        GLES20.glFinish()
        val tg = SystemClock.elapsedRealtimeNanos()
        val bmp = readBack(w, h)
        val t1 = SystemClock.elapsedRealtimeNanos()
        if (workStable) measure(style, lv, w, h, tg - t0, t1 - tg, 0L, direct = false)
        return bmp
    }

    /** 讀回目前的 pbuffer（w×h）到輪替的點陣圖。gl_FragCoord.y＝0 的那一列先讀出來，正好是圖的第一列（y 往下），不用翻轉。 */
    private fun readBack(w: Int, h: Int): Bitmap {
        val buf = readBuf.let { if (it == null || it.capacity() < w * h * 4) ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder()).also { b -> readBuf = b } else it }
        buf.clear()
        GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
        val err = GLES20.glGetError()
        if (err != GLES20.GL_NO_ERROR) throw IllegalStateException("glReadPixels 0x${Integer.toHexString(err)}")
        ringI = (ringI + 1) % ring.size
        var bmp = ring[ringI]
        if (bmp == null || bmp.width != w || bmp.height != h) {
            bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            ring[ringI] = bmp
        }
        buf.rewind()
        bmp!!.copyPixelsFromBuffer(buf)
        return bmp
    }

    private fun destroyWindow() {
        if (win != EGL14.EGL_NO_SURFACE && dpy != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(dpy, surf, surf, ctx)
            EGL14.eglDestroySurface(dpy, win)
        }
        win = EGL14.EGL_NO_SURFACE
        texSurface?.release(); texSurface = null
        winLevel = -1
    }

    /** [read]：讀回（直接顯示時＝交換緩衝）；[copy]：直接顯示時＝玻璃小圖（畫＋讀回）。升降檔的判斷見 [levels]。 */
    private fun measure(style: BgStyle, lv: Int, w: Int, h: Int, gpu: Long, read: Long, copy: Long, direct: Boolean) {
        val si = style.ordinal
        val sz = SIZES
        if (lv >= sz.size) return   // 原生大小剛換過，檔位重建了
        if (style != measuredStyle || lv != measuredLevel) {
            // 換樣式、換檔位：重新量（剛換的前幾張特別慢：編譯著色器、配置緩衝）
            if (style != measuredStyle) { slowWins = 0; fastWins = 0 }
            measuredStyle = style; measuredLevel = lv; warm = 17; nFrames = 0; sumGpu = 0; sumRead = 0; sumCopy = 0
        }
        if (warm < 20) { warm++; return }
        val g1 = (if (direct) gpu else gpu + read) / 1e6f
        winBuf[nFrames] = g1
        sumGpu += gpu; sumRead += read; sumCopy += copy
        if (++nFrames < winBuf.size) return
        val sorted = winBuf.copyOf(); sorted.sort()
        val g = (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2f   // 中位數：偶爾排在介面後面等 GPU 的那幾張不算
        if (verbose || reports++ < 3) Log.i(TAG, "${style.id} ${w}x$h ${if (direct) "direct: gpu %.1fms + swap %.1fms, glass %.1fms" else "readback: gpu %.1fms + read %.1fms, -%.0f"}, %.1f fps".format(g, sumRead / nFrames / 1e6f, sumCopy / nFrames / 1e6f, 1000f / frameMs))
        nFrames = 0; sumGpu = 0; sumRead = 0; sumCopy = 0
        lastCost[si] = g
        if (debugLevel >= 0) return
        val now = SystemClock.uptimeMillis()
        val slow = g > SLOW_MS
        val verySlow = g > VERY_SLOW_MS
        // 升檔要「推估」付得起：約 30% 是固定成本、70% 跟像素數成正比
        val canUp = lv > 0 && g * (.3f + .7f * cost(style, sz, lv - 1) / cost(style, sz, lv)) <= UP_MS
        when {
            slow -> {
                fastWins = 0
                if ((++slowWins >= 2 || verySlow) && lv < sz.size - 1) {
                    slowWins = 0
                    levels[si] = lv + 1
                    lastCost[si] = -1f
                    upBackoff = if (now - lastUpMs < 1500) minOf(upBackoff * 2, 60_000L) else 3000L
                    upBlockedUntil = now + upBackoff
                    Log.i(TAG, "${style.id} 太慢（%.1fms），降到 ${sz[lv + 1][0]}x${sz[lv + 1][1]}".format(g))
                }
            }
            canUp -> {
                slowWins = 0
                if (++fastWins >= 2 && now >= upBlockedUntil) {
                    fastWins = 0
                    levels[si] = lv - 1
                    lastCost[si] = -1f
                    lastUpMs = now
                    Log.i(TAG, "${style.id} 付得起（%.1fms），升到 ${sz[lv - 1][0]}x${sz[lv - 1][1]}".format(g))
                }
            }
            else -> { slowWins = 0; fastWins = 0 }
        }
    }

    private var measuredLevel = -1
    private fun px(sz: Array<IntArray>, lv: Int) = (sz[lv][0] * sz[lv][1]).toFloat()
    /** 推估成本用的像素數：分兩段的樣式＝場的像素＋切邊緣的像素 ×0.35（第二段每個像素只讀一次貼圖）。 */
    private fun cost(style: BgStyle, sz: Array<IntArray>, lv: Int) =
        if (style in SPLIT && !splitFailed && lv > 0) px(sz, lv) + .35f * px(sz, edgeLevel(lv)) else px(sz, lv)

    // ───────────── EGL ─────────────

    private fun ensureGl(w: Int, h: Int): Boolean {
        if (ctx == EGL14.EGL_NO_CONTEXT) {
            dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            val ver = IntArray(2)
            if (dpy == EGL14.EGL_NO_DISPLAY || !EGL14.eglInitialize(dpy, ver, 0, ver, 1)) throw IllegalStateException("eglInitialize")
            config = chooseConfig(8, true) ?: chooseConfig(0, true)
            if (config == null) { windowOk = false; directOk = false }
            config = config ?: chooseConfig(8, false) ?: chooseConfig(0, false) ?: throw IllegalStateException("eglChooseConfig")
            ctx = EGL14.eglCreateContext(dpy, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
            if (ctx == EGL14.EGL_NO_CONTEXT) throw IllegalStateException("eglCreateContext 0x${Integer.toHexString(EGL14.eglGetError())}")
            surfW = 0; surfH = 0
        }
        if (surfW != w || surfH != h) {
            if (surf != EGL14.EGL_NO_SURFACE) {
                EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                EGL14.eglDestroySurface(dpy, surf)
            }
            surf = EGL14.eglCreatePbufferSurface(dpy, config, intArrayOf(EGL14.EGL_WIDTH, w, EGL14.EGL_HEIGHT, h, EGL14.EGL_NONE), 0)
            if (surf == EGL14.EGL_NO_SURFACE) throw IllegalStateException("eglCreatePbufferSurface 0x${Integer.toHexString(EGL14.eglGetError())}")
            surfW = w; surfH = h
            if (!EGL14.eglMakeCurrent(dpy, surf, surf, ctx)) throw IllegalStateException("eglMakeCurrent")
            if (noiseTex == 0) initResources()
        }
        return true
    }

    private fun chooseConfig(alpha: Int, window: Boolean): EGLConfig? {
        val attrs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, alpha,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT or (if (window) EGL14.EGL_WINDOW_BIT else 0), EGL14.EGL_NONE,
        )
        val out = arrayOfNulls<EGLConfig>(1)
        val n = IntArray(1)
        return if (EGL14.eglChooseConfig(dpy, attrs, 0, out, 0, 1, n, 0) && n[0] > 0) out[0] else null
    }

    private fun initResources() {
        // 共用的雜訊貼圖：64×64、每個像素 RGBA 四個獨立的均勻亂數（xorshift32）。2 的次方尺寸，ES 2.0 才能 REPEAT
        val n = 64
        val px = ByteBuffer.allocateDirect(n * n * 4).order(ByteOrder.nativeOrder())
        var z = 0x9E3779B9.toInt()
        repeat(n * n) {
            z = z xor (z shl 13); z = z xor (z ushr 17); z = z xor (z shl 5)
            px.putInt(z)
        }
        px.position(0)
        // 同一份資料兩個貼圖物件：最近鄰（抖色）與線性內插（當 value noise 用；ES 2.0 的取樣方式綁在貼圖上）
        val ids = IntArray(2)
        GLES20.glGenTextures(2, ids, 0)
        noiseTex = ids[0]
        noiseTexL = ids[1]
        for ((id, filter) in listOf(noiseTex to GLES20.GL_NEAREST, noiseTexL to GLES20.GL_LINEAR)) {
            px.position(0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id)
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, n, n, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, px)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, filter)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, filter)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_REPEAT)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_REPEAT)
        }
        // 四角打包的 value noise 貼圖：像素 (i,j) 的 RGBA＝格點 (i,j)、(i+1,j)、(i,j+1)、(i+1,j+1) 的值（取 R 通道，週期 64）。見 [NOISE]
        val q = ByteBuffer.allocateDirect(n * n * 4).order(ByteOrder.nativeOrder())
        fun r(i: Int, j: Int) = px.get(((j and 63) * n + (i and 63)) * 4)
        for (j in 0 until n) for (i in 0 until n) { q.put(r(i, j)); q.put(r(i + 1, j)); q.put(r(i, j + 1)); q.put(r(i + 1, j + 1)) }
        q.position(0)
        val qi = IntArray(1)
        GLES20.glGenTextures(1, qi, 0)
        noiseTexQ = qi[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, noiseTexQ)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, n, n, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, q)
        for (pn in intArrayOf(GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_TEXTURE_MAG_FILTER)) GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, pn, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_REPEAT)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_REPEAT)
        val mv = IntArray(1)
        GLES20.glGetIntegerv(GLES20.GL_MAX_FRAGMENT_UNIFORM_VECTORS, mv, 0)
        Log.i(TAG, "max fragment uniform vectors ${mv[0]}")
        val rg = IntArray(2); val pr = IntArray(1)
        GLES20.glGetShaderPrecisionFormat(GLES20.GL_FRAGMENT_SHADER, GLES20.GL_HIGH_FLOAT, rg, 0, pr, 0)
        val hp = pr[0] >= 20 && !debugNoHighp
        if (hp != fragHighp) { fragHighp = hp; programs.clear(); dropProg = null; fieldProgs.clear(); edgeProgs.clear() }
        Log.i(TAG, "fragment highp precision ${pr[0]} bits → ${if (fragHighp) "扭曲色帶逐像素算" else "扭曲色帶頂點算"}")
        val ext = GLES20.glGetString(GLES20.GL_EXTENSIONS) ?: ""
        Log.i(TAG, "GL ${GLES20.glGetString(GLES20.GL_RENDERER)} / ${GLES20.glGetString(GLES20.GL_VERSION)}, derivatives=${ext.contains("GL_OES_standard_derivatives")}")
    }

    private fun destroyGl() {
        destroyWindow()
        programs.clear()
        dropProg = null
        blurProg = null; blurTex = 0; blurFbo = 0; blurW = 0; blurH = 0
        fieldProgs.clear(); edgeProgs.clear()
        noiseTex = 0
        noiseTexL = 0
        noiseTexQ = 0
        dataTex = 0
        readBuf = null
        if (dpy != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (surf != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(dpy, surf)
            if (ctx != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(dpy, ctx)
            EGL14.eglTerminate(dpy)
        }
        dpy = EGL14.EGL_NO_DISPLAY; ctx = EGL14.EGL_NO_CONTEXT; surf = EGL14.EGL_NO_SURFACE
        surfW = 0; surfH = 0; warm = 0; nFrames = 0; sumGpu = 0; sumCopy = 0
    }

    /**
     * 全螢幕網格（不是一個四邊形）的格數：低頻的量放在頂點著色器算（全精度），片段著色器拿內插好的值。
     * 在頂點算的量越細，網格要越密，內插的折角才看不出來（扭曲色帶 96×54、色塊雜訊 64×36）；
     * 但網格太密，三角形只剩幾個像素，Mali-470 的片段處理效率會掉很多（極光簾 320×180 配 96×54 從 15ms 變 35ms），其他樣式用 32×18。
     */
    private fun gridSize(style: BgStyle) = when (style) {
        BgStyle.WARP -> if (debugGrid > 0) debugGrid to debugGrid * 9 / 16 else if (fragHighp) 32 to 18 else 96 to 54
        BgStyle.SIMPLEX -> 64 to 36
        else -> 32 to 18
    }

    /**
     * 一個著色器樣式：全螢幕網格＋頂點／片段著色器。uniform：u_res（像素）、u_noise（雜訊貼圖）、
     * u_p[0..PARAMS／4)（片段著色器用的樣式參數，vec4 陣列）、u_w／u_v1（頂點著色器用，＝u_p[0]／u_p[1]；分開宣告，兩邊的精度才不會衝突）、
     * u_noiseL（線性內插的雜訊貼圖，第 1 個貼圖單元）。
     */
    /** 全螢幕網格：(gx+1)×(gy+1) 個頂點、gx×gy 格，每格兩個三角形。 */
    private class Grid(gx: Int, gy: Int) {
        val grid: FloatBuffer = ByteBuffer.allocateDirect((gx + 1) * (gy + 1) * 2 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().also { g ->
            for (j in 0..gy) for (i in 0..gx) { g.put(i * 2f / gx - 1f); g.put(j * 2f / gy - 1f) }
            g.position(0)
        }
        val n = gx * gy * 6
        val idx: java.nio.ShortBuffer = ByteBuffer.allocateDirect(n * 2).order(ByteOrder.nativeOrder()).asShortBuffer().also { ix ->
            for (j in 0 until gy) for (i in 0 until gx) {
                val a = (j * (gx + 1) + i).toShort()
                val b = (a + 1).toShort()
                val c = (a + gx + 1).toShort()
                val d = (c + 1).toShort()
                ix.put(a); ix.put(b); ix.put(c); ix.put(b); ix.put(d); ix.put(c)
            }
            ix.position(0)
        }
    }

    private class Program(vert: String, frag: String, gx: Int, gy: Int) {
        constructor(style: BgStyle) : this(style.glVertex(), style.glShader(), gridSize(style).first, gridSize(style).second)
        val id: Int
        val full = Grid(gx, gy)
        /** 玻璃小圖用的粗網格（最多 32×18）。 */
        val small = if (gx <= 32) full else Grid(32, 18)
        val grid get() = full.grid
        val gridN get() = full.n
        val gridIdx get() = full.idx
        private val aPos: Int
        private val uRes: Int
        private val uNoise: Int
        private val uP: Int
        private val uW: Int
        private val uFy: Int
        private val uV1: Int
        private val uNoiseL: Int
        private val uData: Int
        private val uNoiseQ: Int
        private val uVt: Int
        private val uVf: Int
        private val uField: Int

        init {
            val vs = compile(GLES20.GL_VERTEX_SHADER, vert)
            // 片段著色器支援全精度的 GPU（PowerVR 等）全部用全精度：半精度是為了 Mali-470 寫的，
            // 在這些 GPU 上反而讓色帶邊緣起毛（150 的漩渦）。Mali 只有半精度，照原樣
            val fs = compile(GLES20.GL_FRAGMENT_SHADER, if (fragHighp) frag.replace("precision mediump float;", "precision highp float;") else frag)
            id = GLES20.glCreateProgram()
            GLES20.glAttachShader(id, vs)
            GLES20.glAttachShader(id, fs)
            GLES20.glLinkProgram(id)
            val ok = IntArray(1)
            GLES20.glGetProgramiv(id, GLES20.GL_LINK_STATUS, ok, 0)
            if (ok[0] == 0) throw IllegalStateException("link: " + GLES20.glGetProgramInfoLog(id))
            aPos = GLES20.glGetAttribLocation(id, "a_pos")
            uRes = GLES20.glGetUniformLocation(id, "u_res")
            uNoise = GLES20.glGetUniformLocation(id, "u_noise")
            uP = GLES20.glGetUniformLocation(id, "u_p")
            uW = GLES20.glGetUniformLocation(id, "u_w")
            uFy = GLES20.glGetUniformLocation(id, "u_fy")
            uV1 = GLES20.glGetUniformLocation(id, "u_v1")
            uNoiseL = GLES20.glGetUniformLocation(id, "u_noiseL")
            uData = GLES20.glGetUniformLocation(id, "u_data")
            uNoiseQ = GLES20.glGetUniformLocation(id, "u_noiseQ")
            uVt = GLES20.glGetUniformLocation(id, "u_vt")
            uVf = GLES20.glGetUniformLocation(id, "u_vf")
            uField = GLES20.glGetUniformLocation(id, "u_field")
        }

        fun bind(w: Int, h: Int, p: FloatArray, quad: FloatBuffer, flipY: Boolean) {
            GLES20.glUniform2f(uRes, w.toFloat(), h.toFloat())
            // 直接顯示時畫面的第一列在上面（GL 的 y＝0 在下面）：頂點上下翻轉；讀回時本來就對了
            if (uFy >= 0) GLES20.glUniform1f(uFy, if (flipY) -1f else 1f)
            if (uNoise >= 0) GLES20.glUniform1i(uNoise, 0)
            // 參數以 vec4 陣列傳入（PARAMS／4 個），著色器裡用常數索引取用
            if (uP >= 0) GLES20.glUniform4fv(uP, PARAMS / 4, p, 0)
            if (uW >= 0) GLES20.glUniform4fv(uW, 1, p, 0)
            if (uV1 >= 0) GLES20.glUniform4fv(uV1, 1, p, 4)
            if (uNoiseL >= 0) GLES20.glUniform1i(uNoiseL, 1)
            if (uData >= 0) GLES20.glUniform1i(uData, 2)
            if (uNoiseQ >= 0) GLES20.glUniform1i(uNoiseQ, 3)
            if (uVt >= 0) GLES20.glUniform4fv(uVt, 1, p, 56)
            if (uVf >= 0) GLES20.glUniform4fv(uVf, 1, p, 60)
            if (uField >= 0) GLES20.glUniform1i(uField, 4)
            GLES20.glEnableVertexAttribArray(aPos)
            GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, quad)
        }

        private fun compile(type: Int, src: String): Int {
            val s = GLES20.glCreateShader(type)
            GLES20.glShaderSource(s, src)
            GLES20.glCompileShader(s)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) throw IllegalStateException("compile: " + GLES20.glGetShaderInfoLog(s))
            return s
        }
    }

    /**
     * 共用的雜訊函式（半精度安全）。Mali-470 的片段著色器只有半精度：座標一大（例如加上取模 64 的時間），小數就只剩 1／16～1／32，
     * 線性內插貼圖的取樣位置也一樣粗——扭曲色帶因此一格一格、色塊雜訊與特魯謝緞帶的邊緣鋸齒。所以：
     * - 座標保持很小：時間拆成整數（格子位移 ti，整數在半精度下是精確的）與小數（加在座標上）兩部分，由 [putTimes] 放在 u_p[14]／u_p[15]
     * - value noise 不用線性內插的貼圖：一個像素存「自己和右、下、右下」四個格點的值（u_noiseQ，最近鄰），
     *   取一次就拿到四個角，內插在著色器裡用半精度的小數做（和以前線性內插貼圖的結果是同一個函數）
     * gt(p, ti)：value noise 0～1；gnt(p, ti)：梯度雜訊約 −1～1。ti 必須是整數。
     */
    const val NOISE = """
uniform sampler2D u_noiseQ;
float gt(vec2 p, vec2 ti) {
  vec2 i = floor(p);
  vec2 f = p - i;
  f = f * f * (3.0 - 2.0 * f);
  vec4 c = texture2D(u_noiseQ, (mod(i + ti, 64.0) + 0.5) / 64.0);
  return mix(mix(c.r, c.g, f.x), mix(c.b, c.a, f.x), f.y);
}
float g(vec2 p) { return gt(p, vec2(0.0)); }
vec2 gr(vec2 i) { return texture2D(u_noise, (mod(i, 64.0) + 0.5) / 64.0).rg * 2.0 - 1.0; }
float gnt(vec2 p, vec2 ti) {
  vec2 i0 = floor(p);
  vec2 f = p - i0;
  vec2 i = i0 + ti;
  vec2 u = f * f * f * (f * (f * 6.0 - 15.0) + 10.0);
  float a = dot(gr(i), f);
  float b = dot(gr(i + vec2(1.0, 0.0)), f - vec2(1.0, 0.0));
  float c = dot(gr(i + vec2(0.0, 1.0)), f - vec2(0.0, 1.0));
  float d = dot(gr(i + vec2(1.0, 1.0)), f - vec2(1.0, 1.0));
  return 1.6 * mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}
"""

    /**
     * 頂點著色器用的 value noise（全精度；Mali-400 系列的頂點著色器不能讀貼圖，用雜湊）。
     * 低頻、平滑的量（位移場、扭曲座標）放在頂點算再內插：片段著色器是半精度，這些量算到後面誤差會被放大成鋸齒。
     * u_vt／u_vf＝u_p[14]／u_p[15]（[putTimes] 的時間整數／小數，頂點這邊加起來就是全精度的時間）。
     */
    const val VNOISE = """
uniform vec4 u_vt;
uniform vec4 u_vf;
float hv(vec2 i) { return fract(sin(dot(i, vec2(127.1, 311.7))) * 43758.5453); }
float gv(vec2 p) {
  vec2 i = floor(p);
  vec2 f = p - i;
  f = f * f * (3.0 - 2.0 * f);
  return mix(mix(hv(i), hv(i + vec2(1.0, 0.0)), f.x), mix(hv(i + vec2(0.0, 1.0)), hv(i + vec2(1.0, 1.0)), f.x), f.y);
}
"""

    /** 最多四個時間相位（取模 64）拆成整數（u_p[14]）與小數（u_p[15]），給 gt／gnt 用。 */
    fun putTimes(out: FloatArray, t0: Double, t1: Double = 0.0, t2: Double = 0.0, t3: Double = 0.0) {
        for ((k, t) in doubleArrayOf(t0, t1, t2, t3).withIndex()) {
            val w = ((t % 64.0) + 64.0) % 64.0
            val i = kotlin.math.floor(w)
            out[56 + k] = i.toFloat(); out[60 + k] = (w - i).toFloat()
        }
    }

    /**
     * 高光壓縮（所有著色器樣式共用）：亮度超過 0.55 的部分壓扁（最亮約 0.75），色相與明暗結構不變——
     * 主畫面上是白字，背景整片淡色時字會看不清楚。
     */
    const val TONE = "{ float tl = dot(col, vec3(0.299, 0.587, 0.114)); if (tl > 0.55) col *= (0.55 + (tl - 0.55) * 0.45) / tl; }\n  "

    /** 預設的頂點著色器：v_uv＝畫面座標 0～1（y 往下，和讀回的圖一致）。 */
    const val VERTEX = """
attribute vec2 a_pos;
uniform float u_fy;
varying vec2 v_uv;
void main() { v_uv = a_pos * 0.5 + 0.5; gl_Position = vec4(a_pos.x, a_pos.y * u_fy, 0.0, 1.0); }
"""
}

/** 著色器樣式的頂點著色器。 */
internal fun BgStyle.glVertex(): String = when (this) {
    BgStyle.SAUCE -> GlStyles.Sauce.VERTEX
    BgStyle.SIMPLEX -> GlStyles2.Simplex.VERTEX
    BgStyle.WARP -> if (GlBackground.fragHighp) GlBackground.VERTEX else GlStyles2.Warp.VERTEX
    BgStyle.SOFTAURORA -> GlStyles2.SoftAurora.VERTEX
    else -> GlBackground.VERTEX
}

/** B 方案第一段：把形狀的「場」編成 16 位元寫進離屏貼圖。 */
internal fun BgStyle.splitField(): String = when (this) {
    BgStyle.SPIRAL -> GlStyles2.Spiral.FIELD
    BgStyle.TRUCHET -> GlStyles2.Truchet.FIELD
    BgStyle.SIMPLEX -> GlStyles2.Simplex.FIELD
    BgStyle.SAUCE -> GlStyles.Sauce.FIELD
    else -> throw IllegalArgumentException("$id 不分兩段")
}

/** B 方案第二段：讀場、在較高解析度切邊緣上色。 */
internal fun BgStyle.splitEdge(): String = when (this) {
    BgStyle.SPIRAL -> GlStyles2.Spiral.EDGE
    BgStyle.TRUCHET -> GlStyles2.Truchet.EDGE
    BgStyle.SIMPLEX -> GlStyles2.Simplex.EDGE
    BgStyle.SAUCE -> GlStyles.Sauce.EDGE
    else -> throw IllegalArgumentException("$id 不分兩段")
}

/** 著色器樣式的片段著色器原始碼。 */
internal fun BgStyle.glShader(): String = when (this) {
    BgStyle.MESH -> MeshGradient.SHADER
    BgStyle.SAUCE -> GlStyles.Sauce.SHADER
    BgStyle.WATERCOLOR -> Watercolor.SHADER
    BgStyle.SIMPLEX -> GlStyles2.Simplex.SHADER
    BgStyle.WARP -> if (GlBackground.fragHighp) GlStyles2.Warp.SHADER_HP else GlStyles2.Warp.SHADER
    BgStyle.SOFTAURORA -> GlStyles2.SoftAurora.SHADER
    BgStyle.CURTAIN -> GlStyles2.Curtain.SHADER
    BgStyle.LIQUID -> LiquidEther.SHADER
    BgStyle.SPIRAL -> GlStyles2.Spiral.SHADER
    BgStyle.TRUCHET -> GlStyles2.Truchet.SHADER
    BgStyle.BLINDS -> GlStyles.Blinds.SHADER
    else -> error("不是著色器樣式：$id")
}

/** 著色器樣式的參數（主執行緒每格算一次：時間相位取模、色點軌跡、調色盤）。 */
internal fun BgStyle.glParams(st: BgState, out: FloatArray) = when (this) {
    BgStyle.MESH -> MeshGradient.params(st, out)
    BgStyle.SAUCE -> GlStyles.Sauce.params(st, out)
    BgStyle.WATERCOLOR -> Watercolor.params(st, out)
    BgStyle.SIMPLEX -> GlStyles2.Simplex.params(st, out)
    BgStyle.WARP -> GlStyles2.Warp.params(st, out)
    BgStyle.SOFTAURORA -> GlStyles2.SoftAurora.params(st, out)
    BgStyle.CURTAIN -> GlStyles2.Curtain.params(st, out)
    BgStyle.LIQUID -> LiquidEther.params(st, out)
    BgStyle.SPIRAL -> GlStyles2.Spiral.params(st, out)
    BgStyle.TRUCHET -> GlStyles2.Truchet.params(st, out)
    BgStyle.BLINDS -> GlStyles.Blinds.params(st, out)
    else -> Unit
}
