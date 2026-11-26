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

    // ── B 方案（分兩段，見 GlBackground.SPLIT）共用 ──
    /** 第一段：0～1 的場編成 16 位元（R＝高 8 位、G＝低 8 位）。雙線性內插對兩個通道各自是線性的，解回來仍然正確。 */
    private const val ENC = "vec4 enc(float v) { v = clamp(v, 0.0, 0.999); return vec4(floor(v * 255.0) / 255.0, fract(v * 255.0), 0.0, 1.0); }\n"
    /** 第二段：讀場（u_field，第 4 個貼圖單元）；邊緣寬度用 fwidth（OES_standard_derivatives，兩台電視都有；沒有就編譯失敗、改回一層）。 */
    private const val EDGE_HEAD = "#extension GL_OES_standard_derivatives : enable\n" + HEAD +
        "uniform sampler2D u_field;\nfloat dec() { vec4 t = texture2D(u_field, v_uv); return t.r + t.g / 255.0; }\n"

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
  // 先判斷落在哪一段，只做一次分色階（以前五段各做一次 floor／fract／smoothstep，結果相同）
  float k = clamp(floor(mixer), -1.0, 4.0);
  vec3 ca = u_p[6].rgb; vec3 cb = u_p[2].rgb;
  if (k > -0.5 && k < 0.5) { ca = u_p[2].rgb; cb = u_p[3].rgb; }
  else if (k > 0.5 && k < 1.5) { ca = u_p[3].rgb; cb = u_p[4].rgb; }
  else if (k > 1.5 && k < 2.5) { ca = u_p[4].rgb; cb = u_p[5].rgb; }
  else if (k > 2.5 && k < 3.5) { ca = u_p[5].rgb; cb = u_p[6].rgb; }
  vec3 col = mix(ca, cb, ss(clamp(mixer - k, 0.0, 1.0)));
  """ + DITHER + """
  gl_FragColor = vec4(col, 1.0);
}
"""

        /** B 方案第一段：雜訊值（形狀）。 */
        const val FIELD = HEAD + """
varying float v_n;
""" + ENC + """
void main() { gl_FragColor = enc(0.5 + 0.5 * v_n); }
"""

        /** B 方案第二段：讀形狀、在這個解析度的像素上分色階（柔邊約 1.5 個像素，用 fwidth）。 */
        const val EDGE = EDGE_HEAD + """
float hw;
float ss(float m) {
  float st = floor(m * 2.0) / 2.0;
  float f = m * 2.0 - floor(m * 2.0);
  return st + smoothstep(0.5 - hw, 0.5 + hw, f) / 2.0;
}
void main() {
  float shape = dec();
  // f＝(形狀−0.1)×10 的小數：每個像素變 10·fwidth(形狀)；半寬取 0.75 個像素
  hw = clamp(7.5 * fwidth(shape), 0.002, 0.25);
  float mixer = (shape - 0.1) * 5.0;
  // 先判斷落在哪一段，只做一次分色階（以前五段各做一次 floor／fract／smoothstep，結果相同）
  float k = clamp(floor(mixer), -1.0, 4.0);
  vec3 ca = u_p[6].rgb; vec3 cb = u_p[2].rgb;
  if (k > -0.5 && k < 0.5) { ca = u_p[2].rgb; cb = u_p[3].rgb; }
  else if (k > 0.5 && k < 1.5) { ca = u_p[3].rgb; cb = u_p[4].rgb; }
  else if (k > 1.5 && k < 2.5) { ca = u_p[4].rgb; cb = u_p[5].rgb; }
  else if (k > 2.5 && k < 3.5) { ca = u_p[5].rgb; cb = u_p[6].rgb; }
  vec3 col = mix(ca, cb, ss(clamp(mixer - k, 0.0, 1.0)));
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
            // 深色帶有色相（以前是飽和度 .25 的近黑灰，和亮色混出來的過渡是灰濁的，看起來髒）；
            // 第三段改成較深的同色系（以前又是近黑，畫面上大塊黑斑）
            put(out, 8, c(st, 0f, .7f, .10f))
            put(out, 12, c(st, 0f, .95f, .70f))
            put(out, 16, c(st, 12f, .85f, .32f))
            put(out, 20, c(st, -10f, 1f, .58f))
        }

        /**
         * 扭曲一共四輪（以前七輪：後三輪頻率高、色帶摺得很細，看起來髒髒的，也貴），雜訊位移的幅度也調小（4→3）。
         * 只有半精度的 GPU（Mali-470）：雜訊位移與前三輪（幅度大、比較平緩）在頂點著色器算（全精度，網格 96×54），
         * 最後一輪在片段著色器逐像素算（幅度 0.8／4，半精度誤差只造成約半個像素的位移）。
         * 前幾輪放片段算的話，cos 的參數到 20～30、半精度只剩 1／64 弧度，色帶的邊緣一階一階的。
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
  uv += 3.0 * u_v1.z * n2 * vec2(cos(a), sin(a));
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
        const val SHADER_HP = "#extension GL_OES_standard_derivatives : enable\n" + HEAD + """
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
  uv += 3.0 * u_v1.z * n2 * vec2(cos(a), sin(a));
  float tt = u_v1.y;
  float sw = u_v1.w;
  for (int i = 1; i < 5; i++) {
    float fi = float(i);
    uv.x += sw / fi * cos(tt + fi * 1.5 * uv.y);
    uv.y += sw / fi * cos(tt + fi * uv.x);
  }
  vec2 cs = uv * 0.85;
  float shape = 0.5 + 0.5 * sin(cs.x) * cos(cs.y) - 0.1073;
  float mixer = shape * 3.0;
  // 柔化：色帶對折、變化很急的地方（一個像素裡跨過大半條色帶，會出現鋸齒與斑點），把交界的漸層加寬到約 1.5 個像素；
  // 平緩的地方寬度不變（＝原本的樣子）
#ifdef GL_OES_standard_derivatives
  float sw2 = max(1.0, 4.5 * fwidth(shape));
#else
  float sw2 = 1.0;
#endif
  // 過渡用 smoothstep（以前線性：每段交界的斜率突然改變，看得到一條條折痕）
  vec3 col = u_p[2].rgb;
  col = mix(col, u_p[3].rgb, smoothstep(0.0, 1.0, (mixer - 0.5) / sw2 + 0.5));
  col = mix(col, u_p[4].rgb, smoothstep(0.0, 1.0, (mixer - 1.5) / sw2 + 0.5));
  col = mix(col, u_p[5].rgb, smoothstep(0.0, 1.0, (mixer - 2.5) / sw2 + 0.5));
  """ + DITHER + """
  gl_FragColor = vec4(col, 1.0);
}
"""

        const val SHADER = "#extension GL_OES_standard_derivatives : enable\n" + HEAD + """
varying vec2 v_cs;
void main() {
  vec2 uv = v_cs;
  float tt = u_p[1].y;
  float sw = u_p[1].w;
  uv.x += sw * 0.25 * cos(tt + 6.0 * uv.y);
  uv.y += sw * 0.25 * cos(tt + 4.0 * uv.x);
  vec2 cs = uv * 0.85;
  float shape = 0.5 + 0.5 * sin(cs.x) * cos(cs.y) - 0.1073;
  float mixer = shape * 3.0;
  // 柔化：色帶對折、變化很急的地方（一個像素裡跨過大半條色帶，會出現鋸齒與斑點），把交界的漸層加寬到約 1.5 個像素；
  // 平緩的地方寬度不變（＝原本的樣子）
#ifdef GL_OES_standard_derivatives
  float sw2 = max(1.0, 4.5 * fwidth(shape));
#else
  float sw2 = 1.0;
#endif
  // 過渡用 smoothstep（以前線性：每段交界的斜率突然改變，看得到一條條折痕）
  vec3 col = u_p[2].rgb;
  col = mix(col, u_p[3].rgb, smoothstep(0.0, 1.0, (mixer - 0.5) / sw2 + 0.5));
  col = mix(col, u_p[4].rgb, smoothstep(0.0, 1.0, (mixer - 1.5) / sw2 + 0.5));
  col = mix(col, u_p[5].rgb, smoothstep(0.0, 1.0, (mixer - 2.5) / sw2 + 0.5));
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
vec3 band(float y, float rays, float y0, float w, float th, float m, float amp) {
  float d = y - (y0 + w);
  float glow = exp(-d * d / (th * th));
  // 光幕的下緣：柔化約 1.5 個像素（以前是 step 硬邊，解析度提高後看得到一階一階）
  float tail = exp(-max(-d, 0.0) * 5.0) * (1.0 - smoothstep(-1.5 / u_res.y, 1.5 / u_res.y, d)) * 0.35;
  vec3 c = mix(u_p[4].rgb, u_p[5].rgb, m);
  return c * (glow * 0.6 + tail * rays) * amp;
}
void main() {
  vec2 uv = v_uv;
  float x = uv.x * u_p[0].x;
  // 三條光帶的光芒 sin(70x＋3k)：只算一次 sin、cos，另外兩條用角度和公式（以前每條各算一次 sin）
  float rs = sin(x * 70.0);
  float rc = cos(x * 70.0);
  float r1 = rs * -0.9899925 + rc * 0.14112;
  float r2 = rs * 0.96017029 + rc * -0.2794155;
  vec3 light = band(uv.y, 0.8 + 0.2 * rs, 0.36, v_wv.x, v_th.x, v_m.x, 1.0) + band(uv.y, 0.8 + 0.2 * r1, 0.50, v_wv.y, v_th.y, v_m.y, 0.7)
             + band(uv.y, 0.8 + 0.2 * r2, 0.27, v_wv.z, v_th.z, v_m.z, 0.45);
  vec3 sky = mix(u_p[3].rgb, u_p[2].rgb, uv.y);
  vec3 col = sky + (1.0 - exp(-light * 1.1));
  """ + DITHER + """
  gl_FragColor = vec4(col, 1.0);
}
"""
    }

    // ───────────── 極光簾（Subframe aurora-curtain） ─────────────

    /**
     * 極光簾：三層極光的下緣（簾腳）彎曲起伏，往上透出帶細絲的光幕，最下緣一道亮邊。
     * 改寫自 Subframe 的 aurora-curtain（Copyright (c) 2026 Subframe，MIT License，全文見 assets/licenses/subframe-LICENSE.txt）。改動：
     * - fract(sin·43719) 雜湊改成查雜訊貼圖；時間相位在 CPU 取模
     * - 細絲的頻率 145／310 → 60（480×270 畫不出那麼細，放大後會閃）；最細的一層拿掉
     * - 「活躍度」滑桿改成焦點脈動；顏色改成主色系（原版固定綠色）
     * - 座標 y 往下（畫面座標），簾腳在畫面下方、光往上
     * - 拿掉背景的星星：這個樣式只畫得起低解析度（海信 320×180），星星放大六倍變成糊糊的方塊
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
            out[6] = act.toFloat()                // 頂點著色器也要（u_v1.z）
            put(out, 12, c(st, 0f, .9f, .45f))    // 光幕亮色（原版 0.10,0.84,0.40）
            put(out, 16, c(st, 20f, .85f, .16f))  // 光幕高處（0.025,0.28,0.32）
            put(out, 20, c(st, -5f, .8f, .85f))   // 亮邊（0.46,1.0,0.70）
            put(out, 24, c(st, 20f, .7f, .012f))  // 天空下
            put(out, 28, c(st, 25f, .7f, .04f))   // 天空上
        }

        /**
         * 只跟橫向位置 x 有關的量（每層簾腳的高度、飄移、亮斑、往上延伸多高：四次雜訊＋兩次 sin）每格在 CPU 上算好
         * （128 欄 × 3 層），存成一張 128×6 的小貼圖（u_data，第 2 個貼圖單元），片段每層只讀兩個像素——
         * 以前每個像素三層各算一遍，海信上 384×216 就要 30ms。（試過搬到頂點著色器：超過 Mali-400 頂點單元 512 條指令的上限，編譯失敗。）
         * 每層兩列：(簾腳高度高位, 低位, 飄移, 亮斑)、(往上延伸, 細絲, -, -)；簾腳要精確（下緣那道亮邊），用 16 位元。
         * 細絲也只跟 x 有關（原本帶一點隨簾腳的斜度，拿掉後細絲筆直往上），在這裡算好，片段就不用每層每個像素查雜訊——
         * 那次查詢的座標要等讀完貼圖才算得出來，Mali-400 上特別慢。
         */
        const val DATA_W = 384   // 細絲很密（橫向約 107 格雜訊），每格雜訊至少 3 欄
        const val DATA_H = 6
        val data: java.nio.ByteBuffer = java.nio.ByteBuffer.allocateDirect(DATA_W * DATA_H * 4)

        private fun hash(x: Int, y: Int): Float {
            var n = x * 374761393 + y * 668265263
            n = (n xor (n ushr 13)) * 1274126177
            return ((n xor (n ushr 16)) and 0xffff) / 65535f
        }

        /** 平滑的 value noise（0～1）。 */
        private fun vn(x: Double, y: Double): Float {
            val ix = kotlin.math.floor(x); val iy = kotlin.math.floor(y)
            var fx = (x - ix).toFloat(); var fy = (y - iy).toFloat()
            fx = fx * fx * (3 - 2 * fx); fy = fy * fy * (3 - 2 * fy)
            val i = ix.toInt(); val j = iy.toInt()
            val a = hash(i, j); val b = hash(i + 1, j); val c = hash(i, j + 1); val d = hash(i + 1, j + 1)
            return (a + (b - a) * fx) + ((c + (d - c) * fx) - (a + (b - a) * fx)) * fy
        }

        private fun b(v: Float) = (v.coerceIn(0f, 1f) * 255f + .5f).toInt().toByte()

        /** 照這一格的參數（[params] 寫的 work）算出 [data]（GL 執行緒呼叫）。 */
        fun fillData(p: FloatArray) {
            val asp = p[0]; val ph1 = p[4]; val ph2 = p[5]; val act = p[6]
            val tx = (p[56] + p[60]).toDouble(); val ty = (p[57] + p[61]).toDouble(); val tz = (p[58] + p[62]).toDouble(); val tw = (p[59] + p[63]).toDouble()
            data.position(0)
            for (row in 0 until DATA_H) {
                val i = row / 2
                val second = row % 2 == 1
                for (cIdx in 0 until DATA_W) {
                    val x = ((cIdx + .5f) / DATA_W - .5f) * asp + i * .42f
                    if (!second) {
                        val bend = .15f * kotlin.math.sin(x * 2.2f + ph1) + (.03f + act * .08f) * kotlin.math.sin(x * 4.8f - ph2 + i)
                        val hem = -.10f - i * .13f + bend + (.012f + act * .028f) * (vn(x * 15.0, tx + i) - .5f)
                        val hv = ((hem + .7f) / 1.0f).coerceIn(0f, .9999f) * 255f
                        val hi = kotlin.math.floor(hv)
                        val dr = (.017f + act * .015f) * vn(x * 8.0, ty + i)
                        val pa = .48f + .8f * vn(x * 8.0 + i, tw)
                        data.put((hi + .5f).toInt().toByte()); data.put(b(hv - hi)); data.put(b(dr / .04f)); data.put(b((pa - .48f) / .8f))
                    } else {
                        val re = 5.5f + vn(x * 11.0, i + 4.0) * 5f - act * 1.5f
                        val drift = x + (.017f + act * .015f) * vn(x * 8.0, ty + i)
                        val grain = vn(drift * 60.0, tz + i * 4)
                        data.put(b((re - 4f) / 6.5f)); data.put(b(grain)); data.put(0); data.put(0)
                    }
                }
            }
            data.position(0)
        }

        const val SHADER = HEAD + """
uniform sampler2D u_data;
vec3 layerCol(float py, float hem, float grain, float pa, float re, float amt, float act) {
  float rise = py - hem;
  float threads = 0.36 + 0.64 * sqrt(grain) + 0.085;
  float veil = smoothstep(-0.012, 0.022, rise) * exp(-max(rise, 0.0) * re);
  float lip = exp(-abs(rise) / (0.012 + grain * 0.013));
  float halo = exp(-abs(rise) * 16.0) * 0.12;
  vec3 tint = mix(u_p[3].rgb, u_p[4].rgb, smoothstep(0.02, 0.38, max(0.0, rise)));
  return amt * pa * (tint * (veil * threads * (0.5 + act * 0.52) + halo) + u_p[5].rgb * lip * threads * (0.55 + act * 0.4));
}
void main() {
  vec2 uv = v_uv;
  vec2 p = vec2((uv.x - 0.5) * u_p[0].x, 0.5 - uv.y);
  float act = u_p[2].z;
  vec3 color = mix(u_p[6].rgb, u_p[7].rgb, 1.0 - uv.y);
  // 三層各讀兩個像素（簾腳、亮斑、延伸、細絲都在 CPU 算好了），不再查雜訊
  for (int i = 0; i < 3; i++) {
    float layer = float(i);
    vec4 a = texture2D(u_data, vec2(v_uv.x, (layer * 2.0 + 0.5) / 6.0));
    vec4 b = texture2D(u_data, vec2(v_uv.x, (layer * 2.0 + 1.5) / 6.0));
    color += layerCol(p.y, a.r + a.g / 255.0 - 0.7, b.g, 0.48 + a.a * 0.8, 4.0 + b.r * 6.5, layer < 0.5 ? 1.0 : (layer < 1.5 ? 0.32 : 0.1024), act);
  }
  color *= 0.88 + 0.12 * pow(max(0.0, 1.0 - length(p) * 0.6), 0.6);
  vec3 col = 1.0 - exp(-color * 1.4);
  """ + DITHER + """
  gl_FragColor = vec4(col, 1.0);
}
"""
    }
}
