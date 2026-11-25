package tw.liuli.launcher.ui.bg

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 水彩暈染（琉璃原創）：焦點換到哪，就在那裡滴下一滴那個 App 主色的顏料，在紙上慢慢暈開；
 * 焦點不動時，每隔幾秒在附近自己滴一滴鄰近色。
 *
 * 畫法（全部是低頻、柔邊的運算，480×270 放大也不會有鋸齒）：
 * - 每一滴：半徑先快後慢地長大（1 − e^(−t/τ)，之後還會非常緩慢地蔓延），邊界用低頻雜訊扭成不規則的花瓣形
 *   （雜訊座標跟著半徑縮放，暈開時形狀不變、只是變大）
 * - 顏料用「相乘」疊在紙上（顏料是濾光片：紙色 × 顏料的透光色），兩滴重疊的地方自然變深
 * - 水痕：顏料往邊緣堆積、乾掉後邊緣較深——邊緣附近加一圈寬而柔的深色，不是細線
 * - 內部有低頻的濃淡起伏（顏料不均勻）、紙張有淡淡的紋理
 * - 每一滴活 [LIFE] 秒，最後慢慢淡掉；同時最多 [N] 滴，新的一滴取代最淡的那一滴
 *
 * 參數：u_p[0]＝(紙 rgb, 畫面寬高比)；第 i 滴：u_p[1+2i]＝(x, y, 半徑, 濃度)、u_p[2+2i]＝(顏料透光色 rgb, 雜訊種子)。
 */
internal object Watercolor {
    const val N = 5
    /** 一滴存在多久（秒，用背景的動畫時鐘 [BgState.at]）。 */
    private const val LIFE = 42f
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
    private var lastRipple = Int.MIN_VALUE
    private var lastSpawnAt = -1e6f
    private var nextAmbient = 0f
    private val rnd = java.util.Random(0x5EED)

    private fun spawn(at: Float, px: Float, py: Float, h: Float, s: Float, big: Boolean) {
        // 取代最淡（最老）的那一滴
        var k = 0
        for (i in 1 until N) if (birth[i] < birth[k]) k = i
        x[k] = px; y[k] = py; hue[k] = h; sat[k] = s
        rmax[k] = (if (big) .26f else .16f) + rnd.nextFloat() * .1f
        seed[k] = rnd.nextFloat() * 50f
        birth[k] = at
        lastSpawnAt = at
    }

    fun params(st: BgState, out: FloatArray) {
        val at = st.at
        val rip = st.ripple.roundToInt()
        if (lastRipple == Int.MIN_VALUE || at < lastSpawnAt - 1f) {
            // 第一次（或時鐘重來）：在焦點滴一滴大的，之後才開始自己滴
            lastRipple = rip
            for (i in 0 until N) birth[i] = -1e6f
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
            if (age < 0f || age > LIFE) { out[o + 3] = 0f; out[o + 2] = .001f; continue }
            // 半徑：先快後慢長大，之後每秒再蔓延一點點；焦點脈動時剛滴下的那一滴多鼓起一點
            val r = rmax[i] * (1f - exp(-age / GROW_TAU)) + .0025f * age + (if (age < 3f) .02f * st.pulse else 0f)
            // 濃度：滴下的瞬間淡入（0.3 秒），活到最後三分之一開始淡出
            val a = min(1f, age / .3f) * (1f - smoothstep(LIFE * .62f, LIFE, age))
            out[o] = x[i]; out[o + 1] = y[i]; out[o + 2] = max(r, .001f); out[o + 3] = a
            // 顏料的透光色：越飽和、越暗的濾光片，疊上去顏色越重
            val c = androidx.compose.ui.graphics.Color.hsl(
                ((hue[i] % 360f) + 360f) % 360f, (.55f + .8f * sat[i]).coerceIn(0f, .95f), .5f,
            )
            out[o + 4] = c.red; out[o + 5] = c.green; out[o + 6] = c.blue; out[o + 7] = seed[i]
        }
    }

    /** 紙的亮度：水彩是畫在淺色的紙上，但主畫面上有白字，所以紙調暗到中間調。 */
    private const val PAPER_L = .56f

    private fun smoothstep(e0: Float, e1: Float, v: Float): Float {
        val t = ((v - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    // 每一滴：d＝到中心的距離（寬高比校正）；邊界半徑被低頻雜訊推擠（花瓣形），內部濃淡不均，邊緣一圈寬柔的水痕。
    // 顏料以相乘疊上去：col *= mix(1, 透光色, 濃度)
    const val SHADER = """
precision mediump float;
uniform sampler2D u_noise;
uniform sampler2D u_noiseL;
uniform vec4 u_p[12];
varying vec2 v_uv;

float g(vec2 p) {
  vec2 i = floor(p);
  vec2 f = fract(p);
  f = f * f * (3.0 - 2.0 * f);
  return texture2D(u_noiseL, (i + f + 0.5) / 64.0).r;
}

vec3 drop(vec3 col, vec2 uv, vec4 a, vec4 b) {
  vec2 d = (uv - a.xy) * vec2(u_p[0].w, 1.0);
  float dist = length(d);
  // 這一滴碰不到的像素（或已經淡掉）：直接跳過，省下雜訊取樣
  if (a.w < 0.004 || dist > a.z * 1.45) return col;
  vec2 q = d / a.z;
  float n = g(q * 1.3 + b.w);
  float r = a.z * (0.8 + 0.38 * n);
  float e = (dist - r) / a.z;
  // 邊緣與水痕要比 320×180 的一格寬很多（放大六倍），不然會變成鋸齒：柔邊約 0.08 個半徑、水痕約 0.07 個半徑寬
  float inside = 1.0 - smoothstep(-0.05, 0.04, e);
  float rim = exp(-(e + 0.06) * (e + 0.06) * 110.0);
  float body = 0.34 + 0.26 * g(q * 1.9 + b.w + 31.0);
  float k = a.w * inside * clamp(body + rim * 0.55, 0.0, 1.0);
  return col * mix(vec3(1.0), b.rgb, k);
}

void main() {
  vec2 uv = v_uv;
  vec3 col = u_p[0].rgb * (0.95 + 0.07 * g(uv * vec2(28.0, 16.0)));
  col = drop(col, uv, u_p[1], u_p[2]);
  col = drop(col, uv, u_p[3], u_p[4]);
  col = drop(col, uv, u_p[5], u_p[6]);
  col = drop(col, uv, u_p[7], u_p[8]);
  col = drop(col, uv, u_p[9], u_p[10]);
  vec2 v = uv - 0.5;
  col *= 1.0 - 0.35 * dot(v, v);
  col += (texture2D(u_noise, gl_FragCoord.xy / 64.0).rgb - 0.5) / 255.0;
  gl_FragColor = vec4(col, 1.0);
}
"""
}
