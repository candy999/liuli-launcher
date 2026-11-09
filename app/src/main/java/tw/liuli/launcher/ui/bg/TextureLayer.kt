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

/**
 * 背景紋理（tools/make_textures.py 產生的無接縫貼圖）。
 * [depth]：視差深度倍數——顆粒細的紋理（底片、磨砂）位移少一點，看起來貼在表面；紙與布多一點。
 */
enum class BgTexture(val id: String, val label: String, val note: String, val res: Int, val depth: Float) {
    FIBER("fiber", "纖維紙", "細顆粒＋細短纖維，最安靜", R.drawable.tex_fiber, 1f),
    COLD("cold", "冷壓水彩紙", "一顆顆隆起的紙紋，浮雕最明顯", R.drawable.tex_cold, 1f),
    WASHI("washi", "和紙", "長而半透明的纖維，幾乎沒有起伏", R.drawable.tex_washi, 1f),
    LINEN("linen", "亞麻畫布", "細密的經緯編織，紗線粗細不均", R.drawable.tex_linen, 1f),
    FILM("film", "底片顆粒", "純顆粒、沒有浮雕，也能打散色帶", R.drawable.tex_film, 0.6f),
    FROST("frost", "磨砂玻璃", "細小的凹點與零星的閃光", R.drawable.tex_frost, 0.6f),
    RECYCLED("recycled", "再生紙", "短纖維＋深淺不一的雜質斑點", R.drawable.tex_recycled, 1f);

    companion object {
        /** null＝關。 */
        fun byId(id: String?): BgTexture? = entries.firstOrNull { it.id == id }
    }
}

/**
 * 背景紋理層：疊在背景之上、所有介面之下（黑框、App 列、時鐘都在它上面）。
 *
 * - 每個像素是「白＋透明度」或「黑＋透明度」，一般的 alpha 混合就能提亮／壓暗，不用昂貴的 soft-light。
 *   [opacity]（0～100）乘在貼圖的透明度上。
 * - 永遠以原生解析度、1:1 像素貼上，不受背景動態降解析度或模糊影響。
 * - 視差：焦點移動時紋理往反方向位移（最多 ±[PARALLAX]×深度），像背景畫在一張紙上、紙跟著輕輕滑動。
 *   位移取整數像素——半像素位移會被雙線性取樣抹糊、紋理對比忽強忽弱。貼圖無限重複，所以「紋理比畫面大」不用多花記憶體。
 * - 每幀只多一個全螢幕貼圖四邊形（海信 Mali-470 實測每幀約多 1～2ms）；不重新產生紋理。
 */
@Composable
fun TextureLayer(engine: BackgroundEngine, texture: BgTexture?, opacity: Int, modifier: Modifier = Modifier) {
    if (texture == null || opacity <= 0) return
    val tile = ImageBitmap.imageResource(texture.res)
    val brush = remember(tile) { ShaderBrush(ImageShader(tile, TileMode.Repeated, TileMode.Repeated)) }
    val alpha = (opacity / 100f).coerceIn(0f, 1f)
    val range = with(LocalDensity.current) { PARALLAX.toPx() } * texture.depth
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
