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
import tw.liuli.launcher.ui.theme.SLIDE_THRESHOLD
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
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
import tw.liuli.launcher.ui.glass.GlassLit
import tw.liuli.launcher.ui.glass.jellyFocus
import tw.liuli.launcher.ui.glass.rememberJelly
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
    // 焦點時整塊凝結出一片霧玻璃（含邊緣折射、色散），整塊像布丁一樣被帶一下
    val jelly = rememberJelly(gain = .45f)
    Box(
        modifier
            .jellyFocus(jelly, focus, 1.02f)
            .bgTarget(color)
            .focusTarget(focus, requester, onClick = { jelly.press(); onClick() }),
    ) {
        Box(
            Modifier
                .matchParentSize()
                .graphicsLayer {
                    compositingStrategy = CompositingStrategy.ModulateAlpha
                    val v = focus.value()
                    alpha = v
                    scaleX = 0.94f + 0.06f * v
                    scaleY = 0.94f + 0.06f * v
                }
                .liquidGlass(shape, tint = 0.05f),
        )
        // 文字（大時鐘、帶陰影的字）畫成一張離屏圖留著：一分鐘才變一次，其餘每一格只貼這張圖
        Box(Modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) { content() }
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

/**
 * 抽屜打開時，主畫面整個隱藏；抽屜上方那條空白放一行精簡的資訊：左邊時間與日期，右邊天氣與網路。
 * 只是看的，不拿焦點。[visible] 0～1（在繪製階段讀取，淡入時不重新組合）。
 */
@Composable
fun DrawerStatusBar(home: HomeContext, visible: () -> Float, modifier: Modifier = Modifier) {
    androidx.compose.runtime.SideEffect { tw.liuli.launcher.ui.bg.UiTrace.hit("Bar") }
    val context = LocalContext.current
    val now = rememberMinuteClock()
    val is24h = android.text.format.DateFormat.is24HourFormat(context)
    val time = now.format(DateTimeFormatter.ofPattern(if (is24h) "HH:mm" else "h:mm", zh))
    val ampm = if (is24h) null else if (now.hour < 12) "上午" else "下午"
    val w = home.weather
    Row(
        modifier
            .fillMaxWidth()
            .height(100.dp)
            .padding(horizontal = 52.dp)
            .graphicsLayer {
                compositingStrategy = CompositingStrategy.ModulateAlpha
                val v = visible().coerceIn(0f, 1f)
                alpha = v
                translationY = (1f - v) * (-10).dp.toPx()
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GText(time, size = 40.sp, weight = FontWeight.ExtraLight, shadow = true, letterSpacing = (-1).sp)
        if (ampm != null) GText(ampm, Modifier.padding(start = 6.dp, top = 12.dp), size = 14.sp, weight = FontWeight.Light, shadow = true)
        Box(Modifier.padding(horizontal = 14.dp).size(3.dp).background(Ink.Tertiary, CircleShape))
        GText(now.format(DateTimeFormatter.ofPattern("M月d日 EEEE", zh)), size = 16.sp, color = Ink.Secondary, shadow = true)
        Spacer(Modifier.weight(1f))
        if (w == null) {
            GText(if (home.online) "天氣載入中…" else "目前離線", size = 16.sp, color = Ink.Secondary, shadow = true)
        } else {
            LIcon(LIcons.weather(w.kind, w.isDay), 24.dp)
            Spacer(Modifier.width(8.dp))
            GText("${w.temp.roundToInt()}°", size = 32.sp, weight = FontWeight.ExtraLight, shadow = true)
            Spacer(Modifier.width(10.dp))
            val t = w.today
            GText(
                if (t != null) "${w.kind.label}　${t.max.roundToInt()}° / ${t.min.roundToInt()}°" else w.kind.label,
                size = 16.sp, color = Ink.Secondary, shadow = true,
            )
        }
        // 網路：只在有線／無線／斷線之間換圖示，斷線時變淡
        Spacer(Modifier.width(18.dp))
        val net = when (home.network) {
            tw.liuli.launcher.context.NetworkStatus.WIFI -> LIcons.Wifi
            tw.liuli.launcher.context.NetworkStatus.ETHERNET -> LIcons.Ethernet
            tw.liuli.launcher.context.NetworkStatus.NONE -> LIcons.NoNet
        }
        LIcon(net, 20.dp, if (home.network == tw.liuli.launcher.context.NetworkStatus.NONE) Ink.Tertiary else Color.White.copy(alpha = 0.8f))
    }
}

/** 設定按鈕。 */
@Composable
fun GearButton(requester: FocusRequester, onClick: () -> Unit, modifier: Modifier = Modifier) {
    RoundIconButton(LIcons.Settings, BgColors.GEAR, requester, onClick, modifier)
}

/**
 * 右上角圓形圖示按鈕：一小片霧玻璃（邊緣有折射、色散）上的圖示；焦點時整顆亮起來、像布丁一樣彈一下，圖示維持白色。
 */
@Composable
fun RoundIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: Int,
    requester: FocusRequester?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Color.White.copy(alpha = 0.85f),
    iconModifier: Modifier = Modifier,
) {
    val focus = rememberFocusAnim()
    val jelly = rememberJelly()
    Box(
        modifier
            .size(44.dp)
            .jellyFocus(jelly, focus, 1.12f)
            .bgTarget(color)
            .focusTarget(focus, requester, onClick = { jelly.press(); onClick() })
            .liquidGlass(CircleShape, tint = 0.04f),
        contentAlignment = Alignment.Center,
    ) {
        GlassLit(focus, 22.dp, Modifier.matchParentSize(), haze = .14f)
        LIcon(icon, 22.dp, if (focus.focused) Color.White else tint, iconModifier)
    }
}

/**
 * 常用列是空的時候的佔位磚：和 App 磚一樣大、一樣的焦點效果。
 * 確定（或下鍵）打開所有 App；在所有 App 裡長按就能加入常用。
 */
@Composable
fun AddToDockTile(requester: FocusRequester, onFocused: () -> Unit, onClick: () -> Unit) {
    val focus = rememberFocusAnim()
    val jelly = rememberJelly()
    Box(
        Modifier
            .width(150.dp)
            .aspectRatio(16f / 9f)
            .jellyFocus(jelly, focus, TILE_FOCUS_SCALE)
            .focusTarget(focus, requester, onFocused = onFocused, onClick = { jelly.press(); onClick() })
            .liquidGlass(RoundedCornerShape(Dimens.TileRadius), tint = 0.06f),
        contentAlignment = Alignment.Center,
    ) {
        GlassLit(focus, Dimens.TileRadius, Modifier.matchParentSize(), haze = .12f)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            GText("＋", size = 26.sp, weight = FontWeight.Light, color = Ink.Primary)
            GText("加入常用", size = 13.sp, color = if (focus.focused) Ink.Primary else Ink.Secondary)
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
    androidx.compose.runtime.SideEffect { tw.liuli.launcher.ui.bg.UiTrace.hit("Inputs") }
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
    val jelly = rememberJelly()
    val color = when (src.type) {
        TvInputInfo.TYPE_TUNER -> BgColors.TV
        TvInputInfo.TYPE_HDMI -> BgColors.HDMI
        else -> BgColors.OTHER_INPUT
    }
    // 一小片霧玻璃（背後的背景霧化、一點白、邊緣有折射與色散）——靜態的，焦點動畫期間不重畫。
    // 焦點：整顆按鈕自己像布丁一樣被帶一下、彈回來（只改圖層），裡面多亮一層；文字一律白色
    Box(
        Modifier
            .height(40.dp)
            .jellyFocus(jelly, focus, 1.06f)
            .bgTarget(color)
            .focusTarget(focus, requester, onClick = { jelly.press(); onClick() })
            .liquidGlass(RoundedCornerShape(20.dp), tint = 0.05f),
    ) {
        GlassLit(focus, 20.dp, Modifier.matchParentSize(), haze = .17f)
        Row(Modifier.height(40.dp).padding(start = 12.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            LIcon(if (src.type == TvInputInfo.TYPE_TUNER) LIcons.Input else LIcons.Hdmi, 20.dp, Color.White)
            Spacer(Modifier.width(8.dp))
            GText(src.label, size = 15.sp, color = Ink.Primary)
            // 名稱已經包含類型（HDMI1、AV）時不再重複顯示
            if (!src.label.replace(" ", "").contains(src.typeTag, ignoreCase = true)) {
                Spacer(Modifier.width(6.dp))
                GText(src.typeTag, size = 12.sp, color = Ink.Tertiary)
            }
        }
    }
}

/** App 磚焦點時放大的倍率（玻璃外框依這個倍率包住放大後的磚）。 */
const val TILE_FOCUS_SCALE = 1.07f

/**
 * 所有 App 抽屜：從下方滑上來的玻璃面板。第一列按上鍵、或按返回鍵收合。
 *
 * 預先建好：主畫面穩定後就組合好（28 個 App 格子），[open] 為 false 時只量尺寸、不擺放——不畫、拿不到焦點。
 * 打開時只剩滑入動畫；以前每次打開才組合，海信上第一格要 110ms，滑出來會頓一下。
 */
@OptIn(ExperimentalComposeUiApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun AppDrawer(
    open: Boolean,
    apps: List<AppEntry>,
    onClose: () -> Unit,
    onLaunch: (AppEntry) -> Unit,
    onLongClick: (AppEntry, FocusRequester) -> Unit,
    /** 系統 App（按了「顯示系統應用」才有；null＝還沒載入或沒展開）。接在一般 App 後面、同一個格子區裡。 */
    systemApps: List<AppEntry>? = null,
    /** 載入中（按下之後、清單回來之前）。 */
    systemLoading: Boolean = false,
    onToggleSystem: () -> Unit = {},
    onSystemLongClick: (AppEntry, FocusRequester) -> Unit = { _, _ -> },
    slideRef: tw.liuli.launcher.ui.components.SlideRef? = null,
) {
    androidx.compose.runtime.SideEffect { tw.liuli.launcher.ui.bg.UiTrace.hit("Drawer") }
    // 收尾門檻要很小（0.05%，不到半個像素）：預設 1% 對 440dp 的抽屜是 9 個像素，彈簧最後會「喀」地跳到終點
    val slide = remember { Animatable(1f, visibilityThreshold = SLIDE_THRESHOLD) }
    if (slideRef != null) androidx.compose.runtime.DisposableEffect(slideRef) { slideRef.read = { slide.value }; onDispose { slideRef.read = { 1f } } }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val drawerHeightPx = with(density) { 440.dp.toPx() }
    // 往下多延伸一段（平常在螢幕外）：滑入的彈簧會衝過頭一點，底邊抬起時露出的仍是抽屜，不是一條縫
    val overshootPx = with(density) { DRAWER_OVERSHOOT.toPx() }
    val padPx = with(density) { 40.dp.toPx() }
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
            // 「停好」＝彈簧已經結束：彈簧衝過停靠位置的那一格 slide 剛好接近 0，但抽屜還被布丁壓窄著，
            // 那一格若跳過背景，抽屜兩側各露出一條黑線（閃一格）
            slide = { if (openNow.value && kotlin.math.abs(slide.value) < .03f) 0f else 1f },
            // 彈簧還在晃的時候，背景少跳過一圈（抽屜這時被布丁壓窄、壓扁一點點，邊緣外面要有背景）；停好才整塊跳過
            pad = { if (slide.isRunning) padPx else 0f },
        )
        onDispose { bgEngine?.cover = null }
    }
    val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    val lightX = remember { Animatable(.1f) }
    val toggleReq = remember { FocusRequester() }
    var focused by remember { mutableIntStateOf(0) }
    // ── 整排對齊：格子區剛好放得下幾整排，捲動一次捲一整排，上下都不會切到半排 ──
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var cellPx by remember { mutableIntStateOf(0) }          // 一格（磚＋名稱）的實際高度
    var rowsFit by remember { mutableIntStateOf(3) }          // 可視範圍放得下幾整排
    var topRow by remember { mutableIntStateOf(0) }           // 目前最上面是第幾排
    fun showRow(row: Int, animate: Boolean = true) {
        val newTop = when {
            row < topRow -> row
            row >= topRow + rowsFit -> row - rowsFit + 1
            else -> topRow
        }.coerceAtLeast(0)
        if (newTop == topRow && gridState.firstVisibleItemIndex == newTop * DRAWER_COLS && gridState.firstVisibleItemScrollOffset == 0) return
        topRow = newTop
        scope.launch {
            if (animate) runCatching { gridState.animateScrollToItem(newTop * DRAWER_COLS) }
            else runCatching { gridState.scrollToItem(newTop * DRAWER_COLS) }
        }
    }
    // 每個 App 磚一個 FocusRequester（依套件名稱）：解除安裝後焦點要能移到原本位置上的下一個 App
    val requesters = remember { HashMap<String, FocusRequester>() }
    var focusedPkg by remember { mutableStateOf<String?>(null) }
    // 展開系統 App：清單一到，捲到第一個系統 App 那一排、焦點移過去；收合：焦點留在最後那一格
    val sysCount = systemApps?.size ?: -1
    LaunchedEffect(sysCount) {
        if (!open) return@LaunchedEffect
        if (sysCount > 0) {
            val first = systemApps!!.first().packageName
            showRow(apps.size / DRAWER_COLS, animate = true)
            repeat(12) {
                delay(40)
                val r = requesters["sys:$first"]
                if (r != null && runCatching { r.requestFocus() }.isSuccess) return@LaunchedEffect
            }
        } else if (sysCount == -1) {
            showRow(apps.size / DRAWER_COLS, animate = false)
            repeat(8) { delay(40); if (runCatching { toggleReq.requestFocus() }.isSuccess) return@LaunchedEffect }
        }
    }
    // 量化成 240 格（每格約 8 個像素，看不出階梯）：光移動的那半秒面板跟著每一格重烘，停下來就不動
    val lightStep by remember { androidx.compose.runtime.derivedStateOf { (lightX.value * 240f).roundToInt() } }
    // App 清單更新時（例如在抽屜裡解除安裝了一個 App）：原本有焦點的那一格不見了，焦點會掉到抽屜後面的主畫面，
    // 抽屜還開著卻沒辦法操作。改成把焦點交給同一個位置上的下一個 App（刪的是最後一個就交給前一個）
    LaunchedEffect(apps) {
        val pkg = focusedPkg ?: return@LaunchedEffect
        if (!open || apps.isEmpty() || apps.any { it.packageName == pkg }) return@LaunchedEffect
        val idx = focused.coerceIn(0, apps.size - 1)
        val target = apps[idx].packageName
        focusedPkg = target
        focused = idx
        showRow(idx / DRAWER_COLS, animate = false)
        // 等新的格子排好、FocusRequester 掛上去再要焦點（最多試幾次）
        repeat(10) {
            delay(32)
            if (runCatching { requesters[target]?.requestFocus() }.isSuccess && requesters[target] != null) return@LaunchedEffect
        }
    }
    // 打開後 0.5 秒內（滑入＋主畫面淡出＋背景暫停都還在進行）整塊抽屜維持一張離屏圖，之後才回到一般繪製
    var asLayer by remember { mutableStateOf(false) }
    var prep by remember { mutableStateOf(false) }
    val glassGate = remember { tw.liuli.launcher.ui.glass.GlassGate() }
    // 是否擺放（看得到）：打開時立刻擺放；收起時等滑出動畫結束才拿掉
    var shown by remember { mutableStateOf(false) }
    // 先出底框、再逐排出現：滑上來的只有玻璃底框與標題列，停好後 App 一排一排淡入浮上
    val reveal = remember { Animatable(0f) }
    LaunchedEffect(open) {
        if (open) {
            // 打開的順序（弱電視也跟得上）：
            // 1. 準備：玻璃底框（含光、邊緣）在「停下來的位置」烘好，格子排好、畫好，整塊用看不見的透明度畫進一張離屏圖；
            //    打開那一瞬間主執行緒的重新組合、第一次繪製都在這裡做完，等畫面靜下來
            // 2. 只有底框彈上來（App 還是透明的）：每一格只是移動、縮放那張離屏圖
            // 3. 底框幾乎到位，App 才一排一排淡入（這時不再是離屏圖；底框剩下的晃動只是圖層位移，不重畫）
            val t0 = android.os.SystemClock.uptimeMillis()
            shown = true
            asLayer = true
            glassGate.baked = false   // 背景可能換過顏色了：等這一次烘好的
            prep = true
            slide.snapTo(1f)
            reveal.snapTo(0f)
            focused = 0
            topRow = 0
            runCatching { gridState.scrollToItem(0) }
            delay(16)
            apps.firstOrNull()?.let { a -> runCatching { requesters[a.packageName]?.requestFocus() } }
            // 等玻璃烘好（通常一兩格；最多等 0.3 秒），再等畫面靜下來
            kotlinx.coroutines.withTimeoutOrNull(300) { androidx.compose.runtime.snapshotFlow { glassGate.baked }.first { it } }
            tw.liuli.launcher.ui.components.awaitCalmFrames()
            prep = false
            val t1 = android.os.SystemClock.uptimeMillis()
            launch {
                // 抽屜「幾乎到位」（剩不到 3%）就開始逐排出現，和彈簧最後的收尾重疊，不會有一段空框在等
                androidx.compose.runtime.snapshotFlow { slide.value }.first { it < .03f }
                asLayer = false
                android.util.Log.i("LiuliUi", "drawer open: prep ${t1 - t0}ms, first tiles at ${android.os.SystemClock.uptimeMillis() - t0}ms")
                reveal.animateTo(DRAWER_REVEAL_MS, androidx.compose.animation.core.tween(DRAWER_REVEAL_MS.toInt(), easing = androidx.compose.animation.core.LinearEasing))
            }
            slide.animateTo(0f, SheetSpring)
        } else if (shown) {
            // 收起的順序：先把整塊抽屜（底框＋目前的內容）定格成一張離屏圖、等畫面靜下來（關抽屜那一瞬間主畫面要重新組合），
            // 再用彈簧把這一張圖收下去——不逐個動、不重烘玻璃
            val t0 = android.os.SystemClock.uptimeMillis()
            prep = false
            asLayer = true
            tw.liuli.launcher.ui.components.awaitCalmFrames(180)
            val t1 = android.os.SystemClock.uptimeMillis()
            slide.animateTo(1f, SheetExit)
            android.util.Log.i("LiuliUi", "drawer close: freeze ${t1 - t0}ms, gone at ${android.os.SystemClock.uptimeMillis() - t0}ms")
            asLayer = false
            shown = false
        }
    }

    // 滑入中：焦點效果直接到位（整塊抽屜是一張離屏圖，第一格的焦點彈簧會讓整張圖每格重畫）
    androidx.compose.runtime.CompositionLocalProvider(
        tw.liuli.launcher.ui.components.LocalFocusSnap provides { slide.value > 0.001f || asLayer },
        tw.liuli.launcher.ui.components.LocalTileShadow provides { 1f },
        tw.liuli.launcher.ui.glass.LocalGlassHidden provides { false },
    ) {
    Box(
        Modifier
            .fillMaxSize()
            // 收起來的時候也一直「擺著」（只是整塊透明度 0，GPU 完全不畫）：
            // 以前收起就不擺放，Compose 會把裡面一百多個圖層全部丟掉，下次打開的第一格要重建、重新錄製每一塊磚的繪圖指令——
            // 海信上那一格主執行緒要 0.1 秒以上（打開抽屜按下去之後的那一下停頓）
            // 收起中、收起後的抽屜不能被焦點走進去
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
                    if (!open && !shown) {
                        // 收起來了：不畫（圖層與繪圖指令都留著）。位置和「滑到底」時一樣——玻璃是用「目前位置減掉滑動量」推算停靠位置的，
                        // 這裡不跟著位移的話，第一次算出來的停靠位置會高出一整個抽屜，烘出來的是天空那一段的顏色
                        translationY = slide.value * drawerHeightPx + overshootPx
                        alpha = 0f
                        return@graphicsLayer
                    }
                    if (prep) {
                        // 準備中：在停下來的位置、幾乎全透明（約 0.4%，肉眼看不到）畫一次，讓 GPU 把整張離屏圖建好
                        translationY = overshootPx; alpha = .004f
                        compositingStrategy = CompositingStrategy.Offscreen
                        return@graphicsLayer
                    }
                    val s = slide.value
                    translationY = s * drawerHeightPx + overshootPx
                    // 大面積的動作要有「重量」：從稍小、稍透明，一邊上升一邊長到原大（以底邊中央為支點），
                    // 收起時反過來。整塊是一張離屏圖，縮放只是貼圖變形，不會重畫裡面的內容。
                    val k = s.coerceIn(0f, 1f)
                    // 和按鈕同一種布丁：移動得越快、順著移動方向（上下）拉得越長；衝過停靠位置的那一下被壓扁，再晃回來
                    val st = (kotlin.math.abs(slide.velocity) * .020f).coerceAtMost(.09f) - (-s).coerceAtLeast(0f) * 1.4f
                    scaleX = (1f - .05f * k) / (1f + .5f * st); scaleY = (1f - .05f * k) * (1f + st)
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(.5f, 1f)
                    // 快到位時就當成完全不透明：差一點點（0.05%）不透明的話，系統每一格都要把整個抽屜先畫到暫存層再疊上去
                    alpha = if (k < .03f) 1f else 1f - .55f * k * k
                    // 滑動中：整塊抽屜（玻璃＋App 格子）畫成一張離屏圖，GPU 每格只貼一個四邊形；停下來恢復一般繪製
                    // （彈簧衝過頭時 s 是負的，也要維持離屏圖，等彈簧完全停了才換回一般繪製）
                    // 只看「是不是定格中」：App 開始逐排出現之後就一直是一般繪製——底框還在晃（s 在 0 附近來回）時如果跟著
                    // 在離屏圖與一般繪製之間切來切去，每切一次整塊都要重畫
                    // （試過打開期間一直維持離屏圖：背景換色的那一秒變順了，但海信上只要裡面有東西在動——焦點移動、逐排出現——
                    //   整張離屏圖每格都要重畫，抽屜裡移動焦點的掉格從 3% 變成 44～62%，所以不這樣做）
                    compositingStrategy = if (asLayer) CompositingStrategy.Offscreen else CompositingStrategy.Auto
                }
                .liquidGlass(
                    gate = glassGate,
                    shape = RoundedCornerShape(topStart = 34.dp, topEnd = 34.dp), tint = 0.09f,
                    slide = { drawerSlideOffset(slide.value, drawerHeightPx) },
                    frozen = { asLayer || (!open && !shown) || kotlin.math.abs(slide.value) > 0.001f },   // 收起來的時候不重烘
                    // 受光：上緣一道光、一片跟著焦點那一欄移動的柔光、往下漸暗（烘在同一張圖裡；位置量化成 24 格，移動中最多每 0.12 秒重烘一次）
                    light = { androidx.compose.ui.geometry.Offset(lightStep / 240f, .08f) },
                )
                .padding(start = 28.dp, end = 28.dp, top = 24.dp)
                .onPreviewKeyEvent { e ->
                    if (e.type == KeyEventType.KeyDown && e.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_UP && focused < DRAWER_COLS) {
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
            // 格子區：抽屜往螢幕下方多延伸的那段（彈簧衝過頭用）看不到，不算進可視範圍
            androidx.compose.foundation.layout.BoxWithConstraints(
                Modifier.fillMaxWidth().weight(1f).padding(bottom = DRAWER_OVERSHOOT),
            ) {
                val dens = androidx.compose.ui.platform.LocalDensity.current
                val padTop = 14.dp; val padBottom = 14.dp; val gap = 20.dp
                val cell = with(dens) { if (cellPx > 0) cellPx.toFloat() else 112.dp.toPx() }
                val fit = with(dens) {
                    val avail = maxHeight.toPx() - padTop.toPx() - padBottom.toPx()
                    ((avail + gap.toPx()) / (cell + gap.toPx())).toInt().coerceAtLeast(1)
                }
                androidx.compose.runtime.SideEffect { if (fit != rowsFit) rowsFit = fit }
                // 格子區的高度＝剛好 fit 整排（加上下邊距）：任何時候畫面上都是完整的幾排
                val gridH = with(dens) { (padTop.toPx() + fit * cell + (fit - 1) * gap.toPx() + padBottom.toPx()).toDp() }
                // 焦點移動不讓清單自己捲（預設會捲到「剛好看見」＝切出半排），改由 showRow 一次捲一整排
                val noAutoScroll = remember {
                    object : androidx.compose.foundation.gestures.BringIntoViewSpec {
                        override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float) = 0f
                    }
                }
                androidx.compose.runtime.CompositionLocalProvider(androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides noAutoScroll) {
            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Fixed(DRAWER_COLS),
                modifier = Modifier.fillMaxWidth().height(gridH),
                contentPadding = PaddingValues(start = 4.dp, end = 4.dp, top = padTop, bottom = padBottom),
                horizontalArrangement = Arrangement.spacedBy(22.dp),
                verticalArrangement = Arrangement.spacedBy(gap),
            ) {
                val shownApps = if (tw.liuli.launcher.ui.bg.PerfProbe.noTiles) emptyList() else apps
                val sys = systemApps.orEmpty()
                itemsIndexed(shownApps, key = { _, a -> a.packageName }) { i, app ->
                    DrawerCell(i, gridState, { reveal.value }, cellPx, { if (it > cellPx) cellPx = it }) { revealOf ->
                    AppTile(
                        app = app,
                        width = 124.dp,
                        showLabel = true,
                        requester = requesters.getOrPut(app.packageName) { FocusRequester() },
                        onFocused = {
                            focused = i; focusedPkg = app.packageName; showRow(i / DRAWER_COLS)
                            // 面板上的那一片光跟著焦點的那一欄慢慢移過去（滑動中直接到位：光一動，整張離屏圖每一格都要重畫）
                            scope.launch { val x = (i % DRAWER_COLS + .5f) / DRAWER_COLS; if (asLayer) lightX.snapTo(x) else lightX.animateTo(x, androidx.compose.animation.core.tween(520)) }
                        },
                        onClick = { onLaunch(app) },
                        onLongClick = { fr -> onLongClick(app, fr) },
                        extra = Modifier.bgTarget(app.themeColor, isApp = true),
                        reveal = revealOf,
                        revealing = { reveal.value < DRAWER_REVEAL_MS },
                    )
                    }
                }
                // 系統 App：接在後面，操作和一般 App 一樣（開啟、長按、捲動、上緣淡出）
                itemsIndexed(sys, key = { _, a -> "sys:" + a.packageName }) { j, app ->
                    val i = shownApps.size + j
                    DrawerCell(i, gridState, { reveal.value }, cellPx, { if (it > cellPx) cellPx = it }) { revealOf ->
                    AppTile(
                        app = app,
                        width = 124.dp,
                        showLabel = true,
                        requester = requesters.getOrPut("sys:" + app.packageName) { FocusRequester() },
                        onFocused = {
                            focused = i; focusedPkg = null; showRow(i / DRAWER_COLS)
                            scope.launch { val x = (i % DRAWER_COLS + .5f) / DRAWER_COLS; if (asLayer) lightX.snapTo(x) else lightX.animateTo(x, androidx.compose.animation.core.tween(520)) }
                        },
                        onClick = { onLaunch(app) },
                        onLongClick = { fr -> onSystemLongClick(app, fr) },
                        extra = Modifier.bgTarget(app.themeColor, isApp = true),
                        reveal = revealOf,
                        revealing = { reveal.value < DRAWER_REVEAL_MS },
                    )
                    }
                }
                // 最後一格：顯示／隱藏系統應用
                item(key = "sys-toggle") {
                    val i = shownApps.size + sys.size
                    DrawerCell(i, gridState, { reveal.value }, cellPx, { if (it > cellPx) cellPx = it }) { revealOf ->
                        SystemToggleTile(
                            expanded = systemApps != null, loading = systemLoading, count = sys.size,
                            requester = toggleReq,
                            onFocused = {
                                focused = i; focusedPkg = null; showRow(i / DRAWER_COLS)
                                scope.launch { val x = (i % DRAWER_COLS + .5f) / DRAWER_COLS; if (asLayer) lightX.snapTo(x) else lightX.animateTo(x, androidx.compose.animation.core.tween(520)) }
                            },
                            onClick = onToggleSystem,
                            reveal = revealOf,
                        )
                    }
                }
            }
                }
            }
        }
    }
    }
}


/**
 * 抽屜裡的一格：逐排出場時浮上來；捲動中往上離開的那一排越過上緣多少就淡掉多少（不被切一半）。只改這一格的圖層。
 */
@Composable
private fun DrawerCell(
    i: Int,
    gridState: androidx.compose.foundation.lazy.grid.LazyGridState,
    revealMs: () -> Float,
    cellPx: Int,
    onHeight: (Int) -> Unit,
    content: @Composable (reveal: () -> Float) -> Unit,
) {
    androidx.compose.runtime.SideEffect { tw.liuli.launcher.ui.bg.UiTrace.hit("Cell") }
    val pos = { (i - gridState.firstVisibleItemIndex).coerceAtLeast(0) }
    val revealOf = { tw.liuli.launcher.ui.components.rowReveal(revealMs(), pos() / 6 * 3 + pos() % 6 / 2, stagger = 30f, fade = 200f) }
    val cell = androidx.compose.runtime.rememberUpdatedState(cellPx)
    Box(
        Modifier
            .onSizeChanged { onHeight(it.height) }
            .graphicsLayer {
                translationY = (1f - revealOf()) * 14.dp.toPx()
                val firstRow = gridState.firstVisibleItemIndex / DRAWER_COLS
                val off = gridState.firstVisibleItemScrollOffset
                val row = i / DRAWER_COLS
                alpha = when {
                    row < firstRow -> 0f
                    row == firstRow && off > 0 -> (1f - off / (cell.value.coerceAtLeast(1) * .5f)).coerceIn(0f, 1f)
                    else -> 1f
                }
            },
    ) { content(revealOf) }
}

/** 抽屜最後一格：「顯示系統應用」／「隱藏系統應用」。和 App 磚一樣大、同一種霧玻璃與布丁。 */
@Composable
private fun SystemToggleTile(
    expanded: Boolean, loading: Boolean, count: Int,
    requester: FocusRequester, onFocused: () -> Unit, onClick: () -> Unit, reveal: () -> Float,
) {
    val focus = rememberFocusAnim()
    val jelly = rememberJelly()
    Column(Modifier.width(124.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .jellyFocus(jelly, focus, TILE_FOCUS_SCALE)
                .graphicsLayer { alpha = reveal() }
                .focusTarget(focus, requester, onFocused = onFocused, onClick = { jelly.press(); onClick() })
                .liquidGlass(RoundedCornerShape(Dimens.TileRadius), tint = 0.06f),
            contentAlignment = Alignment.Center,
        ) {
            GlassLit(focus, Dimens.TileRadius, Modifier.matchParentSize(), haze = .12f)
            GText(if (loading) "…" else if (expanded) "－" else "＋", size = 28.sp, weight = FontWeight.Light)
        }
        Spacer(Modifier.height(10.dp))
        GText(
            if (loading) "載入中…" else if (expanded) "隱藏系統應用" else "顯示系統應用",
            Modifier.fillMaxWidth().graphicsLayer { alpha = reveal() },
            size = 14.sp,
            weight = if (focus.focused) FontWeight.Medium else FontWeight.Normal,
            color = if (focus.focused) Ink.Primary else Ink.Tertiary,
            align = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

private fun drawerSlideOffset(slide: Float, heightPx: Float) = androidx.compose.ui.geometry.Offset(0f, slide * heightPx)

/** 面板／抽屜打開後維持離屏圖的時間：滑入彈簧約 0.33 秒、主畫面淡出 0.38 秒、背景暫停 0.45 秒，全部結束才回到一般繪製。 */
internal const val PANEL_LAYER_MS = 700L

/** 抽屜裡的 App 逐排出現的總時間（毫秒）。 */
private const val DRAWER_REVEAL_MS = 30f * 14 + 200f

/** 抽屜一排幾個 App。 */
private const val DRAWER_COLS = 6

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
