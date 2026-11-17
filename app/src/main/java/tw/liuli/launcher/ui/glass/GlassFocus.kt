package tw.liuli.launcher.ui.glass

import android.view.KeyEvent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sqrt

/**
 * 液態玻璃焦點：一組可聚焦的東西（一列、一欄、一個格子區）共用「一顆」玻璃，焦點移動時這顆玻璃滑過去。
 *
 * 設計（對照 Apple Liquid Glass）：
 * - 一顆玻璃、整顆滑動（iOS 26 分頁列的選取膠囊）；按下的瞬間就動、就亮。
 * - 位置彈簧略欠阻尼：約 0.19 秒就到位（不黏），衝過頭 5% 左右再輕輕彈回，0.4 秒內安靜。
 * - 形狀隨速度順向拉長（起步瞬間被拉開、煞車時被壓扁一下再彈回），像一顆有彈性的膠。
 * - 出現／消失＝從稍小長到原大（玻璃「凝結」出來），不是單純淡入淡出。
 * - 玻璃蓋在內容「上面」（像一片透鏡）：本體只有一點白霧＋一條細邊線，白色的字透過它仍然是白色。
 *
 * 效能（海信 Mali-470 實測）：動畫期間「只改圖層屬性」（位置、縮放、透明度），玻璃本身的繪圖內容不重畫。
 * 上一版每一幀都在容器的 drawBehind 重畫玻璃——Compose 只要有任何內容重畫，整個視窗的顯示清單就重錄一次，
 * HWUI 把整個畫面當成髒區（全螢幕重繪），動畫每秒 60 格都是全螢幕；改成圖層屬性後，系統只需要重畫玻璃經過的那一小塊。
 * 拉長用圖層的非等比縮放做（兩端的圓角跟著微微變形，正好就是膠的樣子）。
 *
 * 座標：每一格記下自己的 LayoutCoordinates，玻璃在繪製階段即時換算「目標那一格相對於玻璃圖層原點」的位置，
 * 所以清單捲動、面板滑入時玻璃都跟著那一格走；彈簧管的是「玻璃相對於目標格的偏移」（像素），歸零就是到位。
 */
class GlassFocusState internal constructor() {
    internal val items = HashMap<Any, LayoutCoordinates>()
    internal var capsule: LayoutCoordinates? = null

    /** 任何一格（或玻璃自己）的位置改變時遞增：玻璃圖層重新換算位置（只更新圖層屬性）。 */
    internal var layoutTick by mutableIntStateOf(0)

    /** 動畫每推進一格遞增：只在圖層屬性的區塊裡讀取。 */
    internal var frame by mutableLongStateOf(0L)

    /** 玻璃圖層的大小＝目標那一格的大小（加上外擴）；只在換目標時改變。 */
    internal var nodeSize by mutableStateOf(IntSize.Zero)
    internal val wake = Channel<Unit>(Channel.CONFLATED)
    internal var snap: () -> Boolean = { false }

    /** 玻璃比那一格大多少：每邊固定外擴（像素）＋依格子大小的比例（App 磚焦點時會放大，外框要包住放大後的磚）。 */
    internal var padX = 0f
    internal var padY = 0f
    internal var grow = 0f

    internal var target: Any? = null
    internal var present = false
    internal var gen = 0
    internal var last: Rect? = null

    internal var ox = 0f; internal var oy = 0f; internal var vx = 0f; internal var vy = 0f   // 相對目標格的偏移與速度（像素）
    internal var dw = 0f; internal var dh = 0f                                                 // 大小差（從上一格的大小變到這一格）
    internal var s = 0f; internal var sv = 0f; internal var axisY = false                      // 形狀：順向拉長
    internal var z = 0f; internal var zv = 0f                                                  // 按壓：整顆縮一下再彈回
    internal var n = 0f; internal var nv = 0f                                                  // 左右調整：橫向頂一下（像素）
    internal var lift = 0f; internal var glow = 0f; internal var presence = 0f

    internal fun kick() { wake.trySend(Unit) }

    /** [key] 那一格在玻璃圖層座標裡的位置（含外擴）。 */
    internal fun rectOf(key: Any?): Rect? {
        val c = capsule ?: return null
        val i = items[key] ?: return null
        if (!c.isAttached || !i.isAttached) return null
        val r = try { c.localBoundingBoxOf(i, false) } catch (e: Exception) { return null }
        val ex = r.width * grow / 2f + padX
        val ey = r.height * grow / 2f + padY
        return Rect(r.left - ex, r.top - ey, r.right + ex, r.bottom + ey)
    }

    internal fun syncSize() {
        val r = rectOf(target) ?: return
        val sz = IntSize(max(1, r.width.roundToInt()), max(1, r.height.roundToInt()))
        if (sz != nodeSize) nodeSize = sz
    }

    internal fun focusTo(key: Any) {
        // 玻璃還看得見就從它現在的位置滑過去。不能看 present：焦點在同一組裡換格時，舊的那一格先收到「失去焦點」、
        // 新的一格才收到「取得焦點」——上一版在這中間把玻璃標成離開，結果每次換格都直接跳過去、完全沒有滑動（海信上錄影確認）
        val wasHere = presence > 0.05f && target != null
        val from = rectOf(target) ?: last
        target = key
        present = true
        gen++
        glow = 1f
        val to = rectOf(key)
        syncSize()
        if (wasHere && !snap() && from != null && to != null) {
            // 接續目前的位置與速度：玻璃從現在所在的地方滑向新的一格
            ox += from.center.x - to.center.x; oy += from.center.y - to.center.y
            dw += from.width - to.width; dh += from.height - to.height
            axisY = abs(oy) > abs(ox)
        } else {
            ox = 0f; oy = 0f; vx = 0f; vy = 0f; dw = 0f; dh = 0f; s = 0f; sv = 0f
        }
        if (to != null) last = to
        if (snap()) { presence = 1f; glow = 0f; lift = 0f; frame++ } else kick()
    }

    /** [key] 那一格失去焦點：如果玻璃還停在它上面（焦點不是換到同組的另一格），玻璃收掉。 */
    internal fun blur(key: Any) {
        if (target != key || !present) return
        present = false
        if (snap()) { presence = 0f; frame++ } else kick()
    }

    /** 確定鍵：整顆縮一下、亮一下。 */
    fun press() { if (snap()) return; zv -= 2.6f; glow = 1f; kick() }

    /** 已經在最邊邊還往外按：往那個方向頂一下、形狀縮一下。 */
    fun bump(dx: Int, dy: Int) {
        if (snap()) return
        val r = last ?: return
        if (dx != 0) { axisY = false; vx += dx * BUMP * r.height } else if (dy != 0) { axisY = true; vy += dy * BUMP * r.height }
        sv -= 1.8f; glow = 1f; kick()
    }

    /** 左右調整數值（側邊欄的「‹ 值 ›」）：往按的那邊頂一下、亮一下。 */
    fun nudge(dir: Int) { if (snap()) return; nv += dir * 520f; glow = 1f; kick() }

    internal fun busy(): Boolean =
        ox != 0f || oy != 0f || vx != 0f || vy != 0f || dw != 0f || dh != 0f || s != 0f || sv != 0f || z != 0f || zv != 0f ||
            n != 0f || nv != 0f || lift > 0f || glow > 0f || presence != (if (present) 1f else 0f)

    internal fun step(dt: Float) {
        if (dt <= 0f) return
        val r = last
        val len = max(1f, if (r == null) 1f else if (axisY) r.height else r.width)
        val steps = max(1, ceil(dt / .004f).toInt()); val h = dt / steps
        val c = 2 * sqrt(K) * Z; val jc = 2 * sqrt(JK) * JZ; val zc = 2 * sqrt(ZK) * ZZ; val nc = 2 * sqrt(NK) * NZ
        repeat(steps) {
            val ax = -K * ox - c * vx; vx += ax * h; ox += vx * h
            val ay = -K * oy - c * vy; vy += ay * h; oy += vy * h
            val v = (if (axisY) vy else vx) / len
            val acc = (if (axisY) ay else ax) / len
            val sT = (A * abs(v) + B * acc * sign(v)).coerceIn(S_MIN, S_MAX)
            sv += (JK * (sT - s) - jc * sv) * h; s += sv * h
            zv += (-ZK * z - zc * zv) * h; z += zv * h
            nv += (-NK * n - nc * nv) * h; n += nv * h
        }
        s = s.coerceIn(-.16f, .32f); z = z.coerceIn(-.14f, .12f)
        val k = exp(-dt * 22f); dw *= k; dh *= k
        val moving = abs(ox) + abs(oy) > .04f * len || abs(vx) + abs(vy) > .7f * len
        lift += ((if (moving) 1f else 0f) - lift) * (1f - exp(-dt * (if (moving) 40f else 14f)))
        glow *= exp(-dt * 4.6f)
        val pT = if (present) 1f else 0f
        presence += (pT - presence) * (1f - exp(-dt * (if (present) 20f else 14f)))
        // 靜止時收乾淨：玻璃跟那一格完全一樣大、對齊整數像素
        if (abs(ox) < .3f && abs(vx) < 3f) { ox = 0f; vx = 0f }
        if (abs(oy) < .3f && abs(vy) < 3f) { oy = 0f; vy = 0f }
        if (abs(dw) < .4f) dw = 0f
        if (abs(dh) < .4f) dh = 0f
        if (vx == 0f && vy == 0f && abs(s) < 1.2e-3f && abs(sv) < 1.2e-2f) { s = 0f; sv = 0f }
        if (abs(z) < 1e-3f && abs(zv) < 1e-2f) { z = 0f; zv = 0f }
        if (abs(n) < .3f && abs(nv) < 3f) { n = 0f; nv = 0f }
        if (!moving && lift < 4e-3f) lift = 0f
        if (glow < 4e-3f) glow = 0f
        if (abs(pT - presence) < 4e-3f) presence = pT
    }

    /** 整顆的縮放（按壓、移動中微微浮起、出現時從稍小長到原大），不含順向拉長。 */
    internal fun baseScale() = (1f + .04f * lift) * (1f + z) * (.86f + .14f * presence)

    private companion object {
        const val K = 300f; const val Z = .70f          // 位置：0.19 秒到位、衝過頭約 5%、0.4 秒內停
        const val A = .030f; const val B = .0010f       // 形狀目標＝速度 × A（拉長）＋沿運動方向的加速度 × B（起步拉、煞車壓）；速度以「幾格／秒」計
        const val S_MAX = .28f; const val S_MIN = -.13f
        const val JK = 1000f; const val JZ = .34f       // 形狀彈簧：到位後回彈一下半就停
        const val ZK = 900f; const val ZZ = .36f        // 按壓
        const val NK = 800f; const val NZ = .40f        // 左右調整的橫向頂一下
        const val BUMP = 4.2f                           // 到底還按：初速＝格子高度 × 這個數（像素／秒）
    }
}

/** 量測用：玻璃動畫的時間倍率（adb 動作 glassslow:0.1＝放慢十倍，方便截到滑動中的畫面）。不存設定。 */
object GlassDebug {
    @Volatile var timeScale = 1f
}

@Composable
fun rememberGlassFocus(): GlassFocusState = remember { GlassFocusState() }

/**
 * 可聚焦的一格：記下自己的位置；拿到焦點時，玻璃滑過來。
 * 要放在這一格「沒有圖層的排版修飾」之前（例如 height、fillMaxWidth 之前）：
 * 這樣量到的是這一格本來的位置，不含它自己焦點放大的縮放。
 */
fun Modifier.glassFocusItem(state: GlassFocusState, key: Any): Modifier = composed {
    val holder = remember { arrayOfNulls<LayoutCoordinates>(1) }
    DisposableEffect(state, key) {
        holder[0]?.let { state.items[key] = it }
        onDispose { if (state.items[key] === holder[0]) state.items.remove(key) }
    }
    this
        .onGloballyPositioned {
            holder[0] = it
            state.items[key] = it
            if (key == state.target) state.syncSize()
            state.layoutTick++
        }
        .onFocusChanged { if (it.isFocused) state.focusTo(key) else state.blur(key) }
}

/**
 * 一組玻璃焦點的容器：已經在最邊邊還往外按（焦點沒有動）時玻璃往那邊頂一下。
 * （焦點離開這一組時玻璃收掉——由每一格自己的「失去焦點」處理，和事件先後順序無關。）
 * 裡面要放一個 [GlassFocusCapsule]（放在內容之後＝蓋在內容上面）。
 */
fun Modifier.glassFocusHost(
    state: GlassFocusState,
    bumpX: () -> Boolean = { false },
    bumpY: () -> Boolean = { false },
): Modifier = composed {
    val scope = rememberCoroutineScope()
    this
        .onPreviewKeyEvent { e ->
            if (e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount == 0 && state.present) {
                var dx = 0; var dy = 0
                when (e.nativeKeyEvent.keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> dx = -1
                    KeyEvent.KEYCODE_DPAD_RIGHT -> dx = 1
                    KeyEvent.KEYCODE_DPAD_UP -> dy = -1
                    KeyEvent.KEYCODE_DPAD_DOWN -> dy = 1
                }
                if ((dx != 0 && bumpX()) || (dy != 0 && bumpY())) {
                    val g = state.gen
                    // 這個按鍵處理完（下一格）焦點還在同一格＝已經到底了
                    scope.launch { withFrameNanos { }; if (state.present && state.gen == g) state.bump(dx, dy) }
                }
            }
            false
        }
}

/** 玻璃的樣子。 */
enum class GlassKind {
    /** 實心：一點白霧＋細邊線（訊號源、側邊欄的列、圓鈕、時鐘與天氣）。 */
    Fill,

    /** 外框：只有一圈玻璃邊（App 磚——圖片不能被白霧蓋住）。 */
    Frame,
}

/**
 * 那一顆玻璃。放在 [glassFocusHost] 容器（Box）裡、內容之後；不佔版面。
 *
 * [corner]：圓角；null＝膠囊（兩端全圓）。[pad]／[grow]：比那一格大多少（固定外擴＋依大小的比例）。
 * [haze]：白霧的濃度。[gate]：整顆再乘上的透明度（內容還在逐個淡入時玻璃跟著淡入）。
 * [watch]：在圖層區塊裡多讀幾個狀態（例如逐列出場的進度）——那些動畫只移動圖層、不會通知位置改變，讀了才會跟著重算。
 */
@Composable
fun BoxScope.GlassFocusCapsule(
    state: GlassFocusState,
    kind: GlassKind = GlassKind.Fill,
    corner: Dp? = null,
    pad: Dp = 0.dp,
    grow: Float = 0f,
    haze: Float = .13f,
    gate: () -> Float = { 1f },
    watch: () -> Unit = {},
) {
    val snap = tw.liuli.launcher.ui.components.LocalFocusSnap.current
    val context = LocalContext.current
    // 系統關閉動畫（無障礙的「移除動畫」）：玻璃直接到位，不滑、不彈
    val reduce = remember {
        runCatching {
            android.provider.Settings.Global.getFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        }.getOrDefault(false)
    }
    state.snap = { reduce || snap() }
    val density = androidx.compose.ui.platform.LocalDensity.current
    with(density) { state.padX = pad.toPx(); state.padY = pad.toPx() }
    state.grow = grow

    LaunchedEffect(state) {
        var last = 0L
        while (true) {
            if (!state.busy()) { last = 0L; state.wake.receive() }
            withFrameNanos { t ->
                val dt = if (last == 0L) 1f / 60f else ((t - last) / 1e9f).coerceIn(0f, .05f)
                last = t
                state.step(dt * GlassDebug.timeScale)
                state.frame++
            }
        }
    }

    val crossK = if (kind == GlassKind.Frame) .22f else .5f
    Box(
        Modifier
            // 這個 onGloballyPositioned 拿到的是下面 layout 那一層（沒有圖層）的座標：玻璃圖層自己的位移不會算進去
            .onGloballyPositioned { state.capsule = it; state.syncSize(); state.layoutTick++ }
            .layout { m, _ ->
                val sz = state.nodeSize
                val p = m.measure(Constraints.fixed(max(1, sz.width), max(1, sz.height)))
                layout(0, 0) { p.place(0, 0) }
            }
            .graphicsLayer {
                @Suppress("UNUSED_VARIABLE") val f = state.frame
                @Suppress("UNUSED_VARIABLE") val lt = state.layoutTick
                watch()
                compositingStrategy = CompositingStrategy.ModulateAlpha
                val a = state.presence * gate().coerceIn(0f, 1f)
                val to = state.rectOf(state.target) ?: state.last
                // 用「目標大小」（狀態）而不是圖層目前的大小：換目標的那一格圖層還沒重新排版，讀狀態才會在大小改變時重算
                val nw = state.nodeSize.width.toFloat(); val nh = state.nodeSize.height.toFloat()
                if (a <= 0.001f || to == null || nw <= 1f) { alpha = 0f; return@graphicsLayer }
                state.last = to
                val len = max(1f, if (state.axisY) to.height else to.width)
                val sp = ((if (state.axisY) state.vy else state.vx) / len / 9f).coerceIn(-1f, 1f)
                val base = state.baseScale()
                val along = 1f + state.s; val cross = 1f / (1f + crossK * state.s)
                var w = to.width + state.dw; var h = to.height + state.dh
                if (abs(w - nw) < 1f) w = nw
                if (abs(h - nh) < 1f) h = nh
                var cx = to.center.x + state.ox + state.n; var cy = to.center.y + state.oy
                if (state.axisY) cy += sp * len * .08f else cx += sp * len * .08f
                scaleX = max(.05f, w / nw * base * (if (state.axisY) cross else along))
                scaleY = max(.05f, h / nh * base * (if (state.axisY) along else cross))
                val tx = cx - nw / 2f; val ty = cy - nh / 2f
                val still = state.ox == 0f && state.oy == 0f && state.n == 0f && state.s == 0f
                translationX = if (still) tx.roundToInt().toFloat() else tx
                translationY = if (still) ty.roundToInt().toFloat() else ty
                alpha = a
            }
            .drawWithCache {
                val sw = 1.25.dp.toPx()
                val padPx = pad.toPx()
                val r = min((corner?.toPx() ?: Float.MAX_VALUE) + (if (kind == GlassKind.Frame) padPx + size.height * grow / 2f else 0f), min(size.width, size.height) / 2f)
                // 細邊線：左上受光角亮、往右下淡、右下角再一點反光
                val rim = Brush.linearGradient(
                    0f to Color.White.copy(alpha = if (kind == GlassKind.Frame) 1f else .92f),
                    .42f to Color.White.copy(alpha = if (kind == GlassKind.Frame) .50f else .22f),
                    .64f to Color.White.copy(alpha = if (kind == GlassKind.Frame) .42f else .16f),
                    1f to Color.White.copy(alpha = if (kind == GlassKind.Frame) .78f else .52f),
                    start = Offset.Zero, end = Offset(size.width, size.height),
                )
                // 本體：一點白霧，上緣稍亮（由上往下的淡光）
                val body = Brush.verticalGradient(
                    0f to Color.White.copy(alpha = haze + .07f),
                    .62f to Color.White.copy(alpha = haze),
                )
                // 外框：磚與邊線之間那一圈薄薄的霧
                val band = max(sw, padPx)
                onDrawBehind {
                    if (kind == GlassKind.Fill) {
                        drawRoundRect(body, cornerRadius = CornerRadius(r))
                    } else {
                        drawRoundRect(
                            Color.White.copy(alpha = haze), topLeft = Offset(band / 2f, band / 2f),
                            size = Size(size.width - band, size.height - band),
                            cornerRadius = CornerRadius(max(0f, r - band / 2f)), style = Stroke(band),
                        )
                    }
                    drawRoundRect(
                        rim, topLeft = Offset(sw / 2f, sw / 2f), size = Size(size.width - sw, size.height - sw),
                        cornerRadius = CornerRadius(max(0f, r - sw / 2f)), style = Stroke(sw),
                    )
                }
            },
    ) {
        // 從裡面亮起來：焦點滑過來、按下的瞬間多一層白光，約半秒退掉（只改這一層的透明度）
        Box(
            Modifier
                .matchParentSize()
                .graphicsLayer {
                    @Suppress("UNUSED_VARIABLE") val f = state.frame
                    compositingStrategy = CompositingStrategy.ModulateAlpha
                    alpha = state.glow.coerceIn(0f, 1f)
                }
                .drawBehind {
                    val padPx = pad.toPx()
                    val r = min((corner?.toPx() ?: Float.MAX_VALUE) + (if (kind == GlassKind.Frame) padPx + size.height * grow / 2f else 0f), min(size.width, size.height) / 2f)
                    if (kind == GlassKind.Fill) {
                        drawRoundRect(Color.White.copy(alpha = .20f), cornerRadius = CornerRadius(r))
                    } else {
                        val band = max(1.25.dp.toPx(), padPx)
                        drawRoundRect(
                            Color.White.copy(alpha = .36f), topLeft = Offset(band / 2f, band / 2f),
                            size = Size(size.width - band, size.height - band),
                            cornerRadius = CornerRadius(max(0f, r - band / 2f)), style = Stroke(band),
                        )
                    }
                },
        )
    }
}

/**
 * 跟著玻璃一起出現的東西（例如時鐘、天氣焦點時背後那片霧化的背景）：
 * 透明度＝玻璃的出現程度，縮放＝玻璃整顆的縮放。只改圖層屬性。
 */
fun Modifier.glassFocusFollow(state: GlassFocusState): Modifier = graphicsLayer {
    @Suppress("UNUSED_VARIABLE") val f = state.frame
    compositingStrategy = CompositingStrategy.ModulateAlpha
    alpha = state.presence
    val s = state.baseScale()
    scaleX = s; scaleY = s
}

/** 沒選中的按鈕：玻璃片外圍一圈很淡的細線（左上受光角稍亮）。靜態的，不隨焦點重畫。 */
fun Modifier.glassHairline(radius: Dp): Modifier = composed {
    val paint = remember {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { style = android.graphics.Paint.Style.STROKE }
    }
    val shader = remember {
        android.graphics.LinearGradient(
            0f, 0f, 1f, 1f, intArrayOf(0x66FFFFFF, 0x1EFFFFFF, 0x38FFFFFF), floatArrayOf(0f, .55f, 1f),
            android.graphics.Shader.TileMode.CLAMP,
        )
    }
    val m = remember { android.graphics.Matrix() }
    val rf = remember { android.graphics.RectF() }
    this.drawBehind {
        val sw = 1f * density
        m.setScale(size.width, size.height); shader.setLocalMatrix(m)
        paint.shader = shader; paint.strokeWidth = sw
        rf.set(sw / 2, sw / 2, size.width - sw / 2, size.height - sw / 2)
        val r = min(radius.toPx(), min(size.width, size.height) / 2) - sw / 2
        drawIntoCanvas { it.nativeCanvas.drawRoundRect(rf, r, r, paint) }
    }
}
