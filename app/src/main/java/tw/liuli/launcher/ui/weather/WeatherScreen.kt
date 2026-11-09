package tw.liuli.launcher.ui.weather

import android.view.KeyEvent
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tw.liuli.launcher.context.DayForecast
import tw.liuli.launcher.context.HomeContext
import tw.liuli.launcher.context.Slot
import tw.liuli.launcher.context.Weather
import tw.liuli.launcher.context.uvLevel
import tw.liuli.launcher.context.windName
import tw.liuli.launcher.ui.theme.Dimens
import tw.liuli.launcher.ui.theme.GText
import tw.liuli.launcher.ui.theme.Ink
import tw.liuli.launcher.ui.theme.LIcon
import tw.liuli.launcher.ui.theme.LIcons
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

private val zh = Locale.TAIWAN
private val Rain = Color(0xFF9FD4FF)

/**
 * 天氣：直接放在模糊的桌布上，不加框。
 *
 * 遙控器：上下鍵切換日期（今天～7 天後），左右鍵選擇時段（每 3 小時一段），
 * 左邊顯示選中時段的詳細天氣；右邊 8 日列表標出目前選中的那一天。
 * 今天預設選「現在」所在的時段，切到其他天時保持同一個時段（例如都看下午 3 點）。
 */
@Composable
fun WeatherScreen(home: HomeContext, active: Boolean = true) {
    val root = remember { FocusRequester() }

    val w = home.weather
    val nowSlot = LocalDateTime.now().hour / 3
    var day by remember { mutableIntStateOf(0) }
    var slot by remember { mutableIntStateOf(nowSlot) }
    // 頁面是預先組合好的：每次打開都回到「今天、現在」，再拿焦點
    LaunchedEffect(active) {
        if (active) {
            day = 0; slot = LocalDateTime.now().hour / 3
            kotlinx.coroutines.delay(16)
            runCatching { root.requestFocus() }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { e ->
                if (w == null || e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val last = (w.days.size - 1).coerceAtLeast(0)
                when (e.nativeKeyEvent.keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> { day = (day - 1).coerceAtLeast(0); true }
                    KeyEvent.KEYCODE_DPAD_DOWN -> { day = (day + 1).coerceAtMost(last); true }
                    KeyEvent.KEYCODE_DPAD_LEFT -> { slot = (slot - 1).coerceAtLeast(0); true }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> { slot = (slot + 1).coerceAtMost(7); true }
                    else -> false
                }
            }
            .focusRequester(root)
            .focusable()
            .padding(horizontal = Dimens.SafeH, vertical = 40.dp),
    ) {
        if (w == null) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                GText(home.city.name, size = 18.sp, color = Ink.Secondary)
                Spacer(Modifier.height(8.dp))
                GText(if (home.online) "天氣載入中…" else "目前離線，無法取得天氣", size = 24.sp)
            }
            return@Box
        }
        val d = w.days.getOrNull(day) ?: w.days.firstOrNull()
        val date = d?.date ?: LocalDate.now()
        val slots = remember(w, date) { w.slots(date) }
        val sel = slots.firstOrNull { it.start.time.hour / 3 == slot }
        Column(Modifier.align(Alignment.TopStart).width(500.dp)) {
            Detail(w, d, day, sel, isNow = day == 0 && slot == nowSlot, cityName = home.city.name)
            Spacer(Modifier.height(26.dp))
            Slots(slots, slot, if (day == 0) nowSlot else -1)
        }
        Daily(w.days, day, Modifier.align(Alignment.TopEnd).width(330.dp))
        GText("↑↓ 切換日期　←→ 選擇時段　返回鍵回主畫面", Modifier.align(Alignment.BottomStart), size = 12.sp, color = Ink.Faint)
    }
}

private fun dayName(i: Int, date: LocalDate): String = when (i) {
    0 -> "今天"
    1 -> "明天"
    2 -> "後天"
    else -> date.format(DateTimeFormatter.ofPattern("EEEE", zh))
}

/** 選中時段的詳細天氣。今天「現在」這一段用即時觀測（溫度、體感、濕度、風速），其他用逐時預報。 */
@Composable
private fun Detail(w: Weather, d: DayForecast?, dayIndex: Int, s: Slot?, isNow: Boolean, cityName: String) {
    val date = d?.date ?: LocalDate.now()
    val h = s?.start
    val hh = h?.time?.hour ?: 0
    GText(
        "$cityName・${dayName(dayIndex, date)}　${date.format(DateTimeFormatter.ofPattern("M月d日（E）", zh))}",
        size = 18.sp, color = Ink.Secondary, shadow = true,
    )
    if (h == null) {
        Spacer(Modifier.height(16.dp))
        GText("這個時段沒有預報資料", size = 20.sp, color = Ink.Secondary)
        return
    }
    val temp = if (isNow) w.temp else h.temp
    val feels = if (isNow) w.feelsLike else h.feelsLike
    val humidity = if (isNow) w.humidity else h.humidity
    val wind = if (isNow) w.windSpeed else h.windSpeed
    val kind = if (isNow) w.kind else h.kind
    val isDay = if (isNow) w.isDay else h.isDay
    Row(verticalAlignment = Alignment.CenterVertically) {
        LIcon(LIcons.weather(kind, isDay), 70.dp)
        Spacer(Modifier.width(22.dp))
        GText("${temp.roundToInt()}°", size = 110.sp, weight = FontWeight.Thin, shadow = true)
        Spacer(Modifier.width(22.dp))
        Column {
            GText(kind.label, size = 26.sp, weight = FontWeight.Medium)
            GText(
                if (isNow) "現在" else "%d:00 – %d:00".format(hh, (hh + 3).coerceAtMost(24)),
                size = 15.sp, color = Ink.Secondary,
            )
            d?.let { GText("全天 ${it.max.roundToInt()}° / ${it.min.roundToInt()}°", size = 15.sp, color = Ink.Tertiary) }
        }
    }
    Spacer(Modifier.height(10.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
        Stat("體感", "${feels.roundToInt()}°")
        Stat("濕度", "$humidity%")
        Stat(windName(h.windDir), "${wind.roundToInt()} km/h")
        Stat("降雨機率", "${s.rainChance}%")
        Stat("雨量", if (s.precip < 0.05) "0 mm" else "%.1f mm".format(s.precip))
        Stat("紫外線", "${h.uv.roundToInt()}・${uvLevel(h.uv)}")
    }
    Spacer(Modifier.height(10.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
        d?.sunrise?.let { Stat("日出", it.format(DateTimeFormatter.ofPattern("HH:mm"))) }
        d?.sunset?.let { Stat("日落", it.format(DateTimeFormatter.ofPattern("HH:mm"))) }
        d?.let { Stat("全天雨量", if (it.precipSum < 0.05) "0 mm" else "%.1f mm".format(it.precipSum)) }
        d?.let { Stat("紫外線最高", "${it.uvMax.roundToInt()}・${uvLevel(it.uvMax)}") }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column {
        GText(label, size = 12.sp, color = Ink.Tertiary)
        GText(value, size = 17.sp)
    }
}

/** 這一天的 8 個時段；選中的那一段是白色圓角底（和設定面板的焦點一樣），今天已經過去的時段變淡。 */
@Composable
private fun Slots(slots: List<Slot>, selected: Int, nowSlot: Int) {
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(1.dp).drawBehind { drawRect(Color.White.copy(alpha = 0.14f)) })
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            for (k in 0 until 8) {
                val s = slots.firstOrNull { it.start.time.hour / 3 == k }
                val on = k == selected
                val past = nowSlot >= 0 && k < nowSlot
                val ink = if (on) Ink.OnLight else if (past) Ink.Faint else Ink.Primary
                val sub = if (on) Ink.OnLight.copy(alpha = 0.6f) else Ink.Tertiary
                Column(
                    Modifier
                        .width(58.dp)
                        .drawBehind {
                            if (on) drawRoundRect(Color.White.copy(alpha = 0.94f), cornerRadius = CornerRadius(14.dp.toPx()))
                        }
                        .padding(vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    GText(if (k == nowSlot) "現在" else "${k * 3}時", size = 12.sp, color = sub)
                    Spacer(Modifier.height(6.dp))
                    if (s != null) {
                        LIcon(LIcons.weather(s.start.kind, s.start.isDay), 24.dp, tint = if (on) Ink.OnLight else Color.White.copy(alpha = if (past) 0.45f else 1f))
                        Spacer(Modifier.height(6.dp))
                        GText("${s.start.temp.roundToInt()}°", size = 15.sp, color = ink)
                        GText(if (s.rainChance >= 20) "${s.rainChance}%" else " ", size = 11.sp, color = if (on) Ink.OnLight else Rain)
                    } else {
                        GText("—", size = 15.sp, color = sub)
                    }
                }
            }
        }
    }
}

/** 右邊 8 日預報（今天＋往後 7 天）；選中的那一天有淡白底與左側小標記。 */
@Composable
private fun Daily(days: List<DayForecast>, selected: Int, modifier: Modifier) {
    val lo = days.minOfOrNull { it.min } ?: 0.0
    val hi = days.maxOfOrNull { it.max } ?: 1.0
    Column(modifier) {
        GText("${days.size} 日預報", size = 13.sp, color = Ink.Tertiary)
        Spacer(Modifier.height(8.dp))
        days.forEachIndexed { i, d ->
            val on = i == selected
            Row(
                Modifier
                    .height(48.dp)
                    .drawBehind {
                        if (on) {
                            drawRoundRect(Color.White.copy(alpha = 0.16f), cornerRadius = CornerRadius(12.dp.toPx()))
                            drawRoundRect(
                                Color.White, topLeft = Offset(0f, size.height * 0.3f),
                                size = Size(3.dp.toPx(), size.height * 0.4f), cornerRadius = CornerRadius(2.dp.toPx()),
                            )
                        }
                    }
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.width(58.dp)) {
                    GText(
                        if (i < 3) dayName(i, d.date) else d.date.format(DateTimeFormatter.ofPattern("E", zh)),
                        size = 15.sp, weight = if (on) FontWeight.Medium else FontWeight.Normal,
                    )
                    GText(d.date.format(DateTimeFormatter.ofPattern("M/d")), size = 10.sp, color = Ink.Tertiary)
                }
                LIcon(LIcons.weather(d.kind), 22.dp)
                GText(if (d.rainChance >= 20) "${d.rainChance}%" else "", Modifier.width(44.dp).padding(start = 6.dp), size = 12.sp, color = Rain)
                GText("${d.min.roundToInt()}°", Modifier.width(34.dp), size = 15.sp, color = Ink.Tertiary)
                RangeBar(d.min, d.max, lo, hi, Modifier.weight(1f).height(6.dp))
                GText("${d.max.roundToInt()}°", Modifier.padding(start = 10.dp).width(34.dp), size = 15.sp)
            }
        }
    }
}

/** 溫度區間條：整週最低到最高為全長，當天區間用冷到暖的漸層。 */
@Composable
private fun RangeBar(min: Double, max: Double, lo: Double, hi: Double, modifier: Modifier) {
    Box(
        modifier.drawBehind {
            val span = (hi - lo).coerceAtLeast(1.0)
            val r = CornerRadius(size.height / 2)
            drawRoundRect(Color.White.copy(alpha = 0.14f), cornerRadius = r)
            val start = ((min - lo) / span * size.width).toFloat()
            val end = ((max - lo) / span * size.width).toFloat().coerceAtLeast(start + size.height)
            drawRoundRect(
                Brush.horizontalGradient(listOf(Color(0xFF8FD3FF), Color(0xFFFFD27A)), startX = 0f, endX = size.width),
                topLeft = Offset(start, 0f),
                size = Size(end - start, size.height),
                cornerRadius = r,
            )
        },
    )
}
