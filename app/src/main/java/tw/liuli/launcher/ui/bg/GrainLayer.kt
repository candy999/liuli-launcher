package tw.liuli.launcher.ui.bg

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap

/**
 * 細緻噪點：以原生解析度、1:1 像素加在背景上（所有介面之下），每個通道各自獨立，只有 1～3 個色階。
 *
 * 背景的漸層本身已經開了抖色（[DitherScope]、模糊的最後一步），這一層是額外的保險：
 * 背景在弱電視上會以 ÷2～÷5 的解析度繪製再放大，放大時的內插又會產生細小的階梯；
 * 在原生解析度補上幾個色階的噪點，把殘餘的色帶邊緣打散。強度只有 1～3／255，近看也幾乎看不到顆粒。
 *
 * - 噪點用數學產生（xorshift 亂數、三角分佈＝兩個均勻亂數相加，比單一均勻亂數更像真正的抖色），不用圖片資源。
 * - 「加法」混合：噪點值直接加到背景的 R、G、B 上（不是蓋一層半透明圖），不改變色相、不讓畫面變霧。
 *   平均會亮 [level]／255，肉眼分辨不出。
 * - 每幀只多一個貼圖四邊形；不跟著焦點位移（太細，位移看不出來，靜止反而最乾淨）。
 */
@Composable
fun GrainLayer(level: Int, modifier: Modifier = Modifier) {
    if (level <= 0) return
    val brush = remember(level) { ShaderBrush(ImageShader(makeGrain(level), TileMode.Repeated, TileMode.Repeated)) }
    Canvas(modifier.fillMaxSize()) {
        drawRect(brush, blendMode = BlendMode.Plus)
    }
}

/** 噪點強度的名稱（0＝關）。 */
val GRAIN_LABELS = listOf("關", "極細", "細", "中")

private const val TILE = 256

/** 每個通道各自：0～2×[level] 的三角分佈整數噪點（平均 [level]）。不透明，用加法混合疊上去。 */
private fun makeGrain(level: Int): androidx.compose.ui.graphics.ImageBitmap {
    val px = IntArray(TILE * TILE)
    var z = 0x6D2B79F5
    fun next(): Int {
        z = z xor (z shl 13); z = z xor (z ushr 17); z = z xor (z shl 5)
        return z
    }
    val span = level + 1
    fun tri(n: Int): Int = ((n and 0xFFFF) * span ushr 16) + (((n ushr 16) and 0xFFFF) * span ushr 16)
    for (i in px.indices) {
        val r = tri(next())
        val g = tri(next())
        val b = tri(next())
        px[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }
    return Bitmap.createBitmap(px, TILE, TILE, Bitmap.Config.ARGB_8888).asImageBitmap()
}
