package tw.liuli.launcher.ui.bg

import androidx.compose.ui.graphics.Color
import kotlin.math.cos
import kotlin.math.sin

/**
 * 第三批著色器樣式。共同規則同 [GlStyles]：時間在 CPU 端（雙精度）算好、取模後才傳進來；片段著色器只用 mediump；
 * 亂數一律查雜訊貼圖——
 * - g／gt：value noise；gn／gnt：梯度雜訊（Perlin 式，代替 Paper 的 snoise——它的 permute 在半精度下會溢位成條紋）。見 [GlBackground.NOISE]
 * - 時間漂移拆成整數與小數（[GlBackground.putTimes]），座標才保持很小、半精度下不會一格一格
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
uniform vec4 u_p[16];
varying vec2 v_uv;
""" + GlBackground.NOISE
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

        /**
         * 雜訊在頂點著色器算（全精度；網格 64×36，雜訊很低頻，線性內插的誤差不到 0.2 個像素），片段著色器只分色階。
         * 以前在片段著色器（半精度）算：兩層雜訊疊起來再放大五倍分色階，誤差也跟著放大，色塊邊緣一根根地抖（毛邊）。
         * 頂點著色器不能讀貼圖（Mali-400 系列），梯度用雜湊算（全精度下 fract(sin) 沒問題）。
         */
        const val VERTEX = """
attribute vec2 a_pos;
uniform float u_fy;
uniform vec4 u_w;
uniform vec4 u_v1;
varying vec2 v_uv;
varying float v_n;
vec2 hg(vec2 i) {
  float a = fract(sin(dot(i, vec2(127.1, 311.7))) * 43758.5453) * 6.2831853;
  return vec2(cos(a), sin(a));
}
float gnv(vec2 p) {
  vec2 i = floor(p);
  vec2 f = p - i;
  vec2 u = f * f * f * (f * (f * 6.0 - 15.0) + 10.0);
  float a = dot(hg(i), f);
  float b = dot(hg(i + vec2(1.0, 0.0)), f - vec2(1.0, 0.0));
  float c = dot(hg(i + vec2(0.0, 1.0)), f - vec2(0.0, 1.0));
  float d = dot(hg(i + vec2(1.0, 1.0)), f - vec2(1.0, 1.0));
  return 1.45 * mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}
void main() {
  v_uv = a_pos * 0.5 + 0.5;
  vec2 s = (v_uv - 0.5) * vec2(u_w.x, 1.0) * 0.75;
  v_n = 0.5 * gnv(s - vec2(0.0, u_v1.x)) + 0.5 * gnv(2.0 * s + vec2(0.0, u_v1.y));
  gl_Position = vec4(a_pos.x, a_pos.y * u_fy, 0.0, 1.0);
}
"""

        const val SHADER = HEAD + """
varying float v_n;
float ss(float m) {
  float st = floor(m * 2.0) / 2.0;
  float f = m * 2.0 - floor(m * 2.0);
  // 色階之間的柔邊約 1.5 個繪製像素（f 每個像素平均變約 9／解析度）；以前固定 0.06，放大後糊
  float hw = clamp(7.0 / u_res.y, 0.006, 0.03);
  return st + smoothstep(0.5 - hw, 0.5 + hw, f) / 2.0;
}
void main() {
  float shape = 0.5 + 0.5 * v_n;
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

        /**
         * 只有半精度的 GPU（Mali-470）：扭曲分兩段。雜訊位移與前三輪（幅度大、比較平緩）在頂點著色器算（全精度，網格 96×54），
         * 後四輪（頻率高、幅度小）在片段著色器逐像素算。
         * - 全部在片段算：前幾輪幅度大，cos 的參數到 20～30、半精度只剩 1／64 弧度，誤差累積起來色帶的邊緣一階一階的。
         * - 全部在頂點算：高頻的幾輪在一格（約 20 像素）裡彎很多，直線內插在色帶對折的地方變成鋸齒（網格加密到 160 也一樣，而且 GPU 付不起）。
         * 後四輪的幅度是 0.8／4 以下，參數的半精度誤差只造成約半個像素的位移，看不出來。
         */
        const val VERTEX = """
attribute vec2 a_pos;
uniform float u_fy;
uniform vec4 u_w;
uniform vec4 u_v1;
varying vec2 v_uv;
varying vec2 v_cs;
""" + GlBackground.VNOISE + """
void main() {
  v_uv = a_pos * 0.5 + 0.5;
  vec2 uv = (v_uv - 0.5) * vec2(u_w.x, 1.0) * 2.2;
  float t = u_v1.x;
  float n1 = gv(uv + t);
  float n2 = gv(uv * 2.0 - t);
  float a = n1 * 6.2831853;
  uv += 4.0 * u_v1.z * n2 * vec2(cos(a), sin(a));
  float tt = u_v1.y;
  float sw = u_v1.w;
  for (int i = 1; i < 4; i++) {
    float fi = float(i);
    uv.x += sw / fi * cos(tt + fi * 1.5 * uv.y);
    uv.y += sw / fi * cos(tt + fi * uv.x);
  }
  v_cs = uv;
  gl_Position = vec4(a_pos.x, a_pos.y * u_fy, 0.0, 1.0);
}
"""

        /**
         * 片段著色器有全精度（highp）的 GPU（例如 PowerVR）：扭曲整個在片段著色器逐像素算，色帶邊緣沒有網格內插的折角
         * （頂點版的網格 96×54，每格約 20 像素；扭曲很彎的地方色帶的邊會變成一格一格的階梯）。
         * 座標用 gl_FragCoord 換算（全精度），時間與比例用和頂點著色器同名、同為全精度的 u_w／u_v1。
         */
        const val SHADER_HP = HEAD + """
precision highp float;
uniform vec4 u_w;
uniform vec4 u_v1;
uniform float u_fy;
""" + GlBackground.VNOISE + """
void main() {
  vec2 q = gl_FragCoord.xy / u_res;
  if (u_fy < 0.0) q.y = 1.0 - q.y;
  vec2 uv = (q - 0.5) * vec2(u_w.x, 1.0) * 2.2;
  float t = u_v1.x;
  float n1 = gv(uv + t);
  float n2 = gv(uv * 2.0 - t);
  float a = n1 * 6.2831853;
  uv += 4.0 * u_v1.z * n2 * vec2(cos(a), sin(a));
  float tt = u_v1.y;
  float sw = u_v1.w;
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

        const val SHADER = HEAD + """
varying vec2 v_cs;
void main() {
  vec2 uv = v_cs;
  float tt = u_p[1].y;
  float sw = u_p[1].w;
  uv.x += sw * 0.25 * cos(tt + 6.0 * uv.y);
  uv.y += sw * 0.25 * cos(tt + 4.0 * uv.x);
  uv.x += sw * 0.2 * cos(tt + 7.5 * uv.y);
  uv.y += sw * 0.2 * cos(tt + 5.0 * uv.x);
  uv.x += sw * 0.16666667 * cos(tt + 9.0 * uv.y);
  uv.y += sw * 0.16666667 * cos(tt + 6.0 * uv.x);
  uv.x += sw * 0.14285714 * cos(tt + 10.5 * uv.y);
  uv.y += sw * 0.14285714 * cos(tt + 7.0 * uv.x);
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
            out[4] = wrap(st.at * .35 + st.ripple * .8, TAU) / TAU.toFloat()
            put(out, 8, c(st, 0f, .85f, .09f))
            put(out, 12, c(st, 15f, .75f, .50f))
        }

        const val SHADER = HEAD + """
void main() {
  vec2 uv = (v_uv - u_p[0].yz) * vec2(u_p[0].x, 1.0) * 5.6;
  float l = max(length(uv), 0.001);
  float stripe = fract(l + atan(uv.y, uv.x) / 6.2831853 - u_p[1].x);
  float shape = 2.0 * abs(stripe - 0.5);
  // 柔邊約 2.2 個像素：半徑 ×5.6 之後在半精度下只準到約 0.004，柔邊太窄邊緣會起毛
  float px = 2.2 * (5.6 * u_p[0].x / u_res.x) * (1.0 + 0.16 / l);
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
            out[4] = wrap(t * .21, TAU); out[5] = wrap(t * .13, TAU); out[6] = wrap(t * .035, TAU); out[7] = wrap(t * .09, TAU); GlBackground.putTimes(out, t * .05)
            put(out, 8, c(st, 20f, .6f, .025f))
            put(out, 12, c(st, 30f, .5f, .09f))
            put(out, 16, c(st, 0f, .95f, .42f))
            put(out, 20, c(st, 55f, .9f, .42f))
        }

        /**
         * 帶子的高低起伏、厚薄、顏色只跟水平位置有關：在頂點著色器算（全精度；32 欄的網格，起伏最細的一項一個週期也有 26 個點），
         * 片段著色器只剩每條帶子兩個指數。以前全部在片段著色器，480×270 一張要 29ms。
         */
        const val VERTEX = """
attribute vec2 a_pos;
uniform float u_fy;
uniform vec4 u_w;
uniform vec4 u_v1;
varying vec2 v_uv;
varying vec3 v_wv;
varying vec3 v_th;
varying vec3 v_m;
""" + GlBackground.VNOISE + """
void main() {
  v_uv = a_pos * 0.5 + 0.5;
  float x = v_uv.x * u_w.x;
  float t = u_vt.x + u_vf.x;
  vec3 k = vec3(0.0, 1.0, 2.0);
  v_wv = 0.06 * sin(x * 2.1 + u_v1.x + k * 1.7) + 0.035 * sin(x * 4.3 - u_v1.y * 1.3 + k * 2.9)
       + 0.1 * (vec3(gv(vec2(x * 1.1, t)), gv(vec2(x * 1.1 + 7.0, t)), gv(vec2(x * 1.1 + 14.0, t))) - 0.5);
  // 厚薄用兩個 sin（Mali-400 的頂點著色器最多 512 個指令，六次雜訊放不下）
  v_th = 0.03 + 0.03 * (0.5 + 0.3 * sin(x * 1.9 + k * 5.0 + u_v1.z) + 0.2 * sin(x * 3.7 - k * 2.3 - u_v1.z * 1.4));
  v_m = 0.5 + 0.5 * sin(x * 1.3 + k * 2.0 + u_v1.w);
  gl_Position = vec4(a_pos.x, a_pos.y * u_fy, 0.0, 1.0);
}
"""

        const val SHADER = HEAD + """
varying vec3 v_wv;
varying vec3 v_th;
varying vec3 v_m;
vec3 band(float y, float x, float k, float y0, float w, float th, float m, float amp) {
  float d = y - (y0 + w);
  float glow = exp(-d * d / (th * th));
  // 光幕的下緣：柔化約 1.5 個像素（以前是 step 硬邊，解析度提高後看得到一階一階）
  float tail = exp(-max(-d, 0.0) * 5.0) * (1.0 - smoothstep(-1.5 / u_res.y, 1.5 / u_res.y, d)) * 0.35;
  float rays = 0.8 + 0.2 * sin(x * 70.0 + k * 3.0);
  vec3 c = mix(u_p[4].rgb, u_p[5].rgb, m);
  return c * (glow * 0.6 + tail * rays) * amp;
}
void main() {
  vec2 uv = v_uv;
  float x = uv.x * u_p[0].x;
  vec3 light = band(uv.y, x, 0.0, 0.36, v_wv.x, v_th.x, v_m.x, 1.0) + band(uv.y, x, 1.0, 0.50, v_wv.y, v_th.y, v_m.y, 0.7)
             + band(uv.y, x, 2.0, 0.27, v_wv.z, v_th.z, v_m.z, 0.45);
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
            out[4] = wrap(t * .25, TAU); out[5] = wrap(t * .6, TAU)
            GlBackground.putTimes(out, t * .35, t * .2, t * .4, t * .13)
            out[10] = act.toFloat(); out[11] = 0f
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
    float hem = -0.10 - layer * 0.13 + bend + (0.012 + act * 0.028) * (gt(vec2(x * 15.0, u_p[15].x), vec2(0.0, u_p[14].x + layer)) - 0.5);
    float rise = p.y - hem;
    float drift = x + rise * 0.1 + (0.017 + act * 0.015) * gt(vec2(x * 8.0, u_p[15].y), vec2(0.0, u_p[14].y + layer));
    float grain = gt(vec2(drift * 60.0, u_p[15].z), vec2(0.0, u_p[14].z + layer * 4.0));
    float threads = 0.36 + 0.64 * sqrt(grain) + 0.085;
    float patches = 0.48 + 0.8 * gt(vec2(x * 8.0, u_p[15].w), vec2(layer, u_p[14].w));
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
            GlBackground.putTimes(out, st.at * .055)
            out[5] = .085f + (.35f + .4f * st.pulse) * .19f
            put(out, 8, c(st, 0f, .75f, .13f))    // 底（原版 0.33,0.043,0.11）
            put(out, 12, c(st, 40f, .85f, .48f))  // 緞帶（0.99,0.82,0.37）；調暗，整片圖樣才不會搶過圖示
            put(out, 16, c(st, 20f, .8f, .38f))   // 緞帶外緣（0.91,0.52,0.26）
            put(out, 20, c(st, 0f, .8f, .24f))    // 細線（0.42,0.065,0.14）
        }

        const val SHADER = HEAD + """
void main() {
  vec2 p = (v_uv - 0.5) * vec2(u_p[0].x, 1.0) * 7.0 + vec2(u_p[15].x, 0.0);
  vec2 id = mod(floor(p) + vec2(u_p[14].x, 0.0), 64.0);
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
