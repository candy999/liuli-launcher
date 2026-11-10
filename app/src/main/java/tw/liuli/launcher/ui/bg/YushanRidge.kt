package tw.liuli.launcher.ui.bg

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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
    /**
     * 通道圖的解析度：每個邏輯單位幾個像素。這份（軟體點陣圖）只給玻璃、背景模糊這些小圖用，480 寬＝480 像素就夠。
     * 主畫面上的山用 [NATIVE] 倍的硬體點陣圖、以螢幕原生解析度直接畫（見 [Layer.native]）。
     */
    const val SCALE = 1f
    /** 原生解析度的倍率：1080p 螢幕 1920 ÷ 480 邏輯寬＝4。 */
    const val NATIVE = 4f

    class Layer(val name: String, val k: Float, val topY: Float, val x0: Float, val y0: Float, val w: Float, val h: Float, val image: Bitmap) {
        val dst = RectF()
        /**
         * 省效能：把這一層切成寬 [TILE] 的直條，每條只畫到「下面一定被前面的山完全蓋住」的地方為止（層內座標，邏輯單位）。
         * 已經算進視差最大的位移，所以焦點怎麼移都不會露出沒畫的地方。
         */
        var tileBottom = FloatArray(0)
        /** 原生解析度的山（硬體點陣圖，GPU 直接取樣；只畫到 [tileBottom] 的最低點為止，下面一定被蓋住）。 */
        @Volatile var native: Bitmap? = null
        /** 這一層「前面所有山」的剪影（ALPHA_8，邏輯解析度 480×270）：夾在這一層後面的霧用它挖掉被前景擋住的部分。 */
        var frontMask: Bitmap? = null
    }

    /** 原生解析度的山準備好了沒（Compose 狀態：好了之後背景層改成「小圖畫天空＋原生解析度畫山」）。 */
    var nativeReady by androidx.compose.runtime.mutableStateOf(false)
        private set

    const val TILE = 40f
    /** 天空的最低點（邏輯 y）：再往下一定是山，星軌等只畫在這之上。 */
    @Volatile var skyBottom = 270f
        private set
    /** 每個直條（寬 [TILE]）的天空最低點：星軌只畫在各自的天空裡，不畫到一定被山蓋住的地方。 */
    @Volatile var skyStrips = FloatArray(0)
        private set

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
        nativeReady = false
        layers?.forEach { it.native?.recycle(); it.native = null }
        layers?.forEach { it.frontMask = null }
        trailsHi?.recycle()
        layers = null; trails = null; trailsHi = null
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
        /** 把一層畫成通道圖（[scale] 倍、高度只到 [maxH] 邏輯單位）。 */
        fun render(L: JSONObject, scale: Float, maxH: Float = Float.MAX_VALUE): Bitmap {
            val bb = L.getJSONArray("bbox")
            val x0 = bb.getDouble(0).toFloat(); val y0 = bb.getDouble(1).toFloat()
            val x1 = bb.getDouble(2).toFloat(); val y1 = bb.getDouble(3).toFloat()
            // 8 位元：4 位元的話反鋸齒的半透明邊緣精度不夠，視差移動時會看到細細的深色邊
            val bmp = Bitmap.createBitmap(ceil((x1 - x0) * scale).toInt(), ceil(minOf(y1 - y0, maxH) * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            val cv = Canvas(bmp)
            cv.scale(scale, scale); cv.translate(-x0, -y0)
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
            return bmp
        }
        val arr = root.getJSONArray("layers")
        val out = List(arr.length()) { li ->
            val L = arr.getJSONObject(li)
            val bb = L.getJSONArray("bbox")
            val x0 = bb.getDouble(0).toFloat(); val y0 = bb.getDouble(1).toFloat()
            val x1 = bb.getDouble(2).toFloat(); val y1 = bb.getDouble(3).toFloat()
            val bmp = render(L, SCALE)
            bmp.prepareToDraw()
            Layer(L.getString("name"), L.getDouble("K").toFloat(), L.getDouble("topY").toFloat(), x0, y0, x1 - x0, y1 - y0, bmp)
        }
        computeCover(out)
        // 原生解析度：先讓小圖版本可以用（layers 先交出去），再一層一層畫大圖、轉成硬體點陣圖（只佔顯示記憶體）
        layers = out
        for ((li, layer) in out.withIndex()) {
            val maxH = layer.tileBottom.maxOrNull() ?: layer.h
            val big = render(arr.getJSONObject(li), NATIVE, maxH)
            layer.native = if (android.os.Build.VERSION.SDK_INT >= 26) big.copy(Bitmap.Config.HARDWARE, false)?.also { big.recycle() } ?: big else big
        }
        nativeReady = true
        return out
    }

    /**
     * 算每一層「被前面的山完全蓋住」的範圍（邏輯解析度 480×270 的遮罩），存成每個直條畫到哪裡為止。
     * 視差：前後兩層的相對位移最多 W/2·Δk（水平）、H/2·0.35·Δk（垂直），用最壞的情況往內縮。
     */
    private fun computeCover(layers: List<Layer>) {
        val W = 480; val H = 270
        val n = layers.size
        val opaque = Array(n) { li ->
            val L = layers[li]; val bw = L.image.width; val bh = L.image.height
            val px = IntArray(bw * bh); L.image.getPixels(px, 0, bw, 0, 0, bw, bh)
            BooleanArray(W * H).also { m ->
                for (y in 0 until H) for (x in 0 until W) {
                    val bx = ((x + .5f - L.x0) * SCALE).toInt(); val by = ((y + .5f - L.y0) * SCALE).toInt()
                    if (bx in 0 until bw && by in 0 until bh && (px[by * bw + bx] ushr 24) >= 250) m[y * W + x] = true
                }
            }
        }
        val union = BooleanArray(W * H)
        val solid = IntArray(W)
        for (li in n - 1 downTo 0) {
            val L = layers[li]
            val tiles = kotlin.math.ceil(L.w / TILE).toInt()
            if (li == n - 1) {
                L.tileBottom = FloatArray(tiles) { L.h }
            } else {
                // 前面所有層的聯集：每一欄從哪一列開始一路蓋到底
                for (x in 0 until W) { var y = H; while (y > 0 && union[(y - 1) * W + x]) y--; solid[x] = y }
                val kMax = layers.subList(li + 1, n).maxOf { it.k }
                val dx = (W / 2f * (kMax - L.k)).toInt() + 2
                val vm = H / 2f * .35f * (kMax - L.k) + 3f
                L.tileBottom = FloatArray(tiles) { t ->
                    val a = (L.x0 + t * TILE).toInt() - dx; val b = (L.x0 + (t + 1) * TILE).toInt() + dx
                    var m = 0
                    for (x in a..b) m = maxOf(m, if (x in 0 until W) solid[x] else H)
                    (m + vm - L.y0).coerceIn(0f, L.h)
                }
            }
            if (li < n - 1) {
                val a8 = ByteArray(W * H) { if (union[it]) -1 else 0 }
                L.frontMask = Bitmap.createBitmap(W, H, Bitmap.Config.ALPHA_8).apply { copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(a8)) }
            }
            val o = opaque[li]
            for (i in union.indices) if (o[i]) union[i] = true
        }
        // 天空最低點：全部山的聯集往下一路蓋到底的那一列，取最低的一欄，再留視差的餘量
        val col = IntArray(W)
        for (x in 0 until W) { var y = H; while (y > 0 && union[(y - 1) * W + x]) y--; col[x] = y }
        skyBottom = ((col.maxOrNull() ?: H) + 12).coerceAtMost(H).toFloat()
        val strips = kotlin.math.ceil(W / TILE).toInt()
        skyStrips = FloatArray(strips) { s ->
            var m = 0
            for (x in (s * TILE).toInt() - 8..((s + 1) * TILE).toInt() + 8) m = maxOf(m, col[x.coerceIn(0, W - 1)])
            (m + 10).coerceAtMost(H).toFloat()
        }
    }

    // ── 山嵐（高斯霧）的貼圖：很小的 ALPHA_8 圖，畫的時候用畫筆顏色上色、雙線性放大＝高斯模糊般的柔邊 ──
    private const val FOG_W = 160
    private const val FOG_H = 36
    private val fogTex = arrayOfNulls<Bitmap>(3)

    /**
     * 第 [v] 種霧帶的著色器：水平重複、垂直夾住，縮放成寬 [tw]、高 [h]，從 ([off], [top]) 開始。
     * 每次新建（很便宜）：背景執行緒與畫面執行緒都會畫霧，不能共用同一個會改矩陣的物件。
     */
    fun fogShader(v: Int, off: Float, top: Float, tw: Float, h: Float): android.graphics.Shader =
        android.graphics.BitmapShader(fog(v), android.graphics.Shader.TileMode.REPEAT, android.graphics.Shader.TileMode.CLAMP).apply {
            setLocalMatrix(Matrix().apply { setScale(tw / FOG_W, h / FOG_H); postTranslate(off, top) })
        }

    /** 第 [v] 種霧帶（0～2，濃淡分布不同）。水平方向首尾相接，可以無縫重複。 */
    fun fog(v: Int): Bitmap = fogTex[v] ?: buildFog(v).also { fogTex[v] = it }

    private fun buildFog(v: Int): Bitmap {
        val px = ByteArray(FOG_W * FOG_H)
        val tau = (2 * Math.PI).toFloat()
        val rnd = java.util.Random(700L + v * 11L)
        val ph = FloatArray(6) { rnd.nextFloat() * tau }
        for (i in 0 until FOG_W) {
            val u = i / FOG_W.toFloat() * tau
            // 濃淡：幾個整數頻率的正弦相加（首尾接得起來），有濃有稀、偶爾幾乎斷開
            val d = (.5f + .26f * kotlin.math.sin(u + ph[0]) + .18f * kotlin.math.sin(2 * u + ph[1]) + .12f * kotlin.math.sin(3 * u + ph[2]) + .07f * kotlin.math.sin(5 * u + ph[3]))
                .coerceIn(0f, 1f)
            val dd = d * d * (3 - 2 * d)
            // 霧帶的中線與厚度也慢慢起伏
            val c = .5f + .1f * kotlin.math.sin(2 * u + ph[4]) + .05f * kotlin.math.sin(4 * u + ph[5])
            val sig = .12f + .16f * dd
            for (j in 0 until FOG_H) {
                val y = (j + .5f) / FOG_H
                val g = kotlin.math.exp(-((y - c) * (y - c)) / (2 * sig * sig))
                // 上下兩端一定要淡到 0（放大後不會切出一條邊）
                val edge = (kotlin.math.min(y, 1f - y) / .12f).coerceIn(0f, 1f)
                px[j * FOG_W + i] = (255f * dd * g * edge).toInt().coerceIn(0, 255).toByte()
            }
        }
        val argb = IntArray(FOG_W * FOG_H) { ((px[it].toInt() and 0xFF) shl 24) or 0xFFFFFF }
        return Bitmap.createBitmap(argb, FOG_W, FOG_H, Bitmap.Config.ARGB_8888)
    }

    // ── 星軌：面向南方，星星繞著地平線下的南天極轉。先畫成一張同心圓弧的圖，每格只繞著天極慢慢旋轉 ──
    /** 南天極在畫面上的位置（邏輯座標，在山的後面）與星軌圖涵蓋的半徑。 */
    const val POLE_X = 480f * .56f
    const val POLE_Y = 270f * 1.02f
    const val TRAIL_R = 330f
    private const val TRAIL_SCALE = 1.2f
    @Volatile var trails: Bitmap? = null
        private set
    /** 高解析度的星軌（硬體點陣圖，每個邏輯單位 [TRAIL_HI] 像素）：主畫面以原生解析度旋轉著畫，線條清楚。 */
    @Volatile var trailsHi: Bitmap? = null
        private set
    const val TRAIL_HI = 3f
    private val trailsStarted = AtomicBoolean(false)

    fun requestTrails() {
        if (!trailsStarted.compareAndSet(false, true)) return
        Thread({
            trails = runCatching { buildTrails(TRAIL_SCALE) }.getOrNull()
            trailsHi = runCatching { buildTrails(TRAIL_HI).let { b -> b.copy(Bitmap.Config.HARDWARE, false)?.also { b.recycle() } ?: b } }.getOrNull()
        }, "liuli-trails").apply { priority = Thread.MIN_PRIORITY; start() }
    }

    private fun h(n: Int, s: Int): Float {
        var x = n * 374761393 + s * 668265263
        x = (x xor (x ushr 13)) * 1274126177
        return ((x xor (x ushr 16)) and 0xFFFFFF) / 16777216f
    }

    private fun buildTrails(scale: Float): Bitmap {
        val size = ceil(TRAIL_R * 2 * scale).toInt()
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        cv.scale(scale, scale); cv.translate(TRAIL_R, TRAIL_R)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.BUTT }
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
                // 一整段弧，用掃角漸層由尾（透明）到頭（最亮）：沒有分段，就沒有接頭重疊的亮點
                val f = (len / 360f).coerceIn(.002f, .99f)
                val head = android.graphics.Color.argb((bright * 255).toInt().coerceIn(0, 255), cr, cg, cb)
                val mid = android.graphics.Color.argb((bright * .25f * 255).toInt().coerceIn(0, 255), cr, cg, cb)
                val clear = android.graphics.Color.argb(0, cr, cg, cb)
                val sg = android.graphics.SweepGradient(0f, 0f, intArrayOf(clear, mid, head, clear), floatArrayOf(0f, f * .5f, f, (f + .001f).coerceAtMost(1f)))
                sg.setLocalMatrix(Matrix().apply { setRotate(phi - len) })
                p.shader = sg
                cv.drawArc(oval, phi - len, len, false, p)
                p.shader = null
            }
            r += 1.6f; ring++
        }
        return bmp
    }

    /** 星軌每秒轉幾度（真實是每小時 15°，這裡加快到看得出在流動）。 */
    const val TRAIL_DEG_PER_SEC = .6f
    fun trailRect(out: RectF) { out.set(POLE_X - TRAIL_R, POLE_Y - TRAIL_R, POLE_X + TRAIL_R, POLE_Y + TRAIL_R) }
}
