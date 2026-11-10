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
import kotlinx.coroutines.flow.first
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
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
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tw.liuli.launcher.ui.glass.liquidGlass
import tw.liuli.launcher.ui.theme.GText
import tw.liuli.launcher.ui.theme.Ink
import tw.liuli.launcher.ui.theme.SheetExit
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
    /** 會即時變動的右側數值（在組合時讀取，狀態一變就跟著更新）；有值時取代 [detail]。 */
    val liveDetail: (() -> String)? = null,
    /** 可左右調整的數值列：左鍵 -1、右鍵 +1（按住會連續調整）。 */
    val onAdjust: ((Int) -> Unit)? = null,
    val action: () -> Unit,
)

data class SheetSpec(
    val title: String,
    val subtitle: String? = null,
    val items: List<SheetItem>,
    val focusIndex: Int = 0,
    /** 按返回鍵關閉（沒有選任何一項）時執行，例如還原預覽前的樣式。 */
    val onCancel: (() -> Unit)? = null,
    /** 返回上一層時用來重新開啟這一層（右邊的數值會是最新的）；沒有就沿用原本的內容。 */
    val reopen: (() -> Unit)? = null,
    /** 判斷「是不是同一層」用的名字（標題會變的面板要給，例如「已綁定的按鍵（3）」）。 */
    val key: String? = null,
    /** 暫時的畫面（例如「按一下要改的鍵」的等待畫面）：不列入返回路徑。 */
    val transient: Boolean = false,
    /** 標題與清單之間的自訂內容（例如「關於琉璃」的 QR code）。 */
    val header: (@Composable () -> Unit)? = null,
) {
    val id: String get() = key ?: title
}

/**
 * 從右側滑入的玻璃側邊面板（Google TV／tvOS 的設定慣例）。
 * 焦點鎖在面板內；返回鍵由呼叫端關閉。滑入動畫只動 translationX，不重新排版。
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun GlassSheet(
    spec: SheetSpec,
    /** 收起中：從停靠位置加速滑出，不拿焦點，結束時呼叫 [onExited]。 */
    exiting: Boolean = false,
    onExited: () -> Unit = {},
    /** 焦點停在第幾列（返回上一層時，焦點回到當初進去的那一列）。 */
    onFocusIndex: (Int) -> Unit = {},
) {
    val first = remember { FocusRequester() }
    val slide = remember { Animatable(1f) }
    // 打開後 0.5 秒內整塊面板維持一張離屏圖（滑入、主畫面淡出、背景暫停都結束才回到一般繪製）；收起時整段都是。
    // 收起沿用同一個元件（不重新組合整個清單——弱電視上要 100ms 以上，收起動畫會被吃掉）
    var asLayer by remember { androidx.compose.runtime.mutableStateOf(true) }
    // 先出底框、再逐列出現：滑進來的只有玻璃底框與標題（第一格要畫的東西少，不會頓），
    // 停好之後清單一列一列淡入、微微浮上來——內容出現本身成了效果，而不是「卡了一下才出來」。
    // reveal＝停好後經過的毫秒數。
    val reveal = remember { Animatable(0f) }
    LaunchedEffect(exiting) {
        if (exiting) {
            asLayer = true
            slide.animateTo(1f, SheetExit)
            onExited()
        } else {
            // 底框「幾乎到位」（剩不到 3%）就開始逐列出現，和彈簧最後的收尾重疊，不會有一段空框在等
            launch {
                androidx.compose.runtime.snapshotFlow { slide.value }.first { it < .03f }
                asLayer = false
                reveal.animateTo(REVEAL_TOTAL_MS, tween(REVEAL_TOTAL_MS.toInt(), easing = LinearEasing))
            }
            slide.animateTo(0f, SheetSpring)
        }
    }
    val sheetWidthPx = with(androidx.compose.ui.platform.LocalDensity.current) { 340.dp.toPx() }
    val gapPx = with(androidx.compose.ui.platform.LocalDensity.current) { 40.dp.toPx() }
    val focusAt = spec.focusIndex.coerceIn(0, (spec.items.size - 1).coerceAtLeast(0))
    // 讓目標列一開始就被組合（遠一點的項目才不會因還沒放好而拿不到焦點）
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = focusAt)
    // 延遲並重試幾次：面板比較重時，requestFocus 搶太早會落空（例如從 App 抽屜長按開啟時）
    LaunchedEffect(spec) {
        if (exiting) return@LaunchedEffect
        repeat(3) {
            delay(16)
            runCatching { first.requestFocus() }
        }
    }

    // 滑入中：焦點效果直接到位（整塊面板是一張離屏圖，裡面有東西在動就得每格重畫）
    androidx.compose.runtime.CompositionLocalProvider(tw.liuli.launcher.ui.components.LocalFocusSnap provides { slide.value > 0.001f || asLayer }) {
    Box(
        Modifier
            .fillMaxSize()
            // 收起中的面板不能再被焦點走進去
            .focusProperties { if (exiting) enter = { FocusRequester.Cancel } }
            .focusGroup(),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Column(
            Modifier
                .padding(20.dp)
                .width(340.dp)
                .fillMaxHeight()
                .graphicsLayer {
                    val s = slide.value
                    translationX = s * (size.width + 40.dp.toPx())
                    // 從稍小、稍透明滑進來並長到原大（以右側中央為支點）；整塊是一張離屏圖，縮放不重畫內容
                    val k = s.coerceIn(0f, 1f)
                    scaleX = 1f - .06f * k; scaleY = 1f - .06f * k
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(1f, .5f)
                    alpha = 1f - .5f * k * k
                    // 滑動中：整塊面板畫成一張離屏圖；停下來恢復一般繪製
                    compositingStrategy = if (asLayer || s > 0.001f) CompositingStrategy.Offscreen else CompositingStrategy.Auto
                }
                .liquidGlass(
                    RoundedCornerShape(30.dp), tint = 0.09f,
                    slide = { Offset(slide.value * (sheetWidthPx + gapPx), 0f) },
                    frozen = { asLayer || slide.value > 0.001f },
                )
                .padding(horizontal = 16.dp, vertical = 26.dp)
                .trapDirectionalFocus(),
        ) {
            GText(spec.title, Modifier.padding(horizontal = 12.dp), size = 22.sp, weight = FontWeight.Medium)
            if (spec.subtitle != null) {
                Spacer(Modifier.height(4.dp))
                GText(spec.subtitle, Modifier.padding(horizontal = 12.dp), size = 13.sp, color = Ink.Tertiary, maxLines = 7)
            }
            if (spec.header != null) {
                Spacer(Modifier.height(14.dp))
                spec.header.invoke()
            }
            Spacer(Modifier.height(16.dp))
            LazyColumn(Modifier.weight(1f), state = listState, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                itemsIndexed(spec.items) { index, item ->
                    Box(
                        Modifier.graphicsLayer {
                            // 不開暫存層（ModulateAlpha）：半透明時才不會把超出這一列範圍的東西裁掉
                            compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.ModulateAlpha
                            // 第幾個「看得到的」列決定出場順序（捲動過的清單從畫面上第一列開始算）
                            val order = (index - listState.firstVisibleItemIndex).coerceAtLeast(0)
                            val a = rowReveal(reveal.value, order)
                            alpha = a
                            translationY = (1f - a) * 10.dp.toPx()
                        },
                    ) {
                        SheetRow(item, if (index == focusAt) first else null) { onFocusIndex(index) }
                    }
                }
            }
        }
    }
    }
}

@Composable
private fun SheetRow(item: SheetItem, requester: FocusRequester?, onFocused: () -> Unit) {
    val focus = rememberFocusAnim()
    Row(
        Modifier
            .fillMaxWidth()
            .let { m ->
                val adjust = item.onAdjust ?: return@let m
                m.onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (e.nativeKeyEvent.keyCode) {
                        android.view.KeyEvent.KEYCODE_DPAD_LEFT -> { adjust(-1); true }
                        android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> { adjust(1); true }
                        else -> false
                    }
                }
            }
            // 統一操作：有「‹ 值 ›」的列，確定鍵＝換到下一個值（選單不關）；其他列執行動作
            .focusTarget(
                focus, requester,
                onFocused = { onFocused(); item.onFocus?.invoke() },
                onClick = item.onAdjust?.let { adj -> { adj(1) } } ?: item.action,
            )
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
        val detail = item.liveDetail?.invoke() ?: item.detail
        if (detail != null) {
            Spacer(Modifier.width(10.dp))
            GText(
                if (item.onAdjust != null) "‹  $detail  ›" else detail,
                size = 13.sp,
                color = if (onLight) Ink.OnLight.copy(alpha = 0.6f) else Ink.Tertiary,
            )
        }
    }
}

/** 逐列出現：每列間隔、每列淡入的時間（毫秒）。 */
private const val REVEAL_STAGGER_MS = 28f
private const val REVEAL_FADE_MS = 170f
private const val REVEAL_TOTAL_MS = REVEAL_STAGGER_MS * 14 + REVEAL_FADE_MS

/** 第 [order] 個出場的東西在 [ms]（底框停好後經過的毫秒）時的透明度（0～1，先慢後快再停）。 */
internal fun rowReveal(ms: Float, order: Int, stagger: Float = REVEAL_STAGGER_MS, fade: Float = REVEAL_FADE_MS): Float {
    val x = ((ms - order * stagger) / fade).coerceIn(0f, 1f)
    return x * x * (3f - 2f * x)
}
