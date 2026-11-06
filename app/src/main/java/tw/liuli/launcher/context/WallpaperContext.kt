package tw.liuli.launcher.context

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 主畫面的「情境」：時間、日夜、農民曆、節日、天氣、大約位置、裝置狀態。
 * 同一份資料同時用在主畫面卡片，以及帶進網頁桌布的網址參數（規格見 docs/wallpaper-api.md）。
 */
data class HomeContext(
    val now: LocalDateTime,
    val lunar: LunarInfo,
    val festivals: List<Festival>,
    val weather: Weather?,
    val city: City,
    val online: Boolean,
) {
    val isDaylight: Boolean
        get() {
            val rise = weather?.sunrise?.toLocalTime()
            val set = weather?.sunset?.toLocalTime()
            val t = now.toLocalTime()
            return if (rise != null && set != null) t >= rise && t < set else now.hour in 6..17
        }

    /** 0～1：日出到日落的進度；夜間為 -1。網頁可用來畫太陽位置與天空漸層。 */
    val sunProgress: Double
        get() {
            val rise = weather?.sunrise ?: now.toLocalDate().atTime(6, 0)
            val set = weather?.sunset ?: now.toLocalDate().atTime(18, 0)
            val total = java.time.Duration.between(rise, set).seconds.toDouble()
            val passed = java.time.Duration.between(rise, now).seconds.toDouble()
            return if (passed < 0 || passed > total || total <= 0) -1.0 else passed / total
        }

    val period: String
        get() = when (now.hour) {
            in 0..3 -> "midnight"
            in 4..5 -> "dawn"
            in 6..10 -> "morning"
            in 11..12 -> "noon"
            in 13..16 -> "afternoon"
            in 17..18 -> "dusk"
            else -> "night"
        }

    /** 主畫面卡片用：今天最值得提的一件事。 */
    val highlight: String?
        get() = when {
            festivals.isNotEmpty() -> festivals.joinToString("・") { it.name }
            lunar.isBaiBai -> "今日${if (lunar.day == 1) "初一" else "十五"}・拜拜"
            lunar.solarTerm != null -> "今日${lunar.solarTerm}"
            else -> null
        }
}

object ContextParams {

    const val API_VERSION = "1"
    private val hm = DateTimeFormatter.ofPattern("HH:mm")

    /** 轉成網址參數（全部是字串）。鍵名一旦公開就不改名，只新增。 */
    fun toParams(
        ctx: HomeContext,
        screenW: Int,
        screenH: Int,
        glass: String,
        mode: String = "home",
    ): LinkedHashMap<String, String> {
        val p = LinkedHashMap<String, String>()
        val n = ctx.now
        p["v"] = API_VERSION
        p["mode"] = mode

        // 時間
        p["ts"] = n.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli().toString()
        p["tz"] = ZoneId.systemDefault().id
        p["date"] = n.toLocalDate().toString()
        p["year"] = n.year.toString()
        p["month"] = n.monthValue.toString()
        p["day"] = n.dayOfMonth.toString()
        p["hour"] = n.hour.toString()
        p["minute"] = n.minute.toString()
        p["weekday"] = n.dayOfWeek.value.toString() // 1＝星期一 … 7＝星期日
        p["weekend"] = flag(n.dayOfWeek == DayOfWeek.SATURDAY || n.dayOfWeek == DayOfWeek.SUNDAY)
        p["period"] = ctx.period
        p["daylight"] = flag(ctx.isDaylight)
        p["theme"] = if (ctx.isDaylight) "light" else "dark"
        p["sun"] = "%.3f".format(Locale.US, ctx.sunProgress)
        ctx.weather?.sunrise?.let { p["sunrise"] = it.format(hm) }
        ctx.weather?.sunset?.let { p["sunset"] = it.format(hm) }
        p["season"] = ctx.lunar.season

        // 農民曆
        val l = ctx.lunar
        p["lyear"] = l.ganZhiYear
        p["zodiac"] = l.zodiac
        p["lmonth"] = l.month.toString()
        p["lday"] = l.day.toString()
        p["lleap"] = flag(l.isLeapMonth)
        p["ltext"] = "${l.monthText}${l.dayText}"
        p["bai"] = flag(l.isBaiBai)
        p["zuoya"] = flag(l.isZuoYa)
        p["moon"] = "%.3f".format(Locale.US, l.moonPhase)
        l.solarTerm?.let { p["term"] = it }
        p["nextterm"] = l.nextSolarTerm
        p["nexttermdate"] = l.nextSolarTermDate.toString()
        p["yi"] = l.yi.take(8).joinToString(",")
        p["ji"] = l.ji.take(8).joinToString(",")
        p["chong"] = l.chong
        p["sha"] = l.sha
        p["ghostmonth"] = flag(l.month == 7 && !l.isLeapMonth)

        // 節日
        p["festival"] = ctx.festivals.joinToString(",") { it.id }
        p["festivalname"] = ctx.festivals.joinToString(",") { it.name }
        p["holiday"] = flag(ctx.festivals.isNotEmpty())

        // 天氣
        val w = ctx.weather
        if (w != null) {
            p["wx"] = w.kind
            p["wxcode"] = w.code.toString()
            p["wxtext"] = w.label
            p["temp"] = w.temp.roundToInt().toString()
            p["feels"] = w.feelsLike.roundToInt().toString()
            p["humidity"] = w.humidity.toString()
            p["rain"] = "%.1f".format(Locale.US, w.precipitation)
            p["pop"] = w.rainChance.toString()
            p["cloud"] = w.cloudCover.toString()
            p["wind"] = w.windSpeed.roundToInt().toString()
            if (!w.tempMax.isNaN()) p["tmax"] = w.tempMax.roundToInt().toString()
            if (!w.tempMin.isNaN()) p["tmin"] = w.tempMin.roundToInt().toString()
        } else {
            p["wx"] = "unknown"
        }

        // 大約位置（縣市中心，非精確定位）
        p["city"] = ctx.city.name
        p["cityid"] = ctx.city.id
        p["lat"] = "%.2f".format(Locale.US, ctx.city.lat)
        p["lon"] = "%.2f".format(Locale.US, ctx.city.lon)

        // 裝置
        p["w"] = screenW.toString()
        p["h"] = screenH.toString()
        p["glass"] = glass
        p["motion"] = if (glass == "low") "reduce" else "full"
        p["online"] = flag(ctx.online)
        p["lang"] = "zh-TW"
        return p
    }

    fun toJson(params: Map<String, String>): String = JSONObject(params as Map<*, *>).toString()

    private fun flag(b: Boolean) = if (b) "1" else "0"
}

/**
 * 每分鐘更新時間與農曆、每 30 分鐘更新天氣（失敗時 5 分鐘後重試，沿用上次資料）。
 */
@Composable
fun rememberHomeContext(city: City): State<HomeContext> {
    val context = LocalContext.current
    var weather by remember(city) { mutableStateOf<Weather?>(null) }

    LaunchedEffect(city) {
        while (true) {
            val fresh = WeatherRepository.fetch(city)
            if (fresh != null) weather = fresh
            delay(if (fresh != null) 30 * 60_000L else 5 * 60_000L)
        }
    }

    // 農曆一天算一次就好（不是 Compose state，避免在組合期間寫入狀態）
    val cache = remember { LunarCache() }
    fun lunarFor(date: LocalDate): Pair<LunarInfo, List<Festival>> = cache.get(date)

    return produceState(
        initialValue = build(LocalDateTime.now(), city, weather, isOnline(context), ::lunarFor),
        city, weather,
    ) {
        while (true) {
            value = build(LocalDateTime.now(), city, weather, isOnline(context), ::lunarFor)
            val nowMs = System.currentTimeMillis()
            delay(60_000L - nowMs % 60_000L + 50)
        }
    }
}

private class LunarCache {
    private var date: LocalDate? = null
    private var value: Pair<LunarInfo, List<Festival>>? = null

    fun get(d: LocalDate): Pair<LunarInfo, List<Festival>> {
        val cached = value
        if (cached != null && d == date) return cached
        val info = LunarCalc.of(d)
        return (info to Festivals.of(d, info)).also { date = d; value = it }
    }
}

private fun build(
    now: LocalDateTime,
    city: City,
    weather: Weather?,
    online: Boolean,
    lunarFor: (LocalDate) -> Pair<LunarInfo, List<Festival>>,
): HomeContext {
    val (lunar, festivals) = lunarFor(now.toLocalDate())
    return HomeContext(now, lunar, festivals, weather, city, online)
}

private fun isOnline(context: Context): Boolean {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
    val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}
