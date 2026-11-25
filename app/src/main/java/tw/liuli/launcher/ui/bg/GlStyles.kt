package tw.liuli.launcher.ui.bg

import androidx.compose.ui.graphics.Color

/**
 * 第二批著色器樣式：漩渦（Paper Shaders swirl，Apache-2.0）、漸層百葉（琉璃自己寫）。
 * （墨流 Subframe ink-current 試過拿掉了：它好看在原生解析度的細墨邊，Mali-470 只付得起 480×270，放大後邊界變成一格一格的方塊。）
 * 共同規則見 [GlBackground]：時間在 CPU 端算好取模、低頻的座標變換放在頂點著色器（全精度），片段著色器只用 mediump、
 * 亂數一律查雜訊貼圖（u_noise：最近鄰，抖色用；u_noiseL：線性內插，當作 value noise 用）。
 */
internal object GlStyles {
    private const val TAU = Math.PI * 2

    /**
     * 調色：色相 [dh] 相對主色、飽和度 [sat] 只部分跟著主色的飽和度（0.12～0.62 → ×0.6～1.0，灰色系的 App 也還有顏色）、亮度 [l]。
     */
    fun hsl(st: BgState, dh: Float, sat: Float, l: Float): Color =
        Color.hsl((((st.h + dh) % 360f) + 360f) % 360f, (sat * (.45f + .9f * st.s)).coerceIn(0f, 1f), l.coerceIn(0f, 1f))

    private fun BgState.col(dh: Float, sat: Float, l: Float): Color = hsl(this, dh, sat, l)

    private fun put(out: FloatArray, i: Int, c: Color) {
        out[i] = c.red; out[i + 1] = c.green; out[i + 2] = c.blue; out[i + 3] = 0f
    }

    /** 共用的抖色：±½ 階雜訊（最近鄰貼圖）。 */
    private const val DITHER = GlBackground.TONE + "col += (texture2D(u_noise, gl_FragCoord.xy / 64.0).rgb - 0.5) / 255.0;"

    // ───────────── 漩渦（Paper swirl） ─────────────

    /**
     * 漩渦：幾條色帶繞著中心旋轉、越靠近中心扭得越緊；中心就是焦點。
     *
     * 改寫自 Paper Shaders 的 swirl（Apache License 2.0）。改動：GLSL ES 1.00、參數用 Paper 的預設（4 條色帶、扭轉 0.1、中心 0.2），三個顏色的迴圈展開；
     * 不用 fwidth 做反鋸齒，改成固定的窄柔邊（不用 OES_standard_derivatives）；simplex 雜訊改成查貼圖的 value noise；
     * 時間相位在 CPU 取模；中心跟著焦點，中心半徑設下限避免 pow 溢位。
     */
    object Swirl {
        // 漩渦換位置像水裡的漩渦被吸過去：中心分內外兩個——內圈很快跟上焦點、外圈慢慢拖過去，中間的色帶被拉長；
        // 移動中（內外中心還沒會合）扭得更緊、轉得更快，會合後慢慢鬆回來。轉動的相位逐格累加（轉速會變，不能用時間 × 速度）
        private var inX = Float.NaN; private var inY = 0f
        private var outX = 0f; private var outY = 0f
        private var spin = 0.0
        private var lastAt = Float.NaN

        fun params(st: BgState, out: FloatArray) {
            val dt = if (lastAt.isNaN()) 0f else (st.at - lastAt).coerceIn(0f, .25f)
            lastAt = st.at
            if (inX.isNaN()) { inX = st.fx; inY = st.fy; outX = st.fx; outY = st.fy }
            val kIn = 1f - kotlin.math.exp(-dt / .35f)
            val kOut = 1f - kotlin.math.exp(-dt / 1.6f)
            inX += (st.fx - inX) * kIn; inY += (st.fy - inY) * kIn
            outX += (inX - outX) * kOut; outY += (inY - outY) * kOut
            // 內外中心差多遠（畫面高度為 1 的單位）＝漩渦正在被拖動的程度
            val lag = kotlin.math.sqrt(((inX - outX) * 16f / 9f).let { it * it } + (inY - outY).let { it * it }).coerceAtMost(.6f)
            spin += dt * (.22 + 1.6 * lag)
            val t = spin + st.ripple * .6
            out[0] = ((t / TAU) % 1.0).toFloat()          // 相位 0～1（著色器裡角度除以 2π 之後才加：數字小，半精度才不會讓色帶邊緣鋸齒）
            out[1] = .1f + .06f * st.pulse + .22f * lag   // 扭轉（Paper 預設 0.1）；被拖動時扭得更緊
            out[2] = .2f                                   // 中心大小（Paper 預設 0.2）
            out[3] = .008f                                 // 柔邊下限（Paper 預設 0，用 fwidth 反鋸齒；這裡在著色器裡依像素算）
            out[4] = inX * 16f / 9f * .8f; out[5] = inY * .8f; out[6] = outX * 16f / 9f * .8f; out[7] = outY * .8f
            put(out, 8, st.col(0f, .7f, .09f))     // 底色
            put(out, 12, st.col(4f, .9f, .80f))    // 淡（Paper 預設 #ffd1d1）
            put(out, 16, st.col(0f, .95f, .62f))   // 中（#ff8a8a）
            put(out, 20, st.col(-6f, .9f, .21f))   // 深（#660000）
            // 中心那個洞的邊界：原本是 pow(l, 扭轉) 跟 0.2／0.23／0.36 比，半精度的 pow 誤差讓邊緣起毛；
            // 改成在 CPU 換算成半徑 l 的門檻（l＝門檻^(1／扭轉)），著色器直接拿半徑比
            val tw = 3.0 * out[1]
            out[24] = Math.pow(.2, 1.0 / tw).toFloat(); out[25] = Math.pow(.23, 1.0 / tw).toFloat()
            out[26] = Math.pow(.2 + .8 * out[2], 1.0 / tw).toFloat(); out[27] = 0f
        }

        // v_q：畫面座標（寬高比校正、×0.8，跟 Paper 的尺度一樣）；中心在片段著色器裡依半徑在內外兩個中心之間內插
        const val VERTEX = """
attribute vec2 a_pos;
uniform float u_fy;
varying vec2 v_q;
void main() {
  vec2 uv = a_pos * 0.5 + 0.5;
  v_q = uv * vec2(1.7777778, 1.0) * 0.8;
  gl_Position = vec4(a_pos.x, a_pos.y * u_fy, 0.0, 1.0);
}
"""

        // Paper：shape 加上 0.2 × simplex(2.4·uv) 讓色帶邊緣起伏；這裡用線性內插的雜訊貼圖（value noise，−1～1）代替
        const val SHADER = """
precision mediump float;
uniform sampler2D u_noise;
uniform sampler2D u_noiseL;
uniform vec2 u_res;
uniform vec4 u_p[16];
varying vec2 v_q;
"""+ GlBackground.NOISE + """
void main() {
  vec2 ci = u_p[1].xy;
  float w = smoothstep(0.05, 0.75, length(v_q - ci));
  vec2 v_p = v_q - mix(ci, u_p[1].zw, w);
  float l = max(length(v_p), 0.002);
  float twist = 3.0 * u_p[0].y;
  // 兩項各自先取小數再相加：加起來的數字小（＜2），半精度下的小數才夠準（不然色帶邊緣起毛）
  float shape = fract(fract(pow(l, -twist)) + fract(atan(v_p.y, v_p.x) * 0.63661977 + u_p[0].x));
  shape = 1.0 - abs(2.0 * shape - 1.0);
  shape += 0.2 * (2.0 * gt(v_p * 2.4, vec2(7.0)) - 1.0);
  shape *= smoothstep(u_p[6].x, u_p[6].z, l);
  float mixer = shape * 3.0;
  // 柔邊（代替 fwidth）：mixer 每個繪製像素大約變 4.8·(0.64＋扭轉)／(解析度·半徑)，柔邊取約 0.7 個像素——邊緣銳利但不鋸齒
  float soft = clamp(3.4 * (0.64 + twist) / (u_res.y * l), 0.016, 0.3);
  float m1 = smoothstep(0.5 - soft, 0.5 + soft, clamp(mixer, 0.0, 1.0));
  float m2 = smoothstep(0.5 - soft, 0.5 + soft, clamp(mixer - 1.0, 0.0, 1.0));
  float m3 = smoothstep(0.5 - soft, 0.5 + soft, clamp(mixer - 2.0, 0.0, 1.0));
  vec3 grad = mix(mix(u_p[3].rgb, u_p[4].rgb, m2), u_p[5].rgb, m3);
  float outer = m1 * smoothstep(u_p[6].x, max(u_p[6].y, u_p[6].x + 1.5 * 0.8 / u_res.y), l);
  vec3 col = mix(u_p[2].rgb, grad, outer);
  """ + DITHER + """
  gl_FragColor = vec4(col, 1.0);
}
"""
    }

    // ───────────── 漸層百葉（琉璃自己寫） ─────────────

    /**
     * 漸層百葉（琉璃自己寫，只參考效果的樣子）：黑底上一排斜向的百葉片，被焦點處的光源照亮——
     * 靠近光源的葉片亮、每片從一側邊緣亮起往另一側暗下去，帶一點顆粒。只有焦點移動時才動（不是持續動畫）。
     */
    object Blinds {
        fun params(st: BgState, out: FloatArray) {
            out[0] = st.fx; out[1] = st.fy; out[2] = 16f / 9f; out[3] = st.pulse
            out[4] = 7f                              // 每單位長度幾片
            out[5] = (st.ripple % 8f) * .07f         // 每次換焦點，葉片輕輕滑一點
            out[6] = -0.62f                          // 傾斜角（弧度，約 −35°）
            put(out, 8, st.col(0f, .6f, .025f))      // 底
            put(out, 12, st.col(-12f, .95f, .38f))   // 葉片
            put(out, 16, st.col(14f, .6f, .86f))     // 最亮處
        }

        const val SHADER = """
precision mediump float;
uniform sampler2D u_noise;
uniform vec4 u_p[16];
varying vec2 v_uv;
void main() {
  vec2 p = (v_uv - u_p[0].xy) * vec2(u_p[0].z, 1.0);
  float sa = sin(u_p[1].z);
  float ca = cos(u_p[1].z);
  vec2 r = vec2(ca * p.x - sa * p.y, sa * p.x + ca * p.y);
  float x = r.x * u_p[1].x + u_p[1].y;
  float fx = fract(x);
  float cx = (floor(x) + 0.5 - u_p[1].y) / u_p[1].x;
  float across = exp(-cx * cx * (2.2 - 0.8 * u_p[0].w));
  float along = exp(-r.y * r.y * 1.4);
  float prof = smoothstep(0.0, 0.8, fx) * (1.0 - smoothstep(0.86, 1.0, fx));
  float lum = prof * across * along;
  lum *= 0.8 + 0.4 * texture2D(u_noise, gl_FragCoord.xy / 64.0).g;
  vec3 col = mix(u_p[2].rgb, u_p[3].rgb, clamp(lum * 1.6, 0.0, 1.0));
  col = mix(col, u_p[4].rgb, clamp(lum * lum * 1.3, 0.0, 1.0));
  """ + DITHER + """
  gl_FragColor = vec4(col, 1.0);
}
"""
    }
}
