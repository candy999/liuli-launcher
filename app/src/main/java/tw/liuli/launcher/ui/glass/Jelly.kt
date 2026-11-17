package tw.liuli.launcher.ui.glass

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.channels.Channel
import tw.liuli.launcher.ui.components.FocusAnim
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sqrt

/**
 * 最後一次方向鍵的方向、目前有焦點的那一顆按鈕。
 * 主畫面在按鍵一進來時記下方向（[note]）；按鈕拿到焦點時就知道焦點是從哪邊來的。
 */
object JellyInput {
    var dx = 0; private set
    var dy = 0; private set
    private var at = 0L
    var current: JellyState? = null
        internal set
    var gen = 0
        internal set

    fun note(dx: Int, dy: Int) { this.dx = dx; this.dy = dy; at = android.os.SystemClock.uptimeMillis() }
    internal fun recent() = android.os.SystemClock.uptimeMillis() - at < 400
}

/**
 * 布丁按鈕：有焦點的那一顆按鈕「自己」像布丁一樣變形（整顆按鈕的圖層，不是外框）。
 * - 焦點從左邊過來：按鈕被往右帶一下、順向拉長，再彈回來晃兩下。
 * - 已經在最邊邊還往外按：按鈕往那邊撞一下、被壓扁，再彈回來（撞牆感）。
 * - 按確定：整顆縮一下再彈回。
 * 只改圖層屬性（位移、非等比縮放），按鈕的內容不重畫。彈簧沿用玻璃焦點那一套（位移彈簧＋形狀彈簧）。
 */
class JellyState internal constructor(private val gain: Float) {
    internal var frame by mutableLongStateOf(0L)
    internal val wake = Channel<Unit>(Channel.CONFLATED)
    internal var snap: () -> Boolean = { false }
    internal var w = 0f; internal var h = 0f
    internal var refMax = 220f

    internal var o = 0f; internal var v = 0f          // 沿移動方向的位移（像素）
    internal var s = 0f; internal var sv = 0f         // 形狀：順向拉長（負的＝壓扁）
    internal var z = 0f; internal var zv = 0f         // 按壓
    internal var axisY = false

    private fun ref() = max(24f, min(if (axisY) h else w, refMax))

    /** 拿到焦點：依最後一次方向鍵的方向被帶一下。 */
    internal fun arrive() {
        JellyInput.current = this; JellyInput.gen++
        if (snap()) return
        if (JellyInput.recent() && (JellyInput.dx != 0 || JellyInput.dy != 0)) {
            axisY = JellyInput.dy != 0
            val d = if (axisY) JellyInput.dy else JellyInput.dx
            v += d * ARRIVE * ref() * gain
            sv += 2.4f * gain
        } else sv += 1.4f * gain
        wake.trySend(Unit)
    }

    internal fun gone() { if (JellyInput.current === this) JellyInput.current = null }

    /** 已經到底還往外按：撞牆。 */
    fun bump(dx: Int, dy: Int) {
        if (snap()) return
        axisY = dy != 0
        v += (if (axisY) dy else dx) * BUMP * ref() * gain
        sv -= 2.2f * gain
        wake.trySend(Unit)
    }

    fun press() { if (snap()) return; zv -= 2.6f; wake.trySend(Unit) }

    internal fun busy() = o != 0f || v != 0f || s != 0f || sv != 0f || z != 0f || zv != 0f

    internal fun step(dt: Float) {
        if (dt <= 0f) return
        val len = ref()
        val steps = max(1, ceil(dt / .004f).toInt()); val hh = dt / steps
        val c = 2 * sqrt(K) * Z; val jc = 2 * sqrt(JK) * JZ; val zc = 2 * sqrt(ZK) * ZZ
        repeat(steps) {
            val a = -K * o - c * v; v += a * hh; o += v * hh
            val sT = ((A * abs(v) + B * a * sign(v)) / len).coerceIn(S_MIN, S_MAX)
            sv += (JK * (sT - s) - jc * sv) * hh; s += sv * hh
            zv += (-ZK * z - zc * zv) * hh; z += zv * hh
        }
        s = s.coerceIn(-.2f, .3f); z = z.coerceIn(-.14f, .12f)
        // 收尾門檻放寬：尾巴那幾格肉眼看不到，卻每一格都要整個畫面重繪
        if (abs(o) < .5f && abs(v) < 8f) { o = 0f; v = 0f }
        if (v == 0f && abs(s) < 4e-3f && abs(sv) < 5e-2f) { s = 0f; sv = 0f }
        if (abs(z) < 3e-3f && abs(zv) < 4e-2f) { z = 0f; zv = 0f }
    }

    private companion object {
        const val K = 300f; const val Z = .50f           // 位移：被帶出去再彈回，晃一下半
        const val A = .070f; const val B = .0010f
        const val S_MAX = .24f; const val S_MIN = -.16f
        const val JK = 900f; const val JZ = .30f         // 形狀：布丁的晃動
        const val ZK = 900f; const val ZZ = .36f
        const val ARRIVE = 3.0f                          // 焦點過來：初速＝參考長度 × 這個數（像素／秒）
        const val BUMP = 1.9f                            // 撞牆
    }
}

@Composable
fun rememberJelly(gain: Float = 1f): JellyState {
    val j = remember { JellyState(gain) }
    val snap = tw.liuli.launcher.ui.components.LocalFocusSnap.current
    val context = LocalContext.current
    val reduce = remember {
        runCatching {
            android.provider.Settings.Global.getFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        }.getOrDefault(false)
    }
    j.snap = { reduce || snap() }
    j.refMax = with(LocalDensity.current) { 110.dp.toPx() }
    LaunchedEffect(j) {
        var last = 0L
        while (true) {
            if (!j.busy()) { last = 0L; j.wake.receive() }
            withFrameNanos { t ->
                val dt = if (last == 0L) 1f / 60f else ((t - last) / 1e9f).coerceIn(0f, .05f)
                last = t
                j.step(dt * GlassDebug.timeScale)
                j.frame++
            }
        }
    }
    return j
}

/**
 * 焦點時整顆按鈕放大（[scale]）＋布丁變形。取代 focusScale；要放在 focusTarget 之前。
 */
fun Modifier.jellyFocus(j: JellyState, focus: FocusAnim, scale: Float = 1.06f): Modifier = this
    .zIndex(if (focus.focused) 1f else 0f)
    .onFocusChanged { if (it.isFocused) j.arrive() else j.gone() }
    .graphicsLayer {
        @Suppress("UNUSED_VARIABLE") val f = j.frame
        j.w = size.width; j.h = size.height
        val base = (1f + (scale - 1f) * focus.raw()) * (1f + j.z)
        val along = 1f + j.s; val cross = 1f / (1f + .5f * j.s)
        if (j.axisY) { translationY = j.o; scaleX = base * cross; scaleY = base * along }
        else { translationX = j.o; scaleX = base * along; scaleY = base * cross }
    }

/**
 * 焦點時亮起來的那一層（一點白霧＋受光的細邊線）：內容是靜態的，只有這一層的透明度跟著焦點變。
 * 放在按鈕裡面、內容之後（matchParentSize）。[fill]＝false 只有邊線（App 磚：圖片不能被白霧蓋住）。
 */
@Composable
fun GlassLit(focus: FocusAnim, corner: Dp, modifier: Modifier = Modifier, haze: Float = .16f, fill: Boolean = true) {
    Box(
        modifier
            .graphicsLayer {
                compositingStrategy = CompositingStrategy.ModulateAlpha
                alpha = focus.value()
            }
            .drawWithCache {
                val sw = 1.5.dp.toPx()
                val r = min(corner.toPx(), min(size.width, size.height) / 2f)
                val rim = Brush.linearGradient(
                    0f to Color.White.copy(alpha = .95f), .42f to Color.White.copy(alpha = .30f),
                    .64f to Color.White.copy(alpha = .22f), 1f to Color.White.copy(alpha = .62f),
                    start = Offset.Zero, end = Offset(size.width, size.height),
                )
                val body = Brush.verticalGradient(0f to Color.White.copy(alpha = haze + .07f), .62f to Color.White.copy(alpha = haze))
                onDrawBehind {
                    if (fill) drawRoundRect(body, cornerRadius = CornerRadius(r))
                    drawRoundRect(
                        rim, topLeft = Offset(sw / 2f, sw / 2f), size = Size(size.width - sw, size.height - sw),
                        cornerRadius = CornerRadius(max(0f, r - sw / 2f)), style = Stroke(sw),
                    )
                }
            },
    )
}
