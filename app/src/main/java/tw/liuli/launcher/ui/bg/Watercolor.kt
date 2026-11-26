package tw.liuli.launcher.ui.bg

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 水彩暈染（琉璃原創）：焦點換到哪，就在那裡滴下一滴那個 App 主色的顏料，在水裡慢慢暈開；
 * 焦點不動時，每隔幾秒在附近自己滴一滴鄰近色。
 *
 * 畫法（全部是低頻、柔邊的運算，320×180 放大也不會有鋸齒）：
 * - 每一滴：半徑先快後慢地長大（1 − e^(−t/τ)，之後還會非常緩慢地蔓延），邊界用低頻雜訊扭成不規則的花瓣形
 * - 水的擾動：整張紙底下有一層緩慢流動的水流場（隨時間漂移的雜訊位移），水滴的邊界和裡面的顏料跟著漂移、變形；
 *   邊界的花瓣形也會隨時間慢慢起伏
 * - 顏料用「相乘」疊在紙上（顏料是濾光片：紙色 × 顏料的透光色），兩滴重疊的地方自然變深
 * - 水痕：顏料往邊緣堆積、乾掉後邊緣較深——邊緣附近加一圈寬而柔的深色，不是細線
 * - 稀釋：顏料越暈越開越淡（約 30 秒內淡到 45%），活到 [LIFE] 秒的最後三分之一再完全淡掉——水不會越來越濁
 * - 同時最多 [ACTIVE] 滴「活躍」的顏料：再滴下一滴時，最老的那一滴在 [RETIRE] 秒內快速淡出（不會突然消失）
 *
 * 參數：u_p[0]＝(紙 rgb, 畫面寬高比)；第 i 滴：u_p[1+2i]＝(x, y, 半徑, 濃度)、u_p[2+2i]＝(顏料透光色 rgb, 雜訊種子)；
 * 水流的四個時間相位放在 u_p[14]／u_p[15]（整數／小數，[GlBackground.putTimes]）。
 */
internal object Watercolor {
    /** 著色器裡的位置數（含正在退場的）。 */
    const val N = 6
    /** 同時最多幾滴活躍的顏料。 */
    private const val ACTIVE = 4
    /** 被擠掉的那一滴淡出要多久（秒）。 */
    private const val RETIRE = 1.5f
    /** 一滴存在多久（秒，用背景的動畫時鐘 [BgState.at]）。 */
    private const val LIFE = 38f
    /** 長大的時間常數（秒）：大約三倍這麼久就接近最大半徑。 */
    private const val GROW_TAU = 2.6f
    /** 焦點不動時，多久自己滴一滴（秒，加上隨機的 0～4 秒）。 */
    private const val AMBIENT_EVERY = 7f
    /** 連續換焦點時，兩滴之間至少隔多久（秒）：快速掃過不會一下滴滿整個畫面。 */
    private const val MIN_GAP = 0.7f

    private val x = FloatArray(N)
    private val y = FloatArray(N)
    private val hue = FloatArray(N)
    private val sat = FloatArray(N)
    private val rmax = FloatArray(N)
    private val seed = FloatArray(N)
    private val birth = FloatArray(N) { -1e6f }
    /** 開始退場的時間（被新的一滴擠掉）；還沒退場＝很大的數。 */
    private val retireAt = FloatArray(N) { 1e9f }
    private var lastRipple = Int.MIN_VALUE
    private var lastSpawnAt = -1e6f
    private var nextAmbient = 0f
    private val rnd = java.util.Random(0x5EED)
    /**
     * 自己的時鐘（秒）：只用背景動畫時鐘兩格之間的差往前走。動畫時鐘每個循環會歸零（[Loop]），
     * 顏料的出生、壽命不能跟著歸零（以前歸零時會當成「時鐘重來」，整張紙清掉重滴）；走到 [REBASE] 就整批往回挪，單精度才夠用。
     */
    private var clk = 0f
    private var lastAt = Float.NaN
    private const val REBASE = 4096f

    /** 這一滴目前的濃度（0＝看不到）：淡入 × 稀釋 × 壽命尾聲的淡出 × 被擠掉時的快速淡出。 */
    private fun density(i: Int, at: Float): Float {
        val age = at - birth[i]
        if (age < 0f || age > LIFE) return 0f
        val fadeIn = min(1f, age / .3f)
        val dilute = 1f - .55f * smoothstep(0f, 30f, age)
        val end = 1f - smoothstep(LIFE * .68f, LIFE, age)
        val retire = 1f - ((at - retireAt[i]) / RETIRE).coerceIn(0f, 1f)
        return fadeIn * dilute * end * retire
    }

    /** 還算「活躍」：活著、還沒進入壽命尾聲、沒被擠掉。 */
    private fun active(i: Int, at: Float): Boolean {
        val age = at - birth[i]
        return age >= 0f && age < LIFE * .68f && retireAt[i] > at
    }

    private fun spawn(at: Float, px: Float, py: Float, h: Float, s: Float, big: Boolean) {
        // 活躍的已經滿了：最老的那一滴開始快速淡出
        var n = 0; var oldest = -1
        for (i in 0 until N) if (active(i, at)) { n++; if (oldest < 0 || birth[i] < birth[oldest]) oldest = i }
        if (n >= ACTIVE && oldest >= 0) retireAt[oldest] = at
        // 放進目前最淡的位置（通常是已經淡完的；真的都還看得到，就取代最淡的那一滴）
        var k = 0; var best = Float.MAX_VALUE
        for (i in 0 until N) {
            val d = if (active(i, at)) 2f + density(i, at) else density(i, at)
            if (d < best) { best = d; k = i }
        }
        x[k] = px; y[k] = py; hue[k] = h; sat[k] = s
        rmax[k] = (if (big) .26f else .16f) + rnd.nextFloat() * .1f
        seed[k] = rnd.nextInt(64).toFloat()   // 整數：當雜訊的格子位移（座標本身保持很小，半精度才不會一格一格）
        birth[k] = at
        retireAt[k] = 1e9f
        lastSpawnAt = at
    }

    /** 重整（主畫面看不到時）：下一張當成第一次，整張紙清掉、在焦點滴第一滴。 */
    fun reset() { lastRipple = Int.MIN_VALUE }

    fun params(st: BgState, out: FloatArray) {
        val d = Loop.diff(st.at, lastAt)
        if (lastAt.isNaN() || d > 0f) { clk += d.coerceIn(0f, .25f); lastAt = st.at }
        if (clk > REBASE) {
            clk -= REBASE; lastSpawnAt -= REBASE; nextAmbient -= REBASE
            for (i in 0 until N) { birth[i] -= REBASE; if (retireAt[i] < 1e8f) retireAt[i] -= REBASE }
        }
        val at = clk
        val rip = st.ripple.roundToInt()
        if (lastRipple == Int.MIN_VALUE || at < lastSpawnAt - 1f) {
            // 第一次（或時鐘重來）：在焦點滴一滴大的，之後才開始自己滴
            lastRipple = rip
            for (i in 0 until N) { birth[i] = -1e6f; retireAt[i] = 1e9f }
            spawn(at, st.fx, st.fy, st.h, st.s, big = true)
            nextAmbient = at + AMBIENT_EVERY
        } else if (rip != lastRipple && at - lastSpawnAt >= MIN_GAP) {
            // 焦點換了：在新的焦點滴一滴目前主色
            lastRipple = rip
            spawn(at, st.fx, st.fy, st.h, st.s, big = true)
            nextAmbient = at + AMBIENT_EVERY + rnd.nextFloat() * 4f
        } else if (at >= nextAmbient) {
            // 焦點沒動：在焦點附近滴一滴鄰近色（小一點）
            val ang = rnd.nextFloat() * 6.2832f
            val d = .12f + rnd.nextFloat() * .22f
            val px = (st.fx + kotlin.math.cos(ang) * d * .7f).coerceIn(.05f, .95f)
            val py = (st.fy + kotlin.math.sin(ang) * d).coerceIn(.05f, .95f)
            spawn(at, px, py, st.h + (rnd.nextFloat() - .5f) * 70f, st.s, big = false)
            nextAmbient = at + AMBIENT_EVERY + rnd.nextFloat() * 4f
        }

        // 紙是固定的暖灰色（不跟著 App 主色變）：顏色只來自滴上去的顏料
        val paper = androidx.compose.ui.graphics.Color.hsl(36f, .16f, PAPER_L)
        out[0] = paper.red; out[1] = paper.green; out[2] = paper.blue; out[3] = 16f / 9f
        for (i in 0 until N) {
            val age = at - birth[i]
            val o = 4 + i * 8
            val a = density(i, at)
            if (a <= 0f) { out[o + 3] = 0f; out[o + 2] = .001f; continue }
            // 半徑：先快後慢長大，之後每秒再蔓延一點點；焦點脈動時剛滴下的那一滴多鼓起一點
            val r = rmax[i] * (1f - exp(-age / GROW_TAU)) + .0025f * age + (if (age < 3f) .02f * st.pulse else 0f)
            out[o] = x[i]; out[o + 1] = y[i]; out[o + 2] = max(r, .001f); out[o + 3] = a
            // 顏料的透光色：越飽和、越暗的濾光片，疊上去顏色越重
            val c = androidx.compose.ui.graphics.Color.hsl(
                ((hue[i] % 360f) + 360f) % 360f, (.55f + .8f * sat[i]).coerceIn(0f, .95f), .5f,
            )
            out[o + 4] = c.red; out[o + 5] = c.green; out[o + 6] = c.blue; out[o + 7] = seed[i]
        }
        // 水流的時間相位（雙精度算、取模 64）
        // 水流的平移跟著動畫時鐘（循環），速度對齊成一個循環剛好走完雜訊的整數個週期（64 格）
        val t = st.at.toDouble()
        GlBackground.putTimes(out, t * Loop.v(.05, 64.0), t * Loop.v(.043, 64.0), t * Loop.v(.03, 64.0), t * Loop.v(.02, 64.0))
    }

    /** 紙的亮度：水彩是畫在淺色的紙上，但主畫面上有白字，所以紙調暗到中間調。 */
    private const val PAPER_L = .56f

    private fun smoothstep(e0: Float, e1: Float, v: Float): Float {
        val t = ((v - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /**
     * 畫法：先畫一層紙（全螢幕，便宜），每一滴顏料再各自畫一塊只蓋住自己範圍的網格（8×8），用乘法混色（GL_DST_COLOR × 片段顏色）疊上去。
     * 以前六滴全部在同一個全螢幕著色器裡算：這顆 GPU 的分支幾乎省不了事，每個像素都把六滴（12 次雜訊）算完，480×270 一張要 22ms。
     * 水的擾動（緩慢流動的位移場）在每一滴網格的頂點算（全精度、低頻，內插誤差看不出來）。
     */
    const val SHADER = """
precision mediump float;
uniform sampler2D u_noise;
uniform vec4 u_p[16];
varying vec2 v_uv;
""" + GlBackground.NOISE + """
void main() {
  vec2 uv0 = v_uv;
  vec3 col = u_p[0].rgb * (0.95 + 0.07 * g(uv0 * vec2(28.0, 16.0)));
  vec2 v = uv0 - 0.5;
  col *= 1.0 - 0.35 * dot(v, v);
  col += (texture2D(u_noise, gl_FragCoord.xy / 64.0).rgb - 0.5) / 255.0;
  gl_FragColor = vec4(col, 1.0);
}
"""

    /** 一滴的網格：u_va＝(x, y, 半徑, 濃度)；蓋住 1.45 個半徑（加上水流位移的餘裕）。 */
    const val DROP_VERTEX = """
attribute vec2 a_pos;
uniform float u_fy;
uniform vec4 u_w;
uniform vec4 u_va;
varying vec2 v_wv;
""" + GlBackground.VNOISE + """
void main() {
  vec2 hs = vec2(u_va.z * 1.45 / u_w.w, u_va.z * 1.45) + 0.03;
  vec2 uv = u_va.xy + a_pos * hs;
  vec2 s = uv * vec2(u_w.w, 1.0) * 2.2;
  float t1 = u_vt.x + u_vf.x;
  float t2 = u_vt.y + u_vf.y;
  v_wv = uv + (vec2(gv(s + vec2(t1, 3.0)), gv(s + vec2(11.0, t2))) - 0.5) * 0.045;
  vec2 c = uv * 2.0 - 1.0;
  gl_Position = vec4(c.x, c.y * u_fy, 0.0, 1.0);
}
"""

    // 每一滴：d＝到中心的距離（寬高比校正）；邊界半徑被低頻雜訊推擠（花瓣形），內部濃淡不均，邊緣一圈寬柔的水痕。
    // 輸出相乘的係數：mix(1, 透光色, 濃度)
    const val DROP_SHADER = """
precision mediump float;
uniform sampler2D u_noise;
uniform vec2 u_res;
uniform vec4 u_p[16];
uniform vec4 u_a;
uniform vec4 u_b;
varying vec2 v_wv;
""" + GlBackground.NOISE + """
void main() {
  vec4 a = u_a;
  vec4 b = u_b;
  vec2 d = (v_wv - a.xy) * vec2(u_p[0].w, 1.0);
  float dist = length(d);
  vec2 q = d / a.z;
  // 邊界的花瓣形隨時間慢慢起伏（水還沒乾）
  float n = gt(q * 1.3 + vec2(u_p[15].w, 0.0), vec2(b.w + u_p[14].w, b.w));
  float r = a.z * (0.8 + 0.38 * n);
  float e = (dist - r) / a.z;
  // 邊緣：水彩的邊是乾掉時顏料聚在邊上形成的，本來就清楚——柔邊約 0.02 個半徑，但至少 1.5 個繪製像素才不會鋸齒；水痕約 0.06 個半徑寬
  float px = 1.0 / (u_res.y * a.z);
  float soft = max(0.02, 1.5 * px);
  float inside = 1.0 - smoothstep(-soft, soft * 0.8, e);
  float rw = max(0.06, 2.0 * px);
  float re = (e + rw * 0.65) / rw;
  float rim = exp(-re * re);
  // 顏料在水裡不均勻，而且跟著水流慢慢漂
  float body = 0.34 + 0.26 * gt(q * 1.9 + vec2(0.0, u_p[15].z), vec2(b.w + 31.0, b.w + 31.0 + u_p[14].z));
  float k = a.w * inside * clamp(body + rim * 0.55, 0.0, 1.0);
  gl_FragColor = vec4(mix(vec3(1.0), b.rgb, k), 1.0);
}
"""
}
