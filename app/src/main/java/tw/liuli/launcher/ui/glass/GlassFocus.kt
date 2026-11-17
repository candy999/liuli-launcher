package tw.liuli.launcher.ui.glass

import android.view.KeyEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.channels.Channel
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sqrt

/**
 * 液態玻璃焦點：一列（或一欄）可聚焦的東西共用「一顆」玻璃膠囊，焦點移動時這顆玻璃直接滑過去。
 *
 * 設計（對照 Apple Liquid Glass）：
 * - 一顆玻璃、整顆滑動，不是兩顆水滴、不拖泥帶水（iOS 26 分頁列的選取膠囊）。
 * - 按下的瞬間就動、就亮（instantly flexing and energizing with light）；位置接近臨界阻尼，約 0.25 秒到位、不過衝。
 * - 形狀隨速度順向微微拉長、到位時輕輕回彈一下就安靜（彈性只是點綴）。
 * - 材質：本體幾乎透明，只帶一點點霧與白；靠一條很細、受光角較亮的邊線被看見；受光角順著移動方向轉。
 * - 出現／消失不是單純淡入淡出，而是從稍小長到原大（玻璃「凝結」出來）。
 *
 * 位置與速度以「第幾格」為單位，所以每格寬度不同（HDMI1 與 AV）也能連續內插。
 */
class GlassFocusState internal constructor(val vertical: Boolean) {
    /** 每一格在畫面上的位置（根座標）。 */
    internal val rects = mutableStateMapOf<Int, Rect>()
    internal var hostRoot = Offset.Zero
    internal var hostSampleRoot = Offset.Zero

    /** 每畫一幀遞增：繪製時讀它，動畫只觸發重畫、不重新組合。 */
    internal var frame by mutableLongStateOf(0L)
    internal val wake = Channel<Unit>(Channel.CONFLATED)
    internal var snap: () -> Boolean = { false }

    internal var target = -1
    internal var present = false
    internal var x = 0f; internal var v = 0f
    internal var s = 0f; internal var sv = 0f      // 形狀：順向拉長
    internal var z = 0f; internal var zv = 0f      // 按壓：整顆縮一下再彈回
    internal var n = 0f; internal var nv = 0f      // 左右調整：橫向頂一下（dp）
    internal var lift = 0f; internal var glow = 0f
    internal var presence = 0f

    internal fun kick() { wake.trySend(Unit) }

    internal fun focusTo(i: Int) {
        val wasHere = present && presence > 0.05f && target >= 0
        target = i
        present = true
        glow = 1f
        if (!wasHere || snap()) { x = i.toFloat(); v = 0f; s = 0f; sv = 0f }
        if (snap()) { presence = 1f; glow = 0f; frame++ } else kick()
    }

    internal fun leave() {
        present = false
        if (snap()) { presence = 0f; frame++ } else kick()
    }

    /** 確定鍵：整顆縮一下、亮一下。 */
    fun press() { zv -= 2.4f; glow = 1f; kick() }

    /** 已經在最邊邊還往外按：輕輕頂一下、形狀縮一下。 */
    fun bump(dir: Int) { v += dir * 1.1f; sv -= 1.6f; glow = 1f; kick() }

    /** 左右調整數值（側邊欄的「‹ 值 ›」）：往按的那邊頂一下、亮一下。 */
    fun nudge(dir: Int) { nv += dir * 230f; glow = 1f; kick() }

    internal fun busy(): Boolean {
        val t = target.toFloat()
        return abs(t - x) > 0f || v != 0f || s != 0f || sv != 0f || z != 0f || zv != 0f || n != 0f || nv != 0f ||
            lift > 0f || glow > 0f || presence != (if (present) 1f else 0f)
    }

    internal fun step(dt: Float) {
        if (dt <= 0f) return
        val t = target.toFloat()
        val steps = max(1, ceil(dt / .004f).toInt()); val h = dt / steps
        val c = 2 * sqrt(K) * Z; val jc = 2 * sqrt(JK) * JZ; val zc = 2 * sqrt(ZK) * ZZ; val nc = 2 * sqrt(NK) * NZ
        repeat(steps) {
            val acc = K * (t - x) - c * v
            v += acc * h; x += v * h
            val sT = (A * abs(v) + B * acc * sign(v)).coerceIn(S_MIN, S_MAX)
            sv += (JK * (sT - s) - jc * sv) * h; s += sv * h
            zv += (-ZK * z - zc * zv) * h; z += zv * h
            nv += (-NK * n - nc * nv) * h; n += nv * h
        }
        s = s.coerceIn(-.14f, .30f); z = z.coerceIn(-.12f, .12f)
        val moving = abs(t - x) > .03f || abs(v) > .6f
        lift += ((if (moving) 1f else 0f) - lift) * (1f - exp(-dt * (if (moving) 40f else 16f)))
        glow *= exp(-dt * 5.5f)
        val pT = if (present) 1f else 0f
        presence += (pT - presence) * (1f - exp(-dt * (if (present) 22f else 14f)))
        // 靜止時收乾淨：玻璃跟那一格完全一樣大
        if (abs(t - x) < 4e-4f && abs(v) < 4e-3f) { x = t; v = 0f }
        if (v == 0f && abs(s) < 6e-4f && abs(sv) < 6e-3f) { s = 0f; sv = 0f }
        if (abs(z) < 6e-4f && abs(zv) < 6e-3f) { z = 0f; zv = 0f }
        if (abs(n) < .02f && abs(nv) < .2f) { n = 0f; nv = 0f }
        if (!moving && lift < 2e-3f) lift = 0f
        if (glow < 2e-3f) glow = 0f
        if (abs(pT - presence) < 2e-3f) presence = pT
    }

    /** 第 [f] 格（可以是小數、可以超出兩端一點）的位置：相鄰兩格內插，超出兩端用最邊那兩格的間距外推。 */
    internal fun rectAt(f: Float): Rect? {
        if (rects.isEmpty()) return null
        val i0 = kotlin.math.floor(f).toInt(); val fr = f - i0
        val a = rects[i0]; val b = rects[i0 + 1]
        return when {
            a != null && b != null -> lerp(a, b, fr)
            a != null -> rects[i0 - 1]?.let { p -> lerp(p, a, 1f + fr) } ?: a
            b != null -> rects[i0 + 2]?.let { q -> lerp(b, q, fr - 1f) } ?: b
            else -> rects[target]
        }
    }

    private fun lerp(a: Rect, b: Rect, t: Float) = Rect(
        a.left + (b.left - a.left) * t, a.top + (b.top - a.top) * t,
        a.right + (b.right - a.right) * t, a.bottom + (b.bottom - a.bottom) * t,
    )

    private companion object {
        const val K = 420f; const val Z = .88f          // 位置：接近臨界阻尼，約 0.25 秒到位，過衝不到 1%
        const val A = .020f; const val B = .0009f       // 形狀目標＝速度 × A（拉長）＋沿運動方向的加速度 × B（起步拉、煞車壓）
        const val S_MAX = .26f; const val S_MIN = -.12f
        const val JK = 1100f; const val JZ = .30f       // 形狀彈簧：到位後回彈一下半就停
        const val ZK = 1000f; const val ZZ = .38f       // 按壓
        const val NK = 900f; const val NZ = .42f        // 左右調整的橫向頂一下
    }
}

@Composable
fun rememberGlassFocus(vertical: Boolean = false): GlassFocusState = remember { GlassFocusState(vertical) }

/** 可聚焦的一格：回報自己的位置；拿到焦點時，玻璃滑過來。 */
fun Modifier.glassFocusItem(state: GlassFocusState, index: Int): Modifier = composed {
    DisposableEffect(state, index) { onDispose { state.rects.remove(index) } }
    this
        .onGloballyPositioned {
            val p = it.positionInRoot()
            val r = Rect(p.x, p.y, p.x + it.size.width, p.y + it.size.height)
            if (state.rects[index] != r) state.rects[index] = r
        }
        .onFocusChanged { if (it.isFocused) state.focusTo(index) }
}

/**
 * 放玻璃焦點的容器：在所有格子「後面」畫那一顆玻璃，焦點離開這一組時玻璃收掉。
 * [slide]：容器正在滑入（整塊是離屏圖只移動位置）時的位移，取樣背景時扣掉，以停下來的位置為準。
 * [count]：一共幾格（到最邊邊還往外按時頂一下）。
 */
fun Modifier.glassFocusHost(
    state: GlassFocusState,
    count: () -> Int,
    slide: () -> Offset = { Offset.Zero },
): Modifier = composed {
    val backdrop = LocalGlassBackdrop.current
    val snap = tw.liuli.launcher.ui.components.LocalFocusSnap.current
    state.snap = snap
    val painter = remember(backdrop.image) { GlassFocusPainter(backdrop) }
    LaunchedEffect(state) {
        var last = 0L
        while (true) {
            if (!state.busy()) { last = 0L; state.wake.receive() }
            withFrameNanos { t ->
                val dt = if (last == 0L) 1f / 60f else ((t - last) / 1e9f).coerceIn(0f, .05f)
                last = t
                state.step(dt)
                state.frame++
            }
        }
    }
    this
        .onGloballyPositioned {
            state.hostRoot = it.positionInRoot()
            val p = state.hostRoot - slide()
            state.hostSampleRoot = Offset(p.x.roundToInt().toFloat(), p.y.roundToInt().toFloat())
        }
        .onFocusChanged { if (!it.hasFocus && state.present) state.leave() }
        .onPreviewKeyEvent { e ->
            if (e.type != KeyEventType.KeyDown || state.target < 0) return@onPreviewKeyEvent false
            val code = e.nativeKeyEvent.keyCode
            val back = if (state.vertical) KeyEvent.KEYCODE_DPAD_UP else KeyEvent.KEYCODE_DPAD_LEFT
            val fwd = if (state.vertical) KeyEvent.KEYCODE_DPAD_DOWN else KeyEvent.KEYCODE_DPAD_RIGHT
            if (code == back && state.target == 0) state.bump(-1)
            else if (code == fwd && state.target == count() - 1) state.bump(1)
            false
        }
        .drawBehind {
            @Suppress("UNUSED_VARIABLE") val f = state.frame
            @Suppress("UNUSED_VARIABLE") val ver = backdrop.version()
            if (state.presence <= 0f || state.target < 0) return@drawBehind
            painter.draw(this, state)
        }
}

/** 畫那一顆玻璃（不在繪製時配置物件；只有大小改變時更新邊線漸層的矩陣）。 */
private class GlassFocusPainter(private val backdrop: GlassBackdrop) {
    private val fill = backdrop.image?.let { img ->
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG).apply {
            shader = android.graphics.BitmapShader(
                img.asAndroidBitmap(), android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP,
            )
            // 亮度略提、飽和略增（清玻璃把光聚起來）
            val sat = android.graphics.ColorMatrix().apply { setSaturation(1.12f) }
            val gain = android.graphics.ColorMatrix(floatArrayOf(
                1.06f, 0f, 0f, 0f, 6f,
                0f, 1.06f, 0f, 0f, 6f,
                0f, 0f, 1.06f, 0f, 6f,
                0f, 0f, 0f, 1f, 0f,
            ))
            sat.postConcat(gain)
            colorFilter = android.graphics.ColorMatrixColorFilter(sat)
        }
    }
    private val bmpMatrix = android.graphics.Matrix()
    private val haze = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val fallback = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = 0x66141A2E }
    // 細邊線：受光角（左上）亮、往右下淡、右下角再一點反光。漸層建在 0～1 的單位正方形，用矩陣拉到玻璃大小
    private val rimShader = android.graphics.LinearGradient(
        0f, 0f, 1f, 1f,
        intArrayOf(0xF0FFFFFF.toInt(), 0x30FFFFFF, 0x22FFFFFF, 0x80FFFFFF.toInt()),
        floatArrayOf(0f, .45f, .62f, 1f),
        android.graphics.Shader.TileMode.CLAMP,
    )
    private val rimMatrix = android.graphics.Matrix()
    private val rim = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE
        shader = rimShader
    }
    private val rf = android.graphics.RectF()

    fun draw(scope: DrawScope, st: GlassFocusState) = with(scope) {
        val base = st.rectAt(st.x) ?: return@with
        // 換到容器座標
        val l = base.left - st.hostRoot.x; val t = base.top - st.hostRoot.y
        val w = base.width; val h = base.height
        val sp = (st.v / 9f).coerceIn(-1f, 1f)
        val pres = st.presence
        val scale = (1f + .04f * st.lift) * (1f + st.z) * (.94f + .06f * pres)
        val along = 1f + st.s; val cross = 1f / (1f + .5f * st.s)
        var cx = l + w / 2; var cy = t + h / 2
        val hw: Float; val hh: Float
        if (st.vertical) {
            cy += sp * h * .09f
            cx += st.n * density
            hw = w / 2 * scale * cross; hh = h / 2 * scale * along
        } else {
            cx += sp * w * .09f + st.n * density
            hw = w / 2 * scale * along; hh = h / 2 * scale * cross
        }
        rf.set(cx - hw, cy - hh, cx + hw, cy + hh)
        val r = min(hw, hh)
        val alpha = (pres * 255f).roundToInt().coerceIn(0, 255)
        drawIntoCanvas { cv ->
            val c = cv.nativeCanvas
            // 1. 本體：背後那一塊背景（帶一點霧），亮度略提
            val img = backdrop.image
            if (fill != null && img != null && backdrop.screen.width > 0f) {
                val sx = img.width / backdrop.screen.width; val sy = img.height / backdrop.screen.height
                bmpMatrix.setScale(1f / sx, 1f / sy)
                bmpMatrix.postTranslate(-st.hostSampleRoot.x, -st.hostSampleRoot.y)
                fill.shader.setLocalMatrix(bmpMatrix)
                fill.alpha = alpha
                c.drawRoundRect(rf, r, r, fill)
            } else {
                fallback.alpha = (0x66 * pres).roundToInt()
                c.drawRoundRect(rf, r, r, fallback)
            }
            // 2. 一點點白霧（不是乳白）：遠看也看得出焦點；按下去／移動中再亮一點
            val hz = (.13f + .05f * st.glow + .03f * st.lift) * pres
            haze.color = android.graphics.Color.argb((hz * 255f).roundToInt().coerceIn(0, 255), 255, 255, 255)
            c.drawRoundRect(rf, r, r, haze)
            // 3. 細邊線：受光角順著移動方向轉一點
            val sw = 1.2f * density
            rim.strokeWidth = sw
            val ang = sp * .5f
            rimMatrix.setScale(rf.width(), rf.height())
            rimMatrix.postRotate(ang * 57.3f, rf.width() / 2, rf.height() / 2)
            rimMatrix.postTranslate(rf.left, rf.top)
            rimShader.setLocalMatrix(rimMatrix)
            rim.alpha = ((.62f + .3f * st.glow) * pres * 255f).roundToInt().coerceIn(0, 255)
            rf.inset(sw / 2, sw / 2)
            c.drawRoundRect(rf, r - sw / 2, r - sw / 2, rim)
        }
    }
}

/** 沒選中的格子：沒有玻璃，只有一圈很淡的細線（左上受光角稍亮），讓人知道這裡可以選。 */
fun Modifier.glassHairline(radius: androidx.compose.ui.unit.Dp, visible: () -> Float = { 1f }): Modifier = composed {
    val paint = remember {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { style = android.graphics.Paint.Style.STROKE }
    }
    val shader = remember {
        android.graphics.LinearGradient(
            0f, 0f, 1f, 1f, intArrayOf(0x5CFFFFFF, 0x1CFFFFFF, 0x30FFFFFF), floatArrayOf(0f, .55f, 1f),
            android.graphics.Shader.TileMode.CLAMP,
        )
    }
    val m = remember { android.graphics.Matrix() }
    val rf = remember { android.graphics.RectF() }
    this.drawBehind {
        val vis = visible().coerceIn(0f, 1f)
        if (vis <= 0.01f) return@drawBehind
        paint.alpha = (vis * 255f).roundToInt()
        val sw = 1f * density
        m.setScale(size.width, size.height); shader.setLocalMatrix(m)
        paint.shader = shader; paint.strokeWidth = sw
        rf.set(sw / 2, sw / 2, size.width - sw / 2, size.height - sw / 2)
        val r = min(radius.toPx(), min(size.width, size.height) / 2) - sw / 2
        drawIntoCanvas { it.nativeCanvas.drawRoundRect(rf, r, r, paint) }
    }
}
