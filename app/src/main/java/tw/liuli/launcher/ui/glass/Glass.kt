package tw.liuli.launcher.ui.glass

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize

/**
 * 玻璃效果等級（規格表 3.2）。
 * HIGH：動態桌布＋即時模糊；MEDIUM：靜態桌布＋模糊；LOW：不模糊，只用半透明與高光邊。
 */
enum class GlassLevel(val label: String) {
    HIGH("高（動態＋模糊）"),
    MEDIUM("中（模糊）"),
    LOW("低（半透明）"),
}

fun detectGlassLevel(context: Context): GlassLevel {
    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val memory = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
    val totalGb = memory.totalMem / (1024.0 * 1024.0 * 1024.0)
    return when {
        // RenderEffect 模糊需要 Android 12（API 31）
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.isLowRamDevice || totalGb < 1.6 -> GlassLevel.LOW
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && totalGb >= 2.6 -> GlassLevel.HIGH
        else -> GlassLevel.MEDIUM
    }
}

/**
 * 玻璃背後的「景」。玻璃面板不擷取螢幕，而是把同一張桌布在自己的位置再畫一次並模糊，
 * 效果等同背景模糊，成本卻低得多。之後換成網頁桌布時，這裡改成定期擷取的畫面。
 */
class Backdrop(
    val level: GlassLevel,
    val phase: State<Float>,
)

val LocalBackdrop = staticCompositionLocalOf<Backdrop> { error("Backdrop 未提供") }

/**
 * 液態玻璃面板。
 * @param highlight 0～1，焦點強度；越高高光邊越亮、玻璃越白。
 * @param blur 是否模糊背景。App 圖塊等會被圖片蓋滿的元件設 false 省效能。
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(28.dp),
    highlight: Float = 0f,
    blur: Boolean = true,
    blurRadius: Dp = 30.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    val backdrop = LocalBackdrop.current
    var origin by remember { mutableStateOf(Offset.Zero) }
    var rootSize by remember { mutableStateOf(Size.Zero) }
    val useBlur = blur && backdrop.level != GlassLevel.LOW

    Box(
        modifier
            .onGloballyPositioned { coords ->
                origin = coords.positionInRoot()
                rootSize = coords.findRootCoordinates().size.toSize()
            }
            .clip(shape),
    ) {
        if (useBlur) {
            Canvas(Modifier.matchParentSize().blur(blurRadius, BlurredEdgeTreatment.Rectangle)) {
                translate(-origin.x, -origin.y) {
                    drawLiquidWallpaper(backdrop.phase.value, rootSize)
                }
            }
        }
        Box(
            Modifier
                .matchParentSize()
                .drawBehind {
                    val outline = shape.createOutline(size, layoutDirection, this)
                    // 1. 玻璃本體：模糊時用淡白，無模糊時加一層深色讓文字可讀
                    val baseTop = if (useBlur) 0.16f else 0.20f
                    val baseBottom = if (useBlur) 0.06f else 0.10f
                    if (!useBlur) drawOutline(outline, Color(0x8C141A2E))
                    drawOutline(
                        outline,
                        Brush.verticalGradient(
                            listOf(
                                Color.White.copy(alpha = baseTop + 0.10f * highlight),
                                Color.White.copy(alpha = baseBottom + 0.06f * highlight),
                            ),
                        ),
                    )
                },
        )
        content()
        // 光澤與高光邊畫在內容之上，圖片蓋滿時仍看得到玻璃邊
        Box(
            Modifier
                .matchParentSize()
                .drawBehind {
                    val outline = shape.createOutline(size, layoutDirection, this)
                    // 2. 上緣光澤：模擬光線從上方打在曲面玻璃
                    drawRect(
                        Brush.verticalGradient(
                            0f to Color.White.copy(alpha = 0.14f + 0.12f * highlight),
                            0.35f to Color.Transparent,
                        ),
                    )
                    // 3. 高光邊（specular rim）：上亮、中暗、下緣微亮
                    drawOutline(
                        outline,
                        Brush.verticalGradient(
                            0f to Color.White.copy(alpha = 0.60f + 0.40f * highlight),
                            0.45f to Color.White.copy(alpha = 0.08f + 0.20f * highlight),
                            1f to Color.White.copy(alpha = 0.28f + 0.30f * highlight),
                        ),
                        style = Stroke(width = (2f + 1.5f * highlight) * density),
                    )
                },
        )
    }
}

/** 根層桌布。 */
@Composable
fun LiquidWallpaper(phase: State<Float>, modifier: Modifier = Modifier.fillMaxSize()) {
    Canvas(modifier) { drawLiquidWallpaper(phase.value, size) }
}
