package tw.liuli.launcher.ui.bg

import androidx.compose.ui.graphics.Color
import kotlin.math.cos
import kotlin.math.sin

/**
 * 第三批著色器樣式。共同規則同 [GlStyles]：時間在 CPU 端（雙精度）算好、取模後才傳進來；片段著色器只用 mediump；
 * 亂數一律查雜訊貼圖——
 * - g(p)：value noise（線性內插貼圖＋smoothstep 座標，一次取樣），週期 64
 * - gn(p)：梯度雜訊（Perlin 式；四個角的梯度查最近鄰貼圖），範圍約 −1～1，週期 64。用來代替 Paper 的 snoise
 *   （snoise 的 permute 是 mod(x·34, 289)，半精度下會溢位成條紋）
 * 雜訊的時間漂移取模 64 後接得起來（貼圖以 64 格為週期）。
 *
 * 參數：u_p[0] 固定是 (畫面寬高比, 焦點 x, 焦點 y, 脈動)，其餘各樣式自己定義。
 */
internal object GlStyles2 {
    private const val TAU = Math.PI * 2

    private fun put(out: FloatArray, i: Int, c: Color) {
        out[i] = c.red; out[i + 1] = c.green; out[i + 2] = c.blue; out[i + 3] = 0f
    }

    private fun head(st: BgState, out: FloatArray) {
        out[0] = 16f / 9f; out[1] = st.fx; out[2] = st.fy; out[3] = st.pulse
    }

    private fun c(st: BgState, dh: Float, sat: Float, l: Float) = GlStyles.hsl(st, dh, sat, l)

    private fun wrap(v: Double, m: Double) = (((v % m) + m) % m).toFloat()

    private const val HEAD = """
precision mediump float;
uniform sampler2D u_noise;
uniform sampler2D u_noiseL;
uniform vec2 u_res;
uniform vec4 u_p[14];
varying vec2 v_uv;
float g(vec2 p) {
  vec2 i = floor(p);
  vec2 f = fract(p);
  f = f * f * (3.0 - 2.0 * f);
  return texture2D(u_noiseL, (i + f + 0.5) / 64.0).r;
}
vec2 gr(vec2 i) { return texture2D(u_noise, (i + 0.5) / 64.0).rg * 2.0 - 1.0; }
float gn(vec2 p) {
  vec2 i = floor(p);
  vec2 f = fract(p);
  vec2 u = f * f * f * (f * (f * 6.0 - 15.0) + 10.0);
  float a = dot(gr(i), f);
  float b = dot(gr(i + vec2(1.0, 0.0)), f - vec2(1.0, 0.0));
  float c = dot(gr(i + vec2(0.0, 1.0)), f - vec2(0.0, 1.0));
  float d = dot(gr(i + vec2(1.0, 1.0)), f - vec2(1.0, 1.0));
  return 1.6 * mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}
"""
    private const val DITHER = GlBackground.TONE + "col += (texture2D(u_noise, gl_FragCoord.xy / 64.0).rgb - 0.5) / 255.0;"

    // ───────────── 色塊雜訊（Paper simplex-noise） ─────────────

    /**
     * 色塊雜訊：兩層雜訊疊成的地形，依高低分成五種顏色、每種顏色再分兩階，像地圖色塊一樣慢慢流動。
     * 改寫自 Paper Shaders 的 simplex-noise（Apache-2.0）。改動：snoise 換成查貼圖的梯度雜訊；
     * fwidth 反鋸齒換成固定寬度；顏色迴圈展開；配色照預設的結構（一個對比色、淡色、主色、暖色、近白）由主色推出。
     */
    object Simplex {
        fun params(st: BgState, out: FloatArray) {
            head(st, out)
            val t = st.at * .1                          // Paper：0.2 × u_time × speed 0.5
            out[4] = wrap(.3 * t, 64.0); out[5] = wrap(.32 * t, 64.0)
            put(out, 8, c(st, -150f, .6f, .45f))
            put(out, 12, c(st, 10f, .9f, .78f))
            put(out, 16, c(st, 0f, .95f, .58f))
            put(out, 20, c(st, 40f, 1f, .66f))
            put(out, 24, c(st, 0f, .25f, .8f))
        }

        const val SHADER = HEAD + """
float ss(float m) {
  float st = floor(m * 2.0) / 2.0;
  float f = m * 2.0 - floor(m * 2.0);
  // 色階之間的柔邊約 1.5 個繪製像素（f 每個像素平均變約 9／解析度）；以前固定 0.06，放大後糊
  float hw = clamp(7.0 / u_res.y, 0.006, 0.03);
  return st + smoothstep(0.5 - hw, 0.5 + hw, f) / 2.0;
}
void main() {
  vec2 s = (v_uv - 0.5) * vec2(u_p[0].x, 1.0) * 0.75;
  float n = 0.5 * gn(s - vec2(0.0, u_p[1].x)) + 0.5 * gn(2.0 * s + vec2(0.0, u_p[1].y));
  float shape = 0.5 + 0.5 * n;
  float mixer = (shape - 0.1) * 5.0;
  vec3 col = u_p[2].rgb;
  col = mix(col, u_p[3].rgb, ss(clamp(mixer, 0.0, 1.0)));
  col = mix(col, u_p[4].rgb, ss(clamp(mixer - 1.0, 0.0, 1.0)));
  col = mix(col, u_p[5].rgb, ss(clamp(mixer - 2.0, 0.0, 1.0)));
  col = mix(col, u_p[6].rgb, ss(clamp(mixer - 3.0, 0.0, 1.0)));
  if (mixer < 0.0) col = mix(u_p[6].rgb, u_p[2].rgb, ss(clamp(mixer + 1.0, 0.0, 1.0)));
  if (mixer > 4.0) col = mix(u_p[6].rgb, u_p[2].rgb, ss(clamp(mixer - 4.0, 0.0, 1.0)));
  """ + DITHER + """
  gl_FragColor = vec4(col, 1.0);
}
"""
    }

    // ───────────── 扭曲色帶（Paper warp） ─────────────

    /**
     * 扭曲色帶：棋盤格狀的 sin·cos 圖樣被雜訊推開、再一層層漩渦扭曲，變成流動的絲狀色帶。
     * 改寫自 Paper Shaders 的 warp（Apache-2.0）：預設參數（扭曲 0.25、漩渦 0.8、棋盤、比例 0.45、柔邊 1）；
     * 漩渦迭代 10 → 7 次（Mali-470 每一次都是兩個 cos）；時間相位在 CPU 取模。
     */
    object Warp {
        fun params(st: BgState, out: FloatArray) {
            head(st, out)
            val t = .0625 * (st.at * .5 + 118.0)
            out[4] = wrap(t, 64.0); out[5] = wrap(t, TAU)
            out[6] = .25f + .15f * st.pulse; out[7] = .8f
            put(out, 8, c(st, 0f, .25f, .07f))
            put(out, 12, c(st, 0f, .95f, .70f))
            put(out, 16, c(st, 0f, .25f, .07f))
            put(out, 20, c(st, -10f, 1f, .58f))
        }

        const val SHADER = HEAD + """
void main() {
  vec2 uv = (v_uv - 0.5) * vec2(u_p[0].x, 1.0) * 2.2;
  float t = u_p[1].x;
  float n1 = g(uv + t);
  float n2 = g(uv * 2.0 - t);
  float a = n1 * 6.2831853;
  uv += 4.0 * u_p[1].z * n2 * vec2(cos(a), sin(a));
  float tt = u_p[1].y;
  float sw = u_p[1].w;
  for (int i = 1; i < 8; i++) {
    float fi = float(i);
    uv.x += sw / fi * cos(tt + fi * 1.5 * uv.y);
    uv.y += sw / fi * cos(tt + fi * uv.x);
  }
  vec2 cs = uv * 0.85;
  float shape = 0.5 + 0.5 * sin(cs.x) * cos(cs.y) - 0.1073;
  float mixer = shape * 3.0;
  vec3 col = u_p[2].rgb;
  col = mix(col, u_p[3].rgb, clamp(mixer, 0.0, 1.0));
  col = mix(col, u_p[4].rgb, clamp(mixer - 1.0, 0.0, 1.0));
  col = mix(col, u_p[5].rgb, clamp(mixer - 2.0, 0.0, 1.0));
  """ + DITHER + """
  gl_FragColor = vec4(col, 1.0);
}
"""
    }

    // ───────────── 螺旋（Paper spiral） ─────────────

    /**
     * 螺旋：一條等寬的阿基米德螺旋線繞著焦點慢慢轉。
     * 改寫自 Paper Shaders 的 spiral（Apache-2.0）：預設參數（密度 1、線寬 0.5、無扭曲）；
     * fwidth 反鋸齒改成依螺旋的解析式算出每個像素的寬度；中心跟著焦點；轉速放慢。
     */
    object Spiral {
        fun params(st: BgState, out: FloatArray) {
            head(st, out)
            out[4] = wrap(st.at * .35 + st.ripple * .8, TAU)
            put(out, 8, c(st, 0f, .85f, .09f))
            put(out, 12, c(st, 15f, .75f, .50f))
        }

        const val SHADER = HEAD + """
void main() {
  vec2 uv = (v_uv - u_p[0].yz) * vec2(u_p[0].x, 1.0) * 5.6;
  float l = max(length(uv), 0.001);
  float ang = atan(uv.y, uv.x) - u_p[1].x;
  float stripe = fract(l + ang / 6.2831853);
  float shape = 2.0 * abs(stripe - 0.5);
  float px = 1.5 * (5.6 * u_p[0].x / u_res.x) * (1.0 + 0.16 / l);
  float res = smoothstep(0.5 - px, 0.5 + px, shape);
  vec3 col = mix(u_p[2].rgb, u_p[3].rgb, res);
  """ + DITHER + """
  gl_FragColor = vec4(col, 1.0);
}
"""
    }

    // ───────────── 柔極光（琉璃自己寫，只參考效果） ─────────────

    /**
     * 柔極光：暗色天空上三條柔和發光的極光帶，高低起伏、厚薄不一，往上拖出淡淡的光幕，顏色沿著帶子在主色與鄰近色之間變化。
     * 琉璃自己寫（React Bits 的 soft-aurora 只參考效果的樣子，沒有看程式碼）。
     */
    object SoftAurora {
        fun params(st: BgState, out: FloatArray) {
            head(st, out)
            val t = st.at.toDouble()
            out[4] = wrap(t * .21, TAU); out[5] = wrap(t * .13, TAU); out[6] = wrap(t * .05, 64.0); out[7] = wrap(t * .09, TAU)
            put(out, 8, c(st, 20f, .6f, .025f))
            put(out, 12, c(st, 30f, .5f, .09f))
            put(out, 16, c(st, 0f, .95f, .42f))
            put(out, 20, c(st, 55f, .9f, .42f))
        }

        const val SHADER = HEAD + """
vec3 band(vec2 uv, float k, float y0, float amp) {
  float x = uv.x * u_p[0].x;
  float w = 0.06 * sin(x * 2.1 + u_p[1].x + k * 1.7) + 0.035 * sin(x * 4.3 - u_p[1].y * 1.3 + k * 2.9)
          + 0.1 * (g(vec2(x * 1.1 + k * 7.0, u_p[1].z)) - 0.5);
  float d = uv.y - (y0 + w);
  float th = 0.03 + 0.03 * g(vec2(x * 1.7 + k * 5.0, u_p[1].z * 0.7));
  float glow = exp(-d * d / (th * th));
  float tail = exp(-max(-d, 0.0) * 5.0) * step(d, 0.0) * 0.35;
  float rays = 0.8 + 0.2 * sin(x * 70.0 + k * 3.0);
  vec3 c = mix(u_p[4].rgb, u_p[5].rgb, 0.5 + 0.5 * sin(x * 1.3 + k * 2.0 + u_p[1].w));
  return c * (glow * 0.6 + tail * rays) * amp;
}
void main() {
  vec2 uv = v_uv;
  vec3 light = band(uv, 0.0, 0.36, 1.0) + band(uv, 1.0, 0.50, 0.7) + band(uv, 2.0, 0.27, 0.45);
  vec3 sky = mix(u_p[3].rgb, u_p[2].rgb, uv.y);
  vec3 col = sky + (1.0 - exp(-light * 1.1));
  """ + DITHER + """
  gl_FragColor = vec4(col, 1.0);
}
"""
    }

    // ───────────── 極光簾（Subframe aurora-curtain） ─────────────

    /**
     * 極光簾：三層極光的下緣（簾腳）彎曲起伏，往上透出帶細絲的光幕，最下緣一道亮邊；背景有零星的星星。
     * 改寫自 Subframe 的 aurora-curtain（Copyright (c) 2026 Subframe，MIT License，全文見 assets/licenses/subframe-LICENSE.txt）。改動：
     * - fract(sin·43719) 雜湊改成查雜訊貼圖；時間相位在 CPU 取模
     * - 細絲的頻率 145／310 → 60（480×270 畫不出那麼細，放大後會閃）；最細的一層拿掉
     * - 「活躍度」滑桿改成焦點脈動；顏色改成主色系（原版固定綠色）
     * - 座標 y 往下（畫面座標），簾腳在畫面下方、光往上
     */
    object Curtain {
        // 流動的相位逐格累加：以前是「累計時間 × 速度」，焦點一動速度變了，相位一口氣跳好幾十圈（換格子時整片暴衝）
        private var phase = 0.0
        private var lastAt = Float.NaN

        fun params(st: BgState, out: FloatArray) {
            head(st, out)
            val act = (.35f + .65f * st.pulse).toDouble()
            val dt = if (lastAt.isNaN()) 0.0 else (st.at - lastAt).toDouble().coerceIn(0.0, .25)
            lastAt = st.at
            phase += dt * (.09 + act * .07)   // 雙精度，不取模（各相位的週期沒有公倍數，取模會跳）
            val t = phase
            out[4] = wrap(t * .25, TAU); out[5] = wrap(t * .6, TAU); out[6] = wrap(t * .35, 64.0); out[7] = wrap(t * .2, 64.0)
            out[8] = wrap(t * .4, 64.0); out[9] = wrap(t * .13, 64.0); out[10] = act.toFloat(); out[11] = 0f
            put(out, 12, c(st, 0f, .9f, .45f))    // 光幕亮色（原版 0.10,0.84,0.40）
            put(out, 16, c(st, 20f, .85f, .16f))  // 光幕高處（0.025,0.28,0.32）
            put(out, 20, c(st, -5f, .8f, .85f))   // 亮邊（0.46,1.0,0.70）
            put(out, 24, c(st, 20f, .7f, .012f))  // 天空下
            put(out, 28, c(st, 25f, .7f, .04f))   // 天空上
        }

        const val SHADER = HEAD + """
void main() {
  vec2 uv = v_uv;
  vec2 p = vec2((uv.x - 0.5) * u_p[0].x, 0.5 - uv.y);
  float act = u_p[2].z;
  vec3 color = mix(u_p[6].rgb, u_p[7].rgb, 1.0 - uv.y);
  for (int i = 0; i < 3; i++) {
    float layer = float(i);
    float x = p.x + layer * 0.42;
    float bend = 0.15 * sin(x * 2.2 + u_p[1].x) + (0.03 + act * 0.08) * sin(x * 4.8 - u_p[1].y + layer);
    float hem = -0.10 - layer * 0.13 + bend + (0.012 + act * 0.028) * (g(vec2(x * 15.0, u_p[1].z + layer)) - 0.5);
    float rise = p.y - hem;
    float drift = x + rise * 0.1 + (0.017 + act * 0.015) * g(vec2(x * 8.0, u_p[1].w + layer));
    float grain = g(vec2(drift * 60.0, u_p[2].x + layer * 4.0));
    float threads = 0.36 + 0.64 * sqrt(grain) + 0.085;
    float patches = 0.48 + 0.8 * g(vec2(x * 8.0 + layer, u_p[2].y));
    float reach = 5.5 + g(vec2(x * 11.0, layer + 4.0)) * 5.0 - act * 1.5;
    float veil = smoothstep(-0.012, 0.022, rise) * exp(-max(rise, 0.0) * reach);
    float lip = exp(-abs(rise) / (0.012 + grain * 0.013));
    float halo = exp(-abs(rise) * 16.0) * 0.12;
    float amount = pow(0.32, layer) * patches;
    vec3 tint = mix(u_p[3].rgb, u_p[4].rgb, smoothstep(0.02, 0.38, max(0.0, rise)));
    color += amount * (tint * (veil * threads * (0.5 + act * 0.52) + halo) + u_p[5].rgb * lip * threads * (0.55 + act * 0.4));
  }
  vec2 star = vec2(p.x, p.y) * 90.0;
  vec2 cell = floor(star);
  float pts = step(0.996, texture2D(u_noise, (cell + 0.5) / 64.0).b) * (1.0 - smoothstep(0.05, 0.3, length(fract(star) - 0.5)));
  color += pts * 0.42 * (1.0 - smoothstep(0.1, 0.8, max(color.r, max(color.g, color.b))));
  color *= 0.88 + 0.12 * pow(max(0.0, 1.0 - length(p) * 0.6), 0.6);
  vec3 col = 1.0 - exp(-color * 1.4);
  """ + DITHER + """
  gl_FragColor = vec4(col, 1.0);
}
"""
    }

    // ───────────── 特魯謝緞帶（Subframe truchet-ribbon） ─────────────

    /**
     * 特魯謝緞帶：每一格隨機擺一對四分之一圓弧，連起來變成蜿蜒不斷的緞帶，整片慢慢往旁邊捲動。
     * 改寫自 Subframe 的 truchet-ribbon（MIT，同上）。改動：雜湊改成查雜訊貼圖（捲動取模 64，圖樣接得起來）；
     * 緞帶上的細線加寬到至少 1.5 個像素（480×270 放大後才不會閃）；脈動讓緞帶變寬；顏色由主色推出（原版酒紅底、黃緞帶）。
     */
    object Truchet {
        fun params(st: BgState, out: FloatArray) {
            head(st, out)
            out[4] = wrap(st.at * .055, 64.0)
            out[5] = .085f + (.35f + .4f * st.pulse) * .19f
            put(out, 8, c(st, 0f, .75f, .13f))    // 底（原版 0.33,0.043,0.11）
            put(out, 12, c(st, 40f, .85f, .48f))  // 緞帶（0.99,0.82,0.37）；調暗，整片圖樣才不會搶過圖示
            put(out, 16, c(st, 20f, .8f, .38f))   // 緞帶外緣（0.91,0.52,0.26）
            put(out, 20, c(st, 0f, .8f, .24f))    // 細線（0.42,0.065,0.14）
        }

        const val SHADER = HEAD + """
void main() {
  vec2 p = (v_uv - 0.5) * vec2(u_p[0].x, 1.0) * 7.0 + vec2(u_p[1].x, 0.0);
  vec2 id = floor(p);
  vec2 q = fract(p);
  if (texture2D(u_noise, (id + 0.5) / 64.0).a > 0.5) q.x = 1.0 - q.x;
  float d = min(abs(length(q) - 0.5), abs(length(q - 1.0) - 0.5));
  float width = u_p[1].y;
  float aa = 7.0 / u_res.y;
  float ribbon = 1.0 - smoothstep(width - aa, width + aa, d);
  float ridge = 1.0 - smoothstep(width * 0.56, width * 0.75, d);
  vec3 col = mix(u_p[2].rgb, u_p[3].rgb, ribbon);
  col = mix(col, u_p[4].rgb, ribbon * (1.0 - ridge) * 0.38);
  float fw = max(0.012, 1.5 * aa);
  float fine = 1.0 - smoothstep(fw, fw + aa, abs(d - width * 0.68));
  col = mix(col, u_p[5].rgb, fine * ribbon * 0.42);
  """ + DITHER + """
  gl_FragColor = vec4(col, 1.0);
}
"""
    }
}
