package tw.liuli.launcher.ui.bg

import androidx.compose.ui.graphics.Color

/**
 * 第二批著色器樣式：漩渦（攪拌濃稠的雙色流體）、漸層百葉，都是琉璃自己寫的。
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

    // ───────────── 三色濃醬的動態（畫在 CpuLines.swirl） ─────────────

    /**
     * 漩渦：一層濃稠的三色流體，以攪拌中心為圓心分成九瓣（巧克力、焦糖、奶油依序交錯，每色三瓣，瓣寬帶一點變化），本身不動；
     * 只有攪拌的中心（焦點）會動——中心附近的流體被捲進去（越靠中心轉得越多，最多約半圈）、也稍微被往中心吸，
     * 離中心遠的地方完全不受影響，外緣永遠是原本的樣子。
     * 每個像素回推「這裡的流體原本在哪」：以中心為圓心，倒轉 a(r)＝A·exp(−r²／σ²) 的角度、往外放大 1＋P·exp(−r²／σ²) 倍，
     * 再看那一點在十瓣裡的哪一瓣（外緣是直的扇形，往中心被捲成螺旋）。
     * 移動（換格子）：攪拌中心像阻尼彈簧一樣追過去（稍微超過再回來）；外圈的中心慢慢跟上，中間被拉長；
     * 移動中捲紋鬆開（舊位置慢慢回到原狀），後方拖出一點尾巴；停下來後新位置再慢慢捲緊。停著時捲紋輕輕呼吸。
     * 琉璃自己寫的（以前改寫自 Paper Shaders 的 swirl，太規則、整片在轉，已經整個換掉）。
     */
    object Sauce {
        private var cx = Float.NaN; private var cy = 0f        // 攪拌中心（彈簧）
        private var vx = 0f; private var vy = 0f               // 攪拌中心的速度
        private var ox = 0f; private var oy = 0f               // 外圈的中心（慢慢跟上）
        private var wind = 0f                                  // 捲緊程度 0～1
        private var breath = 0.0                               // 呼吸的相位
        private var lastAt = Float.NaN

        /**
         * 還在動（攪拌中心還沒停穩，或還沒捲緊／鬆完）：CPU 版（不是持續動畫的樣式）靠這個決定引擎要不要繼續畫；
         * 停穩之後引擎停下來、補一張原生解析度，之後不再重畫。
         */
        @Volatile var settling = true
            private set

        // 以下四個值是使用者在試調台（網頁預覽）上調好的
        private const val TURNS = .45                          // 完全捲緊時中心轉幾圈
        private const val SIGMA2 = .09f                        // 影響範圍 σ²（σ＝0.30，畫面高度 0.8 的單位）
        private const val PULL = 1.6f                          // 往中心吸：中心取樣放大 2.6 倍
        /** 一圈幾瓣（三色各三瓣）與瓣寬變化：著色器裡寫死的 9.0、1.5 要跟這裡一致（CPU 版 [CpuLines.swirl] 用這兩個值）。 */
        const val WEDGES = 9
        const val WAVE = 1.5f

        fun params(st: BgState, out: FloatArray) {
            // 時鐘只往前走：玻璃小圖用的是稍早的狀態快照，拿它來算不能把時鐘往回撥
            val d = Loop.diff(st.at, lastAt)
            val dt = d.coerceIn(0f, .25f)
            if (lastAt.isNaN() || d > 0f) lastAt = st.at
            val tx = st.fx * 16f / 9f * .8f; val ty = st.fy * .8f
            if (cx.isNaN()) { cx = tx; cy = ty; ox = tx; oy = ty }
            // 攪拌中心：阻尼彈簧（ω＝5、ζ＝0.55，約 0.6 秒到、稍微超過一點再回來）；拆小步才穩
            var left = dt
            while (left > 0f) {
                val h = kotlin.math.min(left, 1f / 120f); left -= h
                val w0 = 5f; val z = .55f
                vx += (w0 * w0 * (tx - cx) - 2f * z * w0 * vx) * h
                vy += (w0 * w0 * (ty - cy) - 2f * z * w0 * vy) * h
                cx += vx * h; cy += vy * h
            }
            // 外圈的中心：慢慢跟上（2.5 秒），中間被拉長
            val kOut = 1f - kotlin.math.exp(-dt / 2.5f)
            ox += (cx - ox) * kOut; oy += (cy - oy) * kOut
            val lag = (kotlin.math.hypot(cx - ox, cy - oy) / .5f).coerceIn(0f, 1f)
            // 捲緊程度：移動中很快鬆開（0.4 秒），停下來慢慢捲緊（3 秒）
            val wT = 1f - .8f * lag
            wind += (wT - wind) * (1f - kotlin.math.exp(-dt / if (wT < wind) .4f else 3f))
            breath += dt * .7
            val a = (TAU * TURNS * wind * (1 + .06 * kotlin.math.sin(breath) + .15 * st.pulse)).toFloat()
            out[4] = cx; out[5] = cy; out[6] = ox; out[7] = oy
            put(out, 12, st.col(-10f, .75f, .16f))  // 深（巧克力）
            put(out, 16, st.col(8f, .7f, .66f))     // 淺（奶油）
            put(out, 20, st.col(30f, .8f, .40f))    // 中（焦糖：色相往暖偏一點）
            // 頂點著色器只讀得到 u_w（out[0..3]）、u_v1（out[4..7]）、u_vt（out[56..59]）：流場的參數都放在這幾格
            out[0] = a; out[1] = SIGMA2; out[2] = PULL * wind; out[3] = 0f
            // 尾巴：攪拌中心附近沿移動方向往回取樣（後方的流體被拖長），最多 0.2
            val sp = kotlin.math.hypot(vx, vy)
            val s = if (sp > 1e-4f) kotlin.math.min(.2f, sp * .15f) / sp else 0f
            out[56] = vx * s; out[57] = vy * s; out[58] = 0f; out[59] = 0f
            settling = kotlin.math.abs(wT - wind) > .01f || sp > .002f || kotlin.math.hypot(tx - cx, ty - cy) > .002f || lag > .02f
        }
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

        /** 旋轉座標（線性）與沿葉片方向的衰減（平滑）在頂點算，片段只切葉片、算橫向明暗（以前每個像素都算 sin、cos、exp）。 */
        const val VERTEX = """
attribute vec2 a_pos;
uniform float u_fy;
uniform vec4 u_w;
uniform vec4 u_v1;
varying vec2 v_uv;
varying float v_rx;
varying float v_along;
void main() {
  v_uv = a_pos * 0.5 + 0.5;
  vec2 p = (v_uv - u_w.xy) * vec2(u_w.z, 1.0);
  float sa = sin(u_v1.z);
  float ca = cos(u_v1.z);
  vec2 r = vec2(ca * p.x - sa * p.y, sa * p.x + ca * p.y);
  v_rx = r.x;
  v_along = exp(-r.y * r.y * 1.4);
  gl_Position = vec4(a_pos.x, a_pos.y * u_fy, 0.0, 1.0);
}
"""

        const val SHADER = """
precision mediump float;
uniform sampler2D u_noise;
uniform vec4 u_p[16];
varying vec2 v_uv;
varying float v_rx;
varying float v_along;
void main() {
  float x = v_rx * u_p[1].x + u_p[1].y;
  float fx = fract(x);
  float cx = (floor(x) + 0.5 - u_p[1].y) / u_p[1].x;
  float across = exp(-cx * cx * (2.2 - 0.8 * u_p[0].w));
  float along = v_along;
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
