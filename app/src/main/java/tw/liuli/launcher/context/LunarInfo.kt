package tw.liuli.launcher.context

import com.nlf.calendar.Lunar
import com.nlf.calendar.LunarMonth
import com.nlf.calendar.Solar
import java.time.LocalDate

/** 某一天的農民曆資訊（已轉成正體字）。 */
data class LunarInfo(
    val ganZhiYear: String,   // 丙午
    val zodiac: String,       // 馬
    val month: Int,           // 1～12
    val isLeapMonth: Boolean,
    val day: Int,             // 1～30
    val monthText: String,    // 九月 / 閏六月
    val dayText: String,      // 十六
    val solarTerm: String?,   // 今天是節氣時才有，例如 立冬
    val nextSolarTerm: String,
    val nextSolarTermDate: LocalDate,
    val yi: List<String>,     // 宜
    val ji: List<String>,     // 忌
    val chong: String,        // 沖 生肖，例如 沖鼠
    val sha: String,          // 煞方位，例如 煞北
    val season: String,       // spring/summer/autumn/winter（依立春、立夏、立秋、立冬）
    val isMonthEnd: Boolean,  // 這個農曆月的最後一天
    val goodHours: List<String>, // 吉時（黃道吉時的地支），例如 子 寅 卯 午 未 酉
) {
    /** 初一、十五：拜拜日。 */
    val isBaiBai: Boolean get() = day == 1 || day == 15

    /** 初二、十六：商家「做牙」拜土地公。 */
    val isZuoYa: Boolean get() = day == 2 || day == 16

    /** 0＝新月、0.5＝滿月，依農曆日推算的月相。 */
    val moonPhase: Double get() = ((day - 1) / 29.53).coerceIn(0.0, 1.0)

    val fullText: String get() = "農曆${monthText}${dayText}"
}

object LunarCalc {

    fun of(date: LocalDate): LunarInfo {
        val lunar: Lunar = Solar.fromYmd(date.year, date.monthValue, date.dayOfMonth).lunar
        val month = lunar.month
        val next = lunar.nextJieQi
        val nextSolar = next.solar
        val monthDays = LunarMonth.fromYm(lunar.year, month)?.dayCount ?: 30

        return LunarInfo(
            ganZhiYear = lunar.yearInGanZhi,
            zodiac = Hant.convert(lunar.yearShengXiao),
            month = kotlin.math.abs(month),
            isLeapMonth = month < 0,
            day = lunar.day,
            monthText = Hant.convert(lunar.monthInChinese) + "月",
            dayText = Hant.convert(lunar.dayInChinese),
            solarTerm = lunar.jieQi.takeIf { it.isNotBlank() }?.let(Hant::convert),
            nextSolarTerm = Hant.convert(next.name),
            nextSolarTermDate = LocalDate.of(nextSolar.year, nextSolar.month, nextSolar.day),
            yi = lunar.dayYi.map(Hant::convert),
            ji = lunar.dayJi.map(Hant::convert),
            chong = "沖" + Hant.convert(lunar.dayChongShengXiao),
            sha = "煞" + Hant.convert(lunar.daySha),
            season = seasonOf(lunar),
            isMonthEnd = lunar.day == monthDays,
            goodHours = lunar.times.take(12).filter { it.tianShenLuck == "吉" }.map { it.zhi },
        )
    }

    /**
     * 農曆的一天從前一天 23:00（子時）開始：23:00 以後的宜忌、初一十五都算下一天。
     * 國曆日期（元旦、母親節…）仍照國曆。
     */
    fun lunarDateOf(now: java.time.LocalDateTime): LocalDate =
        if (now.hour >= 23) now.toLocalDate().plusDays(1) else now.toLocalDate()

    /** 時辰名稱：子時 23:00～01:00、丑時 01:00～03:00 …。 */
    fun shiChen(hour: Int): String = "子丑寅卯辰巳午未申酉戌亥"[((hour + 1) / 2) % 12] + "時"

    private fun seasonOf(lunar: Lunar): String {
        var term = lunar.prevJieQi
        repeat(8) {
            when (term.name) {
                "立春" -> return "spring"
                "立夏" -> return "summer"
                "立秋" -> return "autumn"
                "立冬" -> return "winter"
            }
            val s = term.solar
            term = Solar.fromYmd(s.year, s.month, s.day).next(-1).lunar.prevJieQi
        }
        return "winter"
    }
}

/**
 * lunar-java 輸出簡體字。農民曆用字範圍很小，用對照表轉正體即可，不必引入整套轉換庫。
 */
object Hant {
    private val phrases = mapOf(
        "理发" to "理髮", "余事勿取" to "餘事勿取", "馀事勿取" to "餘事勿取", "上梁" to "上樑", "谷雨" to "穀雨",
        "雇佣" to "僱傭",
    )
    private val chars: Map<Char, Char> = buildMap {
        val pairs = "动動纳納财財开開斋齋会會亲親进進盖蓋栋棟竖豎庙廟谢謝词詞讼訟诉訴订訂结結网網" +
            "猎獵渔漁补補扫掃坏壞诸諸经經络絡酝醞酿釀养養种種归歸宁寧问問带帶头頭疗療针針医醫" +
            "绘繪仓倉栖棲门門硙磑筑築帐帳寿壽殓殮启啟钻鑽坟墳涂塗饰飾墙牆习習艺藝学學货貨产產" +
            "机機无無岁歲鸡雞龙龍马馬猪豬腊臘惊驚蛰蟄满滿处處东東冲沖复復对對鱼魚乐樂礼禮灯燈" +
            "药藥园園车車书書个個为為闭閉关關离離见見贵貴间間时時义義过過传傳厨廚献獻祷禱愿願" +
            "赏賞执執鸣鳴兴興举舉达達远遠迁遷发發余餘庆慶缘緣" +
            // 2026-11 全面稽核補上（1901～2099 每一天的宜忌、月份、節氣、生肖、沖煞、時辰）
            "馀餘丧喪挂掛桥橋厕廁断斷蚁蟻闰閏"
        var i = 0
        while (i + 1 < pairs.length) {
            put(pairs[i], pairs[i + 1])
            i += 2
        }
    }

    fun convert(text: String): String {
        var s = text
        for ((from, to) in phrases) s = s.replace(from, to)
        return buildString(s.length) { s.forEach { append(chars[it] ?: it) } }
    }
}
