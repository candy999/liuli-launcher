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

/**
 * 焦點進入：低阻尼彈簧——起步快、放大時衝過頭一點（約多 1～2% 的大小）再回彈定位，有「被按下去又彈起來」的彈性。
 * 只有縮放用超過 1 的值（FocusAnim.raw），透明度、陰影等用的是夾在 0～1 的 value。
 */
val FocusSpring = spring<Float>(dampingRatio = 0.52f, stiffness = 420f)

/** 焦點離開：不回彈，俐落收回（離開的東西不該搶眼）。 */
val FocusOutSpring = spring<Float>(dampingRatio = 1f, stiffness = 650f)

/** 面板／抽屜滑入：先快後慢，衝過停靠位置一點點再回彈定位。 */
val SheetSpring = spring<Float>(dampingRatio = 0.7f, stiffness = 330f)

/** 面板／抽屜收起：先慢後快地加速離開，不回彈。 */
val SheetExit = androidx.compose.animation.core.tween<Float>(
    durationMillis = 230, easing = androidx.compose.animation.core.FastOutLinearInEasing,
)

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
