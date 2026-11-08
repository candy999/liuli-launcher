package tw.liuli.launcher.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import tw.liuli.launcher.context.HomeContext
import tw.liuli.launcher.ui.components.focusScale
import tw.liuli.launcher.ui.components.focusTarget
import tw.liuli.launcher.ui.components.rememberFocusAnim
import tw.liuli.launcher.ui.glass.liquidGlass
import tw.liuli.launcher.ui.theme.Dimens
import tw.liuli.launcher.ui.theme.GText
import tw.liuli.launcher.ui.theme.Ink
import tw.liuli.launcher.ui.theme.LIcon
import tw.liuli.launcher.ui.theme.LIcons
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

private val zh = Locale.TAIWAN

/** 每分鐘整點更新一次的時間（主畫面不顯示秒，不必每秒重組）。 */
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

@Composable
fun TopBar(
    home: HomeContext,
    inputsButton: FocusRequester,
    settingsButton: FocusRequester,
    onWeather: () -> Unit,
    onInputs: () -> Unit,
    onSettings: () -> Unit,
) {
    val context = LocalContext.current
    val now = rememberMinuteClock()
    val is24h = android.text.format.DateFormat.is24HourFormat(context)
    val time = now.format(DateTimeFormatter.ofPattern(if (is24h) "HH:mm" else "h:mm", zh))
    val ampm = if (is24h) null else if (now.hour < 12) "上午" else "下午"
    val date = now.format(DateTimeFormatter.ofPattern("M月d日 EEEE", zh))

    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.SafeH),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.Bottom) {
                GText(time, size = 76.sp, weight = FontWeight.ExtraLight, shadow = true, letterSpacing = (-1).sp)
                if (ampm != null) {
                    GText(ampm, Modifier.padding(start = 8.dp, bottom = 14.dp), size = 18.sp, weight = FontWeight.Light, shadow = true)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                GText(date, size = 17.sp, color = Ink.Secondary, shadow = true)
                Spacer(Modifier.width(14.dp))
                GText(home.lunar.fullText, size = 17.sp, color = Ink.Secondary, shadow = true)
                home.highlight?.let {
                    Spacer(Modifier.width(12.dp))
                    GText(
                        it,
                        Modifier
                            .background(Ink.GoldSoft, RoundedCornerShape(10.dp))
                            .padding(horizontal = 10.dp, vertical = 2.dp),
                        size = 14.sp,
                        color = Ink.Gold,
                    )
                }
            }
        }
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            val w = home.weather
            if (w != null) {
                GlassButton(onClick = onWeather, shape = RoundedCornerShape(23.dp)) { dark ->
                    Row(Modifier.padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                        LIcon(LIcons.weather(w.kind, w.isDay), 22.dp, if (dark) Ink.OnLight else Color.White)
                        Spacer(Modifier.width(8.dp))
                        GText("${w.temp.roundToInt()}°　${home.city.name}", size = 16.sp, color = if (dark) Ink.OnLight else Ink.Primary)
                    }
                }
            }
            GlassButton(onClick = onInputs, requester = inputsButton) { dark ->
                LIcon(LIcons.Input, 22.dp, if (dark) Ink.OnLight else Color.White)
            }
            GlassButton(onClick = onSettings, requester = settingsButton) { dark ->
                LIcon(LIcons.Settings, 22.dp, if (dark) Ink.OnLight else Color.White)
            }
        }
    }
}

/**
 * 玻璃按鈕：平常是透明玻璃，焦點時變成亮白色（tvOS 的按鈕慣例），內容反成深色。
 */
@Composable
fun GlassButton(
    onClick: () -> Unit,
    requester: FocusRequester? = null,
    shape: Shape = CircleShape,
    height: Dp = 46.dp,
    content: @Composable (dark: Boolean) -> Unit,
) {
    val focus = rememberFocusAnim()
    Box(
        Modifier
            .height(height)
            .let { if (shape == CircleShape) it.width(height) else it }
            .focusScale(focus, 1.12f)
            .focusTarget(focus, requester, onClick = onClick)
            .liquidGlass(shape, highlight = focus.value)
            .drawBehind {
                val v = focus.value()
                if (v > 0.01f) {
                    val outline = shape.createOutline(size, layoutDirection, this)
                    drawOutline(outline, Color.White.copy(alpha = 0.92f * v))
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        content(focus.focused)
    }
}

/** 可聚焦的玻璃卡片（小工具）。 */
@Composable
fun RowScope.GlassCard(
    weight: Float,
    requester: FocusRequester,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    val focus = rememberFocusAnim()
    Box(
        Modifier
            .weight(weight)
            .fillMaxHeight()
            .focusScale(focus, 1.03f)
            .focusTarget(focus, requester, onClick = onClick)
            .liquidGlass(RoundedCornerShape(Dimens.PanelRadius), highlight = focus.value)
            .drawBehind {
                val v = focus.value()
                if (v > 0.01f) {
                    val outline = RoundedCornerShape(Dimens.PanelRadius).createOutline(size, layoutDirection, this)
                    drawOutline(
                        outline,
                        Color.White.copy(alpha = 0.9f * v),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(2.5.dp.toPx()),
                    )
                }
            }
            .padding(horizontal = 24.dp, vertical = 18.dp),
    ) {
        content()
    }
}

@Composable
fun Widgets(
    home: HomeContext,
    calendarCard: FocusRequester,
    weatherCard: FocusRequester,
    onCalendar: () -> Unit,
    onWeather: () -> Unit,
) {
    val l = home.lunar
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.SafeH)
            .height(132.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        GlassCard(1.55f, calendarCard, onCalendar) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxHeight()) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(72.dp)) {
                    GText(home.now.dayOfMonth.toString(), size = 54.sp, weight = FontWeight.ExtraLight)
                    GText(l.monthText, size = 13.sp, color = Ink.Tertiary)
                }
                Spacer(Modifier.width(18.dp))
                Column {
                    GText("${l.ganZhiYear}${l.zodiac}年・${l.monthText}${l.dayText}", size = 17.sp, weight = FontWeight.Medium)
                    Spacer(Modifier.height(6.dp))
                    val tag = home.highlight ?: "下個節氣　${l.nextSolarTerm} ${l.nextSolarTermDate.monthValue}/${l.nextSolarTermDate.dayOfMonth}"
                    GText(
                        tag,
                        Modifier
                            .background(Ink.GoldSoft, RoundedCornerShape(10.dp))
                            .padding(horizontal = 10.dp, vertical = 2.dp),
                        size = 13.sp,
                        color = Ink.Gold,
                    )
                    Spacer(Modifier.height(6.dp))
                    LabeledLine("宜", l.yi.take(5).joinToString(" ").ifBlank { "—" })
                    LabeledLine("忌", l.ji.take(5).joinToString(" ").ifBlank { "—" })
                }
            }
        }
        GlassCard(1f, weatherCard, onWeather) {
            val w = home.weather
            if (w == null) {
                Column(Modifier.fillMaxHeight(), verticalArrangement = Arrangement.Center) {
                    GText(home.city.name, size = 14.sp, color = Ink.Tertiary)
                    Spacer(Modifier.height(4.dp))
                    GText(if (home.online) "天氣載入中…" else "目前離線", size = 18.sp)
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxHeight()) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        LIcon(LIcons.weather(w.kind, w.isDay), 30.dp)
                        Spacer(Modifier.height(2.dp))
                        GText("${w.temp.roundToInt()}°", size = 46.sp, weight = FontWeight.ExtraLight)
                    }
                    Spacer(Modifier.width(18.dp))
                    Column {
                        GText(w.kind.label, size = 17.sp, weight = FontWeight.Medium)
                        val t = w.today
                        if (t != null) {
                            GText(
                                "${t.max.roundToInt()}° / ${t.min.roundToInt()}°　降雨 ${t.rainChance}%",
                                size = 13.sp,
                                color = Ink.Secondary,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            w.days.drop(1).take(4).forEach { d ->
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    GText(d.date.format(DateTimeFormatter.ofPattern("E", zh)).removePrefix("週"), size = 12.sp, color = Ink.Tertiary)
                                    LIcon(LIcons.weather(d.kind), 16.dp, Ink.Secondary)
                                    GText("${d.max.roundToInt()}°", size = 12.sp, color = Ink.Secondary)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LabeledLine(label: String, text: String) {
    Row(Modifier.padding(top = 2.dp)) {
        GText(label, size = 13.sp, color = Ink.Tertiary)
        Spacer(Modifier.width(8.dp))
        GText(text, size = 13.sp, color = Ink.Secondary)
    }
}

