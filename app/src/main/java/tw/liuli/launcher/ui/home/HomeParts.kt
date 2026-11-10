package tw.liuli.launcher.ui.home

import android.media.tv.TvInputInfo
import android.view.KeyEvent
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import tw.liuli.launcher.ui.theme.Dimens
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tw.liuli.launcher.context.Festivals
import tw.liuli.launcher.context.HomeContext
import tw.liuli.launcher.context.tomorrowHint
import tw.liuli.launcher.context.LunarCalc
import tw.liuli.launcher.data.AppEntry
import tw.liuli.launcher.data.TvSource
import tw.liuli.launcher.ui.bg.BgColors
import tw.liuli.launcher.ui.bg.LocalBgLink
import tw.liuli.launcher.ui.bg.bgTarget
import tw.liuli.launcher.ui.components.AppTile
import tw.liuli.launcher.ui.components.trapDirectionalFocus
import tw.liuli.launcher.ui.components.focusRow
import tw.liuli.launcher.ui.components.focusScale
import tw.liuli.launcher.ui.components.focusTarget
import tw.liuli.launcher.ui.components.rememberFocusAnim
import tw.liuli.launcher.ui.glass.liquidGlass
import tw.liuli.launcher.ui.theme.GText
import tw.liuli.launcher.ui.theme.Ink
import tw.liuli.launcher.ui.theme.LIcon
import tw.liuli.launcher.ui.theme.LIcons
import tw.liuli.launcher.ui.theme.SheetExit
import tw.liuli.launcher.ui.theme.SheetSpring
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

private val zh = Locale.TAIWAN

/** 每分鐘整點更新一次的時間（不顯示秒，不必每秒重組）。 */
@Composable
fun rememberMinuteClock(): LocalDateTime {
    val now by produceState(LocalDateTime.now()) {
        while (true) {
            value = LocalDateTime.now()
            delay(60_000L - System.currentTimeMillis() % 60_000L + 20)
        }
    }
    return now
}

/**
 * 平常是桌布上的純文字；取得焦點時，背後浮出玻璃膠囊（玻璃只在互動時出現）。
 */
@Composable
fun GlassOnFocus(
    requester: FocusRequester?,
    color: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(28.dp),
    content: @Composable () -> Unit,
) {
    val focus = rememberFocusAnim()
    Box(
        modifier
            .focusScale(focus, 1.03f)
            .bgTarget(color)
            .focusTarget(focus, requester, onClick = onClick),
    ) {
        Box(
            Modifier
                .matchParentSize()
                .graphicsLayer {
                    // 淡入不開離屏緩衝（弱 GPU 上每次焦點動畫都多一張圖）
                    compositingStrategy = CompositingStrategy.ModulateAlpha
                    val v = focus.value()
                    alpha = v
                    scaleX = 0.94f + 0.06f * v
                    scaleY = 0.94f + 0.06f * v
                }
                .liquidGlass(shape, tint = 0.10f, highlight = focus.value),
        )
        content()
    }
}

/** 左上：時間＋日期＋農曆＋提示（明天初一…）＋宜忌。按確定開月曆。 */
@Composable
fun TodayGroup(home: HomeContext, requester: FocusRequester, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val now = rememberMinuteClock()
    val is24h = android.text.format.DateFormat.is24HourFormat(context)
    val time = now.format(DateTimeFormatter.ofPattern(if (is24h) "HH:mm" else "h:mm", zh))
    val ampm = if (is24h) null else if (now.hour < 12) "上午" else "下午"
    val l = home.lunar
    // 「明天」：農曆以子時換日，所以 23:00 以後的「明天」是再下一個農曆日
    val lunarToday = LunarCalc.lunarDateOf(home.now)
    val tomorrow = remember(lunarToday) {
        val info = LunarCalc.of(lunarToday.plusDays(1))
        info to Festivals.of(home.now.toLocalDate().plusDays(1), info)
    }
    val tag = home.highlight ?: run {
        val (tl, tf) = tomorrow
        when {
            tf.isNotEmpty() -> "明天" + tf.first().name
            tl.isBaiBai -> "明天" + tl.dayText
            else -> null
        }
    }

    GlassOnFocus(requester, BgColors.CLOCK, onClick, modifier) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                GText(time, size = 92.sp, weight = FontWeight.ExtraLight, shadow = true, letterSpacing = (-2).sp)
                if (ampm != null) GText(ampm, Modifier.padding(start = 8.dp, bottom = 16.dp), size = 20.sp, weight = FontWeight.Light, shadow = true)
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                GText(now.format(DateTimeFormatter.ofPattern("M月d日 EEEE", zh)), size = 18.sp, color = Ink.Secondary, shadow = true)
                Box(Modifier.padding(horizontal = 12.dp).size(3.dp).background(Ink.Tertiary, CircleShape))
                GText("農曆" + l.monthText + l.dayText + "・" + LunarCalc.shiChen(now.hour), size = 18.sp, color = Ink.Secondary, shadow = true)
                if (tag != null) {
                    Spacer(Modifier.width(12.dp))
                    GText(
                        tag,
                        Modifier.background(Ink.GoldSoft, RoundedCornerShape(11.dp)).padding(horizontal = 11.dp, vertical = 2.dp),
                        size = 14.sp,
                        color = Ink.Gold,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Row {
                GText("宜", size = 13.sp, color = Ink.Faint)
                Spacer(Modifier.width(6.dp))
                GText(l.yi.take(3).joinToString(" ").ifBlank { "—" }, size = 13.sp, color = Ink.Tertiary)
                Spacer(Modifier.width(18.dp))
                GText("忌", size = 13.sp, color = Ink.Faint)
                Spacer(Modifier.width(6.dp))
                GText(l.ji.take(3).joinToString(" ").ifBlank { "—" }, size = 13.sp, color = Ink.Tertiary)
            }
        }
    }
}

/** 右上：天氣，直接放在桌布上。按確定開天氣頁。 */
@Composable
fun WeatherGroup(home: HomeContext, requester: FocusRequester, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val w = home.weather
    GlassOnFocus(requester, BgColors.WEATHER, onClick, modifier) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp), horizontalAlignment = Alignment.End) {
            if (w == null) {
                GText(if (home.online) "天氣載入中…" else "目前離線", size = 18.sp, color = Ink.Secondary, shadow = true)
                GText(home.city.name, size = 14.sp, color = Ink.Tertiary)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LIcon(LIcons.weather(w.kind, w.isDay), 34.dp)
                    Spacer(Modifier.width(12.dp))
                    GText("${w.temp.roundToInt()}°", size = 52.sp, weight = FontWeight.ExtraLight, shadow = true)
                }
                // 層級：大字溫度 → 主要行（天氣＋今天高低溫）→ 次要行（地點＋降雨）→ 明天
                // 明天值得注意時用金色小標籤（和左邊「今日初一・拜拜」同一種語言），否則只是最淡的一行
                Spacer(Modifier.height(2.dp))
                val t = w.today
                GText(
                    if (t != null) "${w.kind.label}　${t.max.roundToInt()}° / ${t.min.roundToInt()}°" else w.kind.label,
                    size = 17.sp, color = Ink.Secondary, shadow = true,
                )
                GText(
                    home.city.name + (if (t != null) "・降雨 ${t.rainChance}%" else ""),
                    size = 13.sp, color = Ink.Tertiary,
                )
                w.tomorrowHint()?.let { hint ->
                    Spacer(Modifier.height(8.dp))
                    if (hint.notable) {
                        GText(
                            hint.text,
                            Modifier.background(Ink.GoldSoft, RoundedCornerShape(11.dp)).padding(horizontal = 11.dp, vertical = 2.dp),
                            size = 13.sp, color = Ink.Gold,
                        )
                    } else {
                        GText(hint.text, size = 13.sp, color = Ink.Faint)
                    }
                }
            }
        }
    }
}

/** 設定按鈕：平常是一個圖示，焦點時變成亮白圓鈕。 */
@Composable
fun GearButton(requester: FocusRequester, onClick: () -> Unit, modifier: Modifier = Modifier) {
    RoundIconButton(LIcons.Settings, BgColors.GEAR, requester, onClick, modifier)
}

/** 右上角圓形圖示按鈕：平常是圖示，焦點時變亮白圓鈕。 */
@Composable
fun RoundIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: Int,
    requester: FocusRequester?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Color.White.copy(alpha = 0.85f),
) {
    val focus = rememberFocusAnim()
    Box(
        modifier
            .size(44.dp)
            .focusScale(focus, 1.15f)
            .bgTarget(color)
            .focusTarget(focus, requester, onClick = onClick)
            .drawBehind {
                val v = focus.value()
                if (v > 0.01f) drawCircle(Color.White.copy(alpha = 0.95f * v))
            },
        contentAlignment = Alignment.Center,
    ) {
        LIcon(icon, 22.dp, if (focus.focused) Ink.OnLight else tint)
    }
}

/**
 * 常用列是空的時候的佔位磚：和 App 磚一樣大、一樣的焦點效果。
 * 確定（或下鍵）打開所有 App；在所有 App 裡長按就能加入常用。
 */
@Composable
fun AddToDockTile(requester: FocusRequester, onFocused: () -> Unit, onClick: () -> Unit) {
    val focus = rememberFocusAnim()
    Box(
        Modifier
            .width(150.dp)
            .aspectRatio(16f / 9f)
            .focusScale(focus, 1.10f)
            .focusTarget(focus, requester, onFocused = onFocused, onClick = onClick)
            .drawBehind {
                val v = focus.value()
                drawRoundRect(
                    Color.White.copy(alpha = 0.10f + 0.84f * v),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(Dimens.TileRadius.toPx()),
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            GText("＋", size = 26.sp, weight = FontWeight.Light, color = if (focus.focused) Ink.OnLight else Ink.Primary)
            GText("加入常用", size = 13.sp, color = if (focus.focused) Ink.OnLight else Ink.Secondary)
        }
    }
}

/** 網路狀態按鈕：顯示無線／有線／斷線，按下開啟網路設定。 */
@Composable
fun NetworkButton(status: tw.liuli.launcher.context.NetworkStatus, requester: FocusRequester?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val icon = when (status) {
        tw.liuli.launcher.context.NetworkStatus.WIFI -> LIcons.Wifi
        tw.liuli.launcher.context.NetworkStatus.ETHERNET -> LIcons.Ethernet
        tw.liuli.launcher.context.NetworkStatus.NONE -> LIcons.NoNet
    }
    val tint = if (status == tw.liuli.launcher.context.NetworkStatus.NONE) Ink.Tertiary else Color.White.copy(alpha = 0.85f)
    RoundIconButton(icon, BgColors.GEAR, requester, onClick, modifier, tint)
}

/** 訊號源一列玻璃膠囊（電視機才有）。 */
@Composable
fun InputsRow(
    sources: List<TvSource>,
    firstRequester: FocusRequester,
    onSwitch: (TvSource) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier.focusRow(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        GText("訊號源", Modifier.padding(end = 4.dp), size = 13.sp, color = Ink.Tertiary, shadow = true)
        sources.forEachIndexed { i, src ->
            InputChip(src, if (i == 0) firstRequester else null) { onSwitch(src) }
        }
    }
}

@Composable
private fun InputChip(src: TvSource, requester: FocusRequester?, onClick: () -> Unit) {
    val focus = rememberFocusAnim()
    val color = when (src.type) {
        TvInputInfo.TYPE_TUNER -> BgColors.TV
        TvInputInfo.TYPE_HDMI -> BgColors.HDMI
        else -> BgColors.OTHER_INPUT
    }
    val dark = focus.focused
    Row(
        Modifier
            .height(40.dp)
            .focusScale(focus, 1.1f)
            .bgTarget(color)
            .focusTarget(focus, requester, onClick = onClick)
            .liquidGlass(RoundedCornerShape(20.dp), tint = 0.08f)
            .drawBehind {
                val v = focus.value()
                if (v > 0.01f) drawRoundRect(Color.White.copy(alpha = 0.95f * v), cornerRadius = CornerRadius(20.dp.toPx()))
            }
            .padding(start = 12.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LIcon(if (src.type == TvInputInfo.TYPE_TUNER) LIcons.Input else LIcons.Hdmi, 20.dp, if (dark) Ink.OnLight else Color.White)
        Spacer(Modifier.width(8.dp))
        GText(src.label, size = 15.sp, color = if (dark) Ink.OnLight else Ink.Primary)
        // 名稱已經包含類型（HDMI1、AV）時不再重複顯示
        if (!src.label.replace(" ", "").contains(src.typeTag, ignoreCase = true)) {
            Spacer(Modifier.width(6.dp))
            GText(src.typeTag, size = 12.sp, color = if (dark) Ink.OnLight.copy(alpha = 0.55f) else Ink.Tertiary)
        }
    }
}

/**
 * 所有 App 抽屜：從下方滑上來的玻璃面板。第一列按上鍵、或按返回鍵收合。
 *
 * 預先建好：主畫面穩定後就組合好（28 個 App 格子），[open] 為 false 時只量尺寸、不擺放——不畫、拿不到焦點。
 * 打開時只剩滑入動畫；以前每次打開才組合，海信上第一格要 110ms，滑出來會頓一下。
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun AppDrawer(
    open: Boolean,
    apps: List<AppEntry>,
    onClose: () -> Unit,
    onLaunch: (AppEntry) -> Unit,
    onLongClick: (AppEntry, FocusRequester) -> Unit,
) {
    val slide = remember { Animatable(1f) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val drawerHeightPx = with(density) { 440.dp.toPx() }
    // 往下多延伸一段（平常在螢幕外）：滑入的彈簧會衝過頭一點，底邊抬起時露出的仍是抽屜，不是一條縫
    val overshootPx = with(density) { DRAWER_OVERSHOOT.toPx() }
    // 告訴背景：抽屜蓋住的部分不用畫。
    // 「是否打開」要在組合時就更新（rememberUpdatedState），抽屜消失的同一格背景就畫滿；
    // 只看 slide 的話，slide 要等到下一格的 LaunchedEffect 才歸位，中間那一兩格被蓋住的位置沒人畫＝露出黑色。
    val openNow = androidx.compose.runtime.rememberUpdatedState(open)
    val bgEngine = LocalBgLink.current?.engine
    androidx.compose.runtime.DisposableEffect(bgEngine) {
        bgEngine?.cover = tw.liuli.launcher.ui.bg.PanelCover(
            height = drawerHeightPx,
            inset = with(density) { 20.dp.toPx() },
            corner = with(density) { 34.dp.toPx() },
            // 只有抽屜完全停好時才跳過被蓋住的背景：彈出中抽屜會縮放（比原位窄），兩側若不畫背景會露出黑邊
            slide = { if (openNow.value && kotlin.math.abs(slide.value) < .002f) 0f else 1f },
        )
        onDispose { bgEngine?.cover = null }
    }
    val first = remember { FocusRequester() }
    val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    var focused by remember { mutableIntStateOf(0) }
    // 打開後 0.5 秒內（滑入＋主畫面淡出＋背景暫停都還在進行）整塊抽屜維持一張離屏圖，之後才回到一般繪製
    var asLayer by remember { mutableStateOf(false) }
    // 是否擺放（看得到）：打開時立刻擺放；收起時等滑出動畫結束才拿掉
    var shown by remember { mutableStateOf(false) }
    // 先出底框、再逐排出現：滑上來的只有玻璃底框與標題列，停好後 App 一排一排淡入浮上
    val reveal = remember { Animatable(0f) }
    LaunchedEffect(open) {
        if (open) {
            shown = true
            asLayer = true
            reveal.snapTo(0f)
            focused = 0
            runCatching { gridState.scrollToItem(0) }
            delay(16)
            runCatching { first.requestFocus() }
            slide.animateTo(0f, SheetSpring)
            asLayer = false
            reveal.animateTo(DRAWER_REVEAL_MS, androidx.compose.animation.core.tween(DRAWER_REVEAL_MS.toInt(), easing = androidx.compose.animation.core.LinearEasing))
        } else if (shown) {
            // 收起：背景與主畫面已經先畫好（背景不再裁掉被蓋住的部分），抽屜整塊當一張圖加速滑出
            asLayer = true
            slide.animateTo(1f, SheetExit)
            asLayer = false
            shown = false
        }
    }

    // 滑入中：焦點效果直接到位（整塊抽屜是一張離屏圖，第一格的焦點彈簧會讓整張圖每格重畫）
    androidx.compose.runtime.CompositionLocalProvider(tw.liuli.launcher.ui.components.LocalFocusSnap provides { slide.value > 0.001f || asLayer }) {
    Box(
        Modifier
            .fillMaxSize()
            .layout { measurable, constraints ->
                val p = measurable.measure(constraints)
                layout(p.width, p.height) { if (open || shown) p.place(0, 0) }
            }
            // 收起中的抽屜不能再被焦點走進去
            .focusProperties { if (!open) enter = { FocusRequester.Cancel } }
            .focusGroup(),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            Modifier
                .padding(horizontal = 20.dp)
                .fillMaxWidth()
                .height(440.dp + DRAWER_OVERSHOOT)
                .graphicsLayer {
                    val s = slide.value
                    translationY = s * drawerHeightPx + overshootPx
                    // 大面積的動作要有「重量」：從稍小、稍透明，一邊上升一邊長到原大（以底邊中央為支點），
                    // 收起時反過來。整塊是一張離屏圖，縮放只是貼圖變形，不會重畫裡面的內容。
                    val k = s.coerceIn(0f, 1f)
                    scaleX = 1f - .05f * k; scaleY = 1f - .05f * k
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(.5f, 1f)
                    alpha = 1f - .55f * k * k
                    // 滑動中：整塊抽屜（玻璃＋App 格子）畫成一張離屏圖，GPU 每格只貼一個四邊形；停下來恢復一般繪製
                    compositingStrategy = if (asLayer || s > 0.001f) CompositingStrategy.Offscreen else CompositingStrategy.Auto
                }
                .liquidGlass(
                    RoundedCornerShape(topStart = 34.dp, topEnd = 34.dp), tint = 0.09f,
                    slide = { drawerSlideOffset(slide.value, drawerHeightPx) },
                    frozen = { asLayer || slide.value > 0.001f },
                )
                .padding(start = 28.dp, end = 28.dp, top = 24.dp)
                .onPreviewKeyEvent { e ->
                    if (e.type == KeyEventType.KeyDown && e.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_UP && focused < 6) {
                        onClose(); true
                    } else false
                }
                .trapDirectionalFocus(),
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                GText("所有 App", size = 22.sp, weight = FontWeight.Medium)
                Spacer(Modifier.width(12.dp))
                GText("${apps.size} 個", Modifier.padding(bottom = 3.dp), size = 13.sp, color = Ink.Tertiary)
                Spacer(Modifier.weight(1f))
                GText("長按確定鍵：更多選項　返回：收合", Modifier.padding(bottom = 3.dp), size = 12.sp, color = Ink.Faint)
            }
            Spacer(Modifier.height(10.dp))
            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Fixed(6),
                contentPadding = PaddingValues(start = 4.dp, end = 4.dp, top = 12.dp, bottom = 40.dp),
                horizontalArrangement = Arrangement.spacedBy(22.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                itemsIndexed(if (tw.liuli.launcher.ui.bg.PerfProbe.noTiles) emptyList() else apps, key = { _, a -> a.packageName }) { i, app ->
                    Box(
                        Modifier.graphicsLayer {
                            // 以畫面上的第幾排決定出場順序（一排 6 個），同一排左到右再錯開一點點
                            val pos = (i - gridState.firstVisibleItemIndex).coerceAtLeast(0)
                            val a = tw.liuli.launcher.ui.components.rowReveal(reveal.value, pos / 6 * 3 + pos % 6 / 2, stagger = 30f, fade = 200f)
                            alpha = a
                            translationY = (1f - a) * 14.dp.toPx()
                        },
                    ) {
                    AppTile(
                        app = app,
                        width = 124.dp,
                        showLabel = true,
                        requester = if (i == 0) first else null,
                        onFocused = { focused = i },
                        onClick = { onLaunch(app) },
                        onLongClick = { fr -> onLongClick(app, fr) },
                        extra = Modifier.bgTarget(app.themeColor, isApp = true),
                    )
                    }
                }
            }
        }
    }
    }
}


private fun drawerSlideOffset(slide: Float, heightPx: Float) = androidx.compose.ui.geometry.Offset(0f, slide * heightPx)

/** 面板／抽屜打開後維持離屏圖的時間：滑入彈簧約 0.33 秒、主畫面淡出 0.38 秒、背景暫停 0.45 秒，全部結束才回到一般繪製。 */
internal const val PANEL_LAYER_MS = 700L

/** 抽屜裡的 App 逐排出現的總時間（毫秒）。 */
private const val DRAWER_REVEAL_MS = 30f * 14 + 200f

/** 抽屜往螢幕下方多延伸的高度：容納滑入彈簧衝過頭的那一段（約 4～5%，20dp 左右）。 */
private val DRAWER_OVERSHOOT = 48.dp

/**
 * 簡潔模式：只留左下角的時間（含日期、農曆）與右下角的天氣，其他介面都收起來。
 * [t]（0～1）：浮上來的進度（在主畫面收起一半之後才開始），只改圖層的透明度與位置。
 */
@Composable
fun MinimalOverlay(home: HomeContext, drift: Boolean = true, t: () -> Float) {
    val context = LocalContext.current
    val now = rememberMinuteClock()
    val is24h = android.text.format.DateFormat.is24HourFormat(context)
    val time = now.format(DateTimeFormatter.ofPattern(if (is24h) "HH:mm" else "h:mm", zh))
    val ampm = if (is24h) null else if (now.hour < 12) "上午" else "下午"
    val l = home.lunar
    val w = home.weather
    // 微飄移（防烙印）：時間、天氣各自用很長的週期（一兩分鐘）緩慢飄動，同一個位置不會長時間亮著。
    // 時鐘只在繪製階段讀取（只改圖層位置，不重新組合、不重新排版）
    val clock = remember { androidx.compose.runtime.mutableLongStateOf(0L) }
    if (drift) {
        androidx.compose.runtime.LaunchedEffect(Unit) {
            // 每 0.1 秒更新一次就夠了：飄得很慢（每秒約 1.5 dp），每次只移動零點幾個像素，看起來是連續的
            while (true) { clock.longValue = android.os.SystemClock.uptimeMillis(); kotlinx.coroutines.delay(100) }
        }
    }
    fun DrawFloat(phase: Float, ax: Float, ay: Float): Pair<Float, Float> {
        if (!drift) return 0f to 0f
        val s = clock.longValue / 1000f
        return ax * kotlin.math.sin(s * 2f * kotlin.math.PI.toFloat() / 97f + phase) to
            ay * kotlin.math.sin(s * 2f * kotlin.math.PI.toFloat() / 71f + phase * 1.7f)
    }
    Box(
        Modifier
            .fillMaxSize()
            .padding(start = 56.dp, end = 56.dp, bottom = 44.dp)
            .graphicsLayer {
                val v = t()
                alpha = v
                translationY = (1f - v) * 24.dp.toPx()
            },
    ) {
        Column(
            Modifier.align(Alignment.BottomStart).graphicsLayer {
                val (dx, dy) = DrawFloat(0f, 24.dp.toPx(), 14.dp.toPx())
                translationX = dx + 24.dp.toPx(); translationY = dy - 14.dp.toPx()
            },
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                GText(time, size = 84.sp, weight = FontWeight.ExtraLight, shadow = true, letterSpacing = (-2).sp)
                if (ampm != null) GText(ampm, Modifier.padding(start = 8.dp, bottom = 14.dp), size = 19.sp, weight = FontWeight.Light, shadow = true)
            }
            GText(
                now.format(DateTimeFormatter.ofPattern("M月d日 EEEE", zh)) + "・農曆" + l.monthText + l.dayText,
                size = 17.sp, color = Ink.Secondary, shadow = true,
            )
        }
        if (w != null) {
            Column(
                Modifier.align(Alignment.BottomEnd).graphicsLayer {
                    val (dx, dy) = DrawFloat(2.1f, 24.dp.toPx(), 14.dp.toPx())
                    translationX = dx - 24.dp.toPx(); translationY = dy - 14.dp.toPx()
                },
                horizontalAlignment = Alignment.End,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LIcon(LIcons.weather(w.kind, w.isDay), 32.dp)
                    Spacer(Modifier.width(12.dp))
                    GText("${w.temp.roundToInt()}°", size = 52.sp, weight = FontWeight.ExtraLight, shadow = true)
                }
                val td = w.today
                GText(
                    w.kind.label + (if (td != null) "　${td.max.roundToInt()}° / ${td.min.roundToInt()}°" else "") + "・" + home.city.name,
                    size = 15.sp, color = Ink.Secondary, shadow = true,
                )
            }
        }
    }
}
