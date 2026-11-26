package tw.liuli.launcher.ui.bg

import androidx.compose.ui.graphics.Color

/**
 * 第二批著色器樣式：漩渦（顏料被吸進排水口的流場）、漸層百葉，都是琉璃自己寫的。
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

    // ───────────── 漩渦（顏料被吸進排水口） ─────────────

    /**
     * 漩渦：水裡的排水口。外圍一直有顏料流進來，一邊繞著中心轉（越靠內轉得越快）、一邊被往中心吸，最後在中心被吸掉。
     * 每個像素的顏色＝回推「這團顏料是什麼時候、從哪個角度流進來的」：
     * - 流場：往內吸的面積流量 Q（r² 每秒減少 2Q）、繞轉 ω(r)＝Γ／(r²＋rc²)（中心 rc 以內接近整塊轉，不會無限快）。
     *   從外緣 R 流到半徑 r 要 (R²−r²)／2Q 秒，這段時間轉過 k·ln((R²＋rc²)／(r²＋rc²))（k＝Γ／2Q）。
     * - 流進來的顏料是雙色巧克力：外緣上半邊深、半邊淺，交界帶一點起伏，跟著時間慢慢變；
     *   回推之後就是深淺交錯、粗細不一的雙色螺旋，一直往內流——不會越纏越緊，也不是整片在轉。
     * - 兩種顏色由主色推出（深＝巧克力、淺＝奶油）；中心是暗的眼（顏料被吸掉了）。
     * 移動（換格子）：中心像彈簧一樣追過去（會稍微超過再回來）；外圈慢慢拖過去，中間被拉長；移動中旋臂鬆開、
     * 後方拖出尾巴，抵達後再慢慢捲緊。流動的時間與捲緊程度逐格累加（速度會變，不能用時間 × 速度）。
     * 琉璃自己寫的（以前改寫自 Paper Shaders 的 swirl，太規則、整片在轉，已經整個換掉）。
     */
    object Swirl {
        private var cx = Float.NaN; private var cy = 0f        // 內圈中心（彈簧）
        private var vx = 0f; private var vy = 0f               // 內圈中心的速度
        private var ox = 0f; private var oy = 0f               // 外圈中心（慢慢跟上內圈）
        private var wind = 1f                                  // 捲緊程度 0～1
        private var flow = 0.0                                 // 流動的時間（秒，已乘上速度）
        private var lastAt = Float.NaN

        private const val Q = .02                              // 往內吸的面積流量（畫面高度 0.8 的單位）
        private const val RC2 = .0225                          // 中心 rc＝0.15
        private const val R2 = 3.61                            // 外緣 R＝1.9（整個畫面都在裡面）
        private const val K_MAX = 3.0                          // 完全捲緊時，從外緣到中心大約繞 2.5 圈
        // 外緣顏料的四個波隨時間變化的速度：慢（半徑方向只有兩三圈），主要的形狀是被捲起來的螺旋臂。
        // 以前 0.7～1.1：半徑方向十幾圈，像一圈圈的標靶，半精度的誤差也被放大成毛邊
        private val A = doubleArrayOf(.25, .15, .2, .3)

        fun params(st: BgState, out: FloatArray) {
            // 時鐘只往前走：玻璃小圖用的是稍早的狀態快照，拿它來算不能把時鐘往回撥
            val dt = if (lastAt.isNaN()) 0f else (st.at - lastAt).coerceIn(0f, .25f)
            if (lastAt.isNaN() || st.at > lastAt) lastAt = st.at
            val tx = st.fx * 16f / 9f * .8f; val ty = st.fy * .8f
            if (cx.isNaN()) { cx = tx; cy = ty; ox = tx; oy = ty }
            // 內圈中心：阻尼彈簧（ω＝5、ζ＝0.55，約 0.6 秒到、稍微超過一點再回來）；拆小步才穩
            var left = dt
            while (left > 0f) {
                val h = kotlin.math.min(left, 1f / 120f); left -= h
                val w0 = 5f; val z = .55f
                vx += (w0 * w0 * (tx - cx) - 2f * z * w0 * vx) * h
                vy += (w0 * w0 * (ty - cy) - 2f * z * w0 * vy) * h
                cx += vx * h; cy += vy * h
            }
            // 外圈中心：慢慢跟上（2.5 秒），中間的顏料被拉長
            val kOut = 1f - kotlin.math.exp(-dt / 2.5f)
            ox += (cx - ox) * kOut; oy += (cy - oy) * kOut
            val lag = (kotlin.math.hypot(cx - ox, cy - oy) / .5f).coerceIn(0f, 1f)
            // 捲緊程度：被拖動時很快鬆開（0.4 秒），停下來慢慢捲緊（3 秒）
            val wT = 1f - .7f * lag
            wind += (wT - wind) * (1f - kotlin.math.exp(-dt / if (wT < wind) .4f else 3f))
            // 流動：很慢（以前太快）；焦點脈動時稍微快一點
            flow += dt * (.5 + .25 * st.pulse)
            for (i in 0..3) {
                // 外緣顏料的相位：a·t − a·R²／2Q（著色器再加 a·r²／2Q），在 CPU 取模，數字小、半精度才準
                val ph = A[i] * flow - A[i] * R2 / (2 * Q)
                out[i] = (((ph % TAU) + TAU) % TAU).toFloat()
            }
            out[4] = cx; out[5] = cy; out[6] = ox; out[7] = oy
            put(out, 8, st.col(0f, .7f, .07f))      // 中心的眼（顏料被吸掉了）
            put(out, 12, st.col(-10f, .75f, .16f))  // 深（巧克力）
            put(out, 16, st.col(8f, .7f, .66f))     // 淺（奶油）
            out[24] = (K_MAX * wind).toFloat()       // k：從外緣流到這裡轉了幾弧度 ÷ ln(...)
            out[25] = RC2.toFloat()
            out[26] = (1.0 / (2 * Q)).toFloat()
            out[27] = kotlin.math.ln(R2 + RC2).toFloat()
            // 尾巴：沿移動方向往回取樣（後方的顏料被拉長），最多 0.25
            val sp = kotlin.math.hypot(vx, vy)
            val s = if (sp > 1e-4f) kotlin.math.min(.25f, sp * .2f) / sp else 0f
            out[28] = vx * s; out[29] = vy * s; out[30] = 0f; out[31] = 0f
        }

        // v_q：畫面座標（寬高比校正、×0.8：畫面高度＝0.8 單位）
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

        const val SHADER = """#extension GL_OES_standard_derivatives : enable
precision mediump float;
uniform sampler2D u_noise;
uniform vec2 u_res;
uniform vec4 u_p[16];
varying vec2 v_q;
void main() {
  vec2 ci = u_p[1].xy;
  // 中心依距離在內圈（快）與外圈（慢）兩個中心之間內插：移動時外圈落後，中間被拉長
  float w = smoothstep(0.05, 0.9, length(v_q - ci));
  vec2 d = v_q - mix(ci, u_p[1].zw, w) + u_p[7].xy * w;
  float r2 = dot(d, d);
  // 這團顏料流進來時的角度（回推轉過的角度）與流進來的時間（r²／2Q，乘上各波的速度）
  float ph = atan(d.y, d.x) - u_p[6].x * (u_p[6].w - log(r2 + u_p[6].y));
  float rr = r2 * u_p[6].z;
  // 雙色巧克力：外圍流進來的顏料半邊深、半邊淺（1 倍角），交界帶一點 2 倍角的起伏，捲進去後粗細不一
  float v = sin(ph + u_p[0].x + 0.25 * rr + 0.55 * sin(2.0 * ph - u_p[0].y - 0.15 * rr));
  // 交界：柔和但清楚（約 2 個像素＋一點點奶油般的過渡）；淺色那邊靠近交界稍微亮一點，像奶油的光澤
  float fw = 0.75 * fwidth(v) + 0.03;
  float m = smoothstep(-fw, fw, v);
  vec3 col = mix(u_p[3].rgb, u_p[4].rgb, m);
  col += u_p[4].rgb * 0.08 * m * (1.0 - smoothstep(0.0, 0.5, v));
  // 中心的眼：顏料在這裡被吸掉
  col = mix(u_p[2].rgb, col, smoothstep(0.03, 0.15, sqrt(r2)));
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
