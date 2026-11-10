package tw.liuli.launcher.ui.bg

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.core.graphics.PathParser
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.ceil

/**
 * 山稜樣式的玉山（資料檔 yushan_svg.json）。
 *
 * 山是向量：使用者把風格化的玉山圖轉成向量（Illustrator 的 SVG，10 個平塗色），再依使用者畫的稜線分成 7 層——
 * 遠山、東峰、主峰、右側稜、近稜（北峰→風口）、近稜下方的稜、最前面的稜。層的交界吸附在向量色塊的邊上，
 * 每個色塊整塊歸到一層（只有少數橫跨好幾層的大背景色塊依層切開）。
 * 每一層還有：
 * - 一個完整的底色剪影：被前面擋住的地方也有東西
 * - 被擋住的那一圈用這一層看得到的色塊顏色往裡延伸：前景移開時露出的是後面山坡的延續，不是一條深色的縫
 *
 * 顏色不寫死：每個色塊存「受光多少、離多遠」兩個值。載入時把每一層畫成一張通道圖（R＝受光、G＝遠近、A＝覆蓋），
 * 每一格只用色彩矩陣換成當下時段的顏色、整層平移（視差），不重畫向量。
 * 只有選到山稜時才在背景執行緒讀進來，離開琉璃時放掉。
 */
internal object YushanRidge {
    /** 通道圖的解析度：每個邏輯單位幾個像素（480 寬的畫面＝960 像素）。 */
    const val SCALE = 2f

    class Layer(val name: String, val k: Float, val topY: Float, val x0: Float, val y0: Float, val w: Float, val h: Float, val image: Bitmap) {
        val dst = RectF()
    }

    @Volatile var layers: List<Layer>? = null
        private set
    private val started = AtomicBoolean(false)

    fun request() {
        if (!started.compareAndSet(false, true)) return
        Thread({ layers = runCatching { load() }.onFailure { android.util.Log.e("LiuliBg", "玉山向量載入失敗", it) }.getOrNull() }, "liuli-yushan").apply {
            priority = Thread.MIN_PRIORITY
            start()
        }
    }

    /** 離開琉璃：放掉圖（下次要畫時再畫）。 */
    fun release() {
        layers = null; trails = null
        started.set(false); trailsStarted.set(false)
    }

    private fun loopsPath(loops: JSONArray): Path {
        val p = Path().apply { fillType = Path.FillType.EVEN_ODD }
        for (i in 0 until loops.length()) {
            val lp = loops.getJSONArray(i)
            if (lp.length() < 6) continue
            p.moveTo(lp.getDouble(0).toFloat(), lp.getDouble(1).toFloat())
            var j = 2
            while (j < lp.length()) { p.lineTo(lp.getDouble(j).toFloat(), lp.getDouble(j + 1).toFloat()); j += 2 }
            p.close()
        }
        return p
    }

    private fun load(): List<Layer> {
        // 用絕對路徑：正式版會把類別改名搬家，相對於類別的路徑會找不到檔案
        val text = YushanRidge::class.java.getResourceAsStream("/tw/liuli/launcher/ui/bg/yushan_svg.json")!!.bufferedReader(Charsets.UTF_8).use { it.readText() }
        val root = JSONObject(text)
        val colors = root.getJSONObject("colors")
        val svg = root.getJSONObject("svg")
        val toLogical = Matrix().apply {
            val sx = svg.getDouble("sx").toFloat()
            setScale(sx, sx); postTranslate(svg.getDouble("ox").toFloat(), svg.getDouble("oy").toFloat())
        }
        // 色塊顏色 → 通道色（R＝受光、G＝遠近）
        fun chan(c: String): Int {
            val a = colors.getJSONArray(c)
            return android.graphics.Color.argb(255, (a.getDouble(0) * 255).toInt(), (a.getDouble(1) * 255).toInt(), 0)
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val arr = root.getJSONArray("layers")
        return List(arr.length()) { li ->
            val L = arr.getJSONObject(li)
            val bb = L.getJSONArray("bbox")
            val x0 = bb.getDouble(0).toFloat(); val y0 = bb.getDouble(1).toFloat()
            val x1 = bb.getDouble(2).toFloat(); val y1 = bb.getDouble(3).toFloat()
            // 4 位元的通道就夠（平塗只有十種顏色），記憶體減半
            @Suppress("DEPRECATION")
            val bmp = Bitmap.createBitmap(ceil((x1 - x0) * SCALE).toInt(), ceil((y1 - y0) * SCALE).toInt(), Bitmap.Config.ARGB_4444)
            val cv = Canvas(bmp)
            cv.scale(SCALE, SCALE); cv.translate(-x0, -y0)
            val base = L.getJSONObject("base")
            paint.color = chan(base.getString("c")); cv.drawPath(loopsPath(base.getJSONArray("loops")), paint)
            val ext = L.optJSONArray("ext")
            if (ext != null) for (e in 0 until ext.length()) {
                val g = ext.getJSONObject(e); paint.color = chan(g.getString("c")); cv.drawPath(loopsPath(g.getJSONArray("loops")), paint)
            }
            val clipLoops = L.getJSONArray("clip")
            val clip = if (clipLoops.length() > 0) loopsPath(clipLoops) else null
            val items = L.getJSONArray("items")
            for (k in 0 until items.length()) {
                val it = items.getJSONObject(k)
                val path = PathParser.createPathFromPathData(it.getString("d")) ?: continue
                path.transform(toLogical)
                paint.color = chan(it.getString("c"))
                if (it.optInt("clip") == 1 && clip != null) {
                    cv.save(); cv.clipPath(clip); cv.drawPath(path, paint); cv.restore()
                } else cv.drawPath(path, paint)
            }
            bmp.prepareToDraw()
            Layer(L.getString("name"), L.getDouble("K").toFloat(), L.getDouble("topY").toFloat(), x0, y0, x1 - x0, y1 - y0, bmp)
        }
    }

    // ── 星軌：面向南方，星星繞著地平線下的南天極轉。先畫成一張同心圓弧的圖，每格只繞著天極慢慢旋轉 ──
    /** 南天極在畫面上的位置（邏輯座標，在山的後面）與星軌圖涵蓋的半徑。 */
    const val POLE_X = 480f * .56f
    const val POLE_Y = 270f * 1.02f
    const val TRAIL_R = 330f
    private const val TRAIL_SCALE = 1.2f
    @Volatile var trails: Bitmap? = null
        private set
    private val trailsStarted = AtomicBoolean(false)

    fun requestTrails() {
        if (!trailsStarted.compareAndSet(false, true)) return
        Thread({ trails = runCatching { buildTrails() }.getOrNull() }, "liuli-trails").apply { priority = Thread.MIN_PRIORITY; start() }
    }

    private fun h(n: Int, s: Int): Float {
        var x = n * 374761393 + s * 668265263
        x = (x xor (x ushr 13)) * 1274126177
        return ((x xor (x ushr 16)) and 0xFFFFFF) / 16777216f
    }

    private fun buildTrails(): Bitmap {
        val size = ceil(TRAIL_R * 2 * TRAIL_SCALE).toInt()
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        cv.scale(TRAIL_SCALE, TRAIL_SCALE); cv.translate(TRAIL_R, TRAIL_R)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
        val oval = RectF()
        var r = 90f; var ring = 0
        while (r < TRAIL_R) {
            for (k in 0 until 3) {
                val n = ring * 3 + k
                if (h(n, 3) > .6f) continue
                val phi = h(n, 17) * 360f
                val len = (.18f + .55f * h(n, 29)) * 180f / PI.toFloat()
                val bright = .35f + .65f * h(n, 41)
                val warm = h(n, 53)
                val cr = (180 + 75 * warm).toInt(); val cg = (210 + 15 * warm).toInt(); val cb = (255 - 70 * warm).toInt()
                oval.set(-r, -r, r, r)
                p.strokeWidth = .55f + .35f * bright
                // 頭亮、尾巴淡：分 8 段畫，越往尾巴越透明
                val seg = 8
                for (s in 0 until seg) {
                    val a = (1f - s / seg.toFloat()).let { it * it } * bright
                    p.color = android.graphics.Color.argb((a * 255).toInt().coerceIn(0, 255), cr, cg, cb)
                    cv.drawArc(oval, phi - (s + 1) * len / seg, len / seg + .3f, false, p)
                }
            }
            r += 1.6f; ring++
        }
        return bmp
    }

    /** 星軌每秒轉幾度（真實是每小時 15°，這裡加快到看得出在流動）。 */
    const val TRAIL_DEG_PER_SEC = .6f
    fun trailRect(out: RectF) { out.set(POLE_X - TRAIL_R, POLE_Y - TRAIL_R, POLE_X + TRAIL_R, POLE_Y + TRAIL_R) }
}
