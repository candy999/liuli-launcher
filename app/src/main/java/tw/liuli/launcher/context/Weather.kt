package tw.liuli.launcher.context

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
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

data class Weather(
    /** clear / partly / cloudy / fog / drizzle / rain / snow / thunder */
    val kind: String,
    val label: String,
    val code: Int,
    val temp: Double,
    val feelsLike: Double,
    val humidity: Int,
    val precipitation: Double,
    val cloudCover: Int,
    val windSpeed: Double,
    val isDay: Boolean,
    val tempMax: Double,
    val tempMin: Double,
    val rainChance: Int,
    val sunrise: LocalDateTime?,
    val sunset: LocalDateTime?,
    val fetchedAt: Long,
)

/**
 * 天氣：Open-Meteo（免金鑰、免註冊）。之後可加中央氣象署作為第二來源。
 */
object WeatherRepository {

    suspend fun fetch(city: City): Weather? = withContext(Dispatchers.IO) {
        val url = "https://api.open-meteo.com/v1/forecast" +
            "?latitude=${city.lat}&longitude=${city.lon}" +
            "&current=temperature_2m,relative_humidity_2m,apparent_temperature,is_day,precipitation," +
            "weather_code,cloud_cover,wind_speed_10m" +
            "&daily=sunrise,sunset,temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
            "&timezone=auto&forecast_days=1"
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
        val code = c.getInt("weather_code")
        val (kind, label) = classify(code)
        fun dailyDouble(key: String) = d.optJSONArray(key)?.optDouble(0) ?: Double.NaN
        fun dailyTime(key: String) = d.optJSONArray(key)?.optString(0)
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }
        return Weather(
            kind = kind,
            label = label,
            code = code,
            temp = c.optDouble("temperature_2m"),
            feelsLike = c.optDouble("apparent_temperature"),
            humidity = c.optInt("relative_humidity_2m"),
            precipitation = c.optDouble("precipitation", 0.0),
            cloudCover = c.optInt("cloud_cover"),
            windSpeed = c.optDouble("wind_speed_10m"),
            isDay = c.optInt("is_day", 1) == 1,
            tempMax = dailyDouble("temperature_2m_max"),
            tempMin = dailyDouble("temperature_2m_min"),
            rainChance = d.optJSONArray("precipitation_probability_max")?.optInt(0) ?: 0,
            sunrise = dailyTime("sunrise"),
            sunset = dailyTime("sunset"),
            fetchedAt = System.currentTimeMillis(),
        )
    }

    /** WMO 天氣代碼 → 網頁好判斷的大類。 */
    fun classify(code: Int): Pair<String, String> = when (code) {
        0 -> "clear" to "晴"
        1, 2 -> "partly" to "晴時多雲"
        3 -> "cloudy" to "陰"
        45, 48 -> "fog" to "霧"
        in 51..57 -> "drizzle" to "毛毛雨"
        in 61..67, in 80..82 -> "rain" to "雨"
        in 71..77, 85, 86 -> "snow" to "雪"
        in 95..99 -> "thunder" to "雷雨"
        else -> "cloudy" to "多雲"
    }
}
