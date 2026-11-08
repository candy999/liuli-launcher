package tw.liuli.launcher.ui.calendar

import android.view.KeyEvent
import androidx.compose.foundation.background
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import tw.liuli.launcher.context.Festival
import tw.liuli.launcher.context.Festivals
import tw.liuli.launcher.context.LunarCalc
import tw.liuli.launcher.context.LunarInfo
import tw.liuli.launcher.ui.components.focusScale
import tw.liuli.launcher.ui.components.focusTarget
import tw.liuli.launcher.ui.components.rememberFocusAnim
import tw.liuli.launcher.ui.glass.liquidGlass
import tw.liuli.launcher.ui.home.GlassButton
import tw.liuli.launcher.ui.theme.Dimens
import tw.liuli.launcher.ui.theme.GText
import tw.liuli.launcher.ui.theme.Ink
import tw.liuli.launcher.ui.theme.LIcon
import tw.liuli.launcher.ui.theme.LIcons
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

private data class DayInfo(val date: LocalDate, val lunar: LunarInfo, val festivals: List<Festival>) {
    /** 格子裡的小字：節日 > 節氣 > 初一顯示月份 > 農曆日。 */
    val cellLabel: String
        get() = festivals.firstOrNull()?.name
            ?: lunar.solarTerm
            ?: if (lunar.day == 1) lunar.monthText else lunar.dayText
    val isSpecial: Boolean get() = festivals.isNotEmpty() || lunar.solarTerm != null
}

/** 月曆＋農民曆。頻道鍵／上一頁、下一頁鍵也可以換月。 */
@Composable
fun CalendarScreen(today: LocalDate) {
    var month by remember { mutableStateOf(YearMonth.from(today)) }
    var selected by remember { mutableStateOf(today) }
    val days by produceState<List<DayInfo>?>(null, month) {
        value = null
        value = withContext(Dispatchers.Default) {
            (1..month.lengthOfMonth()).map { d ->
                val date = month.atDay(d)
                val lunar = LunarCalc.of(date)
                DayInfo(date, lunar, Festivals.of(date, lunar))
            }
        }
    }
    val anchor = remember { FocusRequester() }
    // 換月後焦點放在今天（若在這個月）或 1 號
    val anchorDate = if (YearMonth.from(today) == month) today else month.atDay(1)
    LaunchedEffect(days) {
        if (days != null) {
            delay(16)
            runCatching { anchor.requestFocus() }
        }
    }

    fun shift(delta: Long) {
        month = month.plusMonths(delta)
        selected = if (YearMonth.from(today) == month) today else month.atDay(1)
    }

    Row(
        Modifier
            .fillMaxSize()
            .padding(horizontal = Dimens.SafeH, vertical = Dimens.SafeV)
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.nativeKeyEvent.keyCode) {
                    KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_MEDIA_REWIND -> { shift(-1); true }
                    KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { shift(1); true }
                    else -> false
                }
            },
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        // ── 左：月檢視 ──
        Column(
            Modifier
                .weight(1.75f)
                .fillMaxHeight()
                .liquidGlass(RoundedCornerShape(30.dp))
                .padding(horizontal = 24.dp, vertical = 20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    GText("${month.year}年${month.monthValue}月", size = 28.sp, weight = FontWeight.Light)
                    val first = days?.firstOrNull()?.lunar
                    val last = days?.lastOrNull()?.lunar
                    if (first != null && last != null) {
                        GText(
                            "農曆 ${first.monthText}${first.dayText} － ${last.monthText}${last.dayText}・${first.ganZhiYear}${first.zodiac}年",
                            size = 13.sp,
                            color = Ink.Tertiary,
                        )
                    }
                }
                GlassButton(onClick = { shift(-1) }) { dark -> LIcon(LIcons.ChevronLeft, 22.dp, if (dark) Ink.OnLight else Ink.Primary) }
                Spacer(Modifier.width(10.dp))
                GlassButton(onClick = { month = YearMonth.from(today); selected = today }, shape = RoundedCornerShape(23.dp)) { dark ->
                    GText("今天", Modifier.padding(horizontal = 18.dp), size = 15.sp, color = if (dark) Ink.OnLight else Ink.Primary)
                }
                Spacer(Modifier.width(10.dp))
                GlassButton(onClick = { shift(1) }) { dark -> LIcon(LIcons.ChevronRight, 22.dp, if (dark) Ink.OnLight else Ink.Primary) }
            }
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth()) {
                listOf("日", "一", "二", "三", "四", "五", "六").forEach {
                    GText(it, Modifier.weight(1f), size = 13.sp, color = Ink.Tertiary, align = TextAlign.Center)
                }
            }
            Spacer(Modifier.height(6.dp))
            val list = days
            if (list != null) {
                // 以星期日為一週第一天
                val lead = month.atDay(1).dayOfWeek.value % 7
                val cells: List<DayInfo?> = List(lead) { null } + list
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    cells.chunked(7).forEach { week ->
                        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            for (i in 0 until 7) {
                                val info = week.getOrNull(i)
                                Box(Modifier.weight(1f).fillMaxHeight()) {
                                    if (info != null) {
                                        DayCell(
                                            info = info,
                                            isToday = info.date == today,
                                            requester = if (info.date == anchorDate) anchor else null,
                                            onFocused = { selected = info.date },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // ── 右：所選日期的農民曆 ──
        val info = days?.firstOrNull { it.date == selected }
        Column(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .liquidGlass(RoundedCornerShape(30.dp))
                .padding(horizontal = 26.dp, vertical = 24.dp),
        ) {
            if (info != null) DayDetail(info, info.date == today)
        }
    }
}

@Composable
private fun DayCell(info: DayInfo, isToday: Boolean, requester: FocusRequester?, onFocused: () -> Unit) {
    val focus = rememberFocusAnim()
    val weekend = info.date.dayOfWeek == DayOfWeek.SATURDAY || info.date.dayOfWeek == DayOfWeek.SUNDAY
    Column(
        Modifier
            .fillMaxSize()
            .focusScale(focus, 1.06f)
            .focusTarget(focus, requester, onFocused, onClick = onFocused)
            .drawBehind {
                val v = focus.value()
                val r = CornerRadius(14.dp.toPx())
                if (v > 0.01f) drawRoundRect(androidx.compose.ui.graphics.Color.White.copy(alpha = 0.94f * v), cornerRadius = r)
                if (isToday) {
                    val w = 1.5.dp.toPx()
                    drawRoundRect(
                        Ink.Gold,
                        topLeft = Offset(w / 2, w / 2),
                        size = Size(size.width - w, size.height - w),
                        cornerRadius = r,
                        style = Stroke(w),
                    )
                }
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        val dark = focus.focused
        GText(
            info.date.dayOfMonth.toString(),
            size = 20.sp,
            weight = if (isToday) FontWeight.Medium else FontWeight.Normal,
            color = when {
                dark -> Ink.OnLight
                weekend -> Ink.Secondary
                else -> Ink.Primary
            },
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (info.lunar.isBaiBai) {
                Box(
                    Modifier
                        .padding(end = 3.dp)
                        .width(4.dp)
                        .height(4.dp)
                        .background(if (dark) Ink.OnLight else Ink.Gold, RoundedCornerShape(2.dp)),
                )
            }
            GText(
                info.cellLabel,
                size = 11.sp,
                color = when {
                    dark -> Ink.OnLight.copy(alpha = 0.7f)
                    info.isSpecial -> Ink.Gold
                    else -> Ink.Tertiary
                },
            )
        }
    }
}

@Composable
private fun DayDetail(info: DayInfo, isToday: Boolean) {
    val l = info.lunar
    val zh = Locale.TAIWAN
    Row(verticalAlignment = Alignment.Bottom) {
        GText(info.date.dayOfMonth.toString(), size = 60.sp, weight = FontWeight.ExtraLight)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.padding(bottom = 10.dp)) {
            GText(info.date.format(DateTimeFormatter.ofPattern("yyyy年M月 EEEE", zh)), size = 15.sp, color = Ink.Secondary)
            if (isToday) GText("今天", size = 13.sp, color = Ink.Gold)
        }
    }
    Spacer(Modifier.height(6.dp))
    GText("農曆${if (l.isLeapMonth) "閏" else ""}${l.monthText}${l.dayText}", size = 20.sp, weight = FontWeight.Medium)
    GText("${l.ganZhiYear}年・生肖${l.zodiac}", size = 14.sp, color = Ink.Tertiary)

    val tags = buildList {
        addAll(info.festivals.map { it.name })
        l.solarTerm?.let { add(it) }
        if (l.isBaiBai) add(if (l.day == 1) "初一拜拜" else "十五拜拜")
        if (l.isZuoYa) add("做牙")
    }
    if (tags.isNotEmpty()) {
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            tags.take(3).forEach {
                GText(
                    it,
                    Modifier
                        .background(Ink.GoldSoft, RoundedCornerShape(10.dp))
                        .padding(horizontal = 10.dp, vertical = 3.dp),
                    size = 13.sp,
                    color = Ink.Gold,
                )
            }
        }
    }
    Spacer(Modifier.height(16.dp))
    Section("宜", l.yi.joinToString("　").ifBlank { "—" })
    Spacer(Modifier.height(12.dp))
    Section("忌", l.ji.joinToString("　").ifBlank { "—" })
    Spacer(Modifier.height(12.dp))
    Section("沖煞", "${l.chong}　${l.sha}")
    Spacer(Modifier.height(12.dp))
    Section("下個節氣", "${l.nextSolarTerm}　${l.nextSolarTermDate.monthValue}月${l.nextSolarTermDate.dayOfMonth}日")
}

@Composable
private fun Section(title: String, body: String) {
    GText(title, size = 13.sp, color = Ink.Tertiary)
    Spacer(Modifier.height(3.dp))
    GText(body, Modifier.fillMaxWidth(), size = 15.sp, color = Ink.Primary, maxLines = 4)
}
