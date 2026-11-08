package tw.liuli.launcher.ui.weather

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
import androidx.compose.runtime.remember
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tw.liuli.launcher.context.DayForecast
import tw.liuli.launcher.context.HomeContext
import tw.liuli.launcher.context.Weather
import tw.liuli.launcher.ui.theme.Dimens
import tw.liuli.launcher.ui.theme.GText
import tw.liuli.launcher.ui.theme.Ink
import tw.liuli.launcher.ui.theme.LIcon
import tw.liuli.launcher.ui.theme.LIcons
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

private val zh = Locale.TAIWAN
private val Rain = Color(0xFF9FD4FF)

/** 天氣：直接放在模糊的桌布上，不加框。左邊現在＋逐 3 小時，右邊 7 日（溫度區間條）。 */
@Composable
fun WeatherScreen(home: HomeContext) {
    val root = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { root.requestFocus() } }
    val w = home.weather

    Box(
        Modifier
            .fillMaxSize()
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
        Column(Modifier.align(Alignment.TopStart)) {
            GText(home.city.name, size = 18.sp, color = Ink.Secondary, shadow = true)
            Row(verticalAlignment = Alignment.CenterVertically) {
                LIcon(LIcons.weather(w.kind, w.isDay), 70.dp)
                Spacer(Modifier.width(22.dp))
                GText("${w.temp.roundToInt()}°", size = 110.sp, weight = FontWeight.Thin, shadow = true)
                Spacer(Modifier.width(22.dp))
                Column {
                    GText(w.kind.label, size = 26.sp, weight = FontWeight.Medium)
                    w.today?.let { GText("最高 ${it.max.roundToInt()}°　最低 ${it.min.roundToInt()}°", size = 15.sp, color = Ink.Secondary) }
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(34.dp)) {
                Stat("體感", "${w.feelsLike.roundToInt()}°")
                Stat("濕度", "${w.humidity}%")
                Stat("風速", "${w.windSpeed.roundToInt()} km/h")
                w.today?.let { Stat("降雨機率", "${it.rainChance}%") }
                w.sunrise?.let { Stat("日出", it.format(DateTimeFormatter.ofPattern("HH:mm"))) }
                w.sunset?.let { Stat("日落", it.format(DateTimeFormatter.ofPattern("HH:mm"))) }
            }
            Spacer(Modifier.height(28.dp))
            Hourly(w)
        }
        Daily(w.days, Modifier.align(Alignment.TopEnd).width(330.dp))
        GText("返回鍵回主畫面", Modifier.align(Alignment.BottomStart), size = 12.sp, color = Ink.Faint)
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column {
        GText(label, size = 12.sp, color = Ink.Tertiary)
        GText(value, size = 17.sp)
    }
}

@Composable
private fun Hourly(w: Weather) {
    Column(Modifier.width(520.dp)) {
        Box(Modifier.fillMaxWidth().height(1.dp).drawBehind { drawRect(Color.White.copy(alpha = 0.14f)) })
        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            w.hours.forEachIndexed { i, h ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    GText(if (i == 0) "現在" else h.time.format(DateTimeFormatter.ofPattern("H時")), size = 12.sp, color = Ink.Tertiary)
                    Spacer(Modifier.height(6.dp))
                    LIcon(LIcons.weather(h.kind, h.isDay), 24.dp)
                    Spacer(Modifier.height(6.dp))
                    GText("${h.temp.roundToInt()}°", size = 15.sp)
                    GText(if (h.rainChance >= 20) "${h.rainChance}%" else " ", size = 11.sp, color = Rain)
                }
            }
        }
    }
}

@Composable
private fun Daily(days: List<DayForecast>, modifier: Modifier) {
    val lo = days.minOfOrNull { it.min } ?: 0.0
    val hi = days.maxOfOrNull { it.max } ?: 1.0
    Column(modifier) {
        GText("7 日預報", size = 13.sp, color = Ink.Tertiary)
        Spacer(Modifier.height(8.dp))
        days.forEachIndexed { i, d ->
            Box(Modifier.fillMaxWidth().height(1.dp).drawBehind { drawRect(Color.White.copy(alpha = 0.08f)) })
            Row(Modifier.height(50.dp), verticalAlignment = Alignment.CenterVertically) {
                GText(if (i == 0) "今天" else d.date.format(DateTimeFormatter.ofPattern("E", zh)), Modifier.width(48.dp), size = 15.sp)
                LIcon(LIcons.weather(d.kind), 22.dp)
                GText(if (d.rainChance >= 20) "${d.rainChance}%" else "", Modifier.width(44.dp).padding(start = 6.dp), size = 12.sp, color = Rain)
                GText("${d.min.roundToInt()}°", Modifier.width(36.dp), size = 15.sp, color = Ink.Tertiary)
                RangeBar(d.min, d.max, lo, hi, Modifier.weight(1f).height(6.dp))
                GText("${d.max.roundToInt()}°", Modifier.padding(start = 10.dp).width(36.dp), size = 15.sp)
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
