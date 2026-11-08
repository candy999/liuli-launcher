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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.zIndex
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
    val value: () -> Float = { anim.value }
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
        val s = 1f + (scale - 1f) * focus.value()
        scaleX = s
        scaleY = s
    }

@Composable
fun rememberFocusAnim(): FocusAnim {
    val anim = remember { Animatable(0f) }
    val focus = remember { FocusAnim(anim) }
    LaunchedEffect(focus.focused) { anim.animateTo(if (focus.focused) 1f else 0f, FocusSpring) }
    return focus
}

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
