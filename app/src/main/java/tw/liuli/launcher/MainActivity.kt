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
import tw.liuli.launcher.ui.components.blockPointer
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.key
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.layout
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.semantics.clearAndSetSemantics
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
import tw.liuli.launcher.ui.bg.GrainLayer
import tw.liuli.launcher.ui.calendar.CalendarScreen
import tw.liuli.launcher.ui.glass.GlassBackdrop
import tw.liuli.launcher.ui.glass.LocalGlassBackdrop
import tw.liuli.launcher.ui.home.HomeScreen
import tw.liuli.launcher.ui.weather.WeatherScreen

class MainActivity : ComponentActivity() {

    /** 已在主畫面時又按 Home 的次數，用來觸發「回到第一個 App」。 */
    private val homePresses = mutableIntStateOf(0)

    /**
     * 開著的無障礙服務只有琉璃自己的「按鍵服務」（只過濾按鍵、不讀畫面）時＝true：畫面的語意樹整棵不提供。
     * 只要有任何無障礙服務開著，Compose 就會在主執行緒上持續整理整棵語意樹（每次焦點移動、排版變動後走訪所有節點、排序、比對）；
     * 海信上抽屜開關、移動焦點時這件事每 0.1 秒佔主執行緒 15～60ms，動畫就在這些空檔裡掉格。
     * 按鍵服務用不到語意樹；有別的服務（TalkBack 這類要讀畫面的）或開了觸控探索時照常提供。
     */
    private val a11yQuiet = androidx.compose.runtime.mutableStateOf(false)
    private fun checkA11y() {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as? android.view.accessibility.AccessibilityManager ?: return
        a11yQuiet.value = runCatching {
            val on = am.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            !am.isTouchExplorationEnabled && on.isNotEmpty() && on.all { it.resolveInfo?.serviceInfo?.packageName == packageName }
        }.getOrDefault(false)
    }

    /** 外部（按鍵服務）要求的動作：序號＋動作名稱。 */
    private val externalAction = mutableStateOf(0 to null as String?)

    /** 滑鼠滾輪累積的格數（觸控板等會送小數）。 */
    private var wheelAcc = 0f

    /**
     * 滑鼠／飛鼠：游標移動記下來（焦點跟著游標，見 MouseInput）；滾輪一格＝一次上／下鍵，
     * 主畫面、抽屜、設定、月曆、天氣不用各自處理，行為和遙控器完全一樣。
     */
    override fun dispatchGenericMotionEvent(ev: android.view.MotionEvent): Boolean {
        if (ev.isFromSource(android.view.InputDevice.SOURCE_CLASS_POINTER)) {
            when (ev.actionMasked) {
                android.view.MotionEvent.ACTION_HOVER_MOVE, android.view.MotionEvent.ACTION_HOVER_ENTER ->
                    tw.liuli.launcher.ui.components.MouseInput.onPointerMotion(ev, 24f * resources.displayMetrics.density)
                android.view.MotionEvent.ACTION_SCROLL -> {
                    val v = ev.getAxisValue(android.view.MotionEvent.AXIS_VSCROLL)
                    if (v != 0f) {
                        if (v * wheelAcc < 0f) wheelAcc = 0f // 換方向重新累積
                        wheelAcc += v
                        // 頁面（月曆、天氣）自己處理滾輪；其他地方一格＝一次上／下鍵
                        fun step(dir: Int) {
                            tw.liuli.launcher.ui.components.MouseInput.onKey()
                            if (tw.liuli.launcher.ui.components.MouseInput.wheel?.invoke(dir, ev.x, ev.y) != true)
                                tapKey(if (dir < 0) KeyEvent.KEYCODE_DPAD_UP else KeyEvent.KEYCODE_DPAD_DOWN)
                        }
                        while (wheelAcc >= 1f) { wheelAcc -= 1f; step(-1) }
                        while (wheelAcc <= -1f) { wheelAcc += 1f; step(1) }
                    }
                    return true
                }
            }
        }
        return super.dispatchGenericMotionEvent(ev)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // 按了遙控器：抖動的飛鼠游標不會把焦點搶走（見 MouseInput.onKey）
        if (event.action == KeyEvent.ACTION_DOWN) tw.liuli.launcher.ui.components.MouseInput.onKey()
        return super.dispatchKeyEvent(event)
    }

    private fun tapKey(code: Int) {
        val t = android.os.SystemClock.uptimeMillis()
        dispatchKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0))
        dispatchKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleAction(intent)
        // 每一幀的實際總耗時（vsync → 畫完）交給背景引擎決定解析度；在另一條執行緒回報，不佔主執行緒
        val metricsThread = HandlerThread("liuli-frames").apply { start() }
        window.addOnFrameMetricsAvailableListener({ _, m, _ ->
            tw.liuli.launcher.ui.bg.KeyLat.onFrame(m.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP), m.getMetric(FrameMetrics.TOTAL_DURATION))
            BackgroundEngine.active?.onFrameMetrics(
                m.getMetric(FrameMetrics.VSYNC_TIMESTAMP),
                m.getMetric(FrameMetrics.TOTAL_DURATION),
                // 主執行緒在這一幀之前就在忙（介面工作）＋重新組合的時間：太長＝這一幀被介面拖慢，不算背景的成本
                m.getMetric(FrameMetrics.UNKNOWN_DELAY_DURATION) + m.getMetric(FrameMetrics.ANIMATION_DURATION),
            )
        }, Handler(metricsThread.looper))
        val lensing = Build.VERSION.SDK_INT >= 33 &&
            !(getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).isLowRamDevice
        checkA11y()
        (getSystemService(Context.ACCESSIBILITY_SERVICE) as? android.view.accessibility.AccessibilityManager)?.let { am ->
            am.addAccessibilityStateChangeListener { checkA11y() }
            am.addTouchExplorationStateChangeListener { checkA11y() }
        }
        setContent {
            Box(
                if (a11yQuiet.value) Modifier.fillMaxSize().clearAndSetSemantics { } else Modifier.fillMaxSize(),
            ) {
                LiuliApp(lensing, homePresses.intValue, externalAction.value)
            }
        }
        // 第一格畫完就拿掉視窗底色：背景永遠蓋滿整個畫面，底色只是每格白白多塗一次全螢幕
        // （海信 Mali-470 填色很慢，抽屜滑動時每格要填 4 個螢幕的像素，省一個很有感）
        window.decorView.post { window.setBackgroundDrawable(null) }
    }

    // 記憶體：離開琉璃時放掉預先準備的東西（見 MemoryTrim），回來再慢慢重建
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            MemoryTrim.hidden = true
            BackgroundEngine.active?.trimMemory()
        }
    }

    // 開了別的 App、螢幕關掉：背景的動畫時鐘歸零（看不到的時候重整，回來從頭開始）
    override fun onStop() {
        BackgroundEngine.active?.restartClock()
        super.onStop()
    }

    override fun onStart() {
        super.onStart()
        if (MemoryTrim.hidden) {
            MemoryTrim.hidden = false
            BackgroundEngine.active?.untrimMemory()
        }
    }

    // 螢幕小時鐘：琉璃在前景時隱藏（主畫面本來就有大時鐘），離開就出現
    override fun onResume() {
        super.onResume()
        checkA11y()   // 從系統設定回來：無障礙服務可能開了或關了
        AppForeground.resumed = true
        AppForeground.resumeCount++
        tw.liuli.launcher.keys.ClockOverlay.liuliResumed = true
        tw.liuli.launcher.keys.ClockOverlay.refresh()
    }

    override fun onPause() {
        AppForeground.resumed = false
        tw.liuli.launcher.keys.ClockOverlay.liuliResumed = false
        tw.liuli.launcher.keys.ClockOverlay.refresh()
        super.onPause()
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
            applyStyle(settings.resolveBgStyle())
            applyFollow(settings.bgFollow, settings.bgHue, settings.bgSat)
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
        if (a == MainActivity.ACTION_INPUTS || a == "drawer") screen = Screen.Home
        if (a == "weather") screen = Screen.Weather
        if (a != null && a.startsWith("calendar:")) {
            calendarStart = runCatching { java.time.LocalDate.parse(a.removePrefix("calendar:")) }.getOrNull()
            screen = Screen.Calendar
        }
    }
    BackHandler(enabled = screen != Screen.Home) { screen = Screen.Home }

    // 子頁面開啟時：桌布模糊、App 隱藏；背景換成該頁的主題色
    val pageBlur = animateFloatAsState(if (screen == Screen.Home) 0f else 1f, tween(420), label = "pageBlur")
    // 過場有先後，不重疊：
    // 進入＝主畫面先加速淡出（0.15 秒），子頁面再從下方浮上來淡入；
    // 離開＝子頁面先往下淡出，完全消失後主畫面才淡入、往上浮回原位。
    // 子頁面的可見度：主畫面先淡出，子頁面再浮上淡入；離開時子頁面先淡出
    val pageVis = animateFloatAsState(
        if (screen != Screen.Home) 1f else 0f,
        if (screen != Screen.Home) tween(PAGE_IN_MS, delayMillis = PAGE_OUT_MS - 30, easing = LinearOutSlowInEasing)
        else tween(PAGE_OUT_MS, easing = FastOutLinearInEasing),
        label = "pageVis",
    )
    var lastPage by remember { mutableStateOf(Screen.Weather) }
    if (screen != Screen.Home) lastPage = screen
    var pagesReady by remember { mutableStateOf(false) }
    // 預熱：組合好之後，用肉眼看不到的透明度（約 0.4%）把兩頁真的畫一次，
    // 讓中文字形快取、圖示貼圖先建好（只組合不畫的話，第一次打開仍要卡 0.3 秒左右）
    var warmPages by remember { mutableStateOf(false) }
    // 離開琉璃時放掉（MemoryTrim），回來 4 秒後再重建
    LaunchedEffect(MemoryTrim.hidden) {
        if (MemoryTrim.hidden) { pagesReady = false; return@LaunchedEffect }
        kotlinx.coroutines.delay(4000); pagesReady = true
        kotlinx.coroutines.delay(600); warmPages = true
        kotlinx.coroutines.delay(400); warmPages = false
    }
    val homeVis = animateFloatAsState(
        if (screen == Screen.Home) 1f else 0f,
        if (screen == Screen.Home) tween(PAGE_IN_MS, delayMillis = PAGE_OUT_MS, easing = LinearOutSlowInEasing)
        else tween(PAGE_OUT_MS, easing = FastOutLinearInEasing),
        label = "homeVis",
    )

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
            // 過場期間背景暫停（換色也延到過場結束），GPU 留給淡入淡出
            engine.holdForUi((PAGE_OUT_MS + PAGE_IN_MS).toLong())
            when (screen) {
                Screen.Calendar -> link.focus(BgColors.CALENDAR, wPx * 0.35f, hPx * 0.55f)
                Screen.Weather -> link.focus(BgColors.WEATHER, wPx * 0.3f, hPx * 0.3f)
                Screen.Home -> Unit
            }
        }

        BackgroundLayer(engine, blurred = { pageBlur.value })
        // 細緻噪點：背景之上、所有介面之下（黑框、App 列都在它上面），原生解析度
        GrainLayer(engine, settings.grain)

        CompositionLocalProvider(LocalGlassBackdrop provides backdrop, LocalBgLink provides link) {
            // 主畫面一直保留（App 清單、焦點都不會重來），子頁面疊在上面
            HomeScreen(
                home = home,
                settings = settings,
                engine = engine,
                homePresses = homePresses,
                externalAction = externalAction,
                active = screen == Screen.Home,
                visibility = { homeVis.value },
                onOpenCalendar = { calendarStart = null; screen = Screen.Calendar },
                onOpenWeather = { screen = Screen.Weather },
            )
            // 月曆、天氣兩頁預先組合好（開好 4 秒後），平常不擺放＝不畫、拿不到焦點；
            // 打開時只剩淡入。以前每次打開才組合：天氣頁第一格主執行緒卡 0.3～0.7 秒、月曆 0.2 秒以上。
            for (page in PAGES) {
                val isLast = page == lastPage
                val placed = (isLast && (screen == page || pageVis.value > 0.001f)) || (warmPages && screen == Screen.Home)
                if (pagesReady || placed) {
                    key(page) {
                        PageHost(placed, alpha = { if (warmPages && screen == Screen.Home) WARM_ALPHA else if (isLast) pageVis.value else 0f }) {
                            when (page) {
                                Screen.Calendar -> CalendarScreen(home.now, calendarStart ?: home.now.toLocalDate(), active = screen == Screen.Calendar)
                                else -> WeatherScreen(home, active = screen == Screen.Weather)
                            }
                        }
                    }
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

/** 頁面過場：舊的先離開（加速淡出）、新的再進來（減速淡入）。 */
private const val PAGE_OUT_MS = 150
private const val PAGE_IN_MS = 260

private val PAGES = listOf(Screen.Calendar, Screen.Weather)
private const val WARM_ALPHA = .004f

/**
 * 子頁面的外殼：一直組合著，[placed] 為 false 時只量尺寸、不擺放（不畫、拿不到焦點）。
 * 淡入淡出與上下浮動只改圖層屬性，頁面內容不用重畫。
 */
@androidx.compose.runtime.Composable
private fun PageHost(placed: Boolean, alpha: () -> Float, content: @androidx.compose.runtime.Composable () -> Unit) {
    val lift = with(LocalDensity.current) { 20.dp.toPx() }
    Box(
        Modifier
            .fillMaxSize()
            .layout { m, c ->
                val p = m.measure(c)
                layout(p.width, p.height) {
                    if (placed) p.placeWithLayer(0, 0) {
                        val a = alpha()
                        // 淡入淡出中：整頁固定成一張快取圖、只改透明度（不然半透明時每格都要把整頁重畫進暫存層，
                        // 海信上每格延遲衝到 200ms 以上）；完全顯示時回到一般繪製
                        compositingStrategy = if (a < .999f) androidx.compose.ui.graphics.CompositingStrategy.Offscreen
                        else androidx.compose.ui.graphics.CompositingStrategy.Auto
                        this.alpha = a
                        // 進出頁面：跟著淡入淡出上下浮動（布丁只用在頁面裡的焦點操作，不用在換頁）
                        translationY = (1f - a) * lift
                    }
                }
            },
    ) {
        // 頁面蓋在主畫面上：游標經過頁面的空白處不會穿到後面的主畫面（放在內容下面，頁面裡可點的東西照樣點得到）
        if (placed) Box(Modifier.fillMaxSize().blockPointer())
        content()
    }
}
