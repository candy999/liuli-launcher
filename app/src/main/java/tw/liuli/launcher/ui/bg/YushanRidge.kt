package tw.liuli.launcher.ui.bg

import android.graphics.Bitmap
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.GZIPInputStream

/**
 * 山稜樣式的玉山地形（資料檔 yushan.bin 由 tools/yushan 產生）。
 *
 * 視點是最常見的玉山照片角度：站在玉山北峰往南望，中偏右是主峰的金字塔、左邊是東峰。
 * 地形來自 NASA SRTM 30 公尺數值地形；玉山一帶放大到 6 公尺，把 SRTM 磨圓的峰頂與稜脊收尖，
 * 再沿著「下坡方向」刻出大小不一的侵蝕溝與碎石坡條紋，平緩的稜頂與鞍部加上岩塊與灌叢的起伏，用清晨從東邊斜照的光算出明暗。
 *
 * 分塊不是照距離一層層切（那是剪紙的做法），而是：
 * - 切在實景的「遮擋邊」：前面的山擋住後面的地形、距離突然跳開的地方
 * - 加上一條主稜切割線：主峰峰頂 → 右肩 → 往下到風口的近稜 → 向右拐。主稜兩側各是一整塊連續的山體
 * 每一塊用網格貼圖畫，網格每個頂點依「那一點的真實距離」移動（視差與距離成反比）：
 * 同一塊山體裡近處移得多、遠處移得少，是連續變化的，稜線不會被切斷。
 * 每一塊被更近的塊擋住的地方往外補了一圈，視差移動時露出來的縫有地形補上。
 *
 * 每一塊的圖：A＝覆蓋、R＝受光（已乘上霧）、G＝霧（空氣透視＋谷底積霧）。畫的時候用色彩矩陣換成背景色系。
 * 全部約 2.6 MB，只有選到山稜時才在背景執行緒讀進來，離開琉璃時放掉。
 */
internal object YushanRidge {
    /** 資料涵蓋的水平範圍：從畫面左緣外 15% 開始（邏輯座標）。 */
    const val X0 = -480f * .15f

    class Piece(
        /** 在資料像素裡的位置與大小。 */
        val x0: Int, val y0: Int, val w: Int, val h: Int,
        /** 網格頂點數（橫、直）與每個頂點的視差係數。 */
        val gw: Int, val gh: Int, val k: FloatArray,
        val image: Bitmap,
        /** 這一塊的中位距離（公尺）與平均高度（邏輯 y）。 */
        val depth: Float, val meanY: Float,
    )

    @Volatile var pieces: List<Piece>? = null
        private set
    /** 資料像素對邏輯座標的倍數。 */
    @Volatile var scale = 1.5f
        private set
    private val started = AtomicBoolean(false)

    fun request() {
        if (!started.compareAndSet(false, true)) return
        Thread({ pieces = runCatching { load() }.getOrNull() }, "liuli-yushan").apply {
            priority = Thread.MIN_PRIORITY
            start()
        }
    }

    /** 離開琉璃：放掉地形圖（下次要畫時再讀）。 */
    fun release() {
        if (pieces == null) return
        pieces = null
        started.set(false)
    }

    private fun load(): List<Piece> {
        val res = YushanRidge::class.java.getResourceAsStream("yushan.bin") ?: error("沒有 yushan.bin")
        DataInputStream(BufferedInputStream(GZIPInputStream(res), 1 shl 16)).use { d ->
            val magic = ByteArray(4).also { d.readFully(it) }
            check(String(magic) == "YSR2")
            val n = d.readInt(); val sc = d.readFloat()
            scale = sc
            return List(n) {
                val x0 = d.readInt(); val y0 = d.readInt(); val w = d.readInt(); val h = d.readInt()
                val gw = d.readInt(); val gh = d.readInt()
                val depth = d.readFloat(); val meanY = d.readFloat()
                val k = FloatArray(gw * gh) { d.readFloat() }
                val a = ByteArray(w * h).also { d.readFully(it) }
                val r = ByteArray(w * h).also { d.readFully(it) }
                val g = ByteArray(w * h).also { d.readFully(it) }
                val px = IntArray(w * h) { i ->
                    ((a[i].toInt() and 255) shl 24) or ((r[i].toInt() and 255) shl 16) or ((g[i].toInt() and 255) shl 8)
                }
                val bmp = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
                bmp.prepareToDraw()
                Piece(x0, y0, w, h, gw, gh, k, bmp, depth, meanY)
            }
        }
    }
}
