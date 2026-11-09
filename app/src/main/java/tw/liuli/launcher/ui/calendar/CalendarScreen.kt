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
import androidx.compose.runtime.mutableStateMapOf
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
fun CalendarScreen(now: java.time.LocalDateTime, initial: LocalDate = now.toLocalDate(), active: Boolean = true) {
    val today = now.toLocalDate()
    var sel by remember(initial) { mutableStateOf(initial) }
    val month = YearMonth.from(sel)
    val root = remember { FocusRequester() }
    // 頁面是預先組合好的：每次打開都回到指定的日子（預設今天），再拿焦點
    LaunchedEffect(active, initial) {
        if (active) {
            sel = initial
            kotlinx.coroutines.delay(16)
            runCatching { root.requestFocus() }
        }
    }
    // 農民曆計算比較慢（宜忌、吉時…）：日期格子先畫，算好的日子放進快取再補上。
    // 這個月算完接著預先算上個月、下個月，翻月時通常已經好了。
    val cache = remember { mutableStateMapOf<LocalDate, DayInfo>() }
    LaunchedEffect(month) {
        for (m in listOf(month, month.plusMonths(1), month.minusMonths(1))) {
            val missing = (1..m.lengthOfMonth()).map { m.atDay(it) }.filter { it !in cache }
            if (missing.isEmpty()) continue
            val computed = withContext(Dispatchers.Default) { missing.map { dayInfo(it) } }
            computed.forEach { cache[it.date] = it }
        }
    }

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
        Column(Modifier.weight(1.4f).fillMaxHeight()) {
            AnimatedContent(
                targetState = month,
                transitionSpec = {
                    val down = targetState > initialState
                    (slideInVertically(tween(380)) { h -> if (down) h / 14 else -h / 14 } + fadeIn(tween(300))) togetherWith fadeOut(tween(150))
                },
                label = "month",
            ) { m ->
                MonthGrid(m, sel, today, cache)
            }
        }
        Column(Modifier.weight(1f).fillMaxHeight()) {
            DayDetail(sel, today, now, cache)
        }
    }
}

private fun dayInfo(date: LocalDate): DayInfo {
    val lunar = LunarCalc.of(date)
    return DayInfo(date, lunar, Festivals.of(date, lunar))
}

@Composable
private fun MonthGrid(month: YearMonth, sel: LocalDate, today: LocalDate, cache: Map<LocalDate, DayInfo>) {
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.Bottom) {
            GText("${month.year}年${month.monthValue}月", size = 34.sp, weight = FontWeight.Light, shadow = true)
            Spacer(Modifier.width(14.dp))
            val first = cache[month.atDay(1)]?.lunar
            val last = cache[month.atEndOfMonth()]?.lunar
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
        // 日期格子立刻畫（不等農民曆），農曆小字算好再淡入
        val lead = month.atDay(1).dayOfWeek.value % 7 // 星期日為一週第一天
        val cells: List<LocalDate?> = List(lead) { null } + (1..month.lengthOfMonth()).map { month.atDay(it) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            cells.chunked(7).forEach { week ->
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (i in 0 until 7) {
                        Box(Modifier.weight(1f).fillMaxHeight()) {
                            val date = week.getOrNull(i)
                            if (date != null) DayCell(date, cache[date], date == sel, date == today)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DayCell(date: LocalDate, info: DayInfo?, selected: Boolean, isToday: Boolean) {
    val v by animateFloatAsState(if (selected) 1f else 0f, tween(160), label = "cell")
    val labelAlpha by animateFloatAsState(if (info != null) 1f else 0f, tween(220), label = "lunar")
    val weekend = date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY
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
            date.dayOfMonth.toString(),
            size = 20.sp,
            weight = if (isToday) FontWeight.Medium else FontWeight.Normal,
            color = when {
                selected -> Ink.OnLight
                weekend -> Ink.Secondary
                else -> Ink.Primary
            },
        )
        // 農曆小字：還沒算好時留著同樣的高度（格子不跳動），算好後淡入
        Row(Modifier.graphicsLayer { alpha = labelAlpha }, verticalAlignment = Alignment.CenterVertically) {
            if (info == null) {
                GText(" ", size = 11.sp)
                return@Row
            }
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
private fun ColumnScope.DayDetail(date: LocalDate, today: LocalDate, now: java.time.LocalDateTime, cache: MutableMap<LocalDate, DayInfo>) {
    // 日期大字立刻換；農民曆不在主執行緒算（以前每移動一天都在主執行緒算，按住方向鍵會頓）。
    // 還沒算好時先留著上一天的內容（變淡），算好再換上，版面不會忽大忽小。
    val info = cache[date]
    LaunchedEffect(date) {
        if (date !in cache) cache[date] = withContext(Dispatchers.Default) { dayInfo(date) }
    }
    var last by remember { mutableStateOf(info) }
    if (info != null) last = info
    GText(date.dayOfMonth.toString(), size = 60.sp, weight = FontWeight.ExtraLight, shadow = true)
    GText(date.format(DateTimeFormatter.ofPattern("yyyy年M月 EEEE", zh)), size = 15.sp, color = Ink.Secondary)
    Spacer(Modifier.height(14.dp))
    val shown = last ?: return
    val stale = info == null
    Column(Modifier.weight(1f).graphicsLayer { alpha = if (stale) 0.35f else 1f }) {
        LunarDetail(shown, date, today, now)
    }
    GText("↑↓←→ 選日期，越過邊界自動翻月　返回鍵回主畫面", size = 12.sp, color = Ink.Faint)
}

@Composable
private fun ColumnScope.LunarDetail(shown: DayInfo, date: LocalDate, today: LocalDate, now: java.time.LocalDateTime) {
    val l = shown.lunar
    val fest = shown.festivals
    // monthText 已含「閏」（例如 閏六月），不要再另外加
    GText("農曆${l.monthText}${l.dayText}", size = 22.sp, weight = FontWeight.Medium)
    GText("${l.ganZhiYear}年・生肖${l.zodiac}", size = 14.sp, color = Ink.Tertiary)
    val isToday = date == today
    val tags = buildList {
        if (isToday) {
            add("今天")
            add("現在" + LunarCalc.shiChen(now.hour))
            // 23:00 起已進入子時：農曆算下一天
            if (now.hour >= 23) add("子時起農曆" + LunarCalc.of(date.plusDays(1)).dayText)
        }
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
    Spacer(Modifier.height(14.dp))
    // 宜忌字數差很多（1901～2099 最長：宜 31 項 95 字、宜＋忌合計 107 字）：依總字數選字級，詞不拆行
    val total = (l.yi + l.ji).sumOf { it.length + 1 }
    val termSize = when {
        total <= 50 -> 15.sp
        total <= 78 -> 14.sp
        total <= 100 -> 13.sp
        else -> 12.sp
    }
    Column(Modifier.weight(1f)) {
        TermField("宜", l.yi, termSize)
        TermField("忌", l.ji, termSize)
        Field("吉時", l.goodHours.joinToString("　").ifBlank { "—" })
        Field("沖煞", "${l.chong}　${l.sha}")
        Field("下個節氣", "${l.nextSolarTerm}　${l.nextSolarTermDate.monthValue}月${l.nextSolarTermDate.dayOfMonth}日")
    }
}

/** 宜／忌：一個詞一個單位自動換行，詞本身不會被拆成兩行。 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun TermField(title: String, terms: List<String>, size: androidx.compose.ui.unit.TextUnit) {
    Row(Modifier.padding(bottom = 8.dp)) {
        GText(title, Modifier.width(56.dp).padding(top = 2.dp), size = 13.sp, color = Ink.Tertiary)
        if (terms.isEmpty()) {
            GText("—", size = size)
        } else {
            androidx.compose.foundation.layout.FlowRow(
                Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                terms.forEach { GText(it, size = size) }
            }
        }
    }
}

@Composable
private fun Field(title: String, body: String) {
    Row(Modifier.padding(bottom = 8.dp)) {
        GText(title, Modifier.width(56.dp).padding(top = 2.dp), size = 13.sp, color = Ink.Tertiary)
        GText(body, Modifier.weight(1f), size = 15.sp, maxLines = 2)
    }
}
