package tw.liuli.launcher.ui.bg

/**
 * 玉山群峰的真實稜線（自動產生，請勿手改）。
 *
 * 來源：NASA SRTM 1 弧秒（約 30 公尺）數值地形（N23E120）。視點是最常見的玉山照片角度——站在玉山北峰（北緯 23.4875°、東經 120.9594°，
 * 眼高約 3,850 公尺）往南望：畫面中偏右是主峰的金字塔、左邊是東峰，兩側遠方是層層的中央山脈。
 * 面向方位角 185°，水平視角 60°（左右各多算 15% 給視差用），垂直略微誇張 1.2 倍（像照片的構圖），含地球曲率與大氣折射修正。
 * 依距離分成 6 層，每層是該距離帶內地形的最高仰角——由遠到近：8 公里外的遠山、3～8 公里（南峰一帶）、
 * 1.5～3 公里（主峰與東峰）、0.7～1.5 公里（風口鞍部）、250～700 公尺、60～250 公尺（北峰山頂的前景）。
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
        "4Q4o4/5W5s6D6b617N7k8G8o9K9t+Q+y/V/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w" +
        "/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w" +
        "/w/w/w/w/w/w/w/w/w/w/r/l/f/Z/T/N/H/A+6+0+t+n+j+g+a+U+O+L+H+D9/929r9g9W9L9A818q8e8S8G767u7i7V7J686y6p6h6Y6P6G585z5p5f5U5L" +
        "5A424v4o4g4Z4R4J3/3z3n3b3P3E252x2o2g2X2O2F171y1p1g1Y1P1E060v0k0Z0O0Dz6zyzqzizazSzKzCy6yyy6zJzXzmz10E0G0E0D0Bz/0B0Z0/1k17" +
        "2G2Q2a2l2+3Z304P4a4W4Q4I4A313q3c3N3T4a5j6t739D+Q/b/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w" +
        "/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w" +
        "/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w3/37333z3u3p3l3f3a3V3P3J3D2922" +
        "2w2p2i2b2T2M2E1/2F2K2P2U2Z2e2j2o2t2y22273A3E3J3O3S3W3b3f3j3o3s3w3034384A4E4I4L4P4T4W4a4d4h4k4o4r4u4x404346495A5D5G5J5L5O" +
        "5R5T5W5Y5b5d5f5h5k5m5o5q5s5t5v5x5z505254555758595+6A6B6C6D6E6F6G6H6H6I6J6J6K6K6L6L6L6L6L6M6M6M6M6L6L6L6L6K6K6J6J6I6H6H6G" +
        "6F6E6D6D6H6L6P6S6W6Z6d6g6j6n6q6t6w6z6265686/7B7E7H7K7M7P7R7U7W7Y7b7d7f7i7k7m7o7q7s7u7w7y7072747677797/8B8C8E8F8H8J8K8M8N" +
        "8P8Q8S8T8V8W8X8Z8a8c8d8e8g8h8i8k8l8m8n8p8q8r8s8u8v8w8x8z80818283858687888+8/9A9B9D9E9F9G9I9J9K9M9N9O9Q9R9S9U9V9X9Y9Z9b9p" +
        "95+K+b+r+8/M/d/t/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w/w" +
        "/w/w/w/w/w/w"

    val y: Array<FloatArray> by lazy {
        val a = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        Array(LAYERS) { l -> FloatArray(N) { k -> val i = (l * N + k) * 2; (a.indexOf(DATA[i]) * 64 + a.indexOf(DATA[i + 1])) / 15f } }
    }
}
