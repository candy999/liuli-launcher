package tw.liuli.launcher.ui.components

import android.view.KeyEvent
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.zIndex
import tw.liuli.launcher.ui.theme.FocusOutSpring
import tw.liuli.launcher.ui.theme.FocusSpring

/**
 * 遙控器友善的點擊：短按確定＝點擊，按住約半秒＝長按。
 * 只有「這個元件收到按下」時放開才算點擊，避免長按開出面板後，放開確定鍵又誤觸面板第一項。
 */
fun Modifier.tvClickable(
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
): Modifier = composed {
    var pressed by remember { mutableStateOf(false) }
    var longFired by remember { mutableStateOf(false) }
    this
        .onKeyEvent { event ->
            val code = event.nativeKeyEvent.keyCode
            val isSelect = code == KeyEvent.KEYCODE_DPAD_CENTER ||
                code == KeyEvent.KEYCODE_ENTER ||
                code == KeyEvent.KEYCODE_NUMPAD_ENTER
            if (!isSelect) return@onKeyEvent false
            when (event.type) {
                KeyEventType.KeyDown -> {
                    if (event.nativeKeyEvent.repeatCount == 0) {
                        pressed = true
                        longFired = false
                    } else if (pressed && !longFired && onLongClick != null) {
                        longFired = true
                        onLongClick()
                    }
                    true
                }
                KeyEventType.KeyUp -> {
                    if (pressed && !longFired) onClick()
                    pressed = false
                    longFired = false
                    true
                }
                else -> false
            }
        }
        .pointerInput(onClick, onLongClick) {
            val longPress: ((Offset) -> Unit)? = onLongClick?.let { handler -> { _: Offset -> handler() } }
            detectTapGestures(onTap = { onClick() }, onLongPress = longPress)
        }
        .focusable()
}

/**
 * 焦點狀態：0（未聚焦）～1（聚焦）的彈簧動畫值。
 * 用 [value] 這個 lambda 在 graphicsLayer／draw 階段讀取，動畫期間不會重新組合。
 */
class FocusAnim internal constructor(private val anim: Animatable<Float, *>) {
    var focused by mutableStateOf(false)
        internal set
    /** 0～1（夾住）：給透明度、陰影、顏色用。 */
    val value: () -> Float = { anim.value.coerceIn(0f, 1f) }
    /** 未夾住的值（彈簧回彈時會略超過 1）：只給縮放用，做出彈性。 */
    val raw: () -> Float = { anim.value }
}

/**
 * 可聚焦元件的共用外殼：處理焦點、點擊、長按，並在焦點時彈簧放大、浮到最上層。
 * [content] 拿到 [FocusAnim]，用它的 value 在繪製階段畫焦點效果。
 */
fun Modifier.focusScale(
    focus: FocusAnim,
    scale: Float = 1.08f,
): Modifier = this
    .zIndex(if (focus.focused) 1f else 0f)
    .graphicsLayer {
        val s = 1f + (scale - 1f) * focus.raw()
        scaleX = s
        scaleY = s
    }

@Composable
fun rememberFocusAnim(): FocusAnim {
    val anim = remember { Animatable(0f) }
    val focus = remember { FocusAnim(anim) }
    val snap = LocalFocusSnap.current
    LaunchedEffect(focus.focused) {
        val target = if (focus.focused) 1f else 0f
        if (snap()) anim.snapTo(target) else anim.animateTo(target, if (focus.focused) FocusSpring else FocusOutSpring)
    }
    return focus
}

/**
 * 回傳 true 時，焦點效果直接跳到最終狀態、不播動畫。
 * 面板／抽屜滑入時由它們提供：滑動中整塊畫成一張離屏圖只移動位置，裡面任何一個元件在動（例如第一格的焦點彈簧），
 * 整張圖每格都要重畫（海信實測：抽屜滑動每格 GPU 21ms → 不動時 1ms）。焦點仍然立刻就位，只是不播放大動畫。
 */
val LocalFocusSnap = androidx.compose.runtime.staticCompositionLocalOf<() -> Boolean> { { false } }

/** 把焦點追蹤、點擊與（選用的）FocusRequester 接到元件上。 */
fun Modifier.focusTarget(
    focus: FocusAnim,
    requester: FocusRequester? = null,
    onFocused: (() -> Unit)? = null,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
): Modifier = this
    .let { if (requester != null) it.focusRequester(requester) else it }
    .onFocusChanged {
        if (focus.focused != it.isFocused) {
            focus.focused = it.isFocused
            if (it.isFocused) onFocused?.invoke()
        }
    }
    .tvClickable(onClick, onLongClick)

/**
 * 一列（常用列、訊號源列）：左右鍵不會離開這一列，到底就停住；上下鍵照常移到別列。
 * [enterTo]：焦點從別列進來時要落在哪一個（例如上次停留的 App）。
 * 不使用 Compose 的 focusRestorer——它會多一個本身可以拿焦點的隱形節點，焦點落在那裡就左右都動不了。
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
fun Modifier.focusRow(enterTo: (() -> FocusRequester?)? = null): Modifier = this
    .focusProperties {
        exit = { dir ->
            if (dir == androidx.compose.ui.focus.FocusDirection.Left || dir == androidx.compose.ui.focus.FocusDirection.Right) {
                FocusRequester.Cancel
            } else {
                FocusRequester.Default
            }
        }
        if (enterTo != null) enter = { enterTo() ?: FocusRequester.Default }
    }
    .focusGroup()

/**
 * 鎖住方向鍵：上下左右（含 Tab 的前後）都不會把焦點帶出這個群組。
 * 只擋「方向移動」，不擋程式呼叫 requestFocus——Compose 1.7 的 requestFocus 也會經過 exit，
 * 若一律回傳 Cancel，開在上面的選單就永遠搶不到焦點（之前抽屜長按選單的 bug）。
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
fun Modifier.trapDirectionalFocus(): Modifier = this
    .focusProperties {
        exit = { dir ->
            when (dir) {
                androidx.compose.ui.focus.FocusDirection.Left,
                androidx.compose.ui.focus.FocusDirection.Right,
                androidx.compose.ui.focus.FocusDirection.Up,
                androidx.compose.ui.focus.FocusDirection.Down,
                androidx.compose.ui.focus.FocusDirection.Next,
                androidx.compose.ui.focus.FocusDirection.Previous -> FocusRequester.Cancel
                else -> FocusRequester.Default
            }
        }
    }
    .focusGroup()
