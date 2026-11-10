package tw.liuli.launcher.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tw.liuli.launcher.data.AppEntry
import tw.liuli.launcher.ui.theme.Dimens
import tw.liuli.launcher.ui.theme.GText
import tw.liuli.launcher.ui.theme.Ink

/**
 * App 圖塊：16:9 橫幅（沒有橫幅的手機版 App 置中顯示圖示）。
 * 焦點效果全部在繪製／圖層階段完成：放大、白色描邊、斜向光澤、陰影。
 */
@Composable
fun AppTile(
    app: AppEntry,
    width: Dp,
    showLabel: Boolean,
    requester: FocusRequester? = null,
    onFocused: (() -> Unit)? = null,
    onClick: () -> Unit,
    onLongClick: (FocusRequester) -> Unit,
    /** 插在焦點處理之前的修飾（例如 bgTarget）。 */
    extra: Modifier = Modifier,
    /** 出場淡入的透明度（0～1，在繪製階段讀取）。 */
    reveal: () -> Float = { 1f },
) {
    val own = remember { FocusRequester() }
    val req = requester ?: own
    val focus = rememberFocusAnim()
    val tileShape = RoundedCornerShape(Dimens.TileRadius)

    Column(Modifier.width(width), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .focusScale(focus, 1.10f)
                // 焦點陰影：畫在淡入層「外面」（淡入層暫時是一張和磚同大的圖，陰影畫在裡面會被切掉＝破圖），
                // 深淺跟著淡入一起出現
                .graphicsLayer {
                    shadowElevation = 8.dp.toPx() * focus.value() * reveal()
                    shape = tileShape
                    clip = false
                }
                // 出場淡入：在「已經放大過」的層裡、整塊先畫好再整體淡入——
                // 不會透出底下的深色底（每個繪圖各自半透明會像破圖），放大的部分也不會被裁成直角
                .graphicsLayer {
                    val a = reveal()
                    alpha = a
                    compositingStrategy = if (a < .999f) CompositingStrategy.Offscreen else CompositingStrategy.Auto
                }
                .then(extra)
                .focusTarget(focus, req, onFocused, onClick, onLongClick = { onLongClick(req) })
                .clip(tileShape)
                .background(Color(0xFF1B1E26))
                .drawWithContent {
                    drawContent()
                    // tvOS 式焦點：放大＋陰影＋斜向光澤，不畫任何框線
                    val v = focus.value()
                    if (v > 0.01f) {
                        drawRect(
                            Brush.linearGradient(
                                0f to Color.White.copy(alpha = 0.24f * v),
                                0.4f to Color.Transparent,
                                start = Offset.Zero,
                                end = Offset(size.width, size.height),
                            ),
                        )
                    }
                },
        ) {
            val banner = app.banner
            val icon = app.icon
            when {
                banner != null -> Image(banner, app.label, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                icon != null -> Image(icon, app.label, Modifier.align(Alignment.Center).size(width * 0.28f))
            }
            if (!app.isTvApp) {
                GText(
                    "手機版",
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(7.dp)
                        .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(7.dp))
                        .padding(horizontal = 6.dp, vertical = 1.dp),
                    size = 10.sp,
                    color = Ink.Secondary,
                )
            }
        }
        if (showLabel) {
            Spacer(Modifier.height(10.dp))
            GText(
                app.label,
                Modifier.fillMaxWidth().graphicsLayer { alpha = reveal() },
                size = 14.sp,
                weight = if (focus.focused) FontWeight.Medium else FontWeight.Normal,
                color = if (focus.focused) Ink.Primary else Ink.Tertiary,
                align = TextAlign.Center,
            )
        }
    }
}
