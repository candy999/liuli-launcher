package tw.liuli.launcher.context

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/** 節日。id 是給網頁判斷用的穩定英文代號，name 是顯示用中文。 */
data class Festival(val id: String, val name: String)

/**
 * 台灣常見節日，全部離線計算（國曆固定日＋農曆＋節氣）。
 * 網頁桌布透過 festival=spring,lantern 這類代號決定要不要換裝。
 */
object Festivals {

    private val solar = mapOf(
        "01-01" to Festival("newyear", "元旦"),
        "02-14" to Festival("valentine", "西洋情人節"),
        "02-28" to Festival("peace", "和平紀念日"),
        "03-08" to Festival("women", "婦女節"),
        "03-12" to Festival("arbor", "植樹節"),
        "04-04" to Festival("children", "兒童節"),
        "05-01" to Festival("labor", "勞動節"),
        "08-08" to Festival("father", "父親節"),
        "09-28" to Festival("teacher", "教師節"),
        "10-10" to Festival("national", "國慶日"),
        "10-25" to Festival("retrocession", "臺灣光復節"),
        "10-31" to Festival("halloween", "萬聖節"),
        "12-24" to Festival("xmaseve", "平安夜"),
        "12-25" to Festival("christmas", "聖誕節"),
        "12-31" to Festival("nye", "跨年夜"),
    )

    // 農曆 月-日
    private val lunar = mapOf(
        "1-1" to Festival("spring", "春節"),
        "1-2" to Festival("spring2", "回娘家"),
        "1-9" to Festival("tiangong", "天公生"),
        "1-15" to Festival("lantern", "元宵節"),
        "2-2" to Festival("tudigong", "土地公生"),
        "3-23" to Festival("mazu", "媽祖生"),
        "5-5" to Festival("dragonboat", "端午節"),
        "7-1" to Festival("ghostgate", "開鬼門"),
        "7-7" to Festival("qixi", "七夕"),
        "7-15" to Festival("zhongyuan", "中元節"),
        "8-15" to Festival("midautumn", "中秋節"),
        "9-9" to Festival("chongyang", "重陽節"),
        "12-8" to Festival("laba", "臘八"),
        "12-16" to Festival("weiya", "尾牙"),
        "12-24" to Festival("songshen", "送神"),
    )

    private val terms = mapOf(
        "清明" to Festival("qingming", "清明節"),
        "冬至" to Festival("dongzhi", "冬至"),
        "立春" to Festival("lichun", "立春"),
    )

    fun of(date: LocalDate, lunarInfo: LunarInfo): List<Festival> = buildList {
        solar["%02d-%02d".format(date.monthValue, date.dayOfMonth)]?.let(::add)

        if (!lunarInfo.isLeapMonth) {
            lunar["${lunarInfo.month}-${lunarInfo.day}"]?.let(::add)
            // 除夕：農曆十二月的最後一天（可能是廿九或三十）
            if (lunarInfo.month == 12 && lunarInfo.isMonthEnd) add(Festival("chuxi", "除夕"))
            // 鬼門關：農曆七月最後一天
            if (lunarInfo.month == 7 && lunarInfo.isMonthEnd) add(Festival("ghostclose", "關鬼門"))
        }
        lunarInfo.solarTerm?.let { terms[it] }?.let(::add)

        // 母親節：五月第二個星期日
        val mothersDay = LocalDate.of(date.year, 5, 1)
            .with(TemporalAdjusters.dayOfWeekInMonth(2, DayOfWeek.SUNDAY))
        if (date == mothersDay) add(Festival("mother", "母親節"))
    }
}
