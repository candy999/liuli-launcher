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
    const val PARAMS = 40

    /** EGL 或著色器編譯失敗：改用 CPU 的備援畫法，不再嘗試。 */
    @Volatile var failed = false
        private set

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
     * 解析度檔位（每個樣式各自一個）：0＝480×270、1＝320×180。起始檔位見 [startLevel]；
     * 480×270 一格平均超過 [SLOW_MS] 就降一檔。量到的 GPU 耗時含「排在介面後面等 GPU」的時間（海信實測同一個樣式 4～12ms 都有），
     * 所以門檻放寬，只抓真的付不起的樣式。[debugLevel]（adb ACTION=glres:1）強制檔位，-1＝自動。
     */
    private val levels = IntArray(BgStyle.entries.size) { -1 }
    @Volatile var debugLevel = -1

    /** 起始檔位（全部從 480×270 開始，付不起再降）。 */
    private fun startLevel(@Suppress("UNUSED_PARAMETER") style: BgStyle) = 0
    private var measuredStyle: BgStyle? = null
    private val SIZES = arrayOf(intArrayOf(480, 270), intArrayOf(320, 180))
    private const val SLOW_MS = 20f

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
    private var grid: FloatBuffer? = null
    private var gridIdx: java.nio.ShortBuffer? = null
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
        override fun onSurfaceTextureSizeChanged(st: android.graphics.SurfaceTexture, w: Int, h: Int) {}
        override fun onSurfaceTextureUpdated(st: android.graphics.SurfaceTexture) {}
    }

    /** 這個樣式付得出畫面了（第一張圖好了，或 GL 失敗改用 CPU 備援）。會順便觸發第一張。 */
    fun ready(style: BgStyle, st: BgState): Boolean {
        if (failed) return true
        if (current == null) request(style, st)
        return current != null
    }

    /** 背景推進了一格：用目前的狀態排一張新圖（主執行緒呼叫）。 */
    fun request(style: BgStyle, st: BgState) {
        if (failed) return
        synchronized(lock) {
            style.glParams(st, pend)
            pendStyle = style
            hasPending = true
            if (inFlight) return
            inFlight = true
        }
        ensureThread().post(render)
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
            workStyle = pendStyle
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
                Log.w(TAG, "GL 背景失敗，改用 CPU 畫法", t)
                failed = true
                runCatching { destroyGl() }
            }
        }
        main.post {
            if (out != null) { current = out; currentStyle = style; version++ }
            if (shown && !directShown) directShown = true
            if (failed) version++
            val again = synchronized(lock) { if (hasPending && !failed) true else { inFlight = false; false } }
            if (again) handler?.post(render)
        }
    }

    private fun pass(prog: Program, w: Int, h: Int, flipY: Boolean) {
        GLES20.glViewport(0, 0, w, h)
        GLES20.glUseProgram(prog.id)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, noiseTexL)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, noiseTex)
        prog.bind(w, h, work, grid!!, flipY)
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, GRID_X * GRID_Y * 6, GLES20.GL_UNSIGNED_SHORT, gridIdx!!)
    }

    private fun draw(style: BgStyle): Bitmap? {
        if (levels[style.ordinal] < 0) levels[style.ordinal] = startLevel(style)
        val lv = if (debugLevel in SIZES.indices) debugLevel else levels[style.ordinal]
        var w = SIZES[lv][0]
        var h = SIZES[lv][1]
        val t = tex
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
                if (winLevel != lv) { t.setDefaultBufferSize(w, h); winLevel = lv }
                if (!EGL14.eglMakeCurrent(dpy, win, win, ctx)) throw IllegalStateException("eglMakeCurrent(window)")
                val prog = programs.getOrPut(style) { Program(style.glVertex(), style.glShader()) }
                // 每 4 格量一次 GPU 耗時（glFinish 會讓 CPU 等 GPU，平常不用）；玻璃小圖每 2 格更新一次（玻璃很模糊，看不出差別）
                val n = frameNo++
                val timed = n % 4 == 0L
                val t0 = SystemClock.elapsedRealtimeNanos()
                pass(prog, w, h, flipY = true)
                if (timed) GLES20.glFinish()
                val tg = SystemClock.elapsedRealtimeNanos()
                if (!EGL14.eglSwapBuffers(dpy, win)) {
                    Log.w(TAG, "eglSwapBuffers 0x${Integer.toHexString(EGL14.eglGetError())}")
                    destroyWindow()
                }
                val t1 = SystemClock.elapsedRealtimeNanos()
                var bmp: Bitmap? = null
                if (n % 2 == 0L || current == null) {
                    if (!EGL14.eglMakeCurrent(dpy, surf, surf, ctx)) throw IllegalStateException("eglMakeCurrent(pbuffer)")
                    pass(prog, BackgroundEngine.GLASS_W, BackgroundEngine.GLASS_H, flipY = false)
                    bmp = readBack(BackgroundEngine.GLASS_W, BackgroundEngine.GLASS_H)
                }
                if (timed) measure(style, lv, w, h, tg - t0, t1 - tg, SystemClock.elapsedRealtimeNanos() - t1, direct = true)
                return bmp
            }
        }
        if (!ensureGl(w, h)) return null
        if (!EGL14.eglMakeCurrent(dpy, surf, surf, ctx)) throw IllegalStateException("eglMakeCurrent")
        val prog = programs.getOrPut(style) { Program(style.glVertex(), style.glShader()) }
        val t0 = SystemClock.elapsedRealtimeNanos()
        pass(prog, w, h, flipY = false)
        GLES20.glFinish()
        val tg = SystemClock.elapsedRealtimeNanos()
        val bmp = readBack(w, h)
        val t1 = SystemClock.elapsedRealtimeNanos()
        measure(style, lv, w, h, tg - t0, t1 - tg, 0L, direct = false)
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

    /** [read]：讀回（直接顯示時＝交換緩衝）；[copy]：直接顯示時＝玻璃小圖（畫＋讀回）。 */
    private fun measure(style: BgStyle, lv: Int, w: Int, h: Int, gpu: Long, read: Long, copy: Long, direct: Boolean) {
        if (style != measuredStyle) { measuredStyle = style; warm = 10; nFrames = 0; sumGpu = 0; sumRead = 0; sumCopy = 0 }
        if (warm < 20) { warm++; return } // 剛建好 context、編譯著色器、換樣式的前幾格特別慢
        sumGpu += gpu; sumRead += read; sumCopy += copy
        if (++nFrames < 30) return
        val g = (if (direct) sumGpu else sumGpu + sumRead) / nFrames / 1e6f
        if (verbose || reports++ < 3) Log.i(TAG, "${style.id} ${w}x$h ${if (direct) "direct: gpu %.1fms + swap %.1fms, glass %.1fms" else "readback: gpu %.1fms + read %.1fms, -%.0f"}".format(sumGpu / nFrames / 1e6f, sumRead / nFrames / 1e6f, sumCopy / nFrames / 1e6f))
        nFrames = 0; sumGpu = 0; sumRead = 0; sumCopy = 0
        if (debugLevel < 0 && lv == 0 && g > SLOW_MS) {
            levels[style.ordinal] = 1
            warm = 10
            Log.i(TAG, "太慢（%.1fms），改用 ${SIZES[1][0]}x${SIZES[1][1]}".format(g))
        }
    }

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
        // 全螢幕網格（不是一個四邊形）：低頻的座標扭曲、漩渦放在頂點著色器算（Mali-470 的頂點處理器是全精度，
        // 而且只有 33×19 個頂點），片段著色器拿內插好的座標，每個像素省下所有三角函數
        val g = ByteBuffer.allocateDirect((GRID_X + 1) * (GRID_Y + 1) * 2 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        for (j in 0..GRID_Y) for (i in 0..GRID_X) { g.put(i * 2f / GRID_X - 1f); g.put(j * 2f / GRID_Y - 1f) }
        g.position(0)
        grid = g
        val ix = ByteBuffer.allocateDirect(GRID_X * GRID_Y * 6 * 2).order(ByteOrder.nativeOrder()).asShortBuffer()
        for (j in 0 until GRID_Y) for (i in 0 until GRID_X) {
            val a = (j * (GRID_X + 1) + i).toShort()
            val b = (a + 1).toShort()
            val c = (a + GRID_X + 1).toShort()
            val d = (c + 1).toShort()
            ix.put(a); ix.put(b); ix.put(c); ix.put(b); ix.put(d); ix.put(c)
        }
        ix.position(0)
        gridIdx = ix
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
        val ext = GLES20.glGetString(GLES20.GL_EXTENSIONS) ?: ""
        Log.i(TAG, "GL ${GLES20.glGetString(GLES20.GL_RENDERER)} / ${GLES20.glGetString(GLES20.GL_VERSION)}, derivatives=${ext.contains("GL_OES_standard_derivatives")}")
    }

    private fun destroyGl() {
        destroyWindow()
        programs.clear()
        noiseTex = 0
        noiseTexL = 0
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

    private const val GRID_X = 32
    private const val GRID_Y = 18

    /**
     * 一個著色器樣式：全螢幕網格＋頂點／片段著色器。uniform：u_res（像素）、u_noise（雜訊貼圖）、
     * u_p[0..PARAMS／4)（片段著色器用的樣式參數，vec4 陣列）、u_w／u_v1（頂點著色器用，＝u_p[0]／u_p[1]；分開宣告，兩邊的精度才不會衝突）、
     * u_noiseL（線性內插的雜訊貼圖，第 1 個貼圖單元）。
     */
    private class Program(vert: String, frag: String) {
        val id: Int
        private val aPos: Int
        private val uRes: Int
        private val uNoise: Int
        private val uP: Int
        private val uW: Int
        private val uFy: Int
        private val uV1: Int
        private val uNoiseL: Int

        init {
            val vs = compile(GLES20.GL_VERTEX_SHADER, vert)
            val fs = compile(GLES20.GL_FRAGMENT_SHADER, frag)
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
    BgStyle.SWIRL -> GlStyles.Swirl.VERTEX
    else -> GlBackground.VERTEX
}

/** 著色器樣式的片段著色器原始碼。 */
internal fun BgStyle.glShader(): String = when (this) {
    BgStyle.MESH -> MeshGradient.SHADER
    BgStyle.SWIRL -> GlStyles.Swirl.SHADER
    BgStyle.BLINDS -> GlStyles.Blinds.SHADER
    else -> error("不是著色器樣式：$id")
}

/** 著色器樣式的參數（主執行緒每格算一次：時間相位取模、色點軌跡、調色盤）。 */
internal fun BgStyle.glParams(st: BgState, out: FloatArray) = when (this) {
    BgStyle.MESH -> MeshGradient.params(st, out)
    BgStyle.SWIRL -> GlStyles.Swirl.params(st, out)
    BgStyle.BLINDS -> GlStyles.Blinds.params(st, out)
    else -> Unit
}
