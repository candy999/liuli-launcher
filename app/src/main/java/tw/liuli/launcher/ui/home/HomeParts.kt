package tw.liuli.launcher.ui.home

import android.media.tv.TvInputInfo
import android.view.KeyEvent
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import tw.liuli.launcher.context.Festivals
import tw.liuli.launcher.context.HomeContext
import tw.liuli.launcher.context.LunarCalc
import tw.liuli.launcher.data.AppEntry
import tw.liuli.launcher.data.TvSource
import tw.liuli.launcher.ui.bg.BgColors
import tw.liuli.launcher.ui.bg.bgTarget
import tw.liuli.launcher.ui.components.AppTile
import tw.liuli.launcher.ui.components.focusRow
import tw.liuli.launcher.ui.components.focusScale
import tw.liuli.launcher.ui.components.focusTarget
import tw.liuli.launcher.ui.components.rememberFocusAnim
import tw.liuli.launcher.ui.glass.liquidGlass
import tw.liuli.launcher.ui.theme.GText
import tw.liuli.launcher.ui.theme.Ink
import tw.liuli.launcher.ui.theme.LIcon
import tw.liuli.launcher.ui.theme.LIcons
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
    val tomorrow = remember(home.now.toLocalDate()) {
        val d = home.now.toLocalDate().plusDays(1)
        val info = LunarCalc.of(d)
        info to Festivals.of(d, info)
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
                GText("農曆" + l.monthText + l.dayText, size = 18.sp, color = Ink.Secondary, shadow = true)
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
                Spacer(Modifier.height(4.dp))
                GText("${w.kind.label}・${home.city.name}", size = 15.sp, color = Ink.Secondary, shadow = true)
                w.today?.let { t ->
                    GText("${t.max.roundToInt()}° / ${t.min.roundToInt()}°　降雨 ${t.rainChance}%", size = 13.sp, color = Ink.Tertiary)
                }
            }
        }
    }
}

/** 設定按鈕：平常是一個圖示，焦點時變成亮白圓鈕。 */
@Composable
fun GearButton(requester: FocusRequester, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val focus = rememberFocusAnim()
    Box(
        modifier
            .size(44.dp)
            .focusScale(focus, 1.15f)
            .bgTarget(BgColors.GEAR)
            .focusTarget(focus, requester, onClick = onClick)
            .drawBehind {
                val v = focus.value()
                if (v > 0.01f) drawCircle(Color.White.copy(alpha = 0.95f * v))
            },
        contentAlignment = Alignment.Center,
    ) {
        LIcon(LIcons.Settings, 22.dp, if (focus.focused) Ink.OnLight else Color.White.copy(alpha = 0.85f))
    }
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
        Spacer(Modifier.width(6.dp))
        GText(src.typeTag, size = 12.sp, color = if (dark) Ink.OnLight.copy(alpha = 0.55f) else Ink.Tertiary)
    }
}

/**
 * 所有 App 抽屜：從下方滑上來的玻璃面板。第一列按上鍵、或按返回鍵收合。
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun AppDrawer(
    apps: List<AppEntry>,
    onClose: () -> Unit,
    onLaunch: (AppEntry) -> Unit,
    onLongClick: (AppEntry, FocusRequester) -> Unit,
) {
    val slide = remember { Animatable(1f) }
    LaunchedEffect(Unit) { slide.animateTo(0f, SheetSpring) }
    val first = remember { FocusRequester() }
    LaunchedEffect(apps.isNotEmpty()) {
        delay(16)
        runCatching { first.requestFocus() }
    }
    var focused by remember { mutableIntStateOf(0) }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Column(
            Modifier
                .padding(horizontal = 20.dp)
                .fillMaxWidth()
                .height(440.dp)
                .graphicsLayer { translationY = slide.value * size.height }
                .liquidGlass(RoundedCornerShape(topStart = 34.dp, topEnd = 34.dp), tint = 0.09f)
                .padding(start = 28.dp, end = 28.dp, top = 24.dp)
                .onPreviewKeyEvent { e ->
                    if (e.type == KeyEventType.KeyDown && e.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_UP && focused < 6) {
                        onClose(); true
                    } else false
                }
                .focusProperties { exit = { FocusRequester.Cancel } }
                .focusGroup(),
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
                columns = GridCells.Fixed(6),
                contentPadding = PaddingValues(start = 4.dp, end = 4.dp, top = 12.dp, bottom = 40.dp),
                horizontalArrangement = Arrangement.spacedBy(22.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                itemsIndexed(apps, key = { _, a -> a.packageName }) { i, app ->
                    AppTile(
                        app = app,
                        width = 124.dp,
                        showLabel = true,
                        requester = if (i == 0) first else null,
                        onFocused = { focused = i },
                        onClick = { onLaunch(app) },
                        onLongClick = { fr -> onLongClick(app, fr) },
                        extra = Modifier.bgTarget(app.themeColor),
                    )
                }
            }
        }
    }
}

