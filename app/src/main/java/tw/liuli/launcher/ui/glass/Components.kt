package tw.liuli.launcher.ui.glass

import android.view.KeyEvent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex

/** 液態感的彈簧：略帶回彈，iOS 26 的焦點手感。 */
val LiquidSpring = spring<Float>(dampingRatio = 0.62f, stiffness = 380f)

@Composable
fun GText(
    text: String,
    modifier: Modifier = Modifier,
    size: TextUnit = 16.sp,
    weight: FontWeight = FontWeight.Normal,
    color: Color = Color.White,
    maxLines: Int = 1,
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = TextStyle(color = color, fontSize = size, fontWeight = weight),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * 遙控器友善的點擊：短按確定鍵＝點擊，按住約半秒＝長按。
 * 只有在「這個元件收到按下」時放開才算點擊，避免長按開出選單後，
 * 放開確定鍵又誤觸選單第一項。
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
            detectTapGestures(
                onTap = { onClick() },
                onLongPress = longPress,
            )
        }
        .focusable()
}

/** 可聚焦的元件共用：焦點時彈簧放大並提到最上層。回傳 0～1 的焦點強度給內容使用。 */
@Composable
fun FocusScale(
    modifier: Modifier = Modifier,
    focusedScale: Float = 1.08f,
    focusRequester: FocusRequester? = null,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    content: @Composable (focus: Float) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) focusedScale else 1f, LiquidSpring, label = "scale")
    val glow by animateFloatAsState(
        if (focused) 1f else 0f,
        spring(stiffness = Spring.StiffnessMediumLow),
        label = "glow",
    )
    Box(
        modifier
            .zIndex(if (focused) 1f else 0f)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .let { if (focusRequester != null) it.focusRequester(focusRequester) else it }
            .onFocusChanged { focused = it.isFocused }
            .tvClickable(onClick, onLongClick),
    ) {
        content(glow)
    }
}

/** 膠囊形玻璃按鈕（頂列用）。 */
@Composable
fun GlassPill(
    text: String,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    onClick: () -> Unit,
) {
    FocusScale(modifier, focusedScale = 1.10f, focusRequester = focusRequester, onClick = onClick) { focus ->
        GlassSurface(shape = RoundedCornerShape(50), highlight = focus) {
            GText(
                text,
                Modifier.padding(horizontal = 22.dp, vertical = 10.dp),
                size = 16.sp,
                weight = FontWeight.Medium,
            )
        }
    }
}

data class MenuAction(val label: String, val detail: String? = null, val action: () -> Unit)

/**
 * 置中的玻璃選單。焦點被鎖在選單內，返回鍵由呼叫端用 BackHandler 關閉。
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun GlassMenu(
    title: String,
    subtitle: String? = null,
    actions: List<MenuAction>,
    width: Dp = 440.dp,
) {
    val first = remember { FocusRequester() }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.40f)),
        contentAlignment = Alignment.Center,
    ) {
        GlassSurface(Modifier.width(width), shape = RoundedCornerShape(34.dp), blurRadius = 40.dp) {
            Column(
                Modifier
                    .padding(horizontal = 18.dp, vertical = 22.dp)
                    .focusProperties { exit = { FocusRequester.Cancel } }
                    .focusGroup(),
            ) {
                GText(title, Modifier.padding(horizontal = 14.dp), size = 22.sp, weight = FontWeight.SemiBold)
                if (subtitle != null) {
                    GText(
                        subtitle,
                        Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
                        size = 14.sp,
                        color = Color.White.copy(alpha = 0.6f),
                    )
                }
                Spacer(Modifier.height(14.dp))
                actions.forEachIndexed { index, item ->
                    MenuRow(item, if (index == 0) first else null)
                    Spacer(Modifier.height(6.dp))
                }
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
}

@Composable
private fun MenuRow(item: MenuAction, focusRequester: FocusRequester?) {
    FocusScale(
        Modifier.fillMaxWidth(),
        focusedScale = 1.03f,
        focusRequester = focusRequester,
        onClick = item.action,
    ) { focus ->
        Row(
            Modifier
                .fillMaxWidth()
                .background(Color.White.copy(alpha = 0.22f * focus), RoundedCornerShape(18.dp))
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            GText(item.label, size = 18.sp, weight = if (focus > 0.5f) FontWeight.SemiBold else FontWeight.Normal)
            if (item.detail != null) {
                GText(item.detail, size = 14.sp, color = Color.White.copy(alpha = 0.55f))
            }
        }
    }
}
