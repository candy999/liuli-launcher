package tw.liuli.launcher.ui.home

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 畫面黑框（像電視邊框／暗角）：[thicknessDp] 粗細、[radiusDp] 內側圓角、[featherDp] 往內漸變到透明的範圍。
 *
 * - 放在背景與「會左右捲動的列」之上、固定 UI 與抽屜／面板之下（由呼叫端決定疊放順序）。
 * - 永遠以原生解析度繪製，不受背景動態降解析度影響。
 * - 遮罩只在設定改變時於背景執行緒算一次（有號距離場，邊緣含抗鋸齒）；每一幀只貼四條邊，中間不花任何填色成本。
 */
@Composable
fun FrameOverlay(thicknessDp: Int, radiusDp: Int, featherDp: Int, modifier: Modifier = Modifier) {
    if (thicknessDp <= 0 && featherDp <= 0) return
    val density = LocalDensity.current.density
    BoxWithConstraints(modifier.fillMaxSize()) {
        val w = constraints.maxWidth
        val h = constraints.maxHeight
        val t = thicknessDp * density
        val r = radiusDp * density
        val f = featherDp * density
        val mask by produceState<FrameMask?>(null, w, h, t, r, f) {
            value = withContext(Dispatchers.Default) { buildFrameMask(w, h, t, r, f) }
        }
        val m = mask ?: return@BoxWithConstraints
        Canvas(Modifier.fillMaxSize()) {
            val k = m.band
            if (k * 2 >= h || k * 2 >= w) {
                drawImage(m.image)
            } else {
                // 上、下兩條全寬，左、右兩條夾在中間
                drawImage(m.image, IntOffset(0, 0), IntSize(w, k), IntOffset(0, 0), IntSize(w, k))
                drawImage(m.image, IntOffset(0, h - k), IntSize(w, k), IntOffset(0, h - k), IntSize(w, k))
                drawImage(m.image, IntOffset(0, k), IntSize(k, h - 2 * k), IntOffset(0, k), IntSize(k, h - 2 * k))
                drawImage(m.image, IntOffset(w - k, k), IntSize(k, h - 2 * k), IntOffset(w - k, k), IntSize(k, h - 2 * k))
            }
        }
    }
}

private class FrameMask(val image: ImageBitmap, val band: Int)

/**
 * 黑色遮罩：內側圓角矩形（往內縮 t、圓角 r）以外全黑；往內 f 的範圍用 smoothstep 漸變到透明。
 * 只計算四邊寬度 [FrameMask.band] 內的像素，中間保持透明。
 */
private suspend fun buildFrameMask(w: Int, h: Int, t: Float, rIn: Float, f: Float): FrameMask? {
    if (w <= 0 || h <= 0) return null
    val hw = w / 2f - t
    val hh = h / 2f - t
    if (hw <= 0f || hh <= 0f) return null
    val r = min(rIn, min(hw, hh))
    val band = min(max(w, h), ceil(t + f + r).toInt() + 2)
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val row = IntArray(w)
    val cx = w / 2f
    val cy = h / 2f
    for (y in 0 until h) {
        kotlin.coroutines.coroutineContext.ensureActive()
        val full = y < band || y >= h - band
        java.util.Arrays.fill(row, 0)
        val py = kotlin.math.abs(y + 0.5f - cy)
        val qy = py - (hh - r)
        fun px(x: Int) {
            val qx = kotlin.math.abs(x + 0.5f - cx) - (hw - r)
            val ox = max(qx, 0f)
            val oy = max(qy, 0f)
            // 圓角矩形的有號距離：外面為正、裡面為負
            val sd = sqrt(ox * ox + oy * oy) + min(max(qx, qy), 0f) - r
            val a = if (f < 0.5f) {
                (sd + 0.5f).coerceIn(0f, 1f) // 硬邊：一個像素的抗鋸齒
            } else {
                val u = ((sd + f) / f).coerceIn(0f, 1f)
                u * u * (3f - 2f * u) // smoothstep：邊緣全黑 → 往內 f 漸變到透明
            }
            val ai = (a * 255f + 0.5f).toInt()
            if (ai > 0) row[x] = ai shl 24
        }
        if (full) {
            for (x in 0 until w) px(x)
            bmp.setPixels(row, 0, w, 0, y, w, 1)
        } else {
            for (x in 0 until band) px(x)
            for (x in w - band until w) px(x)
            bmp.setPixels(row, 0, band, 0, y, band, 1)
            bmp.setPixels(row, w - band, band, w - band, y, band, 1)
        }
    }
    return FrameMask(bmp.asImageBitmap(), band)
}
