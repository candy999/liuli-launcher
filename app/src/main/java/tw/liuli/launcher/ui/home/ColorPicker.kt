package tw.liuli.launcher.ui.home

import android.view.KeyEvent
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tw.liuli.launcher.ui.glass.liquidGlass
import tw.liuli.launcher.ui.theme.SLIDE_THRESHOLD
import tw.liuli.launcher.ui.theme.GText
import tw.liuli.launcher.ui.theme.Ink
import tw.liuli.launcher.ui.theme.SheetSpring
import kotlin.math.roundToInt

/** 背景鮮豔度的範圍（引擎裡的飽和度；畫背景時會再乘 1.6）。 */
const val BG_SAT_MIN = 0.05f
const val BG_SAT_MAX = 0.62f

/**
 * 背景顏色選擇器：一塊二維色盤——左右是色相（一圈 360°）、上下是鮮豔度（上面最鮮豔、下面接近灰）。
 * 遙控器方向鍵移動游標，背景即時跟著變（背景引擎本身有緩衝，換色是平滑的）；確定鍵完成、返回鍵取消（還原）。
 */
@Suppress("UNUSED_PARAMETER") // onCancel：返回鍵由主畫面的 BackHandler 統一處理（還原顏色）
@Composable
fun ColorPickerPanel(
    hue: Float,
    sat: Float,
    onChange: (hue: Float, sat: Float) -> Unit,
    onDone: () -> Unit,
    onCancel: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    // 收尾門檻要很小（0.05%，不到半個像素）：預設 1% 對 440dp 的抽屜是 9 個像素，彈簧最後會「喀」地跳到終點
    val slide = remember { Animatable(1f, visibilityThreshold = SLIDE_THRESHOLD) }
    LaunchedEffect(Unit) {
        runCatching { focus.requestFocus() }
        slide.animateTo(0f, SheetSpring)
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
        Column(
            Modifier
                .padding(20.dp)
                .width(340.dp)
                .fillMaxHeight()
                .graphicsLayer { translationX = slide.value * (size.width + 40.dp.toPx()) }
                .liquidGlass(RoundedCornerShape(30.dp), tint = 0.09f)
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (e.nativeKeyEvent.keyCode) {
                        KeyEvent.KEYCODE_DPAD_LEFT -> { onChange((hue - HUE_STEP + 360f) % 360f, sat); true }
                        KeyEvent.KEYCODE_DPAD_RIGHT -> { onChange((hue + HUE_STEP) % 360f, sat); true }
                        KeyEvent.KEYCODE_DPAD_UP -> { onChange(hue, (sat + SAT_STEP).coerceAtMost(BG_SAT_MAX)); true }
                        KeyEvent.KEYCODE_DPAD_DOWN -> { onChange(hue, (sat - SAT_STEP).coerceAtLeast(BG_SAT_MIN)); true }
                        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> { onDone(); true }
                        else -> false
                    }
                }
                .focusRequester(focus)
                .focusable()
                .padding(horizontal = 28.dp, vertical = 26.dp),
        ) {
            GText("背景顏色", size = 22.sp, weight = FontWeight.Medium)
            Spacer(Modifier.height(4.dp))
            GText("←→ 色相　↑↓ 鮮豔度\n確定：完成　返回：取消", size = 13.sp, color = Ink.Tertiary, maxLines = 2)
            Spacer(Modifier.height(20.dp))
            // 色盤：橫向一圈色相，越往下越灰；游標是一個白圈
            // 不裁切畫布：游標在色盤邊緣時整圈仍看得到（色盤本身用圓角矩形畫）
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(200.dp),
            ) {
                val stops = (0..12).map { i -> i / 12f to Color.hsl(i * 30f % 360f, .8f, .5f) }.toTypedArray()
                val cr = CornerRadius(16.dp.toPx())
                drawRoundRect(Brush.horizontalGradient(*stops), cornerRadius = cr)
                drawRoundRect(Brush.verticalGradient(0f to Color(0x00777777), 1f to Color(0xFF6E6E6E)), cornerRadius = cr)
                val cx = hue / 360f * size.width
                val cy = (1f - (sat - BG_SAT_MIN) / (BG_SAT_MAX - BG_SAT_MIN)) * size.height
                val r = 13.dp.toPx()
                drawCircle(Color.Black.copy(alpha = .35f), r + 2.dp.toPx(), Offset(cx, cy), style = Stroke(2.dp.toPx()))
                drawCircle(Color.White, r, Offset(cx, cy), style = Stroke(3.dp.toPx()))
            }
            Spacer(Modifier.height(20.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                // 預覽：一張 16:9 的迷你桌面——和背景一樣的暗色漸層＋主色光暈，上面幾個小方塊代表 App
                Canvas(Modifier.size(176.dp, 99.dp)) {
                    val cr = CornerRadius(10.dp.toPx())
                    val sv = (sat * 1.6f).coerceIn(0f, 1f)
                    fun col(dh: Float, s: Float, l: Float, a: Float = 1f) =
                        Color.hsl(((hue + dh) % 360f + 360f) % 360f, (s * sv).coerceIn(0f, 1f), l, a)
                    drawRoundRect(
                        Brush.linearGradient(listOf(col(-8f, .7f, .06f), col(14f, .8f, .16f)), Offset.Zero, Offset(size.width * .4f, size.height)),
                        cornerRadius = cr,
                    )
                    drawRoundRect(
                        Brush.radialGradient(listOf(col(0f, .9f, .5f, .5f), col(0f, .9f, .5f, 0f)), center = Offset(size.width * .3f, size.height * .62f), radius = size.width * .55f),
                        cornerRadius = cr,
                    )
                    // 時鐘一小條＋一排 App 方塊
                    val u = size.width / 32f
                    drawRoundRect(Color.White.copy(alpha = .85f), Offset(u * 2f, u * 2f), androidx.compose.ui.geometry.Size(u * 7f, u * 2.2f), CornerRadius(u))
                    drawRoundRect(Color.White.copy(alpha = .4f), Offset(u * 2f, u * 5f), androidx.compose.ui.geometry.Size(u * 10f, u * .9f), CornerRadius(u * .45f))
                    for (k in 0 until 5) {
                        drawRoundRect(
                            Color.White.copy(alpha = if (k == 0) .95f else .7f),
                            Offset(u * (2f + k * 5.8f), u * 9.5f), androidx.compose.ui.geometry.Size(u * 5f, u * 2.9f), CornerRadius(u * .7f),
                        )
                    }
                }
                Spacer(Modifier.width(16.dp))
                Column {
                    GText("色相 ${hue.roundToInt()}°", size = 17.sp)
                    GText("鮮豔度 ${((sat - BG_SAT_MIN) / (BG_SAT_MAX - BG_SAT_MIN) * 100).roundToInt()}%", size = 13.sp, color = Ink.Tertiary)
                }
            }
        }
    }
}

private const val HUE_STEP = 10f
private const val SAT_STEP = 0.0475f
