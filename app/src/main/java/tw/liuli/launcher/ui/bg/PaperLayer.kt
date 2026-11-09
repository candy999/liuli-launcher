package tw.liuli.launcher.ui.bg

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.unit.dp
import tw.liuli.launcher.R
import kotlin.math.roundToInt

/** 紙張紋理強度（等級 1～5 → 貼圖的不透明度倍數；貼圖本身最大 0.5，所以最強約 0.21）。 */
val PAPER_ALPHAS = floatArrayOf(0f, .12f, .18f, .25f, .33f, .42f)

/**
 * 紙張紋理：疊在背景之上、所有介面之下（黑框、App 列、時鐘都在它上面）。
 *
 * - 預先產生的 512×512 無接縫貼圖（tools/make_paper.py）：細顆粒＋纖維＋淡淡的斑駁，左上打光的浮雕；
 *   每個像素是「白＋透明度」或「黑＋透明度」，一般的 alpha 混合就能提亮／壓暗，不用昂貴的 soft-light。
 * - 永遠以原生解析度、1:1 像素貼上，不受背景動態降解析度或模糊影響。
 * - 視差：焦點移動時紋理往反方向位移（最多 ±[PARALLAX]），像背景畫在一張紙上、紙跟著輕輕滑動。
 *   位移取整數像素——半像素位移會被雙線性取樣抹糊、紋理對比忽強忽弱。貼圖無限重複，所以「紋理比畫面大」不用多花記憶體。
 * - 每幀只多一個全螢幕貼圖四邊形（GPU 很便宜）；不重新產生紋理。
 */
@Composable
fun PaperLayer(engine: BackgroundEngine, level: Int, modifier: Modifier = Modifier) {
    if (level <= 0) return
    val tile = ImageBitmap.imageResource(R.drawable.paper_tile)
    val brush = remember(tile) { ShaderBrush(ImageShader(tile, TileMode.Repeated, TileMode.Repeated)) }
    val alpha = PAPER_ALPHAS[level.coerceIn(0, PAPER_ALPHAS.lastIndex)]
    val range = with(LocalDensity.current) { PARALLAX.toPx() }
    Canvas(modifier.fillMaxSize()) {
        @Suppress("UNUSED_VARIABLE") val f = engine.frame // 訂閱：背景每推進一格就更新位移
        val ox = (-(engine.st.fx - .5f) * 2f * range).roundToInt().toFloat()
        val oy = (-(engine.st.fy - .5f) * 2f * range * .6f).roundToInt().toFloat()
        // 平移的是貼圖座標，矩形本身永遠蓋滿畫面
        translate(ox, oy) {
            drawRect(brush, topLeft = Offset(-ox, -oy), size = size, alpha = alpha)
        }
    }
}

private val PARALLAX = 18.dp
