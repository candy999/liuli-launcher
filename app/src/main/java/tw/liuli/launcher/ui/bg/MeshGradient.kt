package tw.liuli.launcher.ui.bg

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.math.cos
import kotlin.math.sin

/**
 * 網格漸層：幾團顏色沿著各自的軌跡緩緩流動、以距離反比加權互相暈染，再加一點有機的扭曲與漩渦。
 *
 * 改寫自 Paper Shaders 的 mesh-gradient（https://shaders.paper.design，Apache License 2.0，
 * 授權全文見 assets/licenses/paper-shaders-LICENSE.txt）。改動：
 * - 改成 GLSL ES 1.00（電視只有 OpenGL ES 2.0），迴圈手動展開、只用常數索引
 * - 曾經把扭曲放到頂點著色器（網格內插）省時間：色點之間陡的交界會變成多邊形的折線（鋸齒），而且實測省不到什麼
 *   （瓶頸在讀回，不在算術），所以扭曲仍然逐像素算
 * - 時間相位與色點軌跡改在 CPU 端以雙精度計算、取模後才傳進著色器（Mali-470 只有半精度）
 * - 拿掉顆粒（grain）選項，改用共用的雜訊貼圖做抖色
 * - 色點數固定 5 個、顏色由目前的主色推出（照 Paper 預設的配色結構：一淡、一深、兩個飽和的鄰近色，再加一團深的互補色），
 *   主色那團被焦點輕輕吸過去，焦點脈動時漩渦稍微加強
 */
internal object MeshGradient {
    /** 播放速度（Paper 的 speed）：1 太快、會搶走注意力，背景用很慢的流動。 */
    private const val SPEED = 0.3
    private const val DISTORTION = 0.8f
    private const val SWIRL = 0.12f
    private const val TAU = Math.PI * 2
    /** 第一團（主色）被焦點吸過去的程度。 */
    private const val FOCUS_PULL = 0.35f

    /** u_p[0]＝(相位 t、相位 0.2t、漩渦、扭曲)；u_p[1..3]＝五個色點位置；u_p[4..8]＝五個色點顏色（rgb）。 */
    fun params(st: BgState, out: FloatArray) {
        val t = 0.5 * (st.at * SPEED + 41.5)
        out[0] = (t % TAU).toFloat()
        out[1] = ((0.2 * t) % TAU).toFloat()
        out[2] = SWIRL + 0.25f * st.pulse
        out[3] = DISTORTION
        val pos = positions(st, t)
        for (i in 0 until 10) out[4 + i] = pos[i]
        val cols = colors(st)
        for (i in 0 until N) {
            val c = cols[i]
            out[16 + i * 4] = c.red; out[17 + i * 4] = c.green; out[18 + i * 4] = c.blue; out[19 + i * 4] = 0f
        }
    }

    private const val N = 5

    /** 五個色點的位置（0～1，y 往下）：Paper 的軌跡；第一團被焦點吸過去。 */
    private fun positions(st: BgState, t: Double): FloatArray {
        val p = FloatArray(10)
        for (i in 0 until N) {
            val a = i * .37
            val b = .6 + ((i / 3.0) % 1.0) * .9
            val c = .8 + (((i + 1) / 4.0) % 1.0)
            p[i * 2] = (.5 + .5 * sin(t * b + a)).toFloat()
            p[i * 2 + 1] = (.5 + .5 * cos(t * c + a * 1.5)).toFloat()
        }
        p[0] += (st.fx - p[0]) * FOCUS_PULL
        p[1] += (st.fy - p[1]) * FOCUS_PULL
        return p
    }

    /**
     * 調色盤（照 Paper 預設的結構：淡、深、兩個飽和色）：主色、深色、鄰近色、淡色、深的互補色。
     * 飽和度只部分跟著 App 主色（灰階的 App 也還有顏色，不會整片變灰）。
     */
    private fun colors(st: BgState): Array<Color> = arrayOf(
        GlStyles.hsl(st, 0f, .85f, .52f + .06f * st.pulse),
        GlStyles.hsl(st, 25f, .7f, .13f),
        GlStyles.hsl(st, -45f, .8f, .46f),
        GlStyles.hsl(st, 15f, .55f, .74f),
        GlStyles.hsl(st, 200f, .45f, .16f),
    )

    /** 著色器沒辦法用（GL 失敗）或第一張圖還沒好：CPU 用幾個柔邊的放射漸層近似。 */
    fun DrawScope.fallback(st: BgState, w: Float, h: Float) {
        val t = 0.5 * (st.at * SPEED + 41.5)
        val pos = positions(st, t)
        val cols = colors(st)
        drawRect(cols[4], size = Size(w, h))
        for (i in N - 2 downTo 0) {
            val c = Offset(pos[i * 2] * w, pos[i * 2 + 1] * h)
            drawRect(Brush.radialGradient(listOf(cols[i], cols[i].copy(alpha = 0f)), center = c, radius = w * .42f), size = Size(w, h))
        }
    }

    /**
     * 把 GL 畫好的小圖拉伸到整個邏輯畫面（480×270；雙線性，外面的 DitherScope 會在原生解析度再抖一次色）。
     * 不能用 createShader 傳進來的 size：那是畫布的實際大小（玻璃是 120×68），樣式卻是在縮放過的 480×270 座標裡畫——
     * 以前照 size 拉伸，玻璃取到的是放大的左上角，顏色對不上背景。
     */
    fun brush(bmp: android.graphics.Bitmap): Brush = object : ShaderBrush() {
        override fun createShader(size: Size): android.graphics.Shader {
            val s = android.graphics.BitmapShader(bmp, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP)
            s.setLocalMatrix(android.graphics.Matrix().apply { setScale(BackgroundEngine.LOGICAL_W / bmp.width, BackgroundEngine.LOGICAL_H / bmp.height) })
            return s
        }
    }

    // 色點權重＝1／(距離^3.5＋0.001)（同 Paper）。距離用平方算：pow(d², 1.75)；d² 設下限，避免 pow(0, y)（有些 GPU 回傳 NaN）
    const val SHADER = """
precision mediump float;
uniform sampler2D u_noise;
uniform vec4 u_p[10];
varying vec2 v_uv;

vec2 rot(vec2 v, float a) {
  float s = sin(a);
  float c = cos(a);
  return vec2(c * v.x - s * v.y, s * v.x + c * v.y);
}

float wt(vec2 d) {
  return 1.0 / (pow(max(dot(d, d), 0.0001), 1.75) + 0.001);
}

void main() {
  vec2 uv = v_uv;
  float t = u_p[0].x;
  float t5 = u_p[0].y;
  float dist = u_p[0].w;
  float radius = smoothstep(0.0, 1.0, length(uv - 0.5));
  float center = 1.0 - radius;

  float sy = smoothstep(0.0, 1.0, uv.y);
  uv.x += dist * center * sin(t + 0.4 * sy) * cos(t5 + 2.4 * sy);
  float sx = smoothstep(0.0, 1.0, uv.x);
  uv.y += dist * center * cos(t + 2.0 * sx);
  sy = smoothstep(0.0, 1.0, uv.y);
  uv.x += dist * center * 0.5 * sin(t + 0.8 * sy) * cos(t5 + 4.8 * sy);
  sx = smoothstep(0.0, 1.0, uv.x);
  uv.y += dist * center * 0.5 * cos(t + 4.0 * sx);

  vec2 r = rot(uv - 0.5, -3.0 * u_p[0].z * radius) + 0.5;

  float w0 = wt(r - u_p[1].xy);
  float w1 = wt(r - u_p[1].zw);
  float w2 = wt(r - u_p[2].xy);
  float w3 = wt(r - u_p[2].zw);
  float w4 = wt(r - u_p[3].xy);
  vec3 col = (u_p[4].rgb * w0 + u_p[5].rgb * w1 + u_p[6].rgb * w2 + u_p[7].rgb * w3 + u_p[8].rgb * w4) / (w0 + w1 + w2 + w3 + w4);

  // 抖色：加上 ±½ 階的雜訊再寫入 8 bit，暗色的大面積漸層不會斷成一圈一圈
  col += (texture2D(u_noise, gl_FragCoord.xy / 64.0).rgb - 0.5) / 255.0;
  gl_FragColor = vec4(col, 1.0);
}
"""
}
