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

    /** [current] 每換一次加一；全螢幕背景在繪製階段讀它（著色器樣式不跟著引擎的 frame 重畫，等新圖來才畫）。 */
    var version by mutableLongStateOf(0L)
        private set

    /**
     * 解析度檔位：0＝480×270、1＝320×180。自動：480×270 一格（GPU＋讀回）平均超過 [SLOW_MS] 就降一檔。
     * [debugLevel]（adb ACTION=glres:1）強制檔位，-1＝自動。
     */
    @Volatile var level = 0
        private set
    @Volatile var debugLevel = -1
    private val SIZES = arrayOf(intArrayOf(480, 270), intArrayOf(320, 180))
    private const val SLOW_MS = 14f

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
    private var quad: FloatBuffer? = null
    private var readBuf: ByteBuffer? = null
    private val ring = arrayOfNulls<Bitmap>(3)
    private var ringI = 0
    private val work = FloatArray(PARAMS)
    private var workStyle: BgStyle? = null

    // 量測
    private var nFrames = 0
    private var sumGpu = 0L
    private var sumCopy = 0L
    private var warm = 0

    @Volatile private var releasePending = false

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
        if (style != null) {
            try {
                out = draw(style)
            } catch (t: Throwable) {
                Log.w(TAG, "GL 背景失敗，改用 CPU 畫法", t)
                failed = true
                runCatching { destroyGl() }
            }
        }
        main.post {
            if (out != null) { current = out; version++ }
            if (failed) version++
            val again = synchronized(lock) { if (hasPending && !failed) true else { inFlight = false; false } }
            if (again) handler?.post(render)
        }
    }

    private fun draw(style: BgStyle): Bitmap? {
        val lv = if (debugLevel in SIZES.indices) debugLevel else level
        val w = SIZES[lv][0]
        val h = SIZES[lv][1]
        if (!ensureGl(w, h)) return null
        val prog = programs.getOrPut(style) { Program(style.glShader()) }
        val t0 = SystemClock.elapsedRealtimeNanos()
        GLES20.glViewport(0, 0, w, h)
        GLES20.glUseProgram(prog.id)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, noiseTex)
        prog.bind(w, h, work, quad!!)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        val buf = readBuf.let { if (it == null || it.capacity() < w * h * 4) ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder()).also { b -> readBuf = b } else it }
        buf.clear()
        // 讀回會等 GPU 畫完：這段時間＝GPU 成本＋讀回
        GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
        val err = GLES20.glGetError()
        if (err != GLES20.GL_NO_ERROR) throw IllegalStateException("glReadPixels 0x${Integer.toHexString(err)}")
        val t1 = SystemClock.elapsedRealtimeNanos()
        ringI = (ringI + 1) % ring.size
        var bmp = ring[ringI]
        if (bmp == null || bmp.width != w || bmp.height != h) {
            bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            ring[ringI] = bmp
        }
        buf.rewind()
        // gl_FragCoord.y＝0 的那一列先讀出來，正好是圖的第一列（著色器裡 y 往下＝畫面往下），不用翻轉
        bmp!!.copyPixelsFromBuffer(buf)
        val t2 = SystemClock.elapsedRealtimeNanos()
        measure(style, lv, w, h, t1 - t0, t2 - t1)
        return bmp
    }

    private fun measure(style: BgStyle, lv: Int, w: Int, h: Int, gpu: Long, copy: Long) {
        if (warm < 20) { warm++; return } // 剛建好 context、編譯著色器的前幾格特別慢
        sumGpu += gpu; sumCopy += copy
        if (++nFrames < 60) return
        val g = sumGpu / nFrames / 1e6f
        val c = sumCopy / nFrames / 1e6f
        Log.i(TAG, "${style.id} ${w}x$h: gpu+read %.1fms, copy %.1fms".format(g, c))
        nFrames = 0; sumGpu = 0; sumCopy = 0
        if (debugLevel < 0 && lv == 0 && g > SLOW_MS) {
            level = 1
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
            config = chooseConfig(8) ?: chooseConfig(0) ?: throw IllegalStateException("eglChooseConfig")
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

    private fun chooseConfig(alpha: Int): EGLConfig? {
        val attrs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, alpha,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT, EGL14.EGL_NONE,
        )
        val out = arrayOfNulls<EGLConfig>(1)
        val n = IntArray(1)
        return if (EGL14.eglChooseConfig(dpy, attrs, 0, out, 0, 1, n, 0) && n[0] > 0) out[0] else null
    }

    private fun initResources() {
        quad = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0)
        }
        // 共用的雜訊貼圖：64×64、每個像素 RGBA 四個獨立的均勻亂數（xorshift32）。2 的次方尺寸，ES 2.0 才能 REPEAT
        val n = 64
        val px = ByteBuffer.allocateDirect(n * n * 4).order(ByteOrder.nativeOrder())
        var z = 0x9E3779B9.toInt()
        repeat(n * n) {
            z = z xor (z shl 13); z = z xor (z ushr 17); z = z xor (z shl 5)
            px.putInt(z)
        }
        px.position(0)
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        noiseTex = ids[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, noiseTex)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, n, n, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, px)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_REPEAT)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_REPEAT)
        val ext = GLES20.glGetString(GLES20.GL_EXTENSIONS) ?: ""
        Log.i(TAG, "GL ${GLES20.glGetString(GLES20.GL_RENDERER)} / ${GLES20.glGetString(GLES20.GL_VERSION)}, derivatives=${ext.contains("GL_OES_standard_derivatives")}")
    }

    private fun destroyGl() {
        programs.clear()
        noiseTex = 0
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

    /** 一個著色器樣式：全螢幕四邊形＋片段著色器。uniform：u_res（像素）、u_noise（雜訊貼圖）、u_p[0..PARAMS)（樣式參數）。 */
    private class Program(frag: String) {
        val id: Int
        private val aPos: Int
        private val uRes: Int
        private val uNoise: Int
        private val uP: Int

        init {
            val vs = compile(GLES20.GL_VERTEX_SHADER, VERTEX)
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
        }

        fun bind(w: Int, h: Int, p: FloatArray, quad: FloatBuffer) {
            GLES20.glUniform2f(uRes, w.toFloat(), h.toFloat())
            if (uNoise >= 0) GLES20.glUniform1i(uNoise, 0)
            // 參數以 vec4 陣列傳入（PARAMS／4 個），著色器裡用常數索引取用
            if (uP >= 0) GLES20.glUniform4fv(uP, PARAMS / 4, p, 0)
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

    private const val VERTEX = """
attribute vec2 a_pos;
void main() { gl_Position = vec4(a_pos, 0.0, 1.0); }
"""
}

/** 著色器樣式的片段著色器原始碼。 */
internal fun BgStyle.glShader(): String = when (this) {
    BgStyle.MESH -> MeshGradient.SHADER
    else -> error("不是著色器樣式：$id")
}

/** 著色器樣式的參數（主執行緒每格算一次：時間相位取模、色點軌跡、調色盤）。 */
internal fun BgStyle.glParams(st: BgState, out: FloatArray) = when (this) {
    BgStyle.MESH -> MeshGradient.params(st, out)
    else -> Unit
}
