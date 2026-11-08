package tw.liuli.launcher.ui.glass

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tw.liuli.launcher.context.HomeContext
import tw.liuli.launcher.settings.LauncherSettings

/**
 * 程式產生的靜態桌布。每組配色只用一個主色相＋鄰近色（避免俗氣），
 * 加上銳利邊緣的流線色帶，讓玻璃後的模糊看得出層次。
 * 演算法與 design/prototype.html 相同。
 */
enum class WallpaperPalette(
    val id: String,
    val label: String,
    val base: Pair<Int, Int>,
    val blobs: List<Blob>,
    val ribbons: List<Ribbon>,
) {
    NIGHT(
        "night", "深海", 0xFF05081A.toInt() to 0xFF0B1330.toInt(),
        listOf(Blob(0x2747D9, .10f, .18f, .55f, .55f), Blob(0x3A2FB0, .88f, .12f, .45f, .45f), Blob(0x0D6F8A, .70f, .98f, .60f, .50f)),
        listOf(Ribbon(0x1B3FBF, 0x1590A8, .60f, .80f, .55f), Ribbon(0x2A2A8F, 0x2A6FD6, .78f, .95f, .45f)),
    ),
    DUSK(
        "dusk", "暮光", 0xFF140B22.toInt() to 0xFF2A1430.toInt(),
        listOf(Blob(0xC2502E, .15f, .95f, .55f, .45f), Blob(0x6B2D9E, .85f, .15f, .45f, .45f), Blob(0xD98A3A, .60f, .70f, .35f, .30f)),
        listOf(Ribbon(0xB5482F, 0x7A2F8F, .62f, .80f, .55f), Ribbon(0x5A2A85, 0xC27A3A, .80f, .95f, .45f)),
    ),
    DAY(
        "day", "晴空", 0xFF2D5F9E.toInt() to 0xFF6FA3D6.toInt(),
        listOf(Blob(0xCFE6FF, .82f, .10f, .45f, .55f), Blob(0x3FB3B0, .10f, .95f, .55f, .45f), Blob(0x5B8FE0, .30f, .20f, .45f, .40f)),
        listOf(Ribbon(0x2F9FB8, 0x3B6FD0, .64f, .82f, .55f), Ribbon(0x9FD4FF, 0x4A8BE0, .80f, .95f, .45f)),
    ),
    FOREST(
        "forest", "夜林", 0xFF03110D.toInt() to 0xFF08221B.toInt(),
        listOf(Blob(0x0E7A5F, .18f, .25f, .55f, .50f), Blob(0x1F4F9E, .88f, .85f, .50f, .40f), Blob(0x3F8F4A, .62f, .10f, .40f, .35f)),
        listOf(Ribbon(0x0F8F70, 0x1A5FAE, .62f, .80f, .55f), Ribbon(0x2F8F5A, 0x0F7F86, .80f, .95f, .45f)),
    );

    companion object {
        fun byId(id: String) = entries.firstOrNull { it.id == id }

        /** 「自動」：白天晴空、日出日落前後暮光、晚上深海。 */
        fun resolve(setting: String, home: HomeContext): WallpaperPalette {
            byId(setting)?.let { return it }
            val sun = home.sunProgress
            return when {
                sun < 0 -> NIGHT
                sun < 0.08 || sun > 0.88 -> DUSK
                else -> DAY
            }
        }

        val choices: List<Pair<String, String>> =
            listOf(LauncherSettings.AUTO to "依時間自動") + entries.map { it.id to it.label }
    }
}

data class Blob(val rgb: Int, val x: Float, val y: Float, val r: Float, val alpha: Float)
data class Ribbon(val from: Int, val to: Int, val top: Float, val bottom: Float, val alpha: Float)

/** 清晰版（畫在背景）與預先模糊版（給玻璃取樣）。 */
@Immutable
class WallpaperImages(
    val palette: WallpaperPalette,
    val sharp: Bitmap,
    val blurred: Bitmap,
)

/** 玻璃需要知道背景長什麼樣子、以及整個畫面多大（桌布座標＝畫面座標）。 */
@Immutable
class GlassBackdrop(
    val images: WallpaperImages?,
    val screen: Size,
    val lensing: Boolean,
)

val LocalGlassBackdrop = staticCompositionLocalOf { GlassBackdrop(null, Size.Zero, false) }

/** 配色或畫面大小改變時，在背景執行緒重新產生一次；平常完全不耗 GPU。 */
@Composable
fun rememberWallpaper(palette: WallpaperPalette, width: Int, height: Int): State<WallpaperImages?> =
    produceState<WallpaperImages?>(null, palette, width, height) {
        if (width <= 0 || height <= 0) return@produceState
        value = withContext(Dispatchers.Default) { WallpaperRenderer.render(palette, width, height) }
    }

object WallpaperRenderer {

    /** 清晰版用 1/2 解析度（色塊柔和，看不出差別），模糊版約 1/8。 */
    fun render(p: WallpaperPalette, screenW: Int, screenH: Int): WallpaperImages {
        val w = (screenW / 2).coerceAtLeast(16)
        val h = (screenH / 2).coerceAtLeast(9)
        val sharp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        paint(Canvas(sharp), w.toFloat(), h.toFloat(), p)
        return WallpaperImages(p, sharp, blur(sharp))
    }

    private fun paint(c: Canvas, w: Float, h: Float, p: WallpaperPalette) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
        paint.shader = LinearGradient(0f, 0f, w * 0.3f, h, p.base.first, p.base.second, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, paint)

        for (b in p.blobs) {
            val r = b.r * w
            paint.shader = RadialGradient(
                b.x * w, b.y * h, r,
                intArrayOf(argb(b.rgb, b.alpha), argb(b.rgb, b.alpha * 0.35f), argb(b.rgb, 0f)),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP,
            )
            c.drawRect(0f, 0f, w, h, paint)
        }

        p.ribbons.forEachIndexed { i, rb ->
            val y0 = rb.top * h
            val y1 = rb.bottom * h
            val s = h / 540f // 原型以 540 高設計
            paint.shader = LinearGradient(0f, 0f, w, 0f, argb(rb.from, rb.alpha), argb(rb.to, rb.alpha), Shader.TileMode.CLAMP)
            val path = Path().apply {
                moveTo(0f, y0 + 30 * s)
                cubicTo(w * .3f, y0 - 70 * s, w * .6f, y0 + 90 * s, w, y0 - (20 + i * 30) * s)
                lineTo(w, y1)
                cubicTo(w * .65f, y1 + 40 * s, w * .35f, y1 - 60 * s, 0f, y1 + 10 * s)
                close()
            }
            c.drawPath(path, paint)
        }
    }

    /**
     * 逐級縮小再放大的近似高斯模糊（每一級都用雙線性濾波），最後提高飽和度與一點亮度，
     * 這就是 iOS 玻璃「比背景更鮮豔」的感覺。只在產生桌布時做一次。
     */
    private fun blur(src: Bitmap): Bitmap {
        var cur = src
        val steps = 4 // 1/2 → 1/32 of screen
        repeat(steps) {
            val next = Bitmap.createScaledBitmap(cur, (cur.width / 2).coerceAtLeast(2), (cur.height / 2).coerceAtLeast(2), true)
            if (cur !== src) cur.recycle()
            cur = next
        }
        // 放大回 1/8 畫面，讓取樣時足夠平滑
        repeat(2) {
            val next = Bitmap.createScaledBitmap(cur, cur.width * 2, cur.height * 2, true)
            cur.recycle()
            cur = next
        }
        val out = Bitmap.createBitmap(cur.width, cur.height, Bitmap.Config.ARGB_8888)
        val matrix = ColorMatrix().apply {
            setSaturation(1.4f)
            postConcat(ColorMatrix(floatArrayOf(
                1.06f, 0f, 0f, 0f, 6f,
                0f, 1.06f, 0f, 0f, 6f,
                0f, 0f, 1.06f, 0f, 6f,
                0f, 0f, 0f, 1f, 0f,
            )))
        }
        Canvas(out).drawBitmap(cur, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = ColorMatrixColorFilter(matrix) })
        cur.recycle()
        return out
    }

    private fun argb(rgb: Int, alpha: Float): Int =
        ((alpha.coerceIn(0f, 1f) * 255).toInt() shl 24) or (rgb and 0xFFFFFF)
}
