package tw.liuli.launcher.ui.home

import androidx.compose.foundation.gestures.detectTapGestures
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.provider.Settings
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import tw.liuli.launcher.ui.components.blockPointer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tw.liuli.launcher.adb.SelfAdb
import tw.liuli.launcher.BuildConfigInfo
import tw.liuli.launcher.MainActivity
import tw.liuli.launcher.update.Updater
import tw.liuli.launcher.context.Cities
import tw.liuli.launcher.context.HomeContext
import tw.liuli.launcher.data.AppEntry
import tw.liuli.launcher.data.AppRepository
import tw.liuli.launcher.data.DockStore
import tw.liuli.launcher.data.HiddenAppsStore
import tw.liuli.launcher.keys.KeyAction
import tw.liuli.launcher.data.TvSources
import tw.liuli.launcher.keys.KeyBinding
import tw.liuli.launcher.keys.KeyMapStore
import tw.liuli.launcher.keys.KeyRemapService
import tw.liuli.launcher.settings.LauncherSettings
import tw.liuli.launcher.system.SystemActions
import tw.liuli.launcher.ui.bg.BackgroundEngine
import tw.liuli.launcher.ui.bg.BgStyle
import tw.liuli.launcher.ui.bg.bgTarget
import tw.liuli.launcher.ui.components.AppTile
import tw.liuli.launcher.ui.components.GlassSheet
import tw.liuli.launcher.ui.components.KeepVisibleSpec
import tw.liuli.launcher.ui.components.SheetItem
import tw.liuli.launcher.ui.components.SheetSpec
import tw.liuli.launcher.ui.components.focusRow
import tw.liuli.launcher.ui.theme.GText
import tw.liuli.launcher.ui.theme.Ink
import tw.liuli.launcher.ui.theme.LIcon
import tw.liuli.launcher.ui.theme.LIcons
import kotlin.math.roundToInt

/** 側邊面板關閉後要把焦點還給誰。 */
private data class OpenSheet(val spec: SheetSpec, val returnTo: FocusRequester?)

/** 返回路徑上的一層：面板內容與當時焦點所在的列。 */
private class SheetFrame(val spec: SheetSpec, val focus: Int)

/**
 * 主畫面 v4（design/home-v4.html）：
 * 時鐘＋農民曆（左上）、天氣（右上）直接放在桌布上，焦點時才浮出玻璃膠囊；
 * 訊號源一列膠囊；常用 App 不加框；按下鍵展開「所有 App」抽屜。
 */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    home: HomeContext,
    settings: LauncherSettings,
    engine: BackgroundEngine,
    homePresses: Int,
    externalAction: Pair<Int, String?>,
    active: Boolean,
    /** 主畫面可見度（0～1）：切換到月曆／天氣時先淡出，回來時等子頁面消失後才淡入。 */
    visibility: () -> Float = { if (active) 1f else 0f },
    onOpenCalendar: () -> Unit,
    onOpenWeather: () -> Unit,
) {
    val traceView = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.SideEffect { tw.liuli.launcher.ui.bg.UiTrace.hit("Home") }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dockStore = remember { DockStore(context) }
    val hiddenStore = remember { HiddenAppsStore(context) }
    val keepVisible = remember { KeepVisibleSpec() }

    var apps by remember { mutableStateOf<List<AppEntry>>(emptyList()) }
    var dock by remember { mutableStateOf<List<String>>(emptyList()) }
    var hidden by remember { mutableStateOf(hiddenStore.load()) }
    var reloadKey by remember { mutableIntStateOf(0) }
    var fakeApps by remember { mutableIntStateOf(0) }
    // 量測用（adb）：ACTION=fakesources:1 在沒有訊號源的機器（模擬器）上放 HDMI1～3、AV 四個假的訊號源，測訊號源列；0 拿掉
    var fakeSources by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf<OpenSheet?>(null) }
    var exitingSheet by remember { mutableStateOf<SheetSpec?>(null) }
    // 返回上一層：從一個面板進到另一個面板時，記下上一層（與當時焦點所在的列）
    val backStack = remember { ArrayList<SheetFrame>() }
    val sheetFocus = remember { IntArray(1) }
    val restoring = remember { BooleanArray(1) }
    // 背景顏色選擇器（色盤）：開著時、打開前的顏色（取消時還原）
    var picking by remember { mutableStateOf(false) }
    var pickerOrigin by remember { mutableStateOf<Pair<Float, Float>?>(null) }
    // 按鍵測試：最後收到的按鍵
    var lastKeyText by remember { mutableStateOf("（還沒按）") }
    val keyTestTitle = "按鍵測試"
    var drawerOpen by remember { mutableStateOf(false) }
    // 所有 App 最後一格「顯示系統應用」：展開的狀態只在抽屜開著的這一次有效（收起抽屜就收合），不存設定
    var systemApps by remember { mutableStateOf<List<AppEntry>?>(null) }
    var systemLoading by remember { mutableStateOf(false) }
    LaunchedEffect(drawerOpen) { if (!drawerOpen) { systemApps = null; systemLoading = false } }
    var pendingFocus by remember { mutableStateOf<FocusRequester?>(null) }
    var dockLabel by remember { mutableStateOf("") }
    var labelX by remember { mutableFloatStateOf(0f) }
    var dockHasFocus by remember { mutableStateOf(true) }
    // 移動常用 App：被抓住的套件名稱，以及開始前的順序（返回鍵取消時還原）
    var movingPkg by remember { mutableStateOf<String?>(null) }
    var moveOrigin by remember { mutableStateOf<List<String>>(emptyList()) }
    val dockListState = androidx.compose.foundation.lazy.rememberLazyListState()

    val firstDock = remember { FocusRequester() }
    val firstInput = remember { FocusRequester() }
    val todayReq = remember { FocusRequester() }
    val weatherReq = remember { FocusRequester() }
    val gearReq = remember { FocusRequester() }
    // ── 技術宅模式：每秒取樣系統狀態（只在技術宅模式、主畫面看得到時） ──
    val geekSampler = remember { tw.liuli.launcher.geek.StatsSampler(context) }
    // 用 State 物件傳下去、不在這裡讀值：每秒更新時只有儀表列重新組合（以前整個主畫面每秒重組一次，海信上 UI 執行緒每次卡 50ms）
    val geekStats = remember { mutableStateOf(tw.liuli.launcher.geek.Stats()) }
    val overlayChangedAt = remember { LongArray(1) }
    var rocketResult by remember { mutableStateOf<tw.liuli.launcher.geek.RocketResult?>(null) }
    LaunchedEffect(settings.geekMode, active, tw.liuli.launcher.AppForeground.resumed) {
        // 琉璃在背景（看 YouTube、HDMI）時不取樣：不量網路延遲、不讀 CPU
        if (!settings.geekMode || !active || !tw.liuli.launcher.AppForeground.resumed) return@LaunchedEffect
        while (true) {
            // 抽屜開著（儀表列被蓋住、看不到）不更新；抽屜、面板開關的那一秒也不更新——
            // 儀表列每更新一次，海信上那一格要 35ms 以上，剛好落在彈出動畫裡就掉格
            if (!drawerOpen && android.os.SystemClock.uptimeMillis() - overlayChangedAt[0] > 1000L)
                runCatching { geekSampler.sample() }.onSuccess { geekStats.value = it }
            delay(1000)
        }
    }
    DisposableEffect(Unit) { onDispose { geekSampler.close() } }
    val tileCenters = remember { HashMap<String, Float>() }
    // 常用列每個 App 的 FocusRequester：關抽屜、關面板時焦點回到上次停留的那一個
    val dockRequesters = remember { HashMap<String, FocusRequester>() }
    // 最後停留的常用 App 存起來：弱電視上看完 Netflix 回來，琉璃常常已被系統回收（等於冷啟動），還是回到同一個位置
    val focusPrefs = remember { context.getSharedPreferences("settings", Context.MODE_PRIVATE) }
    var lastDockPkg by remember { mutableStateOf(focusPrefs.getString("last_dock", null)) }

    // ── App 清單：安裝、移除、更新時自動重新載入 ──
    // App 清單載入完成了沒（還沒載入時常用列什麼都不顯示——不能先閃出「加入常用」再換成 App）
    var appsLoaded by remember { mutableStateOf(false) }
    LaunchedEffect(reloadKey) {
        val loaded = AppRepository.load(context)
        apps = loaded
        val installed = loaded.map { it.packageName }.toSet()
        dock = (dockStore.load() ?: DockStore.defaultFor(loaded)).filter { it in installed }
        appsLoaded = true
    }
    // 常用列第一次出現：一個一個淡入浮上（和面板、抽屜同一套出場語言）
    val dockReveal = remember { Animatable(0f) }
    // 冷啟動的出場：背景開始從焦點擴散（＝玻璃取樣的背景已經是真的、不是黑的）之後，時鐘 → 天氣 → 右上角圓鈕 → 訊號源 → 儀表列
    // 一個一個淡入浮上。在那之前這些都不顯示——不然會先看到一塊塊還沒算好的玻璃貼在全黑的畫面上
    val entrance = remember { Animatable(0f) }
    val bgStarted = engine.revealFrom != null
    LaunchedEffect(bgStarted, appsLoaded) {
        if (bgStarted && appsLoaded && entrance.value == 0f) {
            delay(160)   // 讓每一片玻璃先烘好第一張
            entrance.animateTo(ENTRANCE_MS, tween(ENTRANCE_MS.toInt(), easing = androidx.compose.animation.core.LinearEasing))
        }
    }
    fun Modifier.enter(order: Int): Modifier = graphicsLayer {
        val a = tw.liuli.launcher.ui.components.rowReveal(entrance.value, order, stagger = 90f, fade = 340f)
        compositingStrategy = CompositingStrategy.ModulateAlpha
        alpha = a
        translationY = (1f - a) * 12.dp.toPx()
    }
    LaunchedEffect(appsLoaded) {
        if (appsLoaded) dockReveal.animateTo(DOCK_REVEAL_MS, tween(DOCK_REVEAL_MS.toInt(), easing = androidx.compose.animation.core.LinearEasing))
    }
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                reloadKey++
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        onDispose { context.unregisterReceiver(receiver) }
    }
    val sources = remember(reloadKey, fakeSources) {
        TvSources.list(context).ifEmpty {
            if (!fakeSources) emptyList() else listOf(
                tw.liuli.launcher.data.TvSource("fake.hdmi1", "HDMI1", android.media.tv.TvInputInfo.TYPE_HDMI, true, ""),
                tw.liuli.launcher.data.TvSource("fake.hdmi2", "HDMI2", android.media.tv.TvInputInfo.TYPE_HDMI, true, ""),
                tw.liuli.launcher.data.TvSource("fake.hdmi3", "HDMI3", android.media.tv.TvInputInfo.TYPE_HDMI, true, ""),
                tw.liuli.launcher.data.TvSource("fake.av", "AV", android.media.tv.TvInputInfo.TYPE_COMPOSITE, true, ""),
            )
        }
    }

    // ── 焦點：開啟時直接放到上次停留的常用 App（沒有就第一個）──
    // App 清單載入前不讓任何東西拿焦點（見下方 focusProperties）：否則系統會先把焦點給畫面上第一個元件（時鐘），
    // 清單載好再跳過去——焦點動畫、玻璃、背景都白白動一次，看起來會抖。確定位置之後只對焦一次。
    var initialFocusDone by remember { mutableStateOf(false) }
    // 第一次進入主畫面時焦點要去的地方（focusProperties 的 enter 會直接導到這裡，不經過時鐘）
    var initialTarget by remember { mutableStateOf<FocusRequester?>(null) }
    LaunchedEffect(apps, dock) {
        if (!initialFocusDone && apps.isNotEmpty()) {
            val installed = apps.map { it.packageName }.toSet()
            val shown = dock.filter { it in installed }
            val i = shown.indexOf(lastDockPkg)
            if (i > 0) runCatching { dockListState.scrollToItem(i) }
            delay(32)
            val target = (if (i > 0) dockRequesters[shown[i]] else null) ?: firstDock
            initialTarget = target
            initialFocusDone = true
            runCatching { target.requestFocus() }
            delay(1000)
            initialTarget = null
        }
    }
    var drawerReady by remember { mutableStateOf(false) }
    // 離開琉璃時放掉預先組合的抽屜（MemoryTrim），回來 2.5 秒後再重建
    LaunchedEffect(initialFocusDone, tw.liuli.launcher.MemoryTrim.hidden) {
        if (tw.liuli.launcher.MemoryTrim.hidden) { drawerReady = false; return@LaunchedEffect }
        if (initialFocusDone) { delay(2500); drawerReady = true }
    }
    // 保險：清單一直沒載好（或沒有任何 App）也不能永遠沒有焦點
    LaunchedEffect(Unit) {
        delay(3000)
        if (!initialFocusDone) { initialFocusDone = true; runCatching { firstDock.requestFocus() } }
    }
    // 剛從「軟體更新」裝好新版、這是第一次啟動：說一聲（等出場動畫跑完）
    LaunchedEffect(Unit) {
        val updated = Updater.consumeJustUpdated(context, BuildConfigInfo.versionName(context)) ?: return@LaunchedEffect
        delay(1800)
        android.widget.Toast.makeText(context, "已更新到 $updated", android.widget.Toast.LENGTH_LONG).show()
    }
    // ── 簡潔模式：只留左下角的時間、右下角的天氣，其他介面往下收起 ──
    // 進入：主畫面最上方再按上鍵，或閒置一段時間（設定 → 外觀）；離開：按方向鍵／確定／返回
    var minimal by remember { mutableStateOf(false) }
    val minimalT = animateFloatAsState(
        if (minimal) 1f else 0f,
        if (minimal) tween(560, easing = androidx.compose.animation.core.FastOutSlowInEasing)
        else tween(420, easing = androidx.compose.animation.core.FastOutSlowInEasing),
        label = "minimal",
    )
    val lastKeyAt = remember { longArrayOf(android.os.SystemClock.uptimeMillis()) }
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    // 簡潔模式中沒有選中的項目：一個看不見的點像 DVD 待機標誌一樣慢慢彈來彈去，背景跟著它流動、撞到邊緣換色；
    // 離開時回到原本焦點的顏色與位置
    LaunchedEffect(minimal) {
        if (!minimal) return@LaunchedEffect
        // 自動清理記憶體：進入簡潔模式時結束背景程式，畫面正中央顯示 3 秒結果（跟火箭一樣，知道有清理）
        if (settings.minimalClean) launch {
            try {
                rocketResult = runCatching { tw.liuli.launcher.geek.Rocket.launch(context) }.getOrNull()
                delay(3000)
            } finally { rocketResult = null }
        }
        val saved = engine.snapshotTarget()
        try {
            engine.startBounce()
            kotlinx.coroutines.awaitCancellation()
        } finally {
            engine.stopBounce()
            engine.restoreTarget(saved)
        }
    }
    // 微光：同時請系統調低螢幕亮度（只在琉璃這個視窗有效；電視不支援就忽略，只靠畫面壓暗）
    LaunchedEffect(minimal, settings.minimalDim) {
        val activity = context as? android.app.Activity ?: return@LaunchedEffect
        val target = if (minimal) MINIMAL_DIM_BRIGHTNESS[settings.minimalDim.coerceIn(0, 3)] else -1f
        if (minimal) delay(500) // 等介面收起一半再暗下來
        activity.window.attributes = activity.window.attributes.apply { screenBrightness = target }
    }
    // 閒置自動進入簡潔模式：只在琉璃在前景、停在主畫面時計時。
    // 看 YouTube、切到 HDMI 時琉璃在背景，不能在背景算時間；回到前景（例如按 Home）從頭開始計時
    val foreground = tw.liuli.launcher.AppForeground.resumed
    LaunchedEffect(tw.liuli.launcher.AppForeground.resumeCount) { lastKeyAt[0] = android.os.SystemClock.uptimeMillis() }
    LaunchedEffect(settings.minimalIdle, active, foreground) {
        val idle = settings.minimalIdle
        if (idle <= 0 || !active || !foreground) return@LaunchedEffect
        lastKeyAt[0] = android.os.SystemClock.uptimeMillis()
        while (true) {
            delay(1000)
            // 滑鼠／飛鼠在動也算在用
            val quiet = android.os.SystemClock.uptimeMillis() - maxOf(lastKeyAt[0], tw.liuli.launcher.ui.components.MouseInput.lastMoveAt)
            if (!minimal && sheet == null && !drawerOpen && !picking && movingPkg == null && quiet > idle * 1000L) minimal = true
        }
    }

    // 滑鼠／飛鼠動一下就離開簡潔模式（和按任何鍵一樣）
    val mouseWakes = tw.liuli.launcher.ui.components.MouseInput.wakes
    LaunchedEffect(mouseWakes) { if (mouseWakes > 0 && minimal) minimal = false }

    // 打開側邊欄（設定等面板）時離開簡潔模式：背後的主畫面要看得到，不能變成一片空的
    LaunchedEffect(sheet != null) { if (sheet != null && minimal) minimal = false }

    LaunchedEffect(homePresses) {
        if (homePresses == 0) return@LaunchedEffect
        minimal = false
        sheet = null; exitingSheet = null; drawerOpen = false
        delay(16)
        runCatching { firstDock.requestFocus() }
    }
    LaunchedEffect(sheet, drawerOpen, pendingFocus) {
        // 面板關閉後還原焦點。即使抽屜還開著也要還原（長按開選單的 returnTo 是抽屜裡的圖塊）
        val target = pendingFocus
        if (sheet == null && target != null) {
            // 等常用列重新排好（剛移除／移動過的話要多一兩格），失敗就再試，最後退回常用列第一格
            var ok = false
            for (attempt in 0 until 6) {
                delay(if (attempt == 0) 32 else 48)
                ok = runCatching { target.requestFocus() }.isSuccess || runCatching { firstDock.requestFocus() }.isSuccess
                if (ok) break
            }
            pendingFocus = null
        }
    }
    // 從月曆／天氣回來：焦點回到打開它的地方
    var returnFromPage by remember { mutableStateOf<FocusRequester?>(null) }
    LaunchedEffect(active) {
        val target = returnFromPage
        if (active && target != null) {
            delay(16)
            runCatching { target.requestFocus() }
            returnFromPage = null
        }
    }
    val openCalendar = { returnFromPage = todayReq; onOpenCalendar() }
    val openWeather = { returnFromPage = weatherReq; onOpenWeather() }

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_LONG).show()

    /** 整個關掉（所有層）。 */
    fun closeAll(cancel: Boolean = false) {
        if (cancel) sheet?.spec?.onCancel?.invoke()
        backStack.clear()
        pendingFocus = sheet?.returnTo
        exitingSheet = sheet?.spec // 收起動畫：後面先畫好，面板再加速滑出
        sheet = null
        KeyRemapService.learner = null
    }

    fun open(spec: SheetSpec, returnTo: FocusRequester?) {
        android.util.Log.i("LiuliUi", "sheet: ${spec.title}")
        exitingSheet = null // 開新面板時，還在收起的舊面板直接拿掉（不然之後會被誤顯示）
        val cur = sheet
        if (cur != null && !restoring[0]) {
            val existing = backStack.indexOfLast { it.spec.id == spec.id }
            when {
                // 回到路徑上已有的一層（例如刪除綁定後回到清單）：把它之後的都拿掉
                existing >= 0 -> while (backStack.size > existing) backStack.removeAt(backStack.size - 1)
                // 同一層重新整理（例如切換顯示／隱藏）：不加層
                cur.spec.id == spec.id || cur.spec.transient -> Unit
                else -> backStack.add(SheetFrame(cur.spec, sheetFocus[0]))
            }
        }
        sheet = OpenSheet(spec, returnTo ?: sheet?.returnTo)
    }

    /** 回到上一層（焦點回到當初進去的那一列）；已經是最上層就整個關掉。返回鍵、選完一個選項都走這裡。 */
    fun close(cancel: Boolean = false) {
        if (cancel) sheet?.spec?.onCancel?.invoke()
        val parent = backStack.removeLastOrNull()
        if (parent == null) { closeAll(); return }
        KeyRemapService.learner = null
        restoring[0] = true
        val reopen = parent.spec.reopen
        if (reopen != null) reopen() else open(parent.spec, null)
        restoring[0] = false
        sheet = sheet?.let { it.copy(spec = it.spec.copy(focusIndex = parent.focus)) }
    }

    /** 多步驟流程完成：退回到路徑上的 [id] 那一層之上，再執行 [then]（例如打開綁定清單）。 */
    fun finishTo(id: String, then: () -> Unit) {
        val i = backStack.indexOfLast { it.spec.id == id }
        if (i >= 0) while (backStack.size > i + 1) backStack.removeAt(backStack.size - 1)
        restoring[0] = true
        then()
        restoring[0] = false
    }

    fun dockReturnTarget(): FocusRequester = lastDockPkg?.let { dockRequesters[it] } ?: firstDock

    fun closeDrawer() {
        drawerOpen = false
        pendingFocus = dockReturnTarget()
    }

    fun launchApp(app: AppEntry) {
        if (!SystemActions.launch(context, app.launchIntent)) toast("無法開啟 ${app.label}")
    }

    fun setDock(newDock: List<String>) {
        dock = newDock
        dockStore.save(newDock)
    }

    fun setHidden(pkg: String, hide: Boolean) {
        hidden = if (hide) hidden + pkg else hidden - pkg
        hiddenStore.save(hidden)
        if (hide && pkg in dock) setDock(dock - pkg)
    }

    // ───────────── App 選項 ─────────────
    fun appSheet(app: AppEntry, returnTo: FocusRequester, fromDrawer: Boolean = false) {
        val inDock = app.packageName in dock
        val items = buildList {
            add(SheetItem("開啟") { closeAll(); launchApp(app) })
            if (inDock) {
                // 只有在常用列上長按才能移動（抽屜裡看不到常用列的位置）
                if (!fromDrawer && dock.size > 1) add(SheetItem("移動位置", "左右移動，確定放下") {
                    close()
                    moveOrigin = dock
                    movingPkg = app.packageName
                })
                add(SheetItem("從常用移除") {
                    val pkg = app.packageName
                    if (fromDrawer) {
                        // 從抽屜移除：焦點留在抽屜，不用處理
                        setDock(dock - pkg); close()
                    } else {
                    // 從常用列移除：先把焦點移到相鄰圖塊（兩個都還在），再移除；
                    // 若直接移除，焦點會在圖塊消失時掉到別處（例如時鐘）
                    val i = dock.indexOf(pkg)
                    val neighbor = dock.getOrNull(i + 1) ?: dock.getOrNull(i - 1)
                    close()
                    pendingFocus = null
                    scope.launch {
                        delay(32)
                        if (neighbor != null) {
                            val ni = dock.indexOf(neighbor)
                            val req = if (ni == 0) firstDock else dockRequesters[neighbor]
                            runCatching { req?.requestFocus() }
                            lastDockPkg = neighbor
                            delay(32)
                        }
                        setDock(dock - pkg)
                        // 確認焦點真的還在常用列（移除最後一個時要落到「加入常用」磚）
                        repeat(10) {
                            delay(48)
                            if (dockHasFocus) return@launch
                            runCatching { firstDock.requestFocus() }
                        }
                    }
                    }
                })
            } else {
                add(SheetItem("加入常用", if (dock.size >= DockStore.MAX) "已滿" else null) {
                    if (dock.size < DockStore.MAX) setDock(dock + app.packageName) else toast("常用已滿，請先移除一個")
                    close()
                })
            }
            add(SheetItem("從主畫面隱藏") { setHidden(app.packageName, true); close() })
            add(SheetItem("應用程式資訊", "強制停止、清除快取") { closeAll(); SystemActions.appInfo(context, app.packageName) })
            add(SheetItem("解除安裝") { closeAll(); SystemActions.uninstall(context, app.packageName) })
        }
        open(SheetSpec(app.label, if (app.isTvApp) null else "手機版 App：可能需要滑鼠或觸控操作", items), returnTo)
    }

    // 按鍵服務要求「訊號源」：焦點移到訊號源列
    LaunchedEffect(externalAction) {
        // 量測用（adb）：直接切換背景樣式
        // adb shell am start -n tw.liuli.launcher/.MainActivity --es tw.liuli.launcher.ACTION style:contour
        // 量測用（adb）：山稜的時光色彩固定在某個時刻 tod:HH:MM（tod:now 回到現在時間）
        externalAction.second?.takeIf { externalAction.first > 0 && it.startsWith("tod:") }?.let { v ->
            val hm = v.removePrefix("tod:").split(":")
            tw.liuli.launcher.ui.bg.TimeOfDay.overrideMinutes = if (hm.size == 2) hm[0].toFloat() * 60 + hm[1].toFloat() else -1f
            return@LaunchedEffect
        }
        val styleId = externalAction.second?.removePrefix("style:")
        if (externalAction.first > 0 && externalAction.second?.startsWith("style:") == true && styleId != null) {
            if (styleId == BgStyle.RANDOM_ID) {
                settings.updateBgStyle(BgStyle.RANDOM_ID); engine.applyStyle(settings.resolveBgStyle())
                return@LaunchedEffect
            }
            val st = BgStyle.byId(styleId)
            settings.updateBgStyle(st.id); engine.applyStyle(st)
            return@LaunchedEffect
        }
        if (externalAction.first > 0 && externalAction.second == MainActivity.ACTION_INPUTS) {
            sheet = null; exitingSheet = null; drawerOpen = false
            delay(32)
            runCatching { firstInput.requestFocus() }
        }
    }

    // ───────────── 設定 ─────────────
    // 隨機的範圍：第一列套用隨機，下面每個樣式按確定勾選／取消（至少留一個）
    fun randomSheet(focusIndex: Int) {
        val pool = settings.randomPool
        open(SheetSpec(
            "隨機",
            "勾選要加入隨機的樣式，按確定切換。每次重新啟動時，從勾選的樣式裡換一個（不會連續兩次一樣）。",
            listOf(
                SheetItem("使用隨機", "從 ${pool.size} 個樣式裡抽", selected = settings.bgStyle == BgStyle.RANDOM_ID) {
                    settings.updateBgStyle(BgStyle.RANDOM_ID); engine.applyStyle(settings.resolveBgStyle()); closeAll()
                },
            ) + BgStyle.entries.mapIndexed { i, st ->
                SheetItem(st.label, if (st.id in pool) "加入隨機" else "", selected = st.id in pool) {
                    if (!settings.toggleRandom(st.id)) toast("至少要留一個樣式")
                    randomSheet(i + 1)
                }
            },
            focusIndex = focusIndex,
            key = "random", reopen = { randomSheet(0) },
        ), gearReq)
    }

    fun styleSheet() {
        val before = engine.style
        val random = settings.bgStyle == BgStyle.RANDOM_ID
        open(SheetSpec(
            "背景樣式", "上下移動即時預覽；確定套用，返回取消。",
            listOf(
                // 隨機：每次冷啟動（背景從黑畫面載入）從勾選的樣式裡換一個；進去可以勾選範圍
                SheetItem("隨機", subtitle = "每次重新啟動時，從勾選的 ${settings.randomPool.size} 個樣式裡換一個", selected = random,
                    onFocus = { engine.applyStyle(before) }) { randomSheet(0) },
            ) + BgStyle.entries.map { st ->
                SheetItem(st.label, subtitle = st.note, selected = !random && st == before, onFocus = { engine.applyStyle(st) }) {
                    settings.updateBgStyle(st.id); engine.applyStyle(st); close()
                }
            },
            focusIndex = if (random) 0 else before.ordinal + 1,
            onCancel = { engine.applyStyle(before) },
        ), gearReq)
    }

    fun frameSheet() {
        fun dp(v: Int) = "$v dp"
        open(SheetSpec(
            "邊框", "左右鍵調整，即時預覽。粗細 0 dp＝不內縮、只有圓角。黑框蓋在背景與捲動列上，不會遮住時鐘、天氣等固定介面。",
            listOf(
                SheetItem("黑框粗細", liveDetail = { dp(settings.frameThickness) },
                    onAdjust = { d -> settings.updateFrame(thickness = (settings.frameThickness + d * 4).coerceIn(0, 120)) }) { close() },
                SheetItem("圓角半徑", liveDetail = { "${settings.frameRadius} dp" },
                    onAdjust = { d -> settings.updateFrame(radius = (settings.frameRadius + d * 4).coerceIn(0, 200)) }) { close() },
                SheetItem("漸變範圍", liveDetail = { dp(settings.frameFeather) },
                    onAdjust = { d -> settings.updateFrame(feather = (settings.frameFeather + d * 4).coerceIn(0, 240)) }) { close() },
                SheetItem("全部關閉") { settings.updateFrame(thickness = 0, radius = 0, feather = 0) },
            ),
            reopen = { frameSheet() },
        ), gearReq)
    }

    fun textureSheet() {
        val maxBlur = tw.liuli.launcher.ui.bg.BackgroundEngine.BLUR_SIGMAS.lastIndex
        fun blurText(v: Int) = if (v == 0) "關" else "$v・σ ${tw.liuli.launcher.ui.bg.BackgroundEngine.BLUR_SIGMAS[v].toInt()} px"
        val grainNames = tw.liuli.launcher.ui.bg.GRAIN_LABELS
        fun grainText(v: Int) = if (v == 0) grainNames[0] else "${grainNames[v]}・$v 階"
        open(SheetSpec(
            "背景質感", "左右鍵調整，即時預覽。模糊越強反而越省效能（背景改在小圖上算）。背景的漸層一律做抖色，8 bit 面板上不會一圈一圈斷開；細緻噪點再以原生解析度補上 1～3 個色階的顆粒，打散殘餘的色帶。",
            listOf(
                SheetItem("背景模糊", liveDetail = { blurText(settings.bgBlur) },
                    onAdjust = { d ->
                        val v = (settings.bgBlur + d + maxBlur + 1) % (maxBlur + 1) // 輪巡：最強之後回到關
                        settings.updateBgBlur(v); engine.applyBlur(v)
                    }) { },
                SheetItem("細緻噪點", liveDetail = { grainText(settings.grain) },
                    onAdjust = { d -> settings.updateGrain((settings.grain + d + grainNames.size) % grainNames.size) }) { },
                SheetItem("全部關閉") { settings.updateBgBlur(0); engine.applyBlur(0); settings.updateGrain(0) },
            ),
            reopen = { textureSheet() },
        ), gearReq)
    }

    // 背景色系：自動色彩開關＋固定顏色的二維色盤
    fun colorSheet() {
        fun setAuto(on: Boolean) {
            settings.updateBgFollow(on)
            engine.applyFollow(on, settings.bgHue, settings.bgSat)
        }
        open(SheetSpec(
            "背景色系",
            "自動色彩：背景跟著焦點項目的主題色變化（例如停在 Netflix 就偏紅）。關閉後使用你選的固定顏色。",
            listOf(
                SheetItem("自動色彩", liveDetail = { if (settings.bgFollow) "開" else "關" },
                    onAdjust = { setAuto(!settings.bgFollow) }) { setAuto(!settings.bgFollow) },
                SheetItem("選擇顏色", liveDetail = { "色相 ${settings.bgHue.roundToInt()}°" }) {
                    // 選顏色＝要固定顏色：自動色彩一起關掉
                    setAuto(false)
                    pickerOrigin = settings.bgHue to settings.bgSat
                    closeAll()
                    pendingFocus = null // 焦點交給色盤，不要還原到設定按鈕
                    picking = true
                },
            ),
            reopen = { colorSheet() },
        ), gearReq)
    }

    fun citySheet() {
        val idx = Cities.all.indexOfFirst { it.id == settings.cityId }.coerceAtLeast(0)
        open(SheetSpec("地點", "天氣使用縣市中心的大約位置，不讀取裝置定位。",
            Cities.all.map { city ->
                SheetItem(city.name, selected = city.id == settings.cityId) { settings.updateCity(city.id); close() }
            }, focusIndex = idx,
        ), gearReq)
    }

    fun visibilitySheet(focusIndex: Int) {
        open(SheetSpec("顯示／隱藏 App", "原廠主畫面不列出手機版 App，這裡可以自己決定。",
            apps.mapIndexed { i, app ->
                val isHidden = app.packageName in hidden
                SheetItem(app.label, (if (app.isTvApp) "" else "手機版・") + if (isHidden) "隱藏" else "顯示", selected = !isHidden) {
                    setHidden(app.packageName, !isHidden)
                    visibilitySheet(i)
                }
            }, focusIndex = focusIndex,
        ), gearReq)
    }

    // ───────────── 遙控器按鍵 ─────────────
    // 流程：新增綁定 → 按一下要改的鍵 → 選「按一下」或「按住半秒」→ 選動作 → 回到「已綁定的按鍵」清單
    val learnTitle = "按一下要改的鍵"
    fun pressName(long: Boolean) = if (long) "按住半秒" else "按一下"
    fun bindingTitle(code: Int, long: Boolean) = KeyMapStore.keyName(code) + "・" + pressName(long)

    /** 儲存（action＝null：刪除，恢復原本的功能）。 */
    fun saveBinding(code: Int, long: Boolean, action: KeyAction?) {
        android.util.Log.i("LiuliKeys", "save ${KeyMapStore.keyName(code)} long=$long -> ${action?.encode() ?: "(remove)"}")
        if (KeyRemapService.isProtected(code)) { toast("這是基本鍵，不能改綁"); return }
        if (action == null) KeyMapStore.remove(context, code, long) else KeyMapStore.put(context, KeyBinding(code, long, action))
        KeyRemapService.instance?.reload()
        toast(bindingTitle(code, long) + " → " + (action?.label ?: "原本的功能"))
    }

    // 這顆鍵（這種按法）要做什麼；[after] 存好之後要去哪裡（通常是綁定清單）
    fun chooseKeyAction(code: Int, long: Boolean, after: () -> Unit) {
        val title = bindingTitle(code, long)
        val current = KeyMapStore.load(context).firstOrNull { it.keyCode == code && it.long == long }?.action
        fun pick(action: KeyAction?) {
            saveBinding(code, long, action)
            KeyMapStore.setPending(context, 0)
            // 流程完成：返回路徑退回「遙控器按鍵」那一層，再到下一個畫面（通常是綁定清單）
            finishTo("keys") { after() }
        }
        // 分類子選單：右邊顯示目前選的是這一類裡的哪一個
        fun group(name: String, list: List<KeyAction>, note: String? = null) =
            SheetItem(name, list.firstOrNull { it == current }?.label ?: note) {
                open(SheetSpec("$title：$name", null, list.map { act ->
                    SheetItem(act.label, selected = act == current) { pick(act) }
                }), gearReq)
            }
        val sources = TvSources.list(context)
        val items = buildList {
            add(SheetItem("開啟 App…", (current as? KeyAction.OpenApp)?.label ?: "從清單選一個") {
                open(SheetSpec("$title：開啟哪個 App", null, apps.map { app ->
                    SheetItem(app.label, if (app.isTvApp) null else "手機版",
                        selected = (current as? KeyAction.OpenApp)?.pkg == app.packageName) { pick(KeyAction.OpenApp(app.packageName, app.label)) }
                }), gearReq)
            })
            add(group("訊號源…", listOf(KeyAction.Inputs) + sources.map { KeyAction.Source(it.id, it.label) },
                if (sources.isEmpty()) "訊號源選單" else "指定 HDMI 等"))
            add(group("音量…", KeyAction.VOLUME, "加大、減小、靜音"))
            add(group("媒體播放…", KeyAction.MEDIA, "播放／暫停、快轉…"))
            add(group("琉璃功能…", KeyAction.LIULI, "主畫面、所有 App、月曆…"))
            add(group("系統功能…", KeyAction.SYSTEM, "返回、通知、語音助理…"))
            add(SheetItem(KeyAction.Block.label, "按了沒有反應", selected = current == KeyAction.Block) { pick(KeyAction.Block) })
            if (current != null) add(SheetItem("還原成原本的功能", "刪除這個綁定") { pick(null) })
        }
        val note = if (long) "只設「按住半秒」時，這顆鍵按一下會失去原本的功能（返回鍵、Home 鍵除外）：琉璃攔下按鍵後，沒辦法再把原本的按鍵送回去。"
        else "目前：" + (current?.label ?: "原本的功能")
        open(SheetSpec("$title 要做什麼？", note, items), gearReq)
    }

    // 攔截精靈的第 2 步在 learnKey 之後宣告（兩者互相呼叫），先用變數接上
    var showCapture: (() -> Unit) -> Unit = {}

    // 按鍵測試：按任何鍵，顯示琉璃收到了什麼（收不到＝電視系統直接處理，無法改綁）
    fun keyTestSheet() {
        lastKeyText = "（還沒按）"
        open(SheetSpec(
            keyTestTitle,
            "按遙控器上的任何鍵，琉璃收到的按鍵會顯示在下面。按了卻沒有變化＝這顆鍵由電視系統直接處理（常見於 Netflix、YouTube 等品牌鍵），琉璃收不到，也就無法改綁。按返回結束。",
            listOf(SheetItem("收到的按鍵", liveDetail = { lastKeyText }) { }),
        ), gearReq)
    }

    fun learnKey(after: () -> Unit) {
        if (KeyRemapService.instance == null) {
            toast("按鍵服務還在啟動，請過幾秒再試")
            return
        }
        open(SheetSpec(
            learnTitle,
            "15 秒內，按一下遙控器上要改的那顆鍵（例如 Netflix、YouTube、彩色鍵、節目表鍵）。方向、確定、返回、音量、電源是基本鍵，受保護不能改，按了不會被當成要改的鍵。",
            listOf(SheetItem("取消") { close() }),
            transient = true,
        ), gearReq)
        val learner: (Int) -> Unit = { code ->
            KeyMapStore.setPending(context, code) // 存起來：不小心離開 App 再回來也還在
            showCapture(after)
        }
        KeyRemapService.learner = learner
        scope.launch {
            delay(15_000)
            // 15 秒都沒收到：說明原因，而不是默默關掉
            if (KeyRemapService.learner === learner && sheet?.spec?.title == learnTitle) {
                KeyRemapService.learner = null
                open(SheetSpec(
                    "沒有收到按鍵",
                    "15 秒內琉璃沒有收到可以改的鍵。可能是：那顆鍵由電視系統直接處理（常見於 Netflix、YouTube 等品牌鍵），琉璃收不到；或按鍵服務被系統停用了。可以用「按鍵測試」看看哪些鍵收得到。",
                    listOf(
                        SheetItem("再試一次") { learnKey(after) },
                        SheetItem("按鍵測試") { keyTestSheet() },
                        SheetItem("關閉") { close() },
                    ),
                    transient = true,
                ), gearReq)
            }
        }
    }

    // 攔截狀態：已攔到哪一顆鍵 → 下一步設定「按一下」或「按住半秒」的功能，或重新攔截
    fun captureSheet(after: () -> Unit) {
        val code = KeyMapStore.pending(context)
        if (code == 0) { learnKey(after); return }
        val rules = KeyMapStore.load(context).filter { it.keyCode == code }
        fun cur(long: Boolean) = rules.firstOrNull { it.long == long }?.action?.label ?: "原本的功能"
        open(SheetSpec(
            "已攔截：${KeyMapStore.keyName(code)}",
            "琉璃已經攔截到這顆鍵。下一步選擇要設定「按一下」還是「按住半秒」的功能（右邊是目前的設定），或重新攔截另一顆鍵。離開 App 再回來，這裡仍會記得攔到的鍵。",
            listOf(
                SheetItem("下一步：設定「按一下」", cur(false)) { chooseKeyAction(code, false, after) },
                SheetItem("下一步：設定「按住半秒」", cur(true)) { chooseKeyAction(code, true, after) },
                SheetItem("重新攔截新的按鍵") { KeyMapStore.setPending(context, 0); learnKey(after) },
                SheetItem("清除，不設定了") { KeyMapStore.setPending(context, 0); close() },
            ),
            key = "capture",
        ), gearReq)
    }
    showCapture = { a -> captureSheet(a) }

    // 已綁定的按鍵：每一條都能修改或刪除
    fun bindingsSheet() {
        val rules = KeyMapStore.load(context)
        val items = buildList {
            if (rules.isEmpty()) add(SheetItem("還沒有任何綁定", "回上一層按「新增綁定」開始") { close() })
            rules.forEach { r ->
                val title = bindingTitle(r.keyCode, r.long)
                add(SheetItem(title, r.action.label) {
                    open(SheetSpec(title, "目前：${r.action.label}", listOf(
                        SheetItem("改成其他動作") { chooseKeyAction(r.keyCode, r.long) { bindingsSheet() } },
                        SheetItem("刪除這個綁定", "這顆鍵恢復原本的功能") { saveBinding(r.keyCode, r.long, null); bindingsSheet() },
                        SheetItem("返回清單") { bindingsSheet() },
                    )), gearReq)
                })
            }
            if (rules.size > 1) add(SheetItem("全部刪除") {
                open(SheetSpec("刪除全部 ${rules.size} 個綁定？", "所有按鍵都會恢復原本的功能。", listOf(
                    SheetItem("全部刪除") {
                        rules.forEach { KeyMapStore.remove(context, it.keyCode, it.long) }
                        KeyRemapService.instance?.reload()
                        toast("已刪除全部綁定")
                        bindingsSheet()
                    },
                    SheetItem("取消") { bindingsSheet() },
                )), gearReq)
            })
        }
        open(SheetSpec("已綁定的按鍵（${rules.size}）", "按確定可以修改或刪除。右邊是這顆鍵現在會做的事。", items, key = "bindings", reopen = { bindingsSheet() }), gearReq)
    }

    // 透過本機網路 ADB 自我授權開啟無障礙服務（電視常常沒有無障礙設定選單）
    fun selfGrant() {
        open(SheetSpec(
            "自我授權啟用",
            "第一次會在電視畫面跳出「允許 USB 偵錯？」，請用遙控器選「一律允許」，再回來按一次。需先在開發人員選項開啟網路 ADB 偵錯。",
            listOf(
                SheetItem("開始（連線本機 ADB）") {
                    toast("連線中…請注意電視是否跳出授權對話框")
                    scope.launch {
                        when (val r = SelfAdb.enableAccessibility(context)) {
                            SelfAdb.Result.Success -> {
                                KeyRemapService.instance?.reload()
                                close(); toast("已啟用按鍵服務")
                            }
                            SelfAdb.Result.NeedsAuthorization ->
                                toast("請在電視上按「一律允許」後，再按一次開始")
                            is SelfAdb.Result.NotConnectable ->
                                toast("連不上本機 ADB，請確認已開啟網路 ADB 偵錯（${r.detail}）")
                            is SelfAdb.Result.Failed -> toast("失敗：${r.detail}")
                        }
                    }
                },
                SheetItem("改用無障礙設定") {
                    close()
                    closeAll()
                    if (!SystemActions.launch(context, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))) toast("找不到無障礙設定")
                },
            ),
        ), gearReq)
    }

    // 效能最佳化：透過本機網路 ADB 把琉璃預先編譯成機器碼（弱 CPU 的電視差很多）
    fun perfSheet() {
        open(SheetSpec(
            "效能最佳化",
            "把琉璃預先編譯成機器碼：第一次開面板、抽屜不再卡頓（弱 CPU 的電視差最多）。約 10 秒，完成後琉璃會自動重新啟動。需要網路 ADB 偵錯（和啟用按鍵服務相同）。",
            listOf(
                SheetItem("開始最佳化") {
                    toast("編譯中…約 10 秒，請稍候")
                    scope.launch {
                        when (val r = SelfAdb.compileSelf(context)) {
                            SelfAdb.Result.Success -> {
                                toast("完成，重新啟動中")
                                delay(800)
                                SelfAdb.restartSelf(context)
                            }
                            SelfAdb.Result.NeedsAuthorization ->
                                toast("請在電視上按「一律允許」後，再按一次開始")
                            is SelfAdb.Result.NotConnectable ->
                                toast("連不上本機 ADB，請確認已開啟網路 ADB 偵錯（${r.detail}）")
                            is SelfAdb.Result.Failed -> toast("失敗：${r.detail}")
                        }
                    }
                },
            ),
        ), gearReq)
    }

    // 螢幕小時鐘：看其他 App 時角落的時間（由按鍵服務疊在最上層）
    fun clockSheet() {
        val corners = tw.liuli.launcher.keys.ClockOverlay.CORNERS
        val items = buildList {
            if (!KeyMapStore.isServiceEnabled(context)) {
                add(SheetItem("先啟用按鍵服務", "小時鐘由按鍵服務顯示在其他 App 上層") { selfGrant() })
            }
            add(SheetItem("顯示小時鐘", liveDetail = { if (settings.clockOn) "開" else "關" },
                onAdjust = { settings.updateClock(on = !settings.clockOn) }) { settings.updateClock(on = !settings.clockOn) })
            add(SheetItem("位置", liveDetail = { corners[settings.clockCorner.coerceIn(0, 3)] },
                onAdjust = { d -> settings.updateClock(corner = (settings.clockCorner + d + 4) % 4) }) { close() })
            add(SheetItem("文字大小", liveDetail = { "${settings.clockSize} sp" },
                onAdjust = { d -> settings.updateClock(size = (settings.clockSize + d * 2).coerceIn(12, 72)) }) { close() })
            add(SheetItem("水平位移（往內）", liveDetail = { "${settings.clockX} dp" },
                onAdjust = { d -> settings.updateClock(x = (settings.clockX + d * 4).coerceIn(0, 480)) }) { close() })
            add(SheetItem("垂直位移（往內）", liveDetail = { "${settings.clockY} dp" },
                onAdjust = { d -> settings.updateClock(y = (settings.clockY + d * 4).coerceIn(0, 270)) }) { close() })
            add(SheetItem("背景", liveDetail = { if (settings.clockBg == 1) "半透明底" else "只有陰影" },
                onAdjust = { settings.updateClock(bg = 1 - settings.clockBg) }) { settings.updateClock(bg = 1 - settings.clockBg) })
            add(SheetItem("恢復預設位置與大小") { settings.updateClock(corner = 1, size = 22, x = 40, y = 28, bg = 0) })
        }
        open(SheetSpec(
            "螢幕小時鐘",
            "看 Netflix、YouTube 等其他 App 時，角落仍看得到時間；回到琉璃主畫面會自動隱藏。左右鍵調整（確定鍵＝下一個值），設定時主畫面上也會顯示方便預覽。每分鐘微移幾個像素，避免烙印。",
            items,
            reopen = { clockSheet() },
        ), gearReq)
    }

    fun keysSheet() {
        val enabled = KeyMapStore.isServiceEnabled(context)
        val n = KeyMapStore.load(context).size
        val items = buildList {
            if (!enabled) add(SheetItem("啟用按鍵服務", "第一步：自我授權，不需電腦") { selfGrant() })
            val pendingCode = KeyMapStore.pending(context)
            add(SheetItem("新增綁定", when {
                !enabled -> "需先啟用按鍵服務"
                pendingCode != 0 -> "已攔截：${KeyMapStore.keyName(pendingCode)}，繼續設定"
                else -> "攔截一顆鍵 → 選按法 → 選功能"
            }) {
                if (enabled) captureSheet { bindingsSheet() } else toast("請先啟用按鍵服務")
            })
            add(SheetItem("已綁定的按鍵", if (n == 0) "還沒有" else "$n 個") { bindingsSheet() })
            add(SheetItem("按鍵測試", "看看哪些鍵琉璃收得到") {
                if (enabled) keyTestSheet() else toast("請先啟用按鍵服務")
            })
            add(SheetItem("防止回到原廠主畫面", if (KeyMapStore.guardHome(context)) "開" else "關") {
                KeyMapStore.setGuardHome(context, !KeyMapStore.guardHome(context)); keysSheet()
            })
        }
        val status = when {
            !enabled -> "按鍵服務：未啟用。改綁按鍵、防止回到原廠主畫面、螢幕小時鐘都需要它。也可以用電腦執行：${KeyMapStore.adbEnableCommand(context)}"
            KeyRemapService.instance == null -> "按鍵服務：已啟用，正在啟動…"
            else -> "按鍵服務：已啟用。"
        }
        open(SheetSpec("遙控器按鍵", status, items, key = "keys", reopen = { keysSheet() }), gearReq)
    }

    fun minimalSummary(): String = listOfNotNull(
        minimalIdleText(settings.minimalIdle).let { if (settings.minimalIdle <= 0) "手動" else it },
        if (settings.minimalDim > 0) "微光" + MINIMAL_DIM_LABELS[settings.minimalDim] else null,
        if (settings.minimalDrift) "微飄移" else null,
        if (settings.minimalClean) "自動清理" else null,
    ).joinToString("・")

    // 簡潔模式：只留時間與天氣的畫面；進入方式與省電、防烙印的選項都在這裡
    fun minimalSheet() {
        open(SheetSpec(
            "簡潔模式",
            "其他介面全部收起，只留左下角的時間、右下角的天氣，背景慢慢流動。主畫面最上方再按上鍵進入，按任何方向鍵回來。",
            listOf(
                SheetItem("閒置自動進入", liveDetail = { minimalIdleText(settings.minimalIdle) },
                    onAdjust = { d ->
                        val i = MINIMAL_IDLE_OPTIONS.indexOf(settings.minimalIdle).coerceAtLeast(0)
                        settings.updateMinimalIdle(MINIMAL_IDLE_OPTIONS[(i + d + MINIMAL_IDLE_OPTIONS.size) % MINIMAL_IDLE_OPTIONS.size])
                    }) { },
                SheetItem("微光", subtitle = "壓暗整個畫面：省電、保護面板", liveDetail = { MINIMAL_DIM_LABELS[settings.minimalDim.coerceIn(0, 3)] },
                    onAdjust = { d -> settings.updateMinimalDim((settings.minimalDim + d + 4) % 4) }) { },
                SheetItem("微飄移", subtitle = "時間與天氣緩慢飄動，避免烙印", liveDetail = { if (settings.minimalDrift) "開" else "關" },
                    onAdjust = { settings.updateMinimalDrift(!settings.minimalDrift) }) { },
                SheetItem("自動清理記憶體", subtitle = "進入時結束背景程式", liveDetail = { if (settings.minimalClean) "開" else "關" },
                    onAdjust = { settings.updateMinimalClean(!settings.minimalClean) }) { settings.updateMinimalClean(!settings.minimalClean) },
                SheetItem("現在進入簡潔模式") { closeAll(); minimal = true },
            ),
            key = "minimal", reopen = { minimalSheet() },
        ), gearReq)
    }

    // 時光：會隨時間變化的背景（山稜的光影色調…）用實時，或固定在一個模擬時刻
    fun hm(m: Int) = "%02d:%02d".format(m / 60, m % 60)
    fun timeSummary() = if (settings.timeFixed < 0) "實時" else "固定・" + hm(settings.timeFixed)
    fun timeSheet() {
        val presets = listOf(
            "深夜・星軌" to 60, "黎明藍調" to 5 * 60 + 40, "日出" to 6 * 60 + 20, "早晨" to 8 * 60,
            "正午" to 12 * 60, "午後" to 15 * 60, "夕陽" to 17 * 60, "暮色" to 17 * 60 + 40,
        )
        open(SheetSpec(
            "時光",
            "會隨時間變化的背景（例如玉山星軌的光影與天色）用哪個時刻。實時：跟著現在的時間與太陽高度；固定：停在你選的模擬時刻。",
            listOf(
                SheetItem("時間", liveDetail = { if (settings.timeFixed < 0) "實時" else "固定" },
                    onAdjust = { settings.updateTime(settings.timeFixed < 0) }) { settings.updateTime(settings.timeFixed < 0) },
                SheetItem("模擬時刻", subtitle = "左右鍵每次 10 分鐘（按住連續調整）", liveDetail = { hm(settings.timeSim) + if (settings.timeFixed < 0) "（未使用）" else "" },
                    onAdjust = { d -> settings.updateTime(true, settings.timeSim + d * 10) }) { settings.updateTime(true) },
            ) + presets.map { (name, m) ->
                SheetItem(name, hm(m), selected = settings.timeFixed == m) { settings.updateTime(true, m); close() }
            },
            key = "time", reopen = { timeSheet() },
        ), gearReq)
    }

    // 外觀：和畫面呈現有關的設定都收在這裡
    fun appearanceSheet() {
        open(SheetSpec(
            "外觀", "背景、顏色、質感、邊框與簡潔模式。",
            listOf(
                SheetItem("背景樣式", liveDetail = { if (settings.bgStyle == BgStyle.RANDOM_ID) "隨機・" + engine.style.label else engine.style.label }) { styleSheet() },
                SheetItem("背景色系", liveDetail = { if (settings.bgFollow) "自動（跟著焦點）" else "固定・色相 ${settings.bgHue.roundToInt()}°" }) { colorSheet() },
                SheetItem("背景質感", liveDetail = {
                    listOfNotNull(
                        if (settings.bgBlur > 0) "模糊 ${settings.bgBlur}" else null,
                        if (settings.grain > 0) "噪點 ${tw.liuli.launcher.ui.bg.GRAIN_LABELS[settings.grain]}" else null,
                    ).joinToString("・").ifEmpty { "關" }
                }) { textureSheet() },
                SheetItem("邊框", liveDetail = {
                    if (settings.frameThickness == 0 && settings.frameFeather == 0 && settings.frameRadius == 0) "關"
                    else "粗 ${settings.frameThickness}・圓 ${settings.frameRadius}・漸 ${settings.frameFeather}"
                }) { frameSheet() },
                SheetItem("時光", liveDetail = { timeSummary() }) { timeSheet() },
                SheetItem("簡潔模式", liveDetail = { minimalSummary() }) { minimalSheet() },
            ),
            key = "appearance", reopen = { appearanceSheet() },
        ), gearReq)
    }

    // 進階：不常用、偏技術的項目
    fun advancedSheet() {
        open(SheetSpec(
            "進階", null,
            listOf(
                SheetItem("效能最佳化", "預先編譯成機器碼，操作更順") { perfSheet() },
                SheetItem("預設主畫面", "開啟系統的主畫面應用程式設定") { closeAll(); SystemActions.homeSettings(context) },
                SheetItem("重新整理 App 清單", "安裝或移除 App 後清單沒更新時用") { reloadKey++; toast("已重新整理 App 清單") },
            ),
            key = "advanced", reopen = { advancedSheet() },
        ), gearReq)
    }

    // 第三方授權：改寫自其他開源專案的部分（Apache-2.0 要附上授權、NOTICE 與修改說明；授權全文在 assets/licenses）
    fun licenseSheet() {
        open(SheetSpec(
            "第三方授權", "琉璃本身是 MIT 授權；以下部分改寫自其他開源專案。",
            listOf(
                SheetItem("Paper Shaders", subtitle = "Apache License 2.0・github.com/paper-design/shaders") { },
                SheetItem("Powered by Paper Shaders", subtitle = "https://shaders.paper.design") { },
                SheetItem("Paper 改寫的部分", subtitle = "「網格漸層」「漩渦」背景：改寫成 OpenGL ES 2.0、時間與軌跡改在 CPU 計算、顏色跟著主色。授權全文附在 App 裡（assets/licenses）") { },
            ),
            key = "licenses", reopen = { licenseSheet() },
        ), gearReq)
    }

    // 關於琉璃：版本、手動檢查更新（只看正式版、只在按下時才做）、專案網址 QR code
    fun aboutSheet() {
        val version = BuildConfigInfo.versionName(context)
        open(SheetSpec(
            "關於琉璃", "琉璃 Launcher：給 Google TV／Android TV 的主畫面。",
            listOf(
                // 只有一列：檢查、下載進度、有新版、交給系統安裝、失敗重試，全部都在這一列按確定
                SheetItem(
                    "軟體更新",
                    liveLabel = { Updater.title() },
                    liveSubtitle = { Updater.subtitle(context, version) },
                    liveDetail = { Updater.hint() },
                ) { Updater.press(context) },
                SheetItem("專案網址", subtitle = "github.com/candy999/liuli-launcher") { },
                SheetItem("授權", "MIT・開源") { },
                SheetItem("第三方授權", "部分背景改寫自 Paper Shaders") { licenseSheet() },
            ),
            key = "about", reopen = { aboutSheet() },
            header = { AboutHeader(Updater.PROJECT_URL) },
        ), gearReq)
    }

    // 設定首頁：最常調整的放上面（外觀、地點、App），系統捷徑居中，進階與關於放最後
    fun openSettings() {
        open(SheetSpec(
            "設定", "琉璃 Launcher ${BuildConfigInfo.versionName(context)}",
            listOf(
                SheetItem("外觀", liveDetail = { engine.style.label + if (settings.bgFollow) "・自動色彩" else "・固定色" }) { appearanceSheet() },
                SheetItem("主畫面模式", subtitle = "技術宅：多火箭與系統儀表",
                    liveDetail = { if (settings.geekMode) "技術宅模式" else "正常人模式" },
                    onAdjust = { settings.updateGeekMode(!settings.geekMode) }) { settings.updateGeekMode(!settings.geekMode) },
                SheetItem("地點", liveDetail = { Cities.byId(settings.cityId).name + "・天氣" }) { citySheet() },
                SheetItem("顯示／隱藏 App", liveDetail = { if (hidden.isEmpty()) "" else "已隱藏 ${hidden.size} 個" }) { visibilitySheet(0) },
                SheetItem("螢幕小時鐘", liveDetail = { if (settings.clockOn) "${tw.liuli.launcher.keys.ClockOverlay.CORNERS[settings.clockCorner.coerceIn(0, 3)]}・${settings.clockSize} sp" else "關" }) { clockSheet() },
                SheetItem("遙控器按鍵", when {
                    !KeyMapStore.isServiceEnabled(context) -> "按鍵服務未啟用"
                    KeyMapStore.load(context).isEmpty() -> "改綁、防誤入原廠主畫面"
                    else -> "已綁定 ${KeyMapStore.load(context).size} 個"
                }) { keysSheet() },
                SheetItem("網路") { closeAll(); SystemActions.networkSettings(context) },
                SheetItem("系統設定") { closeAll(); SystemActions.systemSettings(context) },
                SheetItem("進階", "效能最佳化、預設主畫面") { advancedSheet() },
                SheetItem("關於琉璃", liveDetail = { BuildConfigInfo.versionName(context) }) { aboutSheet() },
            ),
            key = "settings", reopen = { openSettings() },
        ), gearReq)
    }

    // 量測用（adb）：ACTION=blur:3、grain:2、sheet:texture、sheet:settings（不用遙控器導覽，避免誤開 App）
    // 放在所有面板函式宣告之後（區域函式要先宣告才能用）
    LaunchedEffect(externalAction) {
        val ext = externalAction.second
        if (externalAction.first > 0 && ext != null) {
            val arg = ext.substringAfter(':', "")
            when (ext.substringBefore(':')) {
                "blur" -> { val v = arg.toIntOrNull() ?: 0; settings.updateBgBlur(v); engine.applyBlur(v) }
                "grain" -> settings.updateGrain((arg.toIntOrNull() ?: 0).coerceIn(0, 3))
                "sheet" -> when (arg) {
                    "texture" -> textureSheet()
                    "settings" -> openSettings()
                    "frame" -> frameSheet()
                    "perf" -> perfSheet()
                    "clock" -> clockSheet()
                    "color" -> colorSheet()
                    "appearance" -> appearanceSheet()
                    "about" -> aboutSheet()
                    "minimal" -> minimalSheet()
                    "advanced" -> advancedSheet()
                }
                "picker" -> {
                    settings.updateBgFollow(false); engine.applyFollow(false, settings.bgHue, settings.bgSat)
                    pickerOrigin = settings.bgHue to settings.bgSat; sheet = null; picking = true
                }
                "drawer" -> { sheet = null; exitingSheet = null; drawerOpen = true }
                "fakeapps" -> fakeApps = (arg.toIntOrNull() ?: 0).coerceIn(0, 200)
                "fakesources" -> fakeSources = arg == "1"
                // 量測用（adb）：模擬滑鼠（Android 9 的 adb 沒有游標移動、滾輪）。mouse:move,x,y 游標移到 (x,y) 像素；mouse:wheel,-1 滾輪往下一格
                "mouse" -> (context as? android.app.Activity)?.let { act ->
                    val p = arg.split(',')
                    val t = android.os.SystemClock.uptimeMillis()
                    val pp = android.view.MotionEvent.PointerProperties().apply { id = 0; toolType = android.view.MotionEvent.TOOL_TYPE_MOUSE }
                    val pc = android.view.MotionEvent.PointerCoords()
                    val action = if (p[0] == "wheel") {
                        pc.setAxisValue(android.view.MotionEvent.AXIS_VSCROLL, p.getOrNull(1)?.toFloatOrNull() ?: -1f)
                        android.view.MotionEvent.ACTION_SCROLL
                    } else {
                        pc.x = p.getOrNull(1)?.toFloatOrNull() ?: 0f; pc.y = p.getOrNull(2)?.toFloatOrNull() ?: 0f
                        android.view.MotionEvent.ACTION_HOVER_MOVE
                    }
                    val ev = android.view.MotionEvent.obtain(t, t, action, 1, arrayOf(pp), arrayOf(pc), 0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_MOUSE, 0)
                    act.dispatchGenericMotionEvent(ev)
                    ev.recycle()
                }
                // 量測用（adb）：bgpace＝記錄接下來 300 格背景的間隔、印到 logcat（LiuliBg pace）；bgcap:30＝背景不升到每秒 60 格（比較用），bgcap:0 恢復；
                // keylat:1＝每次按鍵印出「按下 → 下一格畫面畫完」的延遲（LiuliBg keylat）
                "bgpace" -> engine.startPaceLog()
                // 量測用（adb）：bgstyle:mesh＝這次執行先換成某個背景樣式（不存設定）；glres:0／1＝著色器背景強制 480×270／320×180，glres:-1 自動
                "bgstyle" -> engine.applyStyle(BgStyle.byId(arg))
                "glres" -> tw.liuli.launcher.ui.bg.GlBackground.debugLevel = arg.toIntOrNull() ?: -1
                // gllog:1＝一直印著色器背景的 GPU 耗時（LiuliGl）與玻璃小圖的 CPU 成本（LiuliBg glass）
                "gllog" -> tw.liuli.launcher.ui.bg.GlBackground.verbose = arg == "1"
                // glassdump＝把玻璃取樣的小圖（模糊前的 GL 小圖與模糊後的玻璃圖）存成 PNG（files/shots/），檢查玻璃顏色對不對
                "glassdump" -> Thread {
                    runCatching {
                        val dir = java.io.File(context.getExternalFilesDir(null), "shots").apply { mkdirs() }
                        tw.liuli.launcher.ui.bg.GlBackground.current?.let { b -> java.io.File(dir, "gl.png").outputStream().use { b.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
                        val g = engine.glass.asAndroidBitmap().copy(android.graphics.Bitmap.Config.ARGB_8888, false)
                        java.io.File(dir, "glass.png").outputStream().use { g.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                        android.util.Log.i("LiuliUi", "glassdump saved")
                    }
                }.start()
                "bgcap" -> tw.liuli.launcher.ui.bg.BackgroundEngine.debugNoFast = arg == "30"
                "keylat" -> tw.liuli.launcher.ui.bg.KeyLat.enabled = arg == "1"
                "uitrace" -> tw.liuli.launcher.ui.bg.UiTrace.set(arg == "1", traceView)
                // 量測用（adb）：ACTION=glassslow:0.1 玻璃焦點的動畫放慢十倍（截滑動中的畫面用）；glassslow:1 恢復。不存設定
                "glassslow" -> tw.liuli.launcher.ui.glass.GlassDebug.timeScale = (arg.toFloatOrNull() ?: 1f).coerceIn(0.01f, 1f)
                // 量測用（adb）：ACTION=shot:名稱 把目前的視窗畫面存成 PNG（Android/data/tw.liuli.launcher/files/shots/名稱.png）。
                // 直接拷貝 App 自己的畫面緩衝，不靠系統截圖（模擬器視窗縮小時 screencap 只會拿到黑畫面）
                "shot" -> (context as? android.app.Activity)?.window?.let { win ->
                    val v = win.decorView
                    if (v.width > 0 && v.height > 0) {
                        val bmp = android.graphics.Bitmap.createBitmap(v.width, v.height, android.graphics.Bitmap.Config.ARGB_8888)
                        android.view.PixelCopy.request(win, bmp, { r ->
                            if (r == android.view.PixelCopy.SUCCESS) Thread {
                                runCatching {
                                    val dir = java.io.File(context.getExternalFilesDir(null), "shots").apply { mkdirs() }
                                    java.io.File(dir, arg.ifBlank { "shot" } + ".png").outputStream().use {
                                        bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                                    }
                                    android.util.Log.i("LiuliUi", "shot saved: $arg")
                                }
                                bmp.recycle()
                            }.start() else android.util.Log.w("LiuliUi", "shot failed: $r")
                        }, android.os.Handler(android.os.Looper.getMainLooper()))
                    }
                }
                "probe" -> tw.liuli.launcher.ui.bg.PerfProbe.apply(arg)
                // 量測用（adb）：ACTION=burst:名稱,格數,後續動作 連續每一格拷貝畫面（半解析度）存成 名稱_00.png…，
                // 開始拷貝後立刻執行後續動作（sheet:settings 或 drawer）——看面板滑入的每一格
                "burst" -> (context as? android.app.Activity)?.window?.let { win ->
                    val parts = arg.split(',')
                    val name = parts.getOrNull(0).orEmpty().ifBlank { "burst" }
                    val n = (parts.getOrNull(1)?.toIntOrNull() ?: 24).coerceIn(1, 60)
                    // 後續動作 key:23＝送一個按鍵給自己（例如在面板裡按確定進下一層）
                    val follow = parts.getOrNull(2).orEmpty()
                    // 第四個參數：每幾格拷貝一張（冷啟動要看好幾秒，例如 burst:cold,60,,5）
                    val every = (parts.getOrNull(3)?.toIntOrNull() ?: 1).coerceIn(1, 30)
                    val v = win.decorView
                    val frames = arrayOfNulls<android.graphics.Bitmap>(n)
                    val handler = android.os.Handler(android.os.Looper.getMainLooper())
                    var done = 0
                    fun grab(i: Int) {
                        if (i >= n) return
                        val bmp = android.graphics.Bitmap.createBitmap(v.width / 2, v.height / 2, android.graphics.Bitmap.Config.ARGB_8888)
                        android.view.PixelCopy.request(win, bmp, { r ->
                            if (r == android.view.PixelCopy.SUCCESS) frames[i] = bmp
                            done++
                            if (done == n) Thread {
                                runCatching {
                                    val dir = java.io.File(context.getExternalFilesDir(null), "shots").apply { mkdirs() }
                                    frames.forEachIndexed { k, b ->
                                        if (b != null) java.io.File(dir, "%s_%02d.png".format(name, k)).outputStream().use { b.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                                    }
                                    android.util.Log.i("LiuliUi", "burst saved: $name")
                                }
                            }.start()
                        }, handler)
                        var left = every
                        fun next() { android.view.Choreographer.getInstance().postFrameCallback { if (--left <= 0) grab(i + 1) else next() } }
                        next()
                    }
                    grab(0)
                    when (follow) {
                        "sheet:settings" -> openSettings()
                        "drawer" -> { sheet = null; exitingSheet = null; drawerOpen = true }
                        else -> follow.removePrefix("key:").toIntOrNull()?.takeIf { follow.startsWith("key:") }?.let { code ->
                            Thread { runCatching { android.app.Instrumentation().sendKeyDownUpSync(code) } }.start()
                        }
                    }
                }
                "clock" -> settings.updateClock(on = arg == "1" || arg == "on")
                // 量測用（adb）：ACTION=color:色相,飽和度 固定背景顏色（關掉自動色彩）；color:auto 回到自動色彩
                "color" -> if (arg == "auto") { settings.updateBgFollow(true); engine.applyFollow(true, settings.bgHue, settings.bgSat) } else {
                    val hs = arg.split(',')
                    val h = hs.getOrNull(0)?.toFloatOrNull() ?: 228f; val sa = hs.getOrNull(1)?.toFloatOrNull() ?: .5f
                    settings.updateBgFollow(false); engine.applyFollow(false, h, sa)
                    settings.updateBgColor(h, sa); engine.applyFixedColor(h, sa)
                }
            }
        }
    }

    // 按鍵測試面板開著時才監聽按鍵
    val keyTesting = sheet?.spec?.title == keyTestTitle
    LaunchedEffect(keyTesting) {
        KeyRemapService.monitor = if (!keyTesting) null else { code ->
            val bound = KeyMapStore.load(context).filter { it.keyCode == code }
            lastKeyText = KeyMapStore.keyName(code) + when {
                KeyRemapService.isProtected(code) -> "・基本鍵（不能改）"
                bound.isNotEmpty() -> "・已綁定"
                else -> "・可以改綁"
            }
        }
    }

    // 小時鐘設定面板開著時，主畫面上也顯示小時鐘（即時預覽）
    val clockPreview = sheet?.spec?.title == "螢幕小時鐘"
    LaunchedEffect(clockPreview) {
        tw.liuli.launcher.keys.ClockOverlay.preview = clockPreview
        tw.liuli.launcher.keys.ClockOverlay.refresh()
    }

    // 返回鍵：面板 → 抽屜 → 回到第一個 App（主畫面不會離開）
    BackHandler(enabled = active) {
        when {
            minimal -> minimal = false
            picking -> { pickerOrigin?.let { (h, s) -> settings.updateBgColor(h, s); engine.applyFixedColor(h, s) }; picking = false; pendingFocus = gearReq }
            movingPkg != null -> { dock = moveOrigin; movingPkg = null }
            sheet != null -> close(cancel = true)
            drawerOpen -> closeDrawer()
            else -> runCatching { firstDock.requestFocus() }
        }
    }

    // 面板／抽屜開關（含面板換頁）：滑動動畫期間背景暫停；開著的時候背景用省力模式
    val overlay = drawerOpen || sheet != null || picking
    var overlaySeen by remember { mutableStateOf(false) }
    LaunchedEffect(drawerOpen, sheet?.spec, picking) {
        overlayChangedAt[0] = android.os.SystemClock.uptimeMillis()
        engine.covered = overlay
        tw.liuli.launcher.geek.WeakGpu.init()
        // 設定面板開著：背景壓暗（以前是全螢幕疊一層半透明黑，每格多混一整個螢幕）
        engine.dim = if (sheet != null) 0.22f else 0f
        if (overlaySeen || overlay) engine.holdForUi(UI_SLIDE_HOLD_MS)
        overlaySeen = true
    }

    // 量測用（adb）：ACTION=fakeapps:48 在所有 App 後面接上 48 個假的 App（複製現有的 App、改名），測抽屜捲動；fakeapps:0 拿掉。不存任何設定，重開就沒了
    val visibleApps = remember(apps, hidden, fakeApps) {
        val real = apps.filter { it.packageName !in hidden }
        if (fakeApps <= 0 || real.isEmpty()) real
        else real + List(fakeApps) { i -> real[i % real.size].let { a -> a.copy(packageName = "fake.$i.${a.packageName}", label = "${a.label} ${i + 1}") } }
    }
    val dockApps = remember(apps, dock) { dock.mapNotNull { pkg -> apps.firstOrNull { it.packageName == pkg } } }
    val overlayUp = drawerOpen || sheet != null || picking
    val recedeT = animateFloatAsState(if (overlayUp) 1f else 0f, tween(if (overlayUp) 380 else 200), label = "recede")
    // 收起時主畫面「從一半開始回來」：定格的抽屜／側邊欄收到一半之前，主畫面維持原樣（抽屜：看不到；側邊欄：變暗），
    // 後半段才跟著面板的位置淡回來。面板收前半段時每一格只有那一張圖在動，不用同時重畫整個半透明的主畫面
    val drawerSlide = remember { tw.liuli.launcher.ui.components.SlideRef() }
    val sheetSlide = remember { tw.liuli.launcher.ui.components.SlideRef() }
    val sheetClosing = androidx.compose.runtime.rememberUpdatedState(sheet == null && exitingSheet != null && !drawerOpen && !picking)
    val drawerOpenNow = androidx.compose.runtime.rememberUpdatedState(drawerOpen)
    val recede = remember {
        object : androidx.compose.runtime.State<Float> {
            override val value: Float get() =
                if (sheetClosing.value) ((1f - sheetSlide.read().coerceIn(0f, 1f)) / .5f).coerceIn(0f, 1f) else recedeT.value
        }
    }
    // 抽屜：主畫面整個隱藏（抽屜上方改放一行精簡的時間、天氣）；側邊欄只是變淡，背後的主畫面還看得到
    val drawerHide = animateFloatAsState(if (drawerOpen) 1f else 0f, tween(if (drawerOpen) 220 else 320), label = "drawerHide")
    // 只在「出現／消失」這一刻重新組合，淡入淡出中不每格重組整個主畫面
    val drawerBarShown by remember { androidx.compose.runtime.derivedStateOf { drawerHide.value > 0.001f } }
    // 主畫面被抽屜藏起來的程度：打開時照時間淡出；收起時看抽屜收到哪——前半段維持藏著，後半段淡回來
    val homeHide = remember {
        object : androidx.compose.runtime.State<Float> {
            override val value: Float get() =
                if (drawerOpenNow.value) drawerHide.value
                else ((1f - drawerSlide.read().coerceIn(0f, 1f)) / .5f).coerceIn(0f, 1f)
        }
    }

    // 主畫面一開始變淡，常用列焦點磚的陰影就收掉（淡到 85% 以下就沒有）
    val visNow = androidx.compose.runtime.rememberUpdatedState(visibility)
    val tileShadow: () -> Float = remember {
        {
            val a = (1f - 0.4f * recede.value) * (1f - minimalT.value) * (1f - homeHide.value) * visNow.value()
            ((a - .85f) / .15f).coerceIn(0f, 1f)
        }
    }
    // 主畫面被抽屜完全蓋住（透明度 0）時，主畫面上的玻璃不重畫、不重烘（抽屜、側邊欄自己另外設成「看得到」）
    val homeGlassHidden = remember { androidx.compose.runtime.derivedStateOf { homeHide.value > .999f } }
    val glassHidden: () -> Boolean = remember { { homeGlassHidden.value } }
    CompositionLocalProvider(
        LocalBringIntoViewSpec provides keepVisible,
        tw.liuli.launcher.ui.components.LocalTileShadow provides tileShadow,
        tw.liuli.launcher.ui.glass.LocalGlassHidden provides glassHidden,
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    // 淡入淡出不開全螢幕離屏緩衝（每個繪圖指令各自乘透明度）；回來時往上浮回原位一點點
                    val v = visibility()
                    compositingStrategy = CompositingStrategy.ModulateAlpha
                    alpha = v
                    translationY = (1f - v) * 14.dp.toPx()
                }
                .focusProperties {
                    enter = {
                        when {
                            !active || !initialFocusDone -> FocusRequester.Cancel
                            else -> initialTarget ?: FocusRequester.Default
                        }
                    }
                }
                .focusGroup()
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    lastKeyAt[0] = android.os.SystemClock.uptimeMillis()
                    tw.liuli.launcher.ui.bg.KeyLat.onKey(e.nativeKeyEvent.eventTime)
                    // 布丁按鈕：記下方向（按鈕拿到焦點時知道從哪邊來）；按完焦點沒有動＝已經到底，那一顆撞牆彈一下
                    run {
                        var dx = 0; var dy = 0
                        when (e.nativeKeyEvent.keyCode) {
                            KeyEvent.KEYCODE_DPAD_LEFT -> dx = -1
                            KeyEvent.KEYCODE_DPAD_RIGHT -> dx = 1
                            KeyEvent.KEYCODE_DPAD_UP -> dy = -1
                            KeyEvent.KEYCODE_DPAD_DOWN -> dy = 1
                        }
                        if (dx != 0 || dy != 0) {
                            val ji = tw.liuli.launcher.ui.glass.JellyInput
                            ji.note(dx, dy)
                            val cur = ji.current; val g = ji.gen
                            val wasDrawer = drawerOpen; val wasMinimal = minimal
                            if (cur != null && e.nativeKeyEvent.repeatCount == 0 && movingPkg == null && !picking) scope.launch {
                                androidx.compose.runtime.withFrameNanos { }
                                androidx.compose.runtime.withFrameNanos { }
                                if (ji.current === cur && ji.gen == g && drawerOpen == wasDrawer && minimal == wasMinimal) cur.bump(dx, dy)
                            }
                        }
                    }
                    if (!minimal) return@onPreviewKeyEvent false
                    // 簡潔模式中：方向鍵、確定鍵把介面拉回來（不移動焦點、不觸發任何東西）
                    when (e.nativeKeyEvent.keyCode) {
                        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
                        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> { minimal = false; true }
                        else -> false
                    }
                }
                .onKeyEvent { e ->
                    // 主畫面最上方再按上鍵（焦點已經沒地方去）：進入簡潔模式
                    if (e.type != KeyEventType.KeyDown || e.nativeKeyEvent.keyCode != KeyEvent.KEYCODE_DPAD_UP) return@onKeyEvent false
                    if (sheet != null || drawerOpen || picking || movingPkg != null || !active) return@onKeyEvent false
                    if (!focusManager.moveFocus(androidx.compose.ui.focus.FocusDirection.Up)) minimal = true
                    true
                },
        ) {
            // ── 主畫面內容：抽屜或面板開啟時往後退。分成兩層，中間夾黑框 ──
            // 半透明不開離屏緩衝（ModulateAlpha：每個繪圖指令各自乘透明度）：
            // 預設做法會把整個畫面先畫到一張離屏圖再疊回來，兩層就是每幀多兩張全螢幕，弱 GPU（Mali-470）會排隊。
            // 只變淡、不縮小：縮放比例每幀改變時，所有文字（尤其大時鐘）每幀都要重新點陣化，
            // 弱電視上開面板／抽屜的動畫期間每幀多 25ms 以上。
            val recedeLayer = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    compositingStrategy = CompositingStrategy.ModulateAlpha
                    val m = minimalT.value
                    alpha = (1f - 0.4f * recede.value) * (1f - m) * (1f - homeHide.value)
                    // 簡潔模式：整片往下收、淡出（只動圖層位置，不重新排版）
                    translationY = m * 46.dp.toPx()
                }
            // 第 1 層：會左右捲動的列（訊號源、常用 App、名稱）——在黑框之下，捲到邊緣會被框蓋住
            if (!tw.liuli.launcher.ui.bg.PerfProbe.noHome) Box(recedeLayer) {
                if (sources.isNotEmpty()) {
                    InputsRow(
                        sources = sources,
                        firstRequester = firstInput,
                        onSwitch = { src -> if (!TvSources.open(context, src)) toast("無法切換到 ${src.label}") },
                        modifier = Modifier.padding(start = 48.dp, top = 232.dp).enter(3),
                    )
                }

                // 常用 App：直接放在桌布上，不加框；按下鍵展開所有 App。焦點是一顆玻璃外框，在磚之間滑動
                LazyRow(
                    state = dockListState,
                    modifier = Modifier
                        .padding(top = 290.dp)
                        .fillMaxWidth()
                        .height(116.dp)
                        .onFocusChanged { dockHasFocus = it.hasFocus }
                        .onPreviewKeyEvent { e ->
                            val code = e.nativeKeyEvent.keyCode
                            val mp = movingPkg
                            if (mp != null) {
                                // 移動模式：左右直接搬動、確定放下；其他鍵（返回除外）都不讓焦點離開
                                if (e.type == KeyEventType.KeyDown) when (code) {
                                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                                        val i = dock.indexOf(mp)
                                        val j = i + if (code == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1
                                        if (i >= 0 && j in dock.indices) {
                                            dock = dock.toMutableList().apply { add(j, removeAt(i)) }
                                            scope.launch { keepDockItemVisible(dockListState, j, context) }
                                        }
                                    }
                                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                                        setDock(dock)
                                        movingPkg = null
                                    }
                                }
                                return@onPreviewKeyEvent code != KeyEvent.KEYCODE_BACK
                            }
                            if (e.type == KeyEventType.KeyDown && code == KeyEvent.KEYCODE_DPAD_DOWN) {
                                drawerOpen = true
                                true
                            } else false
                        }
                        .focusRow(enterTo = { dockReturnTarget() }),
                    contentPadding = PaddingValues(start = 48.dp, end = 48.dp, top = 14.dp, bottom = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(22.dp),
                ) {
                    if (appsLoaded && dockApps.isEmpty()) {
                        // 常用全部移除後，這一列必須還有東西能拿焦點，否則下鍵打不開所有 App、也無法再加入常用
                        item(key = "add") {
                            AddToDockTile(
                                requester = firstDock,
                                onFocused = { lastDockPkg = null; dockLabel = "打開所有 App，長按 App 可加入常用"; labelX = 48f * context.resources.displayMetrics.density + 75f * context.resources.displayMetrics.density },
                                onClick = { drawerOpen = true },
                            )
                        }
                    }
                    itemsIndexed(dockApps, key = { _, a -> a.packageName }) { i, app ->
                        val req = if (i == 0) firstDock else dockRequesters.getOrPut(app.packageName) { FocusRequester() }
                        // 移動中：被抓住的圖塊整塊（含陰影）往上浮，其他圖塊變淡
                        Box(
                            Modifier.graphicsLayer {
                                val mp = movingPkg
                                // 逐個出場：這一層只負責浮上來；淡入交給 App 磚裡已放大的那一層（不破圖、不裁角）
                                compositingStrategy = CompositingStrategy.ModulateAlpha
                                val a = tw.liuli.launcher.ui.components.rowReveal(dockReveal.value, i, stagger = 55f, fade = 280f)
                                translationY = (if (mp == app.packageName) -14.dp.toPx() else 0f) + (1f - a) * 18.dp.toPx()
                                alpha = if (mp != null && mp != app.packageName) 0.55f else 1f
                            },
                        ) {
                        AppTile(
                            app = app,
                            width = 150.dp,
                            showLabel = false,
                            requester = req,
                            onFocused = {
                                lastDockPkg = app.packageName
                                focusPrefs.edit().putString("last_dock", app.packageName).apply()
                                dockLabel = app.label
                                labelX = tileCenters[app.packageName] ?: 0f
                            },
                            onClick = { launchApp(app) },
                            onLongClick = { fr -> appSheet(app, fr) },
                            extra = Modifier
                                .onGloballyPositioned {
                                    val cx = it.boundsInRoot().center.x
                                    tileCenters[app.packageName] = cx
                                    // 列捲動或移動位置後，名稱跟著焦點圖塊的實際位置走
                                    if (app.packageName == lastDockPkg) labelX = cx
                                }
                                .bgTarget(app.themeColor, isApp = true),
                            reveal = { tw.liuli.launcher.ui.components.rowReveal(dockReveal.value, i, stagger = 55f, fade = 280f) },
                        )
                        }
                    }
                }

                // 焦點 App 名稱：在焦點圖塊正下方
                // 單行、依實際文字寬度置中在焦點圖塊正下方；只有快超出畫面時才往內推（常用列很長、捲到邊緣也不會跑版）
                Box(
                    Modifier
                        .padding(top = 398.dp)   // 緊貼在放大後的磚下方（磚底 388dp＋放大約 3dp），技術宅儀表列（434dp）上方留得出空間
                        .fillMaxWidth()
                        .layout { measurable, constraints ->
                            val p = measurable.measure(constraints.copy(minWidth = 0))
                            val margin = 16.dp.roundToPx()
                            val maxX = (constraints.maxWidth - p.width - margin).coerceAtLeast(margin)
                            layout(constraints.maxWidth, p.height) {
                                p.placeRelativeWithLayer((labelX - p.width / 2f).roundToInt().coerceIn(margin, maxX), 0)
                            }
                        }
                        .graphicsLayer { alpha = if (dockHasFocus) 1f else 0f },
                ) {
                    GText(
                        if (movingPkg != null) "‹  左右移動  ›　確定放下・返回取消" else dockLabel,
                        size = 16.sp, weight = FontWeight.Medium, shadow = true, maxLines = 1,
                    )
                }

            }

            // 第 2 層：黑框（原生解析度，不受背景降解析度影響）
            FrameOverlay(settings.frameThickness, settings.frameRadius, settings.frameFeather)

            // 第 3 層：固定介面（時鐘、天氣、右上按鈕、抽屜提示）——在黑框之上，不會被遮住
            if (!tw.liuli.launcher.ui.bg.PerfProbe.noHome) Box(recedeLayer) {
                TodayGroup(home, todayReq, openCalendar, Modifier.padding(start = 28.dp, top = 22.dp).enter(0))
                WeatherGroup(home, weatherReq, openWeather, Modifier.align(Alignment.TopEnd).padding(end = 96.dp, top = 30.dp).enter(1))
                // 右上角：網路狀態 + 設定，直向疊放
                Column(
                    Modifier.align(Alignment.TopEnd).padding(end = 28.dp, top = 34.dp).enter(2),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    NetworkButton(home.network, null, { SystemActions.networkSettings(context) })
                    GearButton(gearReq, ::openSettings)
                    if (settings.geekMode) RocketButton(null, onLaunch = {
                        rocketResult = tw.liuli.launcher.geek.Rocket.launch(context)
                        delay(3000)
                        rocketResult = null
                    })
                }

                // 技術宅模式：常用列下方的系統儀表列（不拿焦點，只是看的）
                if (settings.geekMode) GeekDashboardLive(geekStats, Modifier.padding(start = 48.dp, end = 48.dp, top = 434.dp).enter(6))

                // 抽屜提示
                Column(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = if (settings.geekMode) 6.dp else 16.dp)
                        .graphicsLayer { alpha = if (dockHasFocus && !drawerOpen) 1f else 0f }
                        // 滑鼠／飛鼠：點這個提示打開所有 App（看得到的時候才有作用）
                        .pointerInput(Unit) {
                            detectTapGestures { if (dockHasFocus && !drawerOpen && sheet == null) drawerOpen = true }
                        },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    LIcon(LIcons.ChevronUp, 18.dp, Ink.Tertiary)
                    GText("所有 App", size = 13.sp, color = Ink.Tertiary)
                }
            }

            // 抽屜、面板、色盤打開時：游標經過空白處不會穿到後面的主畫面、把焦點帶過去
            if (drawerOpen || sheet != null || picking) Box(Modifier.fillMaxSize().blockPointer())

            // 抽屜上方的精簡資訊列（抽屜打開時才出現）
            // 抽屜上方的資訊列跟抽屜一起預先組合好（平常透明度 0），打開的那一格不用才組合、排版那幾段文字
            if (drawerOpen || drawerBarShown || drawerReady) DrawerStatusBar(home, { drawerHide.value })

            // 抽屜預先建好（主畫面穩定 2.5 秒後，或第一次打開時），之後打開只剩滑入動畫
            if (drawerReady || drawerOpen) {
                AppDrawer(
                    open = drawerOpen,
                    slideRef = drawerSlide,
                    apps = visibleApps,
                    onClose = ::closeDrawer,
                    onLaunch = ::launchApp,
                    onLongClick = { app, fr -> appSheet(app, fr, fromDrawer = true) },
                    systemApps = systemApps,
                    systemLoading = systemLoading,
                    onToggleSystem = {
                        if (systemApps != null) systemApps = null
                        else if (!systemLoading) scope.launch {
                            systemLoading = true
                            val list = runCatching { AppRepository.loadSystem(context, apps.map { it.packageName }.toSet()) }.getOrDefault(emptyList())
                            systemLoading = false
                            if (drawerOpen) { systemApps = list; if (list.isEmpty()) toast("沒有其他系統應用") }
                        }
                    },
                    // 系統 App 不在一般清單裡（重開就不見），所以不提供加入常用／隱藏，只有開啟與應用程式資訊
                    onSystemLongClick = { app, fr ->
                        open(SheetSpec(app.label, "系統應用", listOf(
                            SheetItem("開啟") { closeAll(); launchApp(app) },
                            SheetItem("應用程式資訊", "強制停止、清除快取") { closeAll(); SystemActions.appInfo(context, app.packageName) },
                        )), fr)
                    },
                )
            }

            // 簡潔模式：左下角時間、右下角天氣（主畫面收起一半後才浮上來）
            if (minimalT.value > 0.001f) {
                MinimalOverlay(home, drift = settings.minimalDrift) { ((minimalT.value - .35f) / .65f).coerceIn(0f, 1f) }
                // 微光：整個畫面（背景、時間、天氣）一起壓暗
                if (settings.minimalDim > 0) {
                    Box(Modifier.fillMaxSize().drawBehind {
                        drawRect(Color.Black, alpha = MINIMAL_DIM_SCRIM[settings.minimalDim.coerceIn(0, 3)] * minimalT.value)
                    })
                }
            }
            // 清理記憶體的結果（火箭、或進入簡潔模式時自動清理）：畫面正中央 3 秒，在所有介面之上（簡潔模式收起的介面也擋不到它）
            RocketReport(rocketResult)
            if (picking) {
                ColorPickerPanel(
                    hue = settings.bgHue, sat = settings.bgSat,
                    onChange = { h, s -> settings.updateBgColor(h, s); engine.applyFixedColor(h, s) },
                    onDone = { picking = false; pendingFocus = gearReq },
                    onCancel = {
                        pickerOrigin?.let { (h, s) -> settings.updateBgColor(h, s); engine.applyFixedColor(h, s) }
                        picking = false; pendingFocus = gearReq
                    },
                )
            }
            // 開著的面板；關閉後同一個面板（同一個 key，不重新組合）切到收起動畫，滑出後才拿掉
            (sheet?.spec ?: exitingSheet)?.let { s ->
                key(s) {
                    GlassSheet(
                        s, exiting = sheet == null,
                        onExited = { if (exitingSheet === s) exitingSheet = null },
                        onFocusIndex = { sheetFocus[0] = it },
                        slideRef = sheetSlide,
                    )
                }
            }
        }
    }
}


/** 移動常用 App 時，讓被抓住的圖塊保持在可見範圍內（留一點邊距給放大效果）。 */
private suspend fun keepDockItemVisible(state: androidx.compose.foundation.lazy.LazyListState, index: Int, context: Context) {
    val margin = 40f * context.resources.displayMetrics.density
    val info = state.layoutInfo
    val item = info.visibleItemsInfo.firstOrNull { it.index == index }
    if (item == null) {
        state.animateScrollToItem(index)
        return
    }
    val start = info.viewportStartOffset + margin
    val end = info.viewportEndOffset - margin
    when {
        item.offset < start -> state.animateScrollBy(item.offset - start)
        item.offset + item.size > end -> state.animateScrollBy(item.offset + item.size - end)
    }
}

/** 面板／抽屜滑動動畫的長度（背景在這段時間暫停）：彈簧動畫約 0.4 秒、主畫面淡出 0.38 秒。 */
private const val UI_SLIDE_HOLD_MS = 700L

/** 微光等級的名稱。 */
private val MINIMAL_DIM_LABELS = listOf("關", "輕", "中", "深")

/** 微光：疊在整個畫面上的黑色透明度，與請系統調整的螢幕亮度（電視不支援就只有畫面變暗）。 */
private val MINIMAL_DIM_SCRIM = floatArrayOf(0f, .25f, .45f, .65f)
private val MINIMAL_DIM_BRIGHTNESS = floatArrayOf(-1f, .7f, .45f, .25f)

/** 閒置自動簡潔模式的選項（秒；0＝不自動）。 */
private val MINIMAL_IDLE_OPTIONS = listOf(0, 15, 30, 60, 120, 300)

private fun minimalIdleText(sec: Int) = when {
    sec <= 0 -> "關"
    sec < 60 -> "閒置 $sec 秒"
    else -> "閒置 ${sec / 60} 分鐘"
}

/** 冷啟動出場的總時間（毫秒）：7 個順位、每個間隔 90ms、淡入 340ms。 */
private const val ENTRANCE_MS = 90f * 7 + 340f

/** 常用列第一次出現的總時間（毫秒）：每個間隔 55ms、淡入 280ms。 */
private const val DOCK_REVEAL_MS = 55f * 12 + 280f
