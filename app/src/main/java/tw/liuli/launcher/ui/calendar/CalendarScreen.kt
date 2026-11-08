package tw.liuli.launcher.ui.calendar

import android.view.KeyEvent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tw.liuli.launcher.context.Festival
import tw.liuli.launcher.context.Festivals
import tw.liuli.launcher.context.LunarCalc
import tw.liuli.launcher.context.LunarInfo
import tw.liuli.launcher.ui.theme.Dimens
import tw.liuli.launcher.ui.theme.GText
import tw.liuli.launcher.ui.theme.Ink
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

private data class DayInfo(val date: LocalDate, val lunar: LunarInfo, val festivals: List<Festival>) {
    /** 格子裡的小字：節日 > 節氣 > 初一顯示月份 > 農曆日。 */
    val cellLabel: String
        get() = festivals.firstOrNull()?.name ?: lunar.solarTerm ?: if (lunar.day == 1) lunar.monthText else lunar.dayText
    val isSpecial: Boolean get() = festivals.isNotEmpty() || lunar.solarTerm != null
}

private val zh = Locale.TAIWAN

/**
 * 月曆＋農民曆。直接放在模糊的桌布上，不加框。
 * 上下左右選日期；越過月初、月底自動翻到上一個或下一個月；頻道鍵也能換月。
 */
@Composable
fun CalendarScreen(today: LocalDate) {
    var sel by remember { mutableStateOf(today) }
    val month = YearMonth.from(sel)
    val root = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { root.requestFocus() } }

    Row(
        Modifier
            .fillMaxSize()
            .focusRequester(root)
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val next = when (e.nativeKeyEvent.keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> sel.minusDays(1)
                    KeyEvent.KEYCODE_DPAD_RIGHT -> sel.plusDays(1)
                    KeyEvent.KEYCODE_DPAD_UP -> sel.minusWeeks(1)
                    KeyEvent.KEYCODE_DPAD_DOWN -> sel.plusWeeks(1)
                    KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> sel.minusMonths(1)
                    KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> sel.plusMonths(1)
                    else -> null
                }
                if (next != null) { sel = next; true } else false
            }
            .focusable()
            .padding(horizontal = Dimens.SafeH, vertical = 36.dp),
        horizontalArrangement = Arrangement.spacedBy(44.dp),
    ) {
        Column(Modifier.weight(1.55f).fillMaxHeight()) {
            AnimatedContent(
                targetState = month,
                transitionSpec = {
                    val down = targetState > initialState
                    (slideInVertically(tween(380)) { h -> if (down) h / 14 else -h / 14 } + fadeIn(tween(300))) togetherWith fadeOut(tween(150))
                },
                label = "month",
            ) { m ->
                MonthGrid(m, sel, today)
            }
        }
        Column(Modifier.weight(1f).fillMaxHeight()) {
            DayDetail(sel, today)
        }
    }
}

@Composable
private fun MonthGrid(month: YearMonth, sel: LocalDate, today: LocalDate) {
    val days by produceState<List<DayInfo>?>(null, month) {
        value = withContext(Dispatchers.Default) {
            (1..month.lengthOfMonth()).map { d ->
                val date = month.atDay(d)
                val lunar = LunarCalc.of(date)
                DayInfo(date, lunar, Festivals.of(date, lunar))
            }
        }
    }
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.Bottom) {
            GText("${month.year}年${month.monthValue}月", size = 34.sp, weight = FontWeight.Light, shadow = true)
            Spacer(Modifier.width(14.dp))
            val first = days?.firstOrNull()?.lunar
            val last = days?.lastOrNull()?.lunar
            if (first != null && last != null) {
                GText(
                    "農曆${first.monthText}${first.dayText} － ${last.monthText}${last.dayText}・${first.ganZhiYear}${first.zodiac}年",
                    Modifier.padding(bottom = 6.dp), size = 14.sp, color = Ink.Tertiary,
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth()) {
            listOf("日", "一", "二", "三", "四", "五", "六").forEach {
                GText(it, Modifier.weight(1f), size = 12.sp, color = Ink.Tertiary, align = TextAlign.Center)
            }
        }
        Spacer(Modifier.height(6.dp))
        val list = days ?: return@Column
        val lead = month.atDay(1).dayOfWeek.value % 7 // 星期日為一週第一天
        val cells: List<DayInfo?> = List(lead) { null } + list
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            cells.chunked(7).forEach { week ->
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (i in 0 until 7) {
                        Box(Modifier.weight(1f).fillMaxHeight()) {
                            val info = week.getOrNull(i)
                            if (info != null) DayCell(info, info.date == sel, info.date == today)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DayCell(info: DayInfo, selected: Boolean, isToday: Boolean) {
    val v by animateFloatAsState(if (selected) 1f else 0f, tween(160), label = "cell")
    val weekend = info.date.dayOfWeek == DayOfWeek.SATURDAY || info.date.dayOfWeek == DayOfWeek.SUNDAY
    Column(
        Modifier
            .fillMaxSize()
            .graphicsLayer { scaleX = 1f + 0.08f * v; scaleY = 1f + 0.08f * v }
            .drawBehind {
                val r = CornerRadius(14.dp.toPx())
                if (v > 0.01f) drawRoundRect(Color.White.copy(alpha = 0.94f * v), cornerRadius = r)
                if (isToday) {
                    val w = 1.5.dp.toPx()
                    drawRoundRect(Ink.Gold, Offset(w / 2, w / 2), Size(size.width - w, size.height - w), r, style = Stroke(w))
                }
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        GText(
            info.date.dayOfMonth.toString(),
            size = 20.sp,
            weight = if (isToday) FontWeight.Medium else FontWeight.Normal,
            color = when {
                selected -> Ink.OnLight
                weekend -> Ink.Secondary
                else -> Ink.Primary
            },
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (info.lunar.isBaiBai) {
                Box(Modifier.padding(end = 3.dp).size(4.dp).background(if (selected) Ink.OnLight else Ink.Gold, CircleShape))
            }
            GText(
                info.cellLabel,
                size = 11.sp,
                color = when {
                    selected -> Ink.OnLight.copy(alpha = 0.7f)
                    info.isSpecial -> Ink.Gold
                    else -> Ink.Tertiary
                },
            )
        }
    }
}

@Composable
private fun ColumnScope.DayDetail(date: LocalDate, today: LocalDate) {
    val l = remember(date) { LunarCalc.of(date) }
    val fest = remember(date, l) { Festivals.of(date, l) }
    Row(verticalAlignment = Alignment.Bottom) {
        GText(date.dayOfMonth.toString(), size = 76.sp, weight = FontWeight.ExtraLight, shadow = true)
    }
    GText(date.format(DateTimeFormatter.ofPattern("yyyy年M月 EEEE", zh)), size = 15.sp, color = Ink.Secondary)
    Spacer(Modifier.height(18.dp))
    GText("農曆${if (l.isLeapMonth) "閏" else ""}${l.monthText}${l.dayText}", size = 22.sp, weight = FontWeight.Medium)
    GText("${l.ganZhiYear}年・生肖${l.zodiac}", size = 14.sp, color = Ink.Tertiary)
    val tags = buildList {
        if (date == today) add("今天")
        addAll(fest.map { it.name })
        l.solarTerm?.let { add(it) }
        if (l.isBaiBai) add(l.dayText + "拜拜")
        if (l.isZuoYa) add("做牙")
    }
    Spacer(Modifier.height(10.dp))
    Row(Modifier.height(24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        tags.take(4).forEach {
            GText(it, Modifier.background(Ink.GoldSoft, RoundedCornerShape(11.dp)).padding(horizontal = 11.dp, vertical = 2.dp), size = 13.sp, color = Ink.Gold)
        }
    }
    Spacer(Modifier.height(18.dp))
    Field("宜", l.yi.joinToString("　").ifBlank { "—" })
    Field("忌", l.ji.joinToString("　").ifBlank { "—" })
    Field("沖煞", "${l.chong}　${l.sha}")
    Field("下個節氣", "${l.nextSolarTerm}　${l.nextSolarTermDate.monthValue}月${l.nextSolarTermDate.dayOfMonth}日")
    Spacer(Modifier.weight(1f))
    GText("↑↓←→ 選日期，越過邊界自動翻月　返回鍵回主畫面", size = 12.sp, color = Ink.Faint)
}

@Composable
private fun Field(title: String, body: String) {
    Row(Modifier.padding(bottom = 10.dp)) {
        GText(title, Modifier.width(64.dp).padding(top = 2.dp), size = 13.sp, color = Ink.Tertiary)
        GText(body, Modifier.weight(1f), size = 15.sp, maxLines = 3)
    }
}
