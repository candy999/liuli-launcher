package tw.liuli.launcher.ui.home

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.ExperimentalFoundationApi
import tw.liuli.launcher.ui.components.KeepVisibleSpec
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
import tw.liuli.launcher.ui.components.AppTile
import tw.liuli.launcher.ui.components.GlassSheet
import tw.liuli.launcher.ui.components.SheetItem
import tw.liuli.launcher.ui.components.SheetSpec
import tw.liuli.launcher.ui.glass.WallpaperPalette
import tw.liuli.launcher.ui.glass.liquidGlass
import tw.liuli.launcher.ui.theme.Dimens
import tw.liuli.launcher.ui.theme.GText
import tw.liuli.launcher.ui.theme.Ink

/** 側邊面板關閉後要把焦點還給誰。 */
private data class OpenSheet(val spec: SheetSpec, val returnTo: FocusRequester?)

private const val GRID_COLUMNS = 5

@OptIn(ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    home: HomeContext,
    settings: LauncherSettings,
    homePresses: Int,
    externalAction: Pair<Int, String?>,
    /** false＝子頁面（月曆、天氣）疊在上面：主畫面隱藏、不接收焦點與返回鍵。 */
    active: Boolean,
    onOpenCalendar: () -> Unit,
    onOpenWeather: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val list = rememberLazyListState()
    val keepVisible = remember { KeepVisibleSpec() }
    val dockStore = remember { DockStore(context) }
    val hiddenStore = remember { HiddenAppsStore(context) }

    var apps by remember { mutableStateOf<List<AppEntry>>(emptyList()) }
    var dock by remember { mutableStateOf<List<String>>(emptyList()) }
    var hidden by remember { mutableStateOf(hiddenStore.load()) }
    var reloadKey by remember { mutableIntStateOf(0) }
    var sheet by remember { mutableStateOf<OpenSheet?>(null) }
    var pendingFocus by remember { mutableStateOf<FocusRequester?>(null) }
    var dockLabel by remember { mutableStateOf("") }

    val firstDock = remember { FocusRequester() }
    val inputsButton = remember { FocusRequester() }
    val settingsButton = remember { FocusRequester() }
    val calendarCard = remember { FocusRequester() }
    val weatherCard = remember { FocusRequester() }
    var returnFromPage by remember { mutableStateOf<FocusRequester?>(null) }

    // 從子頁面回來時，焦點回到打開它的卡片
    LaunchedEffect(active) {
        val target = returnFromPage
        if (active && target != null) {
            delay(16)
            runCatching { target.requestFocus() }
            returnFromPage = null
        }
    }
    val openCalendar = { returnFromPage = calendarCard; onOpenCalendar() }
    val openWeather = { returnFromPage = weatherCard; onOpenWeather() }

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

    // ── 焦點：開機與按 Home 都回到常用列第一個 ──
    var initialFocusDone by remember { mutableStateOf(false) }
    LaunchedEffect(apps, dock) {
        if (!initialFocusDone && dock.isNotEmpty() && apps.isNotEmpty()) {
            delay(32)
            if (runCatching { firstDock.requestFocus() }.isSuccess) initialFocusDone = true
        }
    }
    LaunchedEffect(homePresses) {
        if (homePresses == 0) return@LaunchedEffect
        sheet = null
        list.animateScrollToItem(0)
        runCatching { firstDock.requestFocus() }
    }
    LaunchedEffect(sheet, pendingFocus) {
        val target = pendingFocus
        if (sheet == null && target != null) {
            delay(16)
            runCatching { target.requestFocus() }
            pendingFocus = null
        }
    }

    fun close() {
        pendingFocus = sheet?.returnTo
        sheet = null
        KeyRemapService.learner = null
    }

    fun open(spec: SheetSpec, returnTo: FocusRequester?) {
        sheet = OpenSheet(spec, returnTo ?: sheet?.returnTo)
    }

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_LONG).show()

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
                add(SheetItem("從常用列移除") { setDock(dock - app.packageName); close() })
            } else {
                add(SheetItem("加入常用列", if (dock.size >= DockStore.MAX) "已滿" else null) {
                    if (dock.size < DockStore.MAX) setDock(dock + app.packageName) else toast("常用列已滿，請先移除一個")
                    close()
                })
            }
            add(SheetItem("從主畫面隱藏") { setHidden(app.packageName, true); close() })
            add(SheetItem("應用程式資訊", "強制停止、清除快取") { close(); SystemActions.appInfo(context, app.packageName) })
            add(SheetItem("解除安裝") { close(); SystemActions.uninstall(context, app.packageName) })
        }
        open(SheetSpec(app.label, if (app.isTvApp) null else "手機版 App：可能需要滑鼠或觸控操作", items), returnTo)
    }

    // ───────────── 訊號源 ─────────────
    fun inputsSheet() {
        val sources = TvSources.list(context)
        val items = if (sources.isEmpty()) {
            listOf(SheetItem("此裝置沒有可切換的訊號源", "電視盒通常沒有") { close() })
        } else {
            sources.map { src ->
                SheetItem(src.label, src.typeTag) {
                    close()
                    if (!TvSources.open(context, src)) toast("無法切換到 ${src.label}")
                }
            }
        }
        open(SheetSpec("訊號源", "選擇要切換的輸入", items), inputsButton)
    }
    LaunchedEffect(externalAction) {
        if (externalAction.first > 0 && externalAction.second == MainActivity.ACTION_INPUTS) inputsSheet()
    }

    // ───────────── 設定 ─────────────
    fun paletteSheet() {
        val current = settings.palette
        open(SheetSpec("桌布", "所有配色都是同色系；「依時間自動」白天晴空、傍晚暮光、晚上深海。",
            WallpaperPalette.choices.map { (id, label) ->
                SheetItem(label, selected = id == current) { settings.updatePalette(id); close() }
            },
            focusIndex = WallpaperPalette.choices.indexOfFirst { it.first == current }.coerceAtLeast(0),
        ), settingsButton)
    }

    fun citySheet() {
        val idx = Cities.all.indexOfFirst { it.id == settings.cityId }.coerceAtLeast(0)
        open(SheetSpec("地區", "天氣使用縣市中心的大約位置，不讀取裝置定位。",
            Cities.all.map { city ->
                SheetItem(city.name, selected = city.id == settings.cityId) { settings.updateCity(city.id); close() }
            }, focusIndex = idx,
        ), settingsButton)
    }

    fun visibilitySheetImpl(focusIndex: Int) {
        open(SheetSpec("顯示／隱藏 App", "原廠主畫面不列出手機版 App，這裡可以自己決定。",
            apps.mapIndexed { i, app ->
                val isHidden = app.packageName in hidden
                SheetItem(app.label, (if (app.isTvApp) "" else "手機版・") + if (isHidden) "隱藏" else "顯示", selected = !isHidden) {
                    setHidden(app.packageName, !isHidden)
                    visibilitySheetImpl(i)
                }
            }, focusIndex = focusIndex,
        ), settingsButton)
    }

    fun chooseKeyAction(code: Int, long: Boolean) {
        val label = KeyMapStore.keyName(code) + if (long) "・長按" else "・短按"
        fun save(action: KeyAction) {
            KeyMapStore.put(context, KeyBinding(code, long, action))
            KeyRemapService.instance?.reload()
            close()
            toast("$label → ${action.label}")
        }
        val items = KeyAction.fixed.map { a -> SheetItem(a.label) { save(a) } } +
            SheetItem("開啟指定 App…") {
                open(SheetSpec("$label：開啟哪個 App", null, apps.map { app ->
                    SheetItem(app.label, if (app.isTvApp) null else "手機版") { save(KeyAction.OpenApp(app.packageName, app.label)) }
                }), settingsButton)
            }
        open(SheetSpec(label, "只設長按時，除了返回與 Home 鍵，短按會失去原本功能。", items), settingsButton)
    }

    fun learnKey() {
        open(SheetSpec("按下要設定的鍵", "15 秒內按下遙控器上任何一顆鍵，包括 Netflix、YouTube 等快捷鍵。",
            listOf(SheetItem("取消") { close() })), settingsButton)
        KeyRemapService.learner = { code ->
            open(SheetSpec(KeyMapStore.keyName(code), "要設定短按還是長按？", listOf(
                SheetItem("短按") { chooseKeyAction(code, false) },
                SheetItem("長按（按住約半秒）") { chooseKeyAction(code, true) },
            )), settingsButton)
        }
        scope.launch {
            delay(15_000)
            if (KeyRemapService.learner != null) {
                KeyRemapService.learner = null
                if (sheet?.spec?.title == "按下要設定的鍵") close()
            }
        }
    }

    fun keysSheet() {
        val enabled = KeyMapStore.isServiceEnabled(context)
        val items = buildList {
            if (!enabled) add(SheetItem("啟用按鍵服務", "無障礙設定") {
                close()
                if (!SystemActions.launch(context, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))) toast("找不到無障礙設定，請用 ADB 啟用")
            })
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
        open(SheetSpec("遙控器按鍵", status, items), settingsButton)
    }

    fun openSettings() {
        val paletteName = WallpaperPalette.choices.firstOrNull { it.first == settings.palette }?.second ?: "依時間自動"
        open(SheetSpec(
            "設定", "琉璃 Launcher ${BuildConfigInfo.versionName(context)}",
            listOf(
                SheetItem("桌布", paletteName) { paletteSheet() },
                SheetItem("地區", Cities.byId(settings.cityId).name) { citySheet() },
                SheetItem("遙控器按鍵", "改綁、防誤入原廠主畫面") { keysSheet() },
                SheetItem("顯示／隱藏 App", if (hidden.isEmpty()) null else "已隱藏 ${hidden.size} 個") { visibilitySheetImpl(0) },
                SheetItem("系統設定") { close(); SystemActions.systemSettings(context) },
                SheetItem("網路") { close(); SystemActions.networkSettings(context) },
                SheetItem("預設主畫面") { close(); SystemActions.homeSettings(context) },
                SheetItem("重新整理 App 清單") { close(); reloadKey++ },
            ),
        ), settingsButton)
    }

    BackHandler(enabled = active) {
        if (sheet != null) close()
        else scope.launch { list.animateScrollToItem(0) }
    }

    val visibleApps = remember(apps, hidden) { apps.filter { it.packageName !in hidden } }
    val dockApps = remember(apps, dock) { dock.mapNotNull { pkg -> apps.firstOrNull { it.packageName == pkg } } }
    val rows = remember(visibleApps) { visibleApps.chunked(GRID_COLUMNS) }
    val sheetOpen = sheet != null

    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = if (active) 1f else 0f }
            .focusProperties { enter = { if (active) FocusRequester.Default else FocusRequester.Cancel } }
            .focusGroup(),
    ) {
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    // 面板打開時主畫面只做一次靜態模糊（Android 12+），面板滑動時不重算
                    renderEffect = if (sheetOpen && Build.VERSION.SDK_INT >= 31) {
                        BlurEffect(14.dp.toPx(), 14.dp.toPx(), TileMode.Decal)
                    } else null
                    alpha = if (sheetOpen) 0.85f else 1f
                },
        ) {
            val contentWidth = maxWidth - Dimens.SafeH * 2
            val gap = 20.dp
            val tileWidth = (contentWidth - gap * (GRID_COLUMNS - 1)) / GRID_COLUMNS

            // 焦點在頂列、小工具、常用列時，整頁固定在最上面（時鐘一直看得到）
            val pinTop = Modifier.onFocusChanged {
                if (it.hasFocus && (list.firstVisibleItemIndex != 0 || list.firstVisibleItemScrollOffset != 0)) {
                    scope.launch { list.animateScrollToItem(0) }
                }
            }
            val dockTileWidth = ((contentWidth + Dimens.SafeH * 2 - 72.dp - 44.dp - 18.dp * 4) / 5).coerceAtMost(168.dp)

            CompositionLocalProvider(LocalBringIntoViewSpec provides keepVisible) {
            LazyColumn(
                state = list,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = Dimens.SafeV, bottom = 48.dp),
            ) {
                item(key = "top") {
                    Box(pinTop) {
                    TopBar(
                        home = home,
                        inputsButton = inputsButton,
                        settingsButton = settingsButton,
                        onWeather = openWeather,
                        onInputs = ::inputsSheet,
                        onSettings = ::openSettings,
                    )
                    }
                }
                item(key = "widgets") {
                    Column(pinTop) {
                        Spacer(Modifier.height(26.dp))
                        Widgets(home, calendarCard, weatherCard, openCalendar, openWeather)
                    }
                }
                item(key = "dock") {
                    Spacer(Modifier.height(24.dp))
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().then(pinTop)) {
                        LazyRow(
                            modifier = Modifier
                                .padding(horizontal = 36.dp)
                                .fillMaxWidth()
                                .liquidGlass(RoundedCornerShape(Dimens.ShelfRadius))
                                .focusRestorer(),
                            contentPadding = PaddingValues(horizontal = 22.dp, vertical = 18.dp),
                            horizontalArrangement = Arrangement.spacedBy(18.dp),
                        ) {
                            if (dockApps.isEmpty()) {
                                item {
                                    GText(
                                        if (apps.isEmpty()) "載入中…" else "在下方的 App 上長按確定鍵，就能加入常用列",
                                        Modifier.padding(vertical = 30.dp, horizontal = 12.dp),
                                        color = Ink.Secondary,
                                    )
                                }
                            }
                            itemsIndexed(dockApps, key = { _, a -> a.packageName }) { i, app ->
                                AppTile(
                                    app = app,
                                    width = dockTileWidth,
                                    showLabel = false,
                                    requester = if (i == 0) firstDock else null,
                                    onFocused = { dockLabel = app.label },
                                    onClick = { launchApp(app) },
                                    onLongClick = { fr -> appSheet(app, fr) },
                                )
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        GText(dockLabel, size = 15.sp, weight = FontWeight.Medium, shadow = true)
                    }
                }
                item(key = "all-title") {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(start = Dimens.SafeH + 8.dp, end = Dimens.SafeH, top = 22.dp, bottom = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        GText("所有應用程式", Modifier.weight(1f), size = 16.sp, weight = FontWeight.Medium, color = Ink.Secondary, shadow = true)
                        GText("長按確定鍵：更多選項", size = 13.sp, color = Ink.Tertiary)
                    }
                }
                items(rows.size, key = { "row-" + rows[it].first().packageName }) { r ->
                    Row(
                        Modifier
                            .padding(horizontal = Dimens.SafeH)
                            .padding(bottom = 22.dp),
                        horizontalArrangement = Arrangement.spacedBy(gap),
                    ) {
                        for (app in rows[r]) {
                            key(app.packageName) {
                                AppTile(
                                    app = app,
                                    width = tileWidth,
                                    showLabel = true,
                                    onClick = { launchApp(app) },
                                    onLongClick = { fr -> appSheet(app, fr) },
                                )
                            }
                        }
                    }
                }
            }
            }
        }

        if (sheetOpen) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.18f)))
        }
        sheet?.let { s -> key(s.spec) { GlassSheet(s.spec) } }
    }
}
