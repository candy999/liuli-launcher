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
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 主畫面的「情境」：時間、日夜、農民曆、節日、天氣、大約位置。
 * 每分鐘更新一次（時鐘自己另外更新），農曆一天只算一次。
 */
/** 網路狀態。 */
enum class NetworkStatus { WIFI, ETHERNET, NONE }

data class HomeContext(
    val now: LocalDateTime,
    val lunar: LunarInfo,
    val festivals: List<Festival>,
    val weather: Weather?,
    val city: City,
    val network: NetworkStatus,
) {
    val online: Boolean get() = network != NetworkStatus.NONE
    val isDaylight: Boolean
        get() {
            val rise = weather?.sunrise?.toLocalTime()
            val set = weather?.sunset?.toLocalTime()
            val t = now.toLocalTime()
            return if (rise != null && set != null) t >= rise && t < set else now.hour in 6..17
        }

    /** 0～1：日出到日落的進度；夜間為 -1。用來決定自動桌布配色。 */
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
    fun lunarFor(now: LocalDateTime): Pair<LunarInfo, List<Festival>> = cache.get(now)

    return produceState(
        initialValue = build(LocalDateTime.now(), city, weather, networkOf(context), ::lunarFor),
        city, weather,
    ) {
        while (true) {
            value = build(LocalDateTime.now(), city, weather, networkOf(context), ::lunarFor)
            val nowMs = System.currentTimeMillis()
            delay(60_000L - nowMs % 60_000L + 50)
        }
    }
}

private class LunarCache {
    private var key: Pair<LocalDate, LocalDate>? = null
    private var value: Pair<LunarInfo, List<Festival>>? = null

    /** 農曆依子時換日（23:00 起算下一天），國曆節日依國曆日期。 */
    fun get(now: LocalDateTime): Pair<LunarInfo, List<Festival>> {
        val solar = now.toLocalDate()
        val k = solar to LunarCalc.lunarDateOf(now)
        val cached = value
        if (cached != null && k == key) return cached
        val info = LunarCalc.of(k.second)
        return (info to Festivals.of(solar, info)).also { key = k; value = it }
    }
}

private fun build(
    now: LocalDateTime,
    city: City,
    weather: Weather?,
    network: NetworkStatus,
    lunarFor: (LocalDateTime) -> Pair<LunarInfo, List<Festival>>,
): HomeContext {
    val (lunar, festivals) = lunarFor(now)
    return HomeContext(now, lunar, festivals, weather, city, network)
}

private fun networkOf(context: Context): NetworkStatus {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return NetworkStatus.NONE
    val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return NetworkStatus.NONE
    if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return NetworkStatus.NONE
    return when {
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkStatus.ETHERNET
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkStatus.WIFI
        else -> NetworkStatus.WIFI // 其他（如 USB 網卡）當作有連線
    }
}
