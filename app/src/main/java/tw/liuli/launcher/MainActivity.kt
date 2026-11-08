package tw.liuli.launcher

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import tw.liuli.launcher.context.Cities
import tw.liuli.launcher.context.rememberHomeContext
import tw.liuli.launcher.settings.LauncherSettings
import tw.liuli.launcher.ui.calendar.CalendarScreen
import tw.liuli.launcher.ui.glass.GlassBackdrop
import tw.liuli.launcher.ui.glass.LocalGlassBackdrop
import tw.liuli.launcher.ui.glass.WallpaperPalette
import tw.liuli.launcher.ui.glass.rememberWallpaper
import tw.liuli.launcher.ui.home.HomeScreen
import tw.liuli.launcher.ui.weather.WeatherScreen
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    /** 已在主畫面時又按 Home 的次數，用來觸發「回到頂端」。 */
    private val homePresses = mutableIntStateOf(0)

    /** 外部（按鍵服務）要求的動作：序號＋動作名稱。 */
    private val externalAction = mutableStateOf(0 to null as String?)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleAction(intent)
        val lensing = Build.VERSION.SDK_INT >= 33 &&
            !(getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).isLowRamDevice
        setContent {
            LiuliApp(lensing, homePresses.intValue, externalAction.value)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (!handleAction(intent) && intent.hasCategory(Intent.CATEGORY_HOME)) homePresses.intValue++
    }

    private fun handleAction(intent: Intent?): Boolean {
        val action = intent?.getStringExtra(EXTRA_ACTION) ?: return false
        intent.removeExtra(EXTRA_ACTION)
        externalAction.value = (externalAction.value.first + 1) to action
        return true
    }

    companion object {
        const val EXTRA_ACTION = "tw.liuli.launcher.ACTION"
        const val ACTION_INPUTS = "inputs"
    }
}

private enum class Screen { Home, Calendar, Weather }

@Composable
fun LiuliApp(lensing: Boolean, homePresses: Int, externalAction: Pair<Int, String?>) {
    val context = LocalContext.current
    val settings = remember { LauncherSettings(context) }
    val home by rememberHomeContext(Cities.byId(settings.cityId))
    var screen by remember { mutableStateOf(Screen.Home) }

    // 按 Home、或按鍵服務要求開訊號源時，一律回主畫面
    LaunchedEffect(homePresses) { screen = Screen.Home }
    LaunchedEffect(externalAction) { if (externalAction.second == MainActivity.ACTION_INPUTS) screen = Screen.Home }

    BackHandler(enabled = screen != Screen.Home) { screen = Screen.Home }

    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val density = LocalDensity.current
        val wPx = with(density) { maxWidth.toPx() }
        val hPx = with(density) { maxHeight.toPx() }
        val palette = WallpaperPalette.resolve(settings.palette, home)
        val wallpaper by rememberWallpaper(palette, wPx.roundToInt(), hPx.roundToInt())
        val sharp = remember(wallpaper) { wallpaper?.sharp?.asImageBitmap() }

        // 靜態桌布：只在配色改變時重畫
        Canvas(Modifier.fillMaxSize()) {
            val img = sharp
            if (img != null) {
                drawImage(
                    img,
                    dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                    filterQuality = FilterQuality.Medium,
                )
            } else {
                drawRect(Color(0xFF070B1C))
            }
        }

        val backdrop = remember(wallpaper, wPx, hPx, lensing) { GlassBackdrop(wallpaper, Size(wPx, hPx), lensing) }
        CompositionLocalProvider(LocalGlassBackdrop provides backdrop) {
            // 主畫面一直保留（App 清單、捲動位置、焦點都不會重來），子頁面疊在上面
            HomeScreen(
                home = home,
                settings = settings,
                homePresses = homePresses,
                externalAction = externalAction,
                active = screen == Screen.Home,
                onOpenCalendar = { screen = Screen.Calendar },
                onOpenWeather = { screen = Screen.Weather },
            )
            AnimatedVisibility(
                visible = screen != Screen.Home,
                enter = fadeIn(tween(180)),
                exit = fadeOut(tween(140)),
            ) {
                // 退場動畫期間保留最後一個子頁面
                var shown by remember { mutableStateOf(screen) }
                if (screen != Screen.Home) shown = screen
                when (shown) {
                    Screen.Calendar -> CalendarScreen(home.now.toLocalDate())
                    Screen.Weather -> WeatherScreen(home)
                    Screen.Home -> Unit
                }
            }
        }
    }
}

object BuildConfigInfo {
    fun versionName(context: Context): String =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
            .getOrNull() ?: ""
}
