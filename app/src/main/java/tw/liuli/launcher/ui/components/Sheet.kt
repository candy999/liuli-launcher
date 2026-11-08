package tw.liuli.launcher.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tw.liuli.launcher.ui.glass.liquidGlass
import tw.liuli.launcher.ui.theme.GText
import tw.liuli.launcher.ui.theme.Ink
import tw.liuli.launcher.ui.theme.SheetSpring

/**
 * 面板裡的一列。
 * [detail] 短字串，顯示在右側當數值（例如「臺北市」「開」）。
 * [subtitle] 較長的說明，顯示在標題下方一行（例如背景樣式的介紹），不會擠壓標題。
 * [selected] 顯示勾選記號。
 */
data class SheetItem(
    val label: String,
    val detail: String? = null,
    val subtitle: String? = null,
    val selected: Boolean = false,
    /** 焦點移到這一列時執行（例如即時預覽背景樣式）。 */
    val onFocus: (() -> Unit)? = null,
    val action: () -> Unit,
)

data class SheetSpec(
    val title: String,
    val subtitle: String? = null,
    val items: List<SheetItem>,
    val focusIndex: Int = 0,
    /** 按返回鍵關閉（沒有選任何一項）時執行，例如還原預覽前的樣式。 */
    val onCancel: (() -> Unit)? = null,
)

/**
 * 從右側滑入的玻璃側邊面板（Google TV／tvOS 的設定慣例）。
 * 焦點鎖在面板內；返回鍵由呼叫端關閉。滑入動畫只動 translationX，不重新排版。
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun GlassSheet(spec: SheetSpec) {
    val first = remember { FocusRequester() }
    val slide = remember { Animatable(1f) }
    LaunchedEffect(Unit) { slide.animateTo(0f, SheetSpring) }
    val focusAt = spec.focusIndex.coerceIn(0, (spec.items.size - 1).coerceAtLeast(0))
    // 讓目標列一開始就被組合（遠一點的項目才不會因還沒放好而拿不到焦點）
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = focusAt)
    // 延遲並重試幾次：面板比較重時，requestFocus 搶太早會落空（例如從 App 抽屜長按開啟時）
    LaunchedEffect(spec) {
        repeat(3) {
            delay(16)
            runCatching { first.requestFocus() }
        }
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
        Column(
            Modifier
                .padding(20.dp)
                .width(340.dp)
                .fillMaxHeight()
                .graphicsLayer {
                    translationX = slide.value * (size.width + 40.dp.toPx())
                }
                .liquidGlass(RoundedCornerShape(30.dp), tint = 0.09f)
                .padding(horizontal = 16.dp, vertical = 26.dp)
                .focusProperties { exit = { FocusRequester.Cancel } }
                .focusGroup(),
        ) {
            GText(spec.title, Modifier.padding(horizontal = 12.dp), size = 22.sp, weight = FontWeight.Medium)
            if (spec.subtitle != null) {
                Spacer(Modifier.height(4.dp))
                GText(spec.subtitle, Modifier.padding(horizontal = 12.dp), size = 13.sp, color = Ink.Tertiary, maxLines = 3)
            }
            Spacer(Modifier.height(16.dp))
            LazyColumn(Modifier.weight(1f), state = listState, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                itemsIndexed(spec.items) { index, item ->
                    SheetRow(item, if (index == focusAt) first else null)
                }
            }
        }
    }
}

@Composable
private fun SheetRow(item: SheetItem, requester: FocusRequester?) {
    val focus = rememberFocusAnim()
    Row(
        Modifier
            .fillMaxWidth()
            .focusTarget(focus, requester, onFocused = item.onFocus, onClick = item.action)
            .drawBehind {
                val v = focus.value()
                if (v > 0.01f) {
                    drawRoundRect(
                        Color.White.copy(alpha = 0.94f * v),
                        cornerRadius = CornerRadius(16.dp.toPx()),
                    )
                }
            }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val onLight = focus.focused
        if (item.selected) {
            Box(
                Modifier
                    .padding(end = 10.dp)
                    .width(6.dp)
                    .height(6.dp)
                    .background(if (onLight) Ink.OnLight else Ink.Gold, RoundedCornerShape(3.dp)),
            )
        }
        Column(Modifier.weight(1f)) {
            GText(
                item.label,
                size = 17.sp,
                weight = if (item.selected) FontWeight.Medium else FontWeight.Normal,
                color = if (onLight) Ink.OnLight else Ink.Primary,
            )
            // 長說明放在標題下方一行，不會擠壓標題
            if (item.subtitle != null) {
                Spacer(Modifier.height(2.dp))
                GText(
                    item.subtitle,
                    size = 12.sp,
                    maxLines = 2,
                    color = if (onLight) Ink.OnLight.copy(alpha = 0.6f) else Ink.Tertiary,
                )
            }
        }
        if (item.detail != null) {
            Spacer(Modifier.width(10.dp))
            GText(
                item.detail,
                size = 13.sp,
                color = if (onLight) Ink.OnLight.copy(alpha = 0.6f) else Ink.Tertiary,
            )
        }
    }
}
