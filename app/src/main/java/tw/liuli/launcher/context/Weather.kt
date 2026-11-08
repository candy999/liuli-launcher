package tw.liuli.launcher.context

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.LocalDateTime

/** 縣市（大約位置）。只用縣市中心座標，不取用裝置定位。 */
data class City(val id: String, val name: String, val lat: Double, val lon: Double)

object Cities {
    val all = listOf(
        City("taipei", "臺北市", 25.04, 121.56),
        City("newtaipei", "新北市", 25.01, 121.47),
        City("keelung", "基隆市", 25.13, 121.74),
        City("taoyuan", "桃園市", 24.99, 121.30),
        City("hsinchu", "新竹市", 24.80, 120.97),
        City("hsinchucounty", "新竹縣", 24.84, 121.01),
        City("miaoli", "苗栗縣", 24.56, 120.82),
        City("taichung", "臺中市", 24.15, 120.67),
        City("changhua", "彰化縣", 24.08, 120.54),
        City("nantou", "南投縣", 23.91, 120.68),
        City("yunlin", "雲林縣", 23.71, 120.43),
        City("chiayi", "嘉義市", 23.48, 120.45),
        City("chiayicounty", "嘉義縣", 23.46, 120.25),
        City("tainan", "臺南市", 22.99, 120.21),
        City("kaohsiung", "高雄市", 22.63, 120.30),
        City("pingtung", "屏東縣", 22.67, 120.49),
        City("yilan", "宜蘭縣", 24.76, 121.75),
        City("hualien", "花蓮縣", 23.99, 121.60),
        City("taitung", "臺東縣", 22.76, 121.14),
        City("penghu", "澎湖縣", 23.57, 119.58),
        City("kinmen", "金門縣", 24.43, 118.32),
        City("lienchiang", "連江縣", 26.16, 119.95),
    )

    fun byId(id: String?) = all.firstOrNull { it.id == id } ?: all.first()
}

/** 天氣大類，決定圖示。 */
enum class WeatherKind(val label: String) {
    CLEAR("晴"), PARTLY("晴時多雲"), CLOUDY("陰"), FOG("霧"),
    DRIZZLE("毛毛雨"), RAIN("雨"), SNOW("雪"), THUNDER("雷雨");

    companion object {
        /** WMO 天氣代碼 → 大類。 */
        fun of(code: Int): WeatherKind = when (code) {
            0 -> CLEAR
            1, 2 -> PARTLY
            3 -> CLOUDY
            45, 48 -> FOG
            in 51..57 -> DRIZZLE
            in 61..67, in 80..82 -> RAIN
            in 71..77, 85, 86 -> SNOW
            in 95..99 -> THUNDER
            else -> CLOUDY
        }
    }
}

data class HourForecast(val time: LocalDateTime, val temp: Double, val kind: WeatherKind, val rainChance: Int, val isDay: Boolean)

data class DayForecast(val date: LocalDate, val kind: WeatherKind, val max: Double, val min: Double, val rainChance: Int)

data class Weather(
    val kind: WeatherKind,
    val temp: Double,
    val feelsLike: Double,
    val humidity: Int,
    val windSpeed: Double,
    val isDay: Boolean,
    val sunrise: LocalDateTime?,
    val sunset: LocalDateTime?,
    /** 今天起 7 天，第一筆是今天。 */
    val days: List<DayForecast>,
    /** 從現在起每 3 小時一筆，共 8 筆（24 小時）。 */
    val hours: List<HourForecast>,
    val fetchedAt: Long,
) {
    val today: DayForecast? get() = days.firstOrNull()
}

/**
 * 天氣：Open-Meteo（免金鑰、免註冊）。
 */
object WeatherRepository {

    suspend fun fetch(city: City): Weather? = withContext(Dispatchers.IO) {
        val url = "https://api.open-meteo.com/v1/forecast" +
            "?latitude=${city.lat}&longitude=${city.lon}" +
            "&current=temperature_2m,relative_humidity_2m,apparent_temperature,is_day,weather_code,wind_speed_10m" +
            "&hourly=temperature_2m,weather_code,precipitation_probability,is_day" +
            "&daily=weather_code,sunrise,sunset,temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
            "&timezone=auto&forecast_days=7"
        runCatching {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
            }
            try {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                parse(JSONObject(body))
            } finally {
                conn.disconnect()
            }
        }.getOrNull()
    }

    private fun parse(json: JSONObject): Weather {
        val c = json.getJSONObject("current")
        val d = json.getJSONObject("daily")
        val h = json.getJSONObject("hourly")

        fun time(s: String?) = s?.takeIf { it.isNotBlank() }?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }

        val dayDates = d.getJSONArray("time")
        val days = (0 until dayDates.length()).map { i ->
            DayForecast(
                date = LocalDate.parse(dayDates.getString(i)),
                kind = WeatherKind.of(d.getJSONArray("weather_code").optInt(i)),
                max = d.getJSONArray("temperature_2m_max").optDouble(i),
                min = d.getJSONArray("temperature_2m_min").optDouble(i),
                rainChance = d.optJSONArray("precipitation_probability_max")?.optInt(i) ?: 0,
            )
        }

        val now = LocalDateTime.now()
        val hourTimes = h.getJSONArray("time")
        val hours = (0 until hourTimes.length())
            .mapNotNull { i ->
                val t = time(hourTimes.getString(i)) ?: return@mapNotNull null
                if (t.isBefore(now.minusHours(1))) return@mapNotNull null
                HourForecast(
                    time = t,
                    temp = h.getJSONArray("temperature_2m").optDouble(i),
                    kind = WeatherKind.of(h.getJSONArray("weather_code").optInt(i)),
                    rainChance = h.optJSONArray("precipitation_probability")?.optInt(i) ?: 0,
                    isDay = h.optJSONArray("is_day")?.optInt(i, 1) == 1,
                )
            }
            .filterIndexed { i, _ -> i % 3 == 0 }
            .take(8)

        return Weather(
            kind = WeatherKind.of(c.getInt("weather_code")),
            temp = c.optDouble("temperature_2m"),
            feelsLike = c.optDouble("apparent_temperature"),
            humidity = c.optInt("relative_humidity_2m"),
            windSpeed = c.optDouble("wind_speed_10m"),
            isDay = c.optInt("is_day", 1) == 1,
            sunrise = time(d.optJSONArray("sunrise")?.optString(0)),
            sunset = time(d.optJSONArray("sunset")?.optString(0)),
            days = days,
            hours = hours,
            fetchedAt = System.currentTimeMillis(),
        )
    }
}
