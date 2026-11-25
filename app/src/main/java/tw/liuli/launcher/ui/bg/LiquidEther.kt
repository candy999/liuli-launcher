package tw.liuli.launcher.ui.bg

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 液態以太（琉璃自己寫；React Bits 的 liquid-ether 只參考效果的樣子，沒有看程式碼）：
 * 一小格真的流體模擬（Jos Stam 的 stable fluids：半拉格朗日平流＋Jacobi 壓力投影），焦點移動時沿著移動方向推一把、
 * 注入顏料；焦點不動時有一個看不見的指標慢慢繞圈，讓流體一直輕輕流動。GPU 只負責上色：依流速在三個顏色之間取色。
 *
 * 為什麼在 CPU 算：流體要好幾張浮點貼圖來回渲染，Mali-470 沒有浮點渲染目標；80×45 的格子在 CPU 上一步不到 1 毫秒。
 * 模擬在 GL 執行緒推進（[simulate]；主執行緒只交出焦點位置與時鐘），結果寫成一張 80×45 的 RGBA 小圖、上傳成貼圖。
 */
internal object LiquidEther {
    // 格子：海信上 80×45、14 次壓力迭代一步要 12ms（GL 執行緒 37%）；流體本來就是低頻的，80×45、10 次看不出差別
    const val W = 80
    const val H = 45
    private const val N = W * H
    private var u = FloatArray(N); private var v = FloatArray(N)
    private var u0 = FloatArray(N); private var v0 = FloatArray(N)
    private var d = FloatArray(N); private var d0 = FloatArray(N)
    private val p = FloatArray(N)
    private val div = FloatArray(N)
    private var lastAt = -1f
    private var lastFx = Float.NaN
    private var lastFy = Float.NaN

    /** GL 執行緒算好、上傳用的小圖（80×45 RGBA：R＝流速、G＝顏料濃度）。 */
    val glData: ByteBuffer = ByteBuffer.allocateDirect(N * 4).order(ByteOrder.nativeOrder())

    // 主執行緒每格寫入的輸入（焦點位置、動畫時鐘），在 GlBackground 的鎖裡交給 GL 執行緒
    private var inAt = 0f; private var inFx = .5f; private var inFy = .5f
    private var glAt = 0f; private var glFx = .5f; private var glFy = .5f

    /** 在 GlBackground 的鎖裡呼叫：把最新的輸入交給 GL 執行緒。 */
    fun snapshot() {
        glAt = inAt; glFx = inFx; glFy = inFy
    }

    private fun idx(x: Int, y: Int) = y * W + x

    /** 在 (cx, cy)（格子座標）附近把流速往 (tx, ty)（格／秒）拉過去 [blend]、注入 [dye] 的顏料，半徑 [r] 格的高斯範圍。 */
    private fun splat(cx: Float, cy: Float, tx: Float, ty: Float, dye: Float, r: Float, blend: Float) {
        val x0 = (cx - r * 2).toInt().coerceIn(1, W - 2); val x1 = (cx + r * 2).toInt().coerceIn(1, W - 2)
        val y0 = (cy - r * 2).toInt().coerceIn(1, H - 2); val y1 = (cy + r * 2).toInt().coerceIn(1, H - 2)
        for (y in y0..y1) for (x in x0..x1) {
            val dx = x - cx; val dy = y - cy
            val w = exp(-(dx * dx + dy * dy) / (r * r))
            val i = idx(x, y)
            u[i] += (tx - u[i]) * w * blend; v[i] += (ty - v[i]) * w * blend
            d[i] = min(1.5f, d[i] + dye * w)
        }
    }

    private fun advect(dst: FloatArray, src: FloatArray, dt: Float) {
        for (y in 1 until H - 1) for (x in 1 until W - 1) {
            val i = idx(x, y)
            var px = x - dt * u[i]; var py = y - dt * v[i]
            px = px.coerceIn(.5f, W - 1.5f); py = py.coerceIn(.5f, H - 1.5f)
            val ix = px.toInt(); val iy = py.toInt()
            val sx = px - ix; val sy = py - iy
            val a = idx(ix, iy)
            dst[i] = (1 - sy) * ((1 - sx) * src[a] + sx * src[a + 1]) + sy * ((1 - sx) * src[a + W] + sx * src[a + W + 1])
        }
    }

    private fun project() {
        for (y in 1 until H - 1) for (x in 1 until W - 1) {
            val i = idx(x, y)
            div[i] = -.5f * (u[i + 1] - u[i - 1] + v[i + W] - v[i - W])
            p[i] = 0f
        }
        repeat(10) {
            for (y in 1 until H - 1) for (x in 1 until W - 1) {
                val i = idx(x, y)
                p[i] = (div[i] + p[i - 1] + p[i + 1] + p[i - W] + p[i + W]) * .25f
            }
        }
        for (y in 1 until H - 1) for (x in 1 until W - 1) {
            val i = idx(x, y)
            u[i] -= .5f * (p[i + 1] - p[i - 1])
            v[i] -= .5f * (p[i + W] - p[i - W])
        }
    }

    private fun step(dt: Float) {
        advect(u0, u, dt); advect(v0, v, dt)
        var t = u; u = u0; u0 = t
        t = v; v = v0; v0 = t
        project()
        advect(d0, d, dt)
        t = d; d = d0; d0 = t
        val kv = exp(-dt * .3f); val kd = exp(-dt * .12f)
        for (i in 0 until N) { u[i] *= kv; v[i] *= kv; d[i] *= kd }
    }

    /** 參數：u_p[0]＝頭；u_p[2..5]＝底色、慢、中、快四個顏色。 */
    fun params(st: BgState, out: FloatArray) {
        out[0] = 16f / 9f; out[1] = st.fx; out[2] = st.fy; out[3] = st.pulse
        inAt = st.at; inFx = st.fx; inFy = st.fy
        val c0 = GlStyles.hsl(st, 20f, .6f, .03f)
        val c1 = GlStyles.hsl(st, -40f, .9f, .42f)
        val c2 = GlStyles.hsl(st, 0f, .95f, .58f)
        val c3 = GlStyles.hsl(st, 50f, .9f, .78f)
        for ((k, c) in listOf(c0, c1, c2, c3).withIndex()) {
            val o = 8 + k * 4
            out[o] = c.red; out[o + 1] = c.green; out[o + 2] = c.blue; out[o + 3] = 0f
        }
    }

    /** GL 執行緒：用最新的輸入推進流體、寫出小圖（主執行緒完全不用算）。 */
    fun simulate() {
        val at = glAt; val fx = glFx; val fy = glFy
        val dt = if (lastAt < 0f || at < lastAt) 1f / 30f else min(.1f, at - lastAt)
        lastAt = at
        if (dt > 0f) {
            val gx = fx * (W - 1); val gy = fy * (H - 1)
            if (!lastFx.isNaN()) {
                // 焦點在動（引擎已經平滑過）：沿著移動方向推，速度越快推得越用力、注入越多顏料
                val vx = (fx - lastFx) / dt; val vy = (fy - lastFy) / dt
                val sp = sqrt(vx * vx + vy * vy)
                if (sp > .02f) splat(gx, gy, vx * W * .9f, vy * H * .9f, min(1f, sp * 1.6f) * dt * 9f, 3.2f, .6f)
            }
            lastFx = fx; lastFy = fy
            // 閒置時的指標：繞著焦點慢慢畫 8 字，一直輕輕攪動
            val a = at * .45f
            val ax = (fx + .22f * sin(a)).coerceIn(.05f, .95f) * (W - 1)
            val ay = (fy + .16f * sin(a * 2f)).coerceIn(.05f, .95f) * (H - 1)
            splat(ax, ay, cos(a) * 12f, cos(a * 2f) * 8f, dt * 1.4f, 3.4f, .18f)
            step(dt)
        }
        // 寫出：R＝流速、G＝顏料濃度
        glData.position(0)
        for (i in 0 until N) {
            val sp = sqrt(u[i] * u[i] + v[i] * v[i]) * .12f
            glData.put((min(1f, sp) * 255f).toInt().toByte())
            glData.put((min(1f, abs(d[i])) * 255f).toInt().toByte())
            glData.put(0); glData.put(-1)
        }
        glData.position(0)
    }

    const val SHADER = """
precision mediump float;
uniform sampler2D u_noise;
uniform sampler2D u_data;
uniform vec4 u_p[16];
varying vec2 v_uv;
void main() {
  vec4 s = texture2D(u_data, v_uv);
  float sp = s.r;
  float dye = s.g;
  vec3 c = mix(u_p[3].rgb, u_p[4].rgb, smoothstep(0.0, 0.45, sp));
  c = mix(c, u_p[5].rgb, smoothstep(0.45, 1.0, sp));
  vec3 col = mix(u_p[2].rgb, c, clamp(dye * 1.3, 0.0, 1.0));
  """ + GlBackground.TONE + """  col += (texture2D(u_noise, gl_FragCoord.xy / 64.0).rgb - 0.5) / 255.0;
  gl_FragColor = vec4(col, 1.0);
}
"""
}
