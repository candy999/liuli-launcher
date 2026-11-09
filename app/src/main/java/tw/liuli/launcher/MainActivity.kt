package tw.liuli.launcher

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.view.FrameMetrics
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import tw.liuli.launcher.context.Cities
import tw.liuli.launcher.context.rememberHomeContext
import tw.liuli.launcher.settings.LauncherSettings
import tw.liuli.launcher.ui.bg.BackgroundEngine
import tw.liuli.launcher.ui.bg.BackgroundLayer
import tw.liuli.launcher.ui.bg.BgColors
import tw.liuli.launcher.ui.bg.BgLink
import tw.liuli.launcher.ui.bg.BgStyle
import tw.liuli.launcher.ui.bg.LocalBgLink
import tw.liuli.launcher.ui.bg.PaperLayer
import tw.liuli.launcher.ui.calendar.CalendarScreen
import tw.liuli.launcher.ui.glass.GlassBackdrop
import tw.liuli.launcher.ui.glass.LocalGlassBackdrop
import tw.liuli.launcher.ui.home.HomeScreen
import tw.liuli.launcher.ui.weather.WeatherScreen

class MainActivity : ComponentActivity() {

    /** 已在主畫面時又按 Home 的次數，用來觸發「回到第一個 App」。 */
    private val homePresses = mutableIntStateOf(0)

    /** 外部（按鍵服務）要求的動作：序號＋動作名稱。 */
    private val externalAction = mutableStateOf(0 to null as String?)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleAction(intent)
        // 每一幀的實際總耗時（vsync → 畫完）交給背景引擎決定解析度；在另一條執行緒回報，不佔主執行緒
        val metricsThread = HandlerThread("liuli-frames").apply { start() }
        window.addOnFrameMetricsAvailableListener({ _, m, _ ->
            BackgroundEngine.active?.onFrameMetrics(m.getMetric(FrameMetrics.VSYNC_TIMESTAMP), m.getMetric(FrameMetrics.TOTAL_DURATION))
        }, Handler(metricsThread.looper))
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
        if (intent?.hasExtra("bgDiv") == true) {
            tw.liuli.launcher.ui.bg.BackgroundEngine.debugDiv = intent.getIntExtra("bgDiv", 0)
            tw.liuli.launcher.ui.bg.BackgroundEngine.debugEvery = intent.getIntExtra("bgEvery", 2)
            intent.removeExtra("bgDiv")
            return true
        }
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
    val engine = remember {
        BackgroundEngine().apply {
            applyStyle(BgStyle.byId(settings.bgStyle))
            applyFollow(settings.bgFollow)
            applyBlur(settings.bgBlur)
        }
    }
    val scope = rememberCoroutineScope()
    var screen by remember { mutableStateOf(Screen.Home) }
    var lastDir by remember { mutableIntStateOf(0) }

    LaunchedEffect(homePresses) { screen = Screen.Home }
    // 量測用（adb）：ACTION=calendar:2029-12-02 直接打開月曆到那一天
    var calendarStart by remember { mutableStateOf<java.time.LocalDate?>(null) }
    LaunchedEffect(externalAction) {
        val a = externalAction.second
        if (a == MainActivity.ACTION_INPUTS) screen = Screen.Home
        if (a != null && a.startsWith("calendar:")) {
            calendarStart = runCatching { java.time.LocalDate.parse(a.removePrefix("calendar:")) }.getOrNull()
            screen = Screen.Calendar
        }
    }
    BackHandler(enabled = screen != Screen.Home) { screen = Screen.Home }

    // 子頁面開啟時：桌布模糊、App 隱藏；背景換成該頁的主題色
    val pageBlur = animateFloatAsState(if (screen == Screen.Home) 0f else 1f, tween(420), label = "pageBlur")

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown) {
                    engine.noteInput() // 背景讓路：按鍵後短暫停止背景更新，GPU 全給 UI 動畫
                    lastDir = when (e.nativeKeyEvent.keyCode) {
                        KeyEvent.KEYCODE_DPAD_LEFT -> -1
                        KeyEvent.KEYCODE_DPAD_RIGHT -> 1
                        else -> 0
                    }
                }
                false
            },
    ) {
        val density = LocalDensity.current
        val wPx = with(density) { maxWidth.toPx() }
        val hPx = with(density) { maxHeight.toPx() }
        val link = remember(engine, scope, wPx, hPx) { BgLink(engine, scope, { Size(wPx, hPx) }, { lastDir }) }
        val backdrop = remember(wPx, hPx, lensing) { GlassBackdrop(engine.glass, { engine.glassVersion }, Size(wPx, hPx), lensing) }

        LaunchedEffect(screen) {
            when (screen) {
                Screen.Calendar -> link.focus(BgColors.CALENDAR, wPx * 0.35f, hPx * 0.55f)
                Screen.Weather -> link.focus(BgColors.WEATHER, wPx * 0.3f, hPx * 0.3f)
                Screen.Home -> Unit
            }
        }

        BackgroundLayer(engine, blurred = { pageBlur.value })
        // 紙張紋理：背景之上、所有介面之下（黑框、App 列都在它上面），原生解析度
        PaperLayer(engine, settings.paperLevel)

        CompositionLocalProvider(LocalGlassBackdrop provides backdrop, LocalBgLink provides link) {
            // 主畫面一直保留（App 清單、焦點都不會重來），子頁面疊在上面
            HomeScreen(
                home = home,
                settings = settings,
                engine = engine,
                homePresses = homePresses,
                externalAction = externalAction,
                active = screen == Screen.Home,
                onOpenCalendar = { calendarStart = null; screen = Screen.Calendar },
                onOpenWeather = { screen = Screen.Weather },
            )
            AnimatedVisibility(visible = screen != Screen.Home, enter = fadeIn(tween(240)), exit = fadeOut(tween(160))) {
                var shown by remember { mutableStateOf(screen) }
                if (screen != Screen.Home) shown = screen
                when (shown) {
                    Screen.Calendar -> CalendarScreen(home.now, calendarStart ?: home.now.toLocalDate())
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
