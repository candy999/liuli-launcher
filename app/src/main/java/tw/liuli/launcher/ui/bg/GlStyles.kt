package tw.liuli.launcher.ui.bg

import androidx.compose.ui.graphics.Color
import kotlin.math.sin

/**
 * 第二批著色器樣式：墨流（Subframe ink-current，MIT）、漩渦（Paper Shaders swirl，Apache-2.0）、漸層百葉（琉璃自己寫）。
 * 共同規則見 [GlBackground]：時間在 CPU 端算好取模、低頻的座標變換放在頂點著色器（全精度），片段著色器只用 mediump、
 * 亂數一律查雜訊貼圖（u_noise：最近鄰，抖色用；u_noiseL：線性內插，當作 value noise 用）。
 */
internal object GlStyles {
    private const val TAU = Math.PI * 2

    /** 調色：色相 [dh] 相對主色、飽和度乘上主色的飽和度（同 Styles.kt 的 BgState.c）、亮度 [l]。 */
    private fun BgState.col(dh: Float, sat: Float, l: Float): Color =
        Color.hsl((((h + dh) % 360f) + 360f) % 360f, (sat * s * 1.6f).coerceIn(0f, 1f), l.coerceIn(0f, 1f))

    private fun put(out: FloatArray, i: Int, c: Color) {
        out[i] = c.red; out[i + 1] = c.green; out[i + 2] = c.blue; out[i + 3] = 0f
    }

    /** 共用的抖色：±½ 階雜訊（最近鄰貼圖）。 */
    private const val DITHER = "col += (texture2D(u_noise, gl_FragCoord.xy / 64.0).rgb - 0.5) / 255.0;"

    // ───────────── 墨流（Subframe ink-current） ─────────────

    /**
     * 墨流：多層 value noise 互相扭曲，墨色以流線狀的邊界在水裡擴散；焦點附近的水會輕輕旋轉。
     *
     * 改寫自 Subframe 的 ink-current（Copyright (c) 2026 Subframe，MIT License，全文見 assets/licenses/subframe-LICENSE.txt）。改動：
     * - fract(sin(x)·43719) 雜湊改成查 64×64 雜訊貼圖（線性內插＋smoothstep 座標，一次取樣就是一格 value noise）
     * - 疊加的旋轉矩陣改成整數 (2,1;-1,2)：雜訊以 64 格為週期，時間漂移取模 64 後接得起來，不會跳
     * - 顏色：原版是米白紙＋深藍墨；這裡改成主色系的「中間調紙＋深色墨＋亮色邊」（白字在上面才看得清楚），明暗關係不變
     * - 細節多、每像素十六次取樣：480×270、每秒 10 格（流動本來就很慢，看不出一格一格）
     * - 漩渦中心跟著焦點（原本是滑桿），焦點脈動時多轉一點
     */
    object Ink {
        fun params(st: BgState, out: FloatArray) {
            val t = st.at.toDouble()
            out[0] = (0.8 * sin(t * .13)).toFloat() + .9f * st.pulse
            out[1] = ((t * .018) % 64.0).toFloat()
            out[2] = 16f / 9f
            out[3] = 0f
            out[4] = st.fx; out[5] = st.fy
            put(out, 8, st.col(0f, .35f, .19f))
            put(out, 12, st.col(-10f, .8f, .04f))
            put(out, 16, st.col(20f, .9f, .34f))
        }

        const val SHADER = """
precision mediump float;
uniform sampler2D u_noise;
uniform sampler2D u_noiseL;
uniform vec4 u_p[10];
varying vec2 v_uv;

float g(vec2 p) {
  vec2 i = floor(p);
  vec2 f = fract(p);
  f = f * f * (3.0 - 2.0 * f);
  return texture2D(u_noiseL, (i + f + 0.5) / 64.0).r;
}
float L(vec2 p) {
  mat2 m = mat2(2.0, -1.0, 1.0, 2.0);
  float v = 0.54 * g(p);
  p = m * p + 1.7; v += 0.254 * g(p);
  p = m * p + 1.7; v += 0.119 * g(p);
  p = m * p + 1.7; v += 0.056 * g(p);
  return v;
}
void main() {
  float asp = u_p[0].z;
  vec2 p = (v_uv - 0.5) * vec2(asp, 1.0);
  vec2 c = (u_p[1].xy - 0.5) * vec2(asp, 1.0);
  vec2 d = p - c;
  float a = u_p[0].x * exp(-dot(d, d) * 4.0);
  float sa = sin(a);
  float ca = cos(a);
  vec2 q = c + vec2(ca * d.x - sa * d.y, sa * d.x + ca * d.y);
  float n = L(q * 3.0 + vec2(u_p[0].y, 0.0));
  q += vec2(L(q * 3.7 + 4.0), L(q * 3.3 + 11.0)) * 0.6;
  float flow = L(q * 6.0);
  float ink = smoothstep(0.48, 0.64, flow + 0.14 * sin(q.x * 5.0 + n * 8.0));
  float edge = exp(-abs(flow - 0.49) * 65.0);
  vec3 col = mix(u_p[2].rgb, u_p[3].rgb, ink);
  col = mix(col, u_p[4].rgb, edge * 0.5);
  """ + DITHER + """
  gl_FragColor = vec4(col, 1.0);
}
"""
    }

    // ───────────── 漩渦（Paper swirl） ─────────────

    /**
     * 漩渦：幾條色帶繞著中心旋轉、越靠近中心扭得越緊；中心就是焦點。
     *
     * 改寫自 Paper Shaders 的 swirl（Apache License 2.0）。改動：GLSL ES 1.00、色帶數固定 3、三個顏色的迴圈展開；
     * 不用 fwidth 做反鋸齒（柔邊夠寬，看不出差別，也不用 OES_standard_derivatives）；拿掉 simplex 雜訊選項；
     * 時間相位在 CPU 取模；中心跟著焦點，中心半徑設下限避免 pow 溢位。
     */
    object Swirl {
        fun params(st: BgState, out: FloatArray) {
            val t = st.at.toDouble() * .12 + st.ripple * .6
            out[0] = (t % TAU).toFloat()
            out[1] = .26f + .12f * st.pulse   // 扭轉
            out[2] = .25f                      // 中心大小
            out[3] = .78f                      // 柔邊
            out[4] = st.fx; out[5] = st.fy; out[6] = 16f / 9f
            put(out, 8, st.col(0f, .5f, .035f))
            put(out, 12, st.col(8f, .75f, .11f))
            put(out, 16, st.col(-18f, .8f, .21f))
            put(out, 20, st.col(14f, .9f, .33f))
        }

        const val VERTEX = """
attribute vec2 a_pos;
uniform vec4 u_v1;
uniform float u_fy;
varying vec2 v_p;
void main() {
  vec2 uv = a_pos * 0.5 + 0.5;
  v_p = (uv - u_v1.xy) * vec2(u_v1.z, 1.0) * 0.8;
  gl_Position = vec4(a_pos.x, a_pos.y * u_fy, 0.0, 1.0);
}
"""

        const val SHADER = """
precision mediump float;
uniform sampler2D u_noise;
uniform vec4 u_p[10];
varying vec2 v_p;
void main() {
  float l = max(length(v_p), 0.002);
  float twist = 3.0 * u_p[0].y;
  float angle = 3.0 * atan(v_p.y, v_p.x) + u_p[0].x;
  float shape = fract(pow(l, -twist) + angle / 6.2831853);
  shape = 1.0 - abs(2.0 * shape - 1.0);
  float lt = pow(l, twist);
  shape *= smoothstep(0.2, 0.2 + 0.8 * u_p[0].z, lt);
  float mixer = shape * 3.0;
  float soft = u_p[0].w;
  float m1 = smoothstep(0.5 - 0.5 * soft, 0.5 + 0.5 * soft, clamp(mixer, 0.0, 1.0));
  float m2 = smoothstep(0.5 - 0.5 * soft, 0.5 + 0.5 * soft, clamp(mixer - 1.0, 0.0, 1.0));
  float m3 = smoothstep(0.5 - 0.5 * soft, 0.5 + 0.5 * soft, clamp(mixer - 2.0, 0.0, 1.0));
  vec3 grad = mix(mix(u_p[3].rgb, u_p[4].rgb, m2), u_p[5].rgb, m3);
  float outer = m1 * smoothstep(0.2, 0.26, lt);
  vec3 col = mix(u_p[2].rgb, grad, outer);
  """ + DITHER + """
  gl_FragColor = vec4(col, 1.0);
}
"""
    }

    // ───────────── 漸層百葉（琉璃自己寫） ─────────────

    /**
     * 漸層百葉：一排直立的百葉片，底下是主色系的斜向漸層；每片朝向焦點（光源）的那一側比較亮，
     * 焦點附近有一圈聚光。只有焦點移動時才動（不是持續動畫）。
     */
    object Blinds {
        fun params(st: BgState, out: FloatArray) {
            out[0] = st.fx; out[1] = st.fy; out[2] = 16f / 9f; out[3] = st.pulse
            out[4] = 18f                          // 百葉片數
            out[5] = (st.ripple % 8f) * .02f      // 漸層隨每次換焦點輕輕推移
            put(out, 8, st.col(-20f, .7f, .07f))
            put(out, 12, st.col(18f, .85f, .20f))
            put(out, 16, st.col(0f, .95f, .46f))
        }

        const val SHADER = """
precision mediump float;
uniform sampler2D u_noise;
uniform vec4 u_p[10];
varying vec2 v_uv;
void main() {
  vec2 uv = v_uv;
  vec2 f = u_p[0].xy;
  float k = u_p[1].x;
  float x = uv.x * k;
  float fx = fract(x);
  float cx = (floor(x) + 0.5) / k;
  float tilt = clamp((f.x - cx) * 4.0, -1.0, 1.0);
  float prof = mix(1.0 - fx, fx, tilt * 0.5 + 0.5);
  prof = prof * prof * (3.0 - 2.0 * prof);
  vec3 base = mix(u_p[2].rgb, u_p[3].rgb, clamp(1.0 - uv.y * 0.8 + (cx - 0.5) * 0.5 + u_p[1].y - 0.2, 0.0, 1.0));
  vec2 d = (uv - f) * vec2(u_p[0].z, 1.0);
  float spot = exp(-dot(d, d) * (5.0 - 1.5 * u_p[0].w));
  vec3 col = base * (0.55 + 0.5 * prof) + u_p[4].rgb * spot * (0.25 + 0.55 * prof);
  float gap = smoothstep(0.0, 0.07, fx) * smoothstep(1.0, 0.93, fx);
  col *= mix(0.55, 1.0, gap);
  """ + DITHER + """
  gl_FragColor = vec4(col, 1.0);
}
"""
    }
}
