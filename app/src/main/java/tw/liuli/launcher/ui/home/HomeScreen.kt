package tw.liuli.launcher.ui.home

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
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.boundsInRoot
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
import tw.liuli.launcher.context.Cities
import tw.liuli.launcher.context.HomeContext
import tw.liuli.launcher.data.AppEntry
import tw.liuli.launcher.data.AppRepository
import tw.liuli.launcher.data.DockStore
import tw.liuli.launcher.data.HiddenAppsStore
import tw.liuli.launcher.data.TvSources
import tw.liuli.launcher.keys.KeyAction
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
    onOpenCalendar: () -> Unit,
    onOpenWeather: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dockStore = remember { DockStore(context) }
    val hiddenStore = remember { HiddenAppsStore(context) }
    val keepVisible = remember { KeepVisibleSpec() }

    var apps by remember { mutableStateOf<List<AppEntry>>(emptyList()) }
    var dock by remember { mutableStateOf<List<String>>(emptyList()) }
    var hidden by remember { mutableStateOf(hiddenStore.load()) }
    var reloadKey by remember { mutableIntStateOf(0) }
    var sheet by remember { mutableStateOf<OpenSheet?>(null) }
    var drawerOpen by remember { mutableStateOf(false) }
    var pendingFocus by remember { mutableStateOf<FocusRequester?>(null) }
    var dockLabel by remember { mutableStateOf("") }
    var labelX by remember { mutableFloatStateOf(0f) }
    var dockHasFocus by remember { mutableStateOf(true) }

    val firstDock = remember { FocusRequester() }
    val firstInput = remember { FocusRequester() }
    val todayReq = remember { FocusRequester() }
    val weatherReq = remember { FocusRequester() }
    val gearReq = remember { FocusRequester() }
    val tileCenters = remember { HashMap<String, Float>() }
    // 常用列每個 App 的 FocusRequester：關抽屜、關面板時焦點回到上次停留的那一個
    val dockRequesters = remember { HashMap<String, FocusRequester>() }
    var lastDockPkg by remember { mutableStateOf<String?>(null) }

    // ── App 清單：安裝、移除、更新時自動重新載入 ──
    LaunchedEffect(reloadKey) {
        val loaded = AppRepository.load(context)
        apps = loaded
        val installed = loaded.map { it.packageName }.toSet()
        dock = (dockStore.load() ?: DockStore.defaultFor(loaded)).filter { it in installed }
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
    val sources = remember(reloadKey) { TvSources.list(context) }

    // ── 焦點：預設在常用列第一個 App ──
    var initialFocusDone by remember { mutableStateOf(false) }
    LaunchedEffect(apps, dock) {
        if (!initialFocusDone && dock.isNotEmpty() && apps.isNotEmpty()) {
            delay(32)
            if (runCatching { firstDock.requestFocus() }.isSuccess) initialFocusDone = true
        }
    }
    LaunchedEffect(homePresses) {
        if (homePresses == 0) return@LaunchedEffect
        sheet = null; drawerOpen = false
        delay(16)
        runCatching { firstDock.requestFocus() }
    }
    LaunchedEffect(sheet, drawerOpen, pendingFocus) {
        // 面板關閉後還原焦點。即使抽屜還開著也要還原（長按開選單的 returnTo 是抽屜裡的圖塊）
        val target = pendingFocus
        if (sheet == null && target != null) {
            delay(16)
            runCatching { target.requestFocus() }
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

    fun close(cancel: Boolean = false) {
        if (cancel) sheet?.spec?.onCancel?.invoke()
        pendingFocus = sheet?.returnTo
        sheet = null
        KeyRemapService.learner = null
    }

    fun open(spec: SheetSpec, returnTo: FocusRequester?) {
        sheet = OpenSheet(spec, returnTo ?: sheet?.returnTo)
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
    fun appSheet(app: AppEntry, returnTo: FocusRequester) {
        val inDock = app.packageName in dock
        val idx = dock.indexOf(app.packageName)
        val items = buildList {
            add(SheetItem("開啟") { close(); launchApp(app) })
            if (inDock) {
                if (idx > 0) add(SheetItem("往左移") {
                    setDock(dock.toMutableList().apply { add(idx - 1, removeAt(idx)) }); close()
                })
                if (idx < dock.lastIndex) add(SheetItem("往右移") {
                    setDock(dock.toMutableList().apply { add(idx + 1, removeAt(idx)) }); close()
                })
                add(SheetItem("從常用移除") { setDock(dock - app.packageName); close() })
            } else {
                add(SheetItem("加入常用", if (dock.size >= DockStore.MAX) "已滿" else null) {
                    if (dock.size < DockStore.MAX) setDock(dock + app.packageName) else toast("常用已滿，請先移除一個")
                    close()
                })
            }
            add(SheetItem("從主畫面隱藏") { setHidden(app.packageName, true); close() })
            add(SheetItem("應用程式資訊", "強制停止、清除快取") { close(); SystemActions.appInfo(context, app.packageName) })
            add(SheetItem("解除安裝") { close(); SystemActions.uninstall(context, app.packageName) })
        }
        open(SheetSpec(app.label, if (app.isTvApp) null else "手機版 App：可能需要滑鼠或觸控操作", items), returnTo)
    }

    // 按鍵服務要求「訊號源」：焦點移到訊號源列
    LaunchedEffect(externalAction) {
        if (externalAction.first > 0 && externalAction.second == MainActivity.ACTION_INPUTS) {
            sheet = null; drawerOpen = false
            delay(32)
            runCatching { firstInput.requestFocus() }
        }
    }

    // ───────────── 設定 ─────────────
    fun styleSheet() {
        val before = engine.style
        open(SheetSpec(
            "背景樣式", "上下移動即時預覽；確定套用，返回取消。",
            BgStyle.entries.map { st ->
                SheetItem(st.label, subtitle = st.note, selected = st == before, onFocus = { engine.applyStyle(st) }) {
                    settings.updateBgStyle(st.id); engine.applyStyle(st); close()
                }
            },
            focusIndex = before.ordinal,
            onCancel = { engine.applyStyle(before) },
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

    fun chooseKeyAction(code: Int, long: Boolean) {
        val label = KeyMapStore.keyName(code) + if (long) "・長按" else "・短按"
        fun save(action: KeyAction) {
            if (KeyRemapService.isProtected(code)) { close(); toast("這是基本鍵，不能改綁"); return }
            KeyMapStore.put(context, KeyBinding(code, long, action))
            KeyRemapService.instance?.reload()
            close()
            toast("$label → ${action.label}")
        }
        val items = KeyAction.fixed.map { a -> SheetItem(a.label) { save(a) } } +
            SheetItem("開啟指定 App…") {
                open(SheetSpec("$label：開啟哪個 App", null, apps.map { app ->
                    SheetItem(app.label, if (app.isTvApp) null else "手機版") { save(KeyAction.OpenApp(app.packageName, app.label)) }
                }), gearReq)
            }
        open(SheetSpec(label, "只設長按時，除了返回與 Home 鍵，短按會失去原本功能。", items), gearReq)
    }

    fun learnKey() {
        if (KeyRemapService.instance == null) {
            toast("按鍵服務尚未啟動，請稍候幾秒再試")
            return
        }
        open(SheetSpec(
            "按下要改綁的鍵",
            "15 秒內按一下遙控器上要改綁的鍵，例如 Netflix、YouTube 或彩色鍵。方向鍵、確定、返回、音量、電源等基本鍵受保護，不能改綁（按了會用來取消）。",
            listOf(SheetItem("取消") { close() }),
        ), gearReq)
        KeyRemapService.learner = { code ->
            if (KeyRemapService.isProtected(code)) {
                toast("${KeyMapStore.keyName(code)} 是基本鍵，受保護不能改綁")
            } else {
                open(SheetSpec(KeyMapStore.keyName(code), "要設定短按還是長按？", listOf(
                    SheetItem("短按") { chooseKeyAction(code, false) },
                    SheetItem("長按（按住約半秒）") { chooseKeyAction(code, true) },
                )), gearReq)
            }
        }
    }
    LaunchedEffect(sheet?.spec?.title) {
        if (sheet?.spec?.title == "按下要改綁的鍵") {
            delay(15_000)
            if (KeyRemapService.learner != null && sheet?.spec?.title == "按下要改綁的鍵") close()
        }
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
                    if (!SystemActions.launch(context, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))) toast("找不到無障礙設定")
                },
            ),
        ), gearReq)
    }

    fun keysSheet() {
        val enabled = KeyMapStore.isServiceEnabled(context)
        val items = buildList {
            if (!enabled) add(SheetItem("啟用按鍵服務", "自我授權，不需電腦") { selfGrant() })
            add(SheetItem("新增按鍵對應", if (enabled) null else "需先啟用") {
                if (enabled) learnKey() else toast("請先啟用按鍵服務")
            })
            add(SheetItem("防止回到原廠主畫面", if (KeyMapStore.guardHome(context)) "開" else "關") {
                KeyMapStore.setGuardHome(context, !KeyMapStore.guardHome(context)); close()
            })
            KeyMapStore.load(context).forEach { r ->
                add(SheetItem(KeyMapStore.keyName(r.keyCode) + if (r.long) "・長按" else "・短按", r.action.label + "・刪除") {
                    KeyMapStore.remove(context, r.keyCode, r.long)
                    KeyRemapService.instance?.reload()
                    close()
                    toast("已刪除")
                })
            }
        }
        val status = if (enabled) "服務已啟用。" else "服務未啟用。若無障礙選單找不到，可用電腦執行：${KeyMapStore.adbEnableCommand(context)}"
        open(SheetSpec("遙控器按鍵", status, items), gearReq)
    }

    fun openSettings() {
        open(SheetSpec(
            "設定", "琉璃 Launcher ${BuildConfigInfo.versionName(context)}",
            listOf(
                SheetItem("地點", Cities.byId(settings.cityId).name) { citySheet() },
                SheetItem("背景樣式", engine.style.label) { styleSheet() },
                SheetItem("背景色系", if (engine.follow) "跟隨焦點" else "固定深藍") {
                    val on = !engine.follow
                    settings.updateBgFollow(on); engine.applyFollow(on); close()
                    toast("背景色系：" + if (on) "跟隨焦點" else "固定深藍")
                },
                SheetItem("顯示／隱藏 App", if (hidden.isEmpty()) null else "已隱藏 ${hidden.size} 個") { visibilitySheet(0) },
                SheetItem("遙控器按鍵", "改綁、防誤入原廠主畫面") { keysSheet() },
                SheetItem("系統設定") { close(); SystemActions.systemSettings(context) },
                SheetItem("網路") { close(); SystemActions.networkSettings(context) },
                SheetItem("預設主畫面") { close(); SystemActions.homeSettings(context) },
                SheetItem("重新整理 App 清單") { close(); reloadKey++ },
            ),
        ), gearReq)
    }

    // 返回鍵：面板 → 抽屜 → 回到第一個 App（主畫面不會離開）
    BackHandler(enabled = active) {
        when {
            sheet != null -> close(cancel = true)
            drawerOpen -> closeDrawer()
            else -> runCatching { firstDock.requestFocus() }
        }
    }

    val visibleApps = remember(apps, hidden) { apps.filter { it.packageName !in hidden } }
    val dockApps = remember(apps, dock) { dock.mapNotNull { pkg -> apps.firstOrNull { it.packageName == pkg } } }
    val recede = animateFloatAsState(if (drawerOpen || sheet != null) 1f else 0f, tween(380), label = "recede")

    CompositionLocalProvider(LocalBringIntoViewSpec provides keepVisible) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = if (active) 1f else 0f }
                .focusProperties { enter = { if (active) FocusRequester.Default else FocusRequester.Cancel } }
                .focusGroup(),
        ) {
            // ── 主畫面內容：抽屜或面板開啟時往後退 ──
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val k = recede.value
                        alpha = 1f - 0.4f * k
                        scaleX = 1f - 0.045f * k
                        scaleY = 1f - 0.045f * k
                    },
            ) {
                TodayGroup(home, todayReq, openCalendar, Modifier.padding(start = 28.dp, top = 22.dp))
                WeatherGroup(home, weatherReq, openWeather, Modifier.align(Alignment.TopEnd).padding(end = 96.dp, top = 30.dp))
                // 右上角：網路狀態 + 設定，直向疊放
                Column(
                    Modifier.align(Alignment.TopEnd).padding(end = 28.dp, top = 34.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    NetworkButton(home.network, null, { SystemActions.networkSettings(context) })
                    GearButton(gearReq, ::openSettings)
                }

                if (sources.isNotEmpty()) {
                    InputsRow(
                        sources = sources,
                        firstRequester = firstInput,
                        onSwitch = { src -> if (!TvSources.open(context, src)) toast("無法切換到 ${src.label}") },
                        modifier = Modifier.padding(start = 48.dp, top = 232.dp),
                    )
                }

                // 常用 App：直接放在桌布上，不加框；按下鍵展開所有 App
                LazyRow(
                    modifier = Modifier
                        .padding(top = 290.dp)
                        .fillMaxWidth()
                        .height(116.dp)
                        .onFocusChanged { dockHasFocus = it.hasFocus }
                        .onPreviewKeyEvent { e ->
                            if (e.type == KeyEventType.KeyDown && e.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                                drawerOpen = true
                                true
                            } else false
                        }
                        .focusRow(enterTo = { dockReturnTarget() }),
                    contentPadding = PaddingValues(start = 48.dp, end = 48.dp, top = 14.dp, bottom = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(22.dp),
                ) {
                    if (dockApps.isEmpty()) {
                        item {
                            GText(
                                if (apps.isEmpty()) "載入中…" else "按下鍵打開所有 App，長按確定鍵即可加入常用",
                                Modifier.padding(vertical = 30.dp),
                                color = Ink.Secondary,
                                shadow = true,
                            )
                        }
                    }
                    itemsIndexed(dockApps, key = { _, a -> a.packageName }) { i, app ->
                        val req = if (i == 0) firstDock else dockRequesters.getOrPut(app.packageName) { FocusRequester() }
                        AppTile(
                            app = app,
                            width = 150.dp,
                            showLabel = false,
                            requester = req,
                            onFocused = {
                                lastDockPkg = app.packageName
                                dockLabel = app.label
                                labelX = tileCenters[app.packageName] ?: 0f
                            },
                            onClick = { launchApp(app) },
                            onLongClick = { fr -> appSheet(app, fr) },
                            extra = Modifier
                                .onGloballyPositioned { tileCenters[app.packageName] = it.boundsInRoot().center.x }
                                .bgTarget(app.themeColor),
                        )
                    }
                }

                // 焦點 App 名稱：在焦點圖塊正下方
                Box(
                    Modifier
                        .padding(top = 412.dp)
                        .width(300.dp)
                        .offset { IntOffset((labelX - 150.dp.toPx()).roundToInt(), 0) }
                        .graphicsLayer { alpha = if (dockHasFocus) 1f else 0f },
                ) {
                    GText(dockLabel, Modifier.fillMaxWidth(), size = 16.sp, weight = FontWeight.Medium, shadow = true, align = TextAlign.Center)
                }

                // 抽屜提示
                Column(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 16.dp)
                        .graphicsLayer { alpha = if (dockHasFocus && !drawerOpen) 1f else 0f },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    LIcon(LIcons.ChevronUp, 18.dp, Ink.Tertiary)
                    GText("所有 App", size = 13.sp, color = Ink.Tertiary)
                }
            }

            if (drawerOpen) {
                AppDrawer(
                    apps = visibleApps,
                    sheetOpen = sheet != null,
                    onClose = ::closeDrawer,
                    onLaunch = ::launchApp,
                    onLongClick = { app, fr -> appSheet(app, fr) },
                )
            }

            if (sheet != null) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.22f)))
            }
            sheet?.let { s -> key(s.spec) { GlassSheet(s.spec) } }
        }
    }
}
