package tw.liuli.launcher.ui.theme

import androidx.compose.animation.core.spring
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 設計規則（docs/ux-design.md）：UI 本身只有白色與一個暖金強調色，
 * 其餘色彩只來自桌布與 App 圖示。
 */
object Ink {
    val Primary = Color.White
    val Secondary = Color.White.copy(alpha = 0.72f)
    val Tertiary = Color.White.copy(alpha = 0.50f)
    val Faint = Color.White.copy(alpha = 0.28f)
    /** 唯一的強調色：節氣、節日、拜拜日。 */
    val Gold = Color(0xFFFFD99A)
    val GoldSoft = Color(0x2EFFC878)
    /** 側邊面板焦點列：白底深字。 */
    val OnLight = Color(0xFF111318)
}

object Dimens {
    val SafeH = 48.dp
    val SafeV = 28.dp
    val PanelRadius = 26.dp
    val ShelfRadius = 34.dp
    val TileRadius = 18.dp
}

/** 焦點彈簧：偏硬、輕微回彈，跟手不拖泥帶水。 */
val FocusSpring = spring<Float>(dampingRatio = 0.78f, stiffness = 700f)

/** 面板滑入。 */
val SheetSpring = spring<Float>(dampingRatio = 0.9f, stiffness = 500f)

private val TextShadow = Shadow(Color(0x40000000), blurRadius = 16f)

@Composable
fun GText(
    text: String,
    modifier: Modifier = Modifier,
    size: TextUnit = 16.sp,
    weight: FontWeight = FontWeight.Normal,
    color: Color = Ink.Primary,
    maxLines: Int = 1,
    align: TextAlign? = null,
    shadow: Boolean = false,
    letterSpacing: TextUnit = TextUnit.Unspecified,
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = TextStyle(
            color = color,
            fontSize = size,
            fontWeight = weight,
            textAlign = align ?: TextAlign.Unspecified,
            shadow = if (shadow) TextShadow else null,
            letterSpacing = letterSpacing,
        ),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}
