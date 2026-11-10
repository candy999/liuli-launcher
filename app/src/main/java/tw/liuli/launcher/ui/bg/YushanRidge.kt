package tw.liuli.launcher.ui.bg

/**
 * 玉山群峰的真實稜線（自動產生，請勿手改）。
 *
 * 來源：NASA SRTM 1 弧秒（約 30 公尺）數值地形（N23E120），視點為阿里山祝山觀日平台（北緯 23.506°、東經 120.812°，眼高約 2,466 公尺），
 * 面向方位角 107.5°（玉山主峰方向），水平視角 40°（左右各多算 15% 給視差用），含地球曲率與大氣折射修正。
 * 依距離分成 6 層，每層是該距離帶內地形的最高仰角——由遠到近：14 公里外（玉山主峰、北峰、東峰、南峰一線）、
 * 10～14 公里（西峰、前峰）、6.5～10、3.5～6.5、1.5～3.5、0.3～1.5 公里（祝山腳下）。
 * 已換算成 480×270 邏輯座標的 y 值；每個取樣以兩個 base64 字元存（單位 1/15 像素）。
 */
internal object YushanRidge {
    const val LAYERS = 6
    const val N = 321
    /** 取樣涵蓋的水平範圍：畫面寬的 1.3 倍，從 -15% 開始。 */
    const val X0 = -480f * .15f
    const val SPAN = 480f * 1.3f

    private const val DATA =
        "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" +
        "iah/hfhKg8g0hniNigixjEjNjFiyiTiAh1hag0gSfwfMeseaecesfEfef2gQguhMhlhlhfhYhOg+g0gkgrgvgugjgRf7fsfjfgfcfNe/e0e9fIfSfffuf7gL" +
        "gfgyhChUhehrh4iCiPiditi9jVjmj7kSkrlPlzmVm2nOnfn1/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w" +
        "/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w" +
        "/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w" +
        "/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w" +
        "/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/b+x+I9e818M7j646N5i414I3a2m1i0fzfygxkwqvxu9uPtntDszs4s8s+tA" +
        "tBtCs8sArCqXp1pcoroIn/nrnPnDm8m1mvmpmjmUmPmKmEl9l3l0l0lzl8mGmPmXmempm3nFnTngntn6oGoTogopovmGl/l5lylrlnljlelalVlQlKlCk6kx" +
        "kskokjkekakVkQkLkEj9j7j6j4j3j1jzjyjwjvj1j8kEkLkSkZkgknktk0k7lBlIlOlQlRlSlTlUlVlVlWlWlXlXlYlYlnmAmZmynLnln+oYoypFpQpZpjps" +
        "p1p9qFqMqnrMrxsXs9tkuIuYuou3vGvVvlv0wDwSwkw1xHxYxqx8yNyfyxzCzUzlz20H0Z0q071M1d1u1/2R2g2v2+3L3Z3g3m3s3y353/4G4N4U4b4i4o4t" +
        "4y43475C5I5O5U5Z5e5i5m5p5u5y5256596B6E6H6K6O6S6W6a6e6h6l6o6o6f6X6O6G59515y5u5q5m5h5d5c5Z5X5i5m5o5t5x5255585/6C6E6G6K6O6R" +
        "6M6E6G6A56505t5n5g5Y5R5J464r4d4A3G222q2e2S2G161d1f1X1L1M1O1P1O1N1K1J1H1E1D1C1A08030x0p0g0V0Iz4znzYzQzHy/y2ytykyeyayTyIyC" +
        "x+x7x3xoxTw6u3s9s7s8s8s7s5s2szsusssuswswszs1s2s4s5s7s7s6s3szsususvswsxsusqsmsisesbsXsRsMsHsDr/r7r4r1ryrvrvrxr4sCsMsWshss" +
        "s4tEtQtbtuuD"

    val y: Array<FloatArray> by lazy {
        val a = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        Array(LAYERS) { l -> FloatArray(N) { k -> val i = (l * N + k) * 2; (a.indexOf(DATA[i]) * 64 + a.indexOf(DATA[i + 1])) / 15f } }
    }
}
