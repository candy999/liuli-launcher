package tw.liuli.launcher.ui.theme

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import tw.liuli.launcher.context.WeatherKind

/** 線條圖示（24×24、1.6 線寬），不依賴 material-icons。 */
object LIcons {
    private fun line(vararg paths: String): ImageVector =
        ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
            .apply {
                for (p in paths) {
                    addPath(
                        pathData = addPathNodes(p),
                        stroke = SolidColor(Color.White),
                        strokeLineWidth = 1.6f,
                        strokeLineCap = StrokeCap.Round,
                        strokeLineJoin = StrokeJoin.Round,
                    )
                }
            }
            .build()

    private const val CLOUD_LOW = "M7 19h10a4 4 0 0 0 .6-7.96A6 6 0 0 0 6.2 12 3.5 3.5 0 0 0 7 19z"
    private const val CLOUD_HIGH = "M7 15h10a4 4 0 0 0 .6-7.96A6 6 0 0 0 6.2 8 3.5 3.5 0 0 0 7 15z"

    val Input = line("M5 4.5h14a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-8a2 2 0 0 1 2-2z", "M8.5 20.5h7", "M12 16.5v4")
    val Settings = line(
        "M15 12a3 3 0 1 1-6 0a3 3 0 1 1 6 0z",
        "M19.4 15a1.7 1.7 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.8-.3 1.7 1.7 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1a1.7 1.7 0 0 0-1.1-1.5 1.7 1.7 0 0 0-1.8.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0 .3-1.8 1.7 1.7 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1a1.7 1.7 0 0 0 1.5-1.1 1.7 1.7 0 0 0-.3-1.8l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.8.3H9a1.7 1.7 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.8-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.8V9a1.7 1.7 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1z",
    )
    val ChevronLeft = line("M14.5 6l-6 6 6 6")
    val ChevronRight = line("M9.5 6l6 6-6 6")

    val Sun = line(
        "M16 12a4 4 0 1 1-8 0a4 4 0 1 1 8 0z",
        "M12 2.5v2", "M12 19.5v2", "M5.3 5.3l1.4 1.4", "M17.3 17.3l1.4 1.4",
        "M2.5 12h2", "M19.5 12h2", "M5.3 18.7l1.4-1.4", "M17.3 6.7l1.4-1.4",
    )
    val Moon = line("M20.5 13.2A8.5 8.5 0 1 1 10.8 3.5a6.6 6.6 0 0 0 9.7 9.7z")
    val Cloud = line(CLOUD_LOW)
    val Partly = line(
        "M9 3v1.2", "M4.4 4.9l.9.9", "M2.5 9.5h1.2", "M13.6 4.9l-.9.9",
        "M11.6 10.4A3 3 0 1 0 6.3 12",
        "M9.5 20h7.5a3.5 3.5 0 0 0 .5-6.96A5 5 0 0 0 8.8 14 3 3 0 0 0 9.5 20z",
    )
    val Rain = line(CLOUD_HIGH, "M8.5 18l-1 2.8", "M12.5 18l-1 2.8", "M16.5 18l-1 2.8")
    val Drizzle = line(CLOUD_HIGH, "M9 18.5v1", "M13 18.5v1", "M17 18.5v1")
    val Snow = line(CLOUD_HIGH, "M8.5 19h.01", "M12.5 20.5h.01", "M16.5 19h.01")
    val Thunder = line(CLOUD_HIGH, "M13 16l-2.5 3.2h3L11 22.5")
    val Fog = line(CLOUD_HIGH, "M5 18.5h14", "M7.5 21.5h9")

    fun weather(kind: WeatherKind, isDay: Boolean = true): ImageVector = when (kind) {
        WeatherKind.CLEAR -> if (isDay) Sun else Moon
        WeatherKind.PARTLY -> if (isDay) Partly else Moon
        WeatherKind.CLOUDY -> Cloud
        WeatherKind.FOG -> Fog
        WeatherKind.DRIZZLE -> Drizzle
        WeatherKind.RAIN -> Rain
        WeatherKind.SNOW -> Snow
        WeatherKind.THUNDER -> Thunder
    }
}

@Composable
fun LIcon(icon: ImageVector, size: Dp = 22.dp, tint: Color = Color.White, modifier: Modifier = Modifier) {
    Image(
        imageVector = icon,
        contentDescription = null,
        modifier = modifier.size(size),
        colorFilter = if (tint == Color.White) null else ColorFilter.tint(tint),
    )
}
