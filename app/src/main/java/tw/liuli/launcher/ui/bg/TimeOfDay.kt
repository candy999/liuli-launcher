package tw.liuli.launcher.ui.bg

import java.util.Calendar
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin

/**
 * 時光色彩：山稜的顏色跟著一天的時間走（不跟焦點的 App）。
 *
 * 依玉山（北緯 23.47°、東經 120.96°）的「太陽仰角」選色票，季節不同時日出日落的時間自動跟著變；
 * 上午、下午各一條：同樣的仰角，早上偏冷偏粉、傍晚偏暖偏橘紫。相鄰的色票之間平順過渡。
 * 每個色票：天空（頂、中、地平線）、山的陰影、最遠的山嵐、受光面的顏色與強度、雲的受光與陰影、日月光暈、星星。
 * 深夜像長曝光：天空深青藍、山的暗部被拉亮帶銀藍的月光、地平線一抹遠方城鎮的暖光，星星拉成星軌。
 */
internal object TimeOfDay {
    class Pal(
        val name: String,
        val skyTop: FloatArray, val skyMid: FloatArray, val skyHor: FloatArray,
        val body: FloatArray, val haze: FloatArray, val lit: FloatArray, val litK: Float,
        val cLit: FloatArray, val cShade: FloatArray, val glow: FloatArray, val glowA: Float, val stars: Float,
    )

    private fun c(r: Int, g: Int, b: Int) = floatArrayOf(r / 255f, g / 255f, b / 255f)
    private fun p(name: String, st: IntArray, sm: IntArray, sh: IntArray, bo: IntArray, hz: IntArray, li: IntArray, litK: Float,
                  cl: IntArray, cs: IntArray, gl: IntArray, glowA: Float, stars: Float) =
        Pal(name, c(st[0], st[1], st[2]), c(sm[0], sm[1], sm[2]), c(sh[0], sh[1], sh[2]), c(bo[0], bo[1], bo[2]), c(hz[0], hz[1], hz[2]),
            c(li[0], li[1], li[2]), litK, c(cl[0], cl[1], cl[2]), c(cs[0], cs[1], cs[2]), c(gl[0], gl[1], gl[2]), glowA, stars)
    private fun i(vararg v: Int) = v

    private val NIGHT = p("深夜", i(8, 18, 42), i(18, 40, 74), i(54, 64, 86), i(8, 14, 28), i(46, 66, 104), i(132, 152, 192), .45f, i(124, 140, 176), i(34, 46, 72), i(255, 186, 136), .16f, 1f)
    private val DAWN_BLUE = p("黎明藍調", i(14, 24, 58), i(42, 60, 110), i(128, 112, 150), i(10, 16, 36), i(64, 82, 130), i(150, 145, 190), .32f, i(140, 135, 180), i(45, 55, 92), i(210, 150, 170), .22f, .45f)
    private val SUNRISE_GLOW = p("朝霞", i(38, 64, 132), i(160, 132, 168), i(255, 172, 122), i(20, 20, 46), i(124, 120, 166), i(255, 168, 132), .85f, i(255, 188, 162), i(112, 96, 142), i(255, 150, 92), .48f, .04f)
    private val SUNRISE_GOLD = p("日照金山", i(52, 102, 192), i(165, 172, 212), i(255, 214, 168), i(14, 24, 54), i(142, 162, 212), i(255, 192, 128), 1f, i(255, 228, 202), i(142, 150, 192), i(255, 200, 140), .36f, 0f)
    private val MORNING_GOLD = p("晨光", i(60, 126, 222), i(150, 190, 240), i(240, 236, 226), i(11, 28, 56), i(140, 186, 236), i(242, 208, 172), .85f, i(255, 250, 244), i(150, 176, 216), i(255, 232, 192), .24f, 0f)
    private val MORNING = p("上午", i(62, 136, 232), i(140, 195, 245), i(226, 240, 250), i(10, 30, 60), i(145, 192, 242), i(226, 212, 196), .72f, i(255, 255, 255), i(160, 190, 230), i(255, 246, 226), .16f, 0f)
    private val NOON = p("正午", i(44, 114, 236), i(118, 180, 246), i(205, 230, 250), i(11, 33, 66), i(150, 198, 248), i(236, 232, 224), .6f, i(255, 255, 255), i(170, 198, 236), i(255, 255, 240), .1f, 0f)
    private val AFTERNOON = p("午後", i(88, 160, 242), i(170, 214, 246), i(237, 247, 249), i(10, 29, 58), i(147, 197, 248), i(216, 190, 170), .8f, i(255, 252, 246), i(160, 186, 226), i(255, 240, 210), .18f, 0f)
    private val GOLDEN_PM = p("黃金時刻", i(70, 120, 206), i(192, 190, 214), i(255, 218, 168), i(16, 24, 50), i(160, 170, 214), i(255, 190, 128), .95f, i(255, 230, 196), i(150, 150, 190), i(255, 200, 130), .36f, 0f)
    private val SUNSET_GOLD = p("夕照", i(56, 80, 156), i(212, 150, 140), i(255, 164, 94), i(24, 20, 44), i(170, 140, 170), i(255, 150, 94), 1f, i(255, 176, 126), i(150, 100, 126), i(255, 140, 70), .5f, 0f)
    private val SUNSET_GLOW = p("晚霞", i(34, 44, 104), i(150, 100, 142), i(250, 128, 100), i(18, 16, 38), i(120, 100, 146), i(236, 130, 120), .55f, i(255, 150, 130), i(100, 70, 110), i(255, 110, 80), .45f, .05f)
    private val DUSK_BLUE = p("暮色藍調", i(16, 26, 66), i(46, 58, 112), i(150, 100, 130), i(10, 15, 34), i(58, 72, 120), i(150, 126, 172), .3f, i(140, 120, 166), i(40, 45, 80), i(200, 120, 140), .2f, .4f)

    private val KEYS_AM = listOf(-18f to NIGHT, -10f to DAWN_BLUE, -3f to SUNRISE_GLOW, 4f to SUNRISE_GOLD, 12f to MORNING_GOLD, 30f to MORNING, 55f to NOON, 90f to NOON)
    private val KEYS_PM = listOf(-18f to NIGHT, -10f to DUSK_BLUE, -3f to SUNSET_GLOW, 4f to SUNSET_GOLD, 12f to GOLDEN_PM, 30f to AFTERNOON, 55f to NOON, 90f to NOON)

    private fun mix(a: FloatArray, b: FloatArray, s: Float) = FloatArray(3) { a[it] + (b[it] - a[it]) * s }

    /** 太陽仰角（度）→ 色票（相鄰兩個之間平順內插）。 */
    fun palAt(elev: Float, pm: Boolean): Pal {
        val keys = if (pm) KEYS_PM else KEYS_AM
        var k = 0
        while (k < keys.size - 2 && elev > keys[k + 1].first) k++
        val (e0, a) = keys[k]; val (e1, b) = keys[k + 1]
        val f = ((elev - e0) / (e1 - e0)).coerceIn(0f, 1f)
        val s = f * f * (3 - 2 * f)
        return Pal(
            if (s < .5f) a.name else b.name,
            mix(a.skyTop, b.skyTop, s), mix(a.skyMid, b.skyMid, s), mix(a.skyHor, b.skyHor, s),
            mix(a.body, b.body, s), mix(a.haze, b.haze, s), mix(a.lit, b.lit, s), a.litK + (b.litK - a.litK) * s,
            mix(a.cLit, b.cLit, s), mix(a.cShade, b.cShade, s), mix(a.glow, b.glow, s), a.glowA + (b.glowA - a.glowA) * s,
            a.stars + (b.stars - a.stars) * s,
        )
    }

    class Sun(val elev: Float, val pm: Boolean, val tst: Float)

    /** 太陽位置（NOAA 近似公式）。[minutes]＝台灣時間當天的分鐘數。 */
    fun sunAt(dayOfYear: Int, minutes: Float): Sun {
        val lat = 23.47 * PI / 180; val lon = 120.96; val tz = 8
        val g = 2 * PI / 365 * (dayOfYear - 1 + (minutes / 60 - 12) / 24)
        val eqt = 229.18 * (.000075 + .001868 * cos(g) - .032077 * sin(g) - .014615 * cos(2 * g) - .040849 * sin(2 * g))
        val decl = .006918 - .399912 * cos(g) + .070257 * sin(g) - .006758 * cos(2 * g) + .000907 * sin(2 * g) - .002697 * cos(3 * g) + .00148 * sin(3 * g)
        val tst = minutes + eqt + 4 * lon - 60 * tz
        val ha = (tst / 4 - 180) * PI / 180
        val cz = (sin(lat) * sin(decl) + cos(lat) * cos(decl) * cos(ha)).coerceIn(-1.0, 1.0)
        return Sun((90 - acos(cz) * 180 / PI).toFloat(), tst > 720, tst.toFloat())
    }

    // 每 10 秒算一次就夠（顏色變化很慢）
    private var cachedAt = 0L
    private var cachedSun: Sun? = null
    private var cachedPal: Pal? = null
    /** 測試用（adb tod:HH:MM）：設成 0～1439 時固定用這個時刻（分鐘），負數＝不覆寫。優先於 [fixedMinutes]。 */
    @Volatile var overrideMinutes = -1f
    /**
     * 全域的「時光」參數（設定 → 外觀 → 時光）：負數＝實時（跟著現在的時間）；0～1439＝固定在這個時刻（分鐘）。
     * 所有會隨時間變化的背景都從 [minutes]／[now] 取時間，不要自己讀時鐘。
     */
    @Volatile var fixedMinutes = -1f
    private var cachedM = -1f

    /** 背景要用的「現在幾點」（分鐘，0～1440）：實時或使用者固定的模擬時刻。 */
    fun minutes(): Float {
        if (overrideMinutes >= 0) return overrideMinutes
        if (fixedMinutes >= 0) return fixedMinutes
        val cal = Calendar.getInstance()
        return cal.get(Calendar.HOUR_OF_DAY) * 60f + cal.get(Calendar.MINUTE) + cal.get(Calendar.SECOND) / 60f
    }

    val isLive: Boolean get() = overrideMinutes < 0 && fixedMinutes < 0

    fun now(nowMs: Long): Pair<Sun, Pal> {
        val pinned = !isLive
        if (cachedPal == null || (!pinned && nowMs - cachedAt > 10_000) || (pinned && minutes() != cachedM)) {
            val m = minutes()
            val sun = sunAt(Calendar.getInstance().get(Calendar.DAY_OF_YEAR), m)
            cachedSun = sun; cachedPal = palAt(sun.elev, sun.pm); cachedAt = nowMs; cachedM = m
        }
        return cachedSun!! to cachedPal!!
    }
}
