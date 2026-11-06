package tw.liuli.launcher.ui.home

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
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
import tw.liuli.launcher.system.SystemActions
import tw.liuli.launcher.ui.glass.FocusScale
import tw.liuli.launcher.ui.glass.GText
import tw.liuli.launcher.ui.glass.GlassInput
import tw.liuli.launcher.ui.glass.GlassMenu
import tw.liuli.launcher.ui.glass.GlassPill
import tw.liuli.launcher.ui.glass.GlassSurface
import tw.liuli.launcher.ui.glass.LocalBackdrop
import tw.liuli.launcher.ui.glass.MenuAction
import tw.liuli.launcher.wallpaper.LauncherSettings
import tw.liuli.launcher.wallpaper.WallpaperPacks
import tw.liuli.launcher.wallpaper.WallpaperSource
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 目前開著的浮層。 */
private sealed interface Overlay {
    val returnFocus: FocusRequester?
}

private data class MenuOverlay(
    val title: String,
    val subtitle: String?,
    val actions: List<MenuAction>,
    override val returnFocus: FocusRequester?,
    val focusIndex: Int = 0,
) : Overlay

private data class InputOverlay(
    val title: String,
    val hint: String,
    val initial: String,
    val onSubmit: (String) -> Unit,
    override val returnFocus: FocusRequester?,
) : Overlay

/** 內建網頁桌布的顯示名稱。 */
private val builtinTitles = mapOf(
    "liuli-sky" to "琉璃天空（依時間、天氣、農曆自動變化）",
    "rain-glass" to "玻璃雨滴",
    "aurora" to "光暈極光",
    "ocean" to "海浪",
)

@Composable
fun HomeScreen(
    homePresses: Int,
    externalAction: Pair<Int, String?>,
    home: HomeContext,
    settings: LauncherSettings,
    onPacksChanged: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scroll = rememberScrollState()
    val dockStore = remember { DockStore(context) }
    val hiddenStore = remember { HiddenAppsStore(context) }

    var apps by remember { mutableStateOf<List<AppEntry>>(emptyList()) }
    var dock by remember { mutableStateOf<List<String>>(emptyList()) }
    var hidden by remember { mutableStateOf(hiddenStore.load()) }
    var reloadKey by remember { mutableIntStateOf(0) }
    var overlay by remember { mutableStateOf<Overlay?>(null) }
    var pendingFocus by remember { mutableStateOf<FocusRequester?>(null) }

    val firstDockFocus = remember { FocusRequester() }
    val inputsButton = remember { FocusRequester() }
    val settingsButton = remember { FocusRequester() }

    // 載入 App；安裝、移除、更新時自動重新載入
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

    var initialFocusDone by remember { mutableStateOf(false) }
    LaunchedEffect(apps, dock) {
        if (!initialFocusDone && apps.isNotEmpty()) {
            delay(50)
            if (runCatching { firstDockFocus.requestFocus() }.isSuccess) initialFocusDone = true
        }
    }

    LaunchedEffect(homePresses) {
        if (homePresses == 0) return@LaunchedEffect
        overlay = null
        scroll.animateScrollTo(0)
        runCatching { firstDockFocus.requestFocus() }
    }

    LaunchedEffect(overlay, pendingFocus) {
        val target = pendingFocus
        if (overlay == null && target != null) {
            delay(30)
            runCatching { target.requestFocus() }
            pendingFocus = null
        }
    }

    fun closeOverlay() {
        pendingFocus = overlay?.returnFocus
        overlay = null
        KeyRemapService.learner = null
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

    fun menu(title: String, subtitle: String?, actions: List<MenuAction>, returnTo: FocusRequester?, focusIndex: Int = 0) {
        overlay = MenuOverlay(title, subtitle, actions, returnTo, focusIndex)
    }

    // ───────────── App 長按選單 ─────────────
    fun showAppMenu(app: AppEntry, returnTo: FocusRequester) {
        val inDock = app.packageName in dock
        val actions = buildList {
            add(MenuAction("開啟") { closeOverlay(); launchApp(app) })
            if (inDock) {
                val idx = dock.indexOf(app.packageName)
                if (idx > 0) add(MenuAction("在常用列往左移") {
                    setDock(dock.toMutableList().apply { add(idx - 1, removeAt(idx)) }); closeOverlay()
                })
                if (idx < dock.lastIndex) add(MenuAction("在常用列往右移") {
                    setDock(dock.toMutableList().apply { add(idx + 1, removeAt(idx)) }); closeOverlay()
                })
                add(MenuAction("從常用列移除") { setDock(dock - app.packageName); closeOverlay() })
            } else {
                add(MenuAction("加入常用列", if (dock.size >= DockStore.MAX) "已滿 ${DockStore.MAX} 個" else null) {
                    if (dock.size < DockStore.MAX) setDock(dock + app.packageName) else toast("常用列已滿，請先移除一個")
                    closeOverlay()
                })
            }
            add(MenuAction("從主畫面隱藏", "可在 設定 → 顯示／隱藏 App 找回") {
                setHidden(app.packageName, true); closeOverlay()
            })
            add(MenuAction("應用程式資訊", "強制停止、清除快取") {
                closeOverlay(); SystemActions.appInfo(context, app.packageName)
            })
            add(MenuAction("解除安裝") { closeOverlay(); SystemActions.uninstall(context, app.packageName) })
        }
        menu(app.label, app.packageName + if (app.isTvApp) "" else "・手機版", actions, returnTo)
    }

    // ───────────── 訊號源 ─────────────
    fun showInputs() {
        val sources = TvSources.list(context)
        val actions = if (sources.isEmpty()) {
            listOf(MenuAction("此裝置沒有可切換的訊號源", "電視盒通常沒有") { closeOverlay() })
        } else {
            sources.map { src ->
                MenuAction(src.label, src.typeTag) {
                    closeOverlay()
                    if (!TvSources.open(context, src)) toast("無法切換到 ${src.label}")
                }
            }
        }
        menu("訊號源", "選擇要切換的輸入", actions, inputsButton)
    }

    // 無障礙服務要求開啟訊號源選單（遙控器按鍵對應）
    LaunchedEffect(externalAction) {
        if (externalAction.first > 0 && externalAction.second == MainActivity.ACTION_INPUTS) showInputs()
    }

    // ───────────── 設定：顯示／隱藏 App ─────────────
    fun showVisibility(focusIndex: Int = 0) {
        val actions = apps.mapIndexed { i, app ->
            val isHidden = app.packageName in hidden
            MenuAction(
                (if (isHidden) "○ " else "● ") + app.label,
                (if (app.isTvApp) "" else "手機版・") + if (isHidden) "已隱藏" else "顯示中",
            ) {
                setHidden(app.packageName, !isHidden)
                showVisibility(i)
            }
        }
        menu("顯示／隱藏 App", "● 顯示　○ 隱藏。手機版 App 原廠主畫面不會列出，這裡可以自己決定。", actions, settingsButton, focusIndex)
    }

    // ───────────── 設定：桌布 ─────────────
    fun showWallpaper() {
        val current = settings.wallpaper
        fun mark(source: WallpaperSource) = if (current == source) "使用中" else null
        val actions = buildList {
            add(MenuAction("原生流動色塊", mark(WallpaperSource.Liquid) ?: "最省效能") {
                settings.updateWallpaper(WallpaperSource.Liquid); closeOverlay()
            })
            WallpaperPacks.builtinNames(context).forEach { name ->
                val src = WallpaperSource.Builtin(name)
                add(MenuAction(builtinTitles[name] ?: name, mark(src) ?: "內建") {
                    settings.updateWallpaper(src); closeOverlay()
                })
            }
            WallpaperPacks.installed(context).forEach { pack ->
                val src = WallpaperSource.Zip(pack.id)
                add(MenuAction(pack.title, mark(src) ?: "已匯入的 zip") {
                    settings.updateWallpaper(src); closeOverlay()
                })
            }
            add(MenuAction("輸入網頁網址…", (current as? WallpaperSource.Url)?.let { "使用中" }) {
                overlay = InputOverlay(
                    "網頁桌布網址",
                    "線上網頁，琉璃會自動在網址後面加上時間、天氣、農曆等參數。網址上自己寫的參數優先（可用來測試，例如 ?wx=rain）。",
                    (current as? WallpaperSource.Url)?.url ?: "https://",
                    { url ->
                        if (url.startsWith("http://") || url.startsWith("https://")) {
                            settings.updateWallpaper(WallpaperSource.Url(url)); closeOverlay()
                        } else toast("網址要以 http:// 或 https:// 開頭")
                    },
                    settingsButton,
                )
            })
            add(MenuAction("從網址下載 zip 離線包…") {
                overlay = InputOverlay(
                    "下載桌布 zip",
                    "zip 裡要有 index.html（放在根目錄或唯一的資料夾裡）。下載後離線也能用。",
                    "https://",
                    { url ->
                        closeOverlay()
                        toast("下載中…")
                        scope.launch {
                            WallpaperPacks.installFromUrl(context, url)
                                .onSuccess { id ->
                                    onPacksChanged()
                                    settings.updateWallpaper(WallpaperSource.Zip(id))
                                    toast("已安裝並套用桌布")
                                }
                                .onFailure { toast("安裝失敗：${it.message}") }
                        }
                    },
                    settingsButton,
                )
            })
            val drop = WallpaperPacks.dropDir(context)?.absolutePath ?: "（無法取得）"
            add(MenuAction("匯入資料夾裡的 zip", "用 ADB 或檔案管理員放到 $drop") {
                closeOverlay()
                scope.launch {
                    val n = WallpaperPacks.installDropped(context)
                    onPacksChanged()
                    toast(if (n > 0) "已匯入 $n 個桌布包" else "資料夾裡沒有 zip：$drop")
                }
            })
            val packs = WallpaperPacks.installed(context)
            if (packs.isNotEmpty()) add(MenuAction("移除已匯入的桌布包…") {
                menu("移除桌布包", null, packs.map { pack ->
                    MenuAction(pack.title, pack.id) {
                        if (current == WallpaperSource.Zip(pack.id)) settings.updateWallpaper(WallpaperSource.Liquid)
                        WallpaperPacks.remove(context, pack.id)
                        onPacksChanged()
                        closeOverlay()
                        toast("已移除 ${pack.title}")
                    }
                }, settingsButton)
            })
            add(MenuAction("網頁接收遙控器按鍵", if (settings.forwardKeys) "開" else "關") {
                settings.updateForwardKeys(!settings.forwardKeys); closeOverlay()
            })
        }
        menu("桌布", "網頁桌布會收到時間、天氣、農曆、節日等參數，自己決定要怎麼變化。", actions, settingsButton)
    }

    // ───────────── 設定：地區 ─────────────
    fun showCities() {
        val idx = Cities.all.indexOfFirst { it.id == settings.cityId }.coerceAtLeast(0)
        menu("地區（天氣用）", "只使用縣市中心的大約位置，不讀取裝置定位。", Cities.all.map { city ->
            MenuAction(city.name, if (city.id == settings.cityId) "使用中" else null) {
                settings.updateCity(city.id); closeOverlay()
            }
        }, settingsButton, idx)
    }

    // ───────────── 設定：遙控器按鍵 ─────────────
    fun chooseAction(code: Int, long: Boolean) {
        val label = KeyMapStore.keyName(code) + if (long) "（長按）" else "（短按）"
        fun save(action: KeyAction) {
            KeyMapStore.put(context, KeyBinding(code, long, action))
            KeyRemapService.instance?.reload()
            closeOverlay()
            toast("$label → ${action.label}")
        }
        val actions = KeyAction.fixed.map { a -> MenuAction(a.label) { save(a) } } +
            MenuAction("開啟指定 App…") {
                menu("$label 要開啟哪個 App", null, apps.map { app ->
                    MenuAction(app.label, if (app.isTvApp) null else "手機版") {
                        save(KeyAction.OpenApp(app.packageName, app.label))
                    }
                }, settingsButton)
            }
        menu("$label 要做什麼", "只設長按時，除了返回鍵與 Home 鍵，短按會失去原本功能。", actions, settingsButton)
    }

    fun startLearning() {
        menu("請按下遙控器上要設定的鍵", "15 秒內按下任何一顆鍵（包括 Netflix、YouTube 等快捷鍵）", listOf(
            MenuAction("取消") { closeOverlay() },
        ), settingsButton)
        KeyRemapService.learner = { code ->
            menu(KeyMapStore.keyName(code), "要設定短按還是長按？", listOf(
                MenuAction("短按") { chooseAction(code, false) },
                MenuAction("長按（按住約半秒）") { chooseAction(code, true) },
            ), settingsButton)
        }
        scope.launch {
            delay(15_000)
            if (KeyRemapService.learner != null) {
                KeyRemapService.learner = null
                if ((overlay as? MenuOverlay)?.title == "請按下遙控器上要設定的鍵") closeOverlay()
            }
        }
    }

    fun showKeyMenu() {
        val enabled = KeyMapStore.isServiceEnabled(context)
        val rules = KeyMapStore.load(context)
        val actions = buildList {
            if (!enabled) {
                add(MenuAction("啟用按鍵服務", "設定 → 無障礙 → 琉璃 遙控器按鍵") {
                    closeOverlay()
                    if (!SystemActions.launch(context, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))) {
                        toast("找不到無障礙設定，請用 ADB 啟用")
                    }
                })
            }
            add(MenuAction("新增按鍵對應…", if (enabled) null else "需先啟用服務") {
                if (enabled) startLearning() else toast("請先啟用按鍵服務")
            })
            add(MenuAction("防止回到原廠主畫面", if (KeyMapStore.guardHome(context)) "開" else "關") {
                KeyMapStore.setGuardHome(context, !KeyMapStore.guardHome(context)); closeOverlay()
            })
            rules.forEach { r ->
                add(MenuAction(
                    KeyMapStore.keyName(r.keyCode) + if (r.long) "・長按" else "・短按",
                    "${r.action.label}（按此刪除）",
                ) {
                    KeyMapStore.remove(context, r.keyCode, r.long)
                    KeyRemapService.instance?.reload()
                    closeOverlay()
                    toast("已刪除")
                })
            }
        }
        val status = if (enabled) "服務已啟用。" else
            "服務未啟用。若無障礙選單裡找不到，可用電腦執行：${KeyMapStore.adbEnableCommand(context)}"
        menu("遙控器按鍵", status, actions, settingsButton)
    }

    // ───────────── 設定主選單 ─────────────
    val glassLevel = LocalBackdrop.current.level
    fun showSettings() {
        menu(
            "設定",
            "琉璃 Launcher ${BuildConfigInfo.versionName(context)}・玻璃效果：${glassLevel.label}",
            listOf(
                MenuAction("桌布", "網頁、zip 離線包、原生") { showWallpaper() },
                MenuAction("地區", Cities.byId(settings.cityId).name) { showCities() },
                MenuAction("遙控器按鍵", "重新對應、防止回原廠主畫面") { showKeyMenu() },
                MenuAction("顯示／隱藏 App", if (hidden.isEmpty()) null else "已隱藏 ${hidden.size} 個") { showVisibility() },
                MenuAction("系統設定") { closeOverlay(); SystemActions.systemSettings(context) },
                MenuAction("網路設定") { closeOverlay(); SystemActions.networkSettings(context) },
                MenuAction("預設主畫面") { closeOverlay(); SystemActions.homeSettings(context) },
                MenuAction("重新整理應用程式") { closeOverlay(); reloadKey++ },
            ),
            settingsButton,
        )
    }

    BackHandler(enabled = true) {
        if (overlay != null) closeOverlay()
        else scope.launch { scroll.animateScrollTo(0) }
    }

    val visibleApps = apps.filter { it.packageName !in hidden }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(horizontal = 48.dp, vertical = 28.dp),
        ) {
            TopBar(
                home = home,
                onInputs = ::showInputs,
                onNetwork = { SystemActions.networkSettings(context) },
                onSettings = ::showSettings,
                inputsButton = inputsButton,
                settingsButton = settingsButton,
            )
            Spacer(Modifier.height(22.dp))
            InfoCards(home)
            Spacer(Modifier.height(26.dp))

            val dockApps = dock.mapNotNull { pkg -> apps.firstOrNull { it.packageName == pkg } }
            SectionTitle("常用")
            GlassSurface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(36.dp)) {
                Row(
                    Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 22.dp, vertical = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    if (dockApps.isEmpty()) {
                        GText(
                            if (apps.isEmpty()) "載入中…" else "在下方的 App 上長按確定鍵，即可加入常用列",
                            Modifier.padding(16.dp),
                            color = Color.White.copy(alpha = 0.7f),
                        )
                    }
                    dockApps.forEachIndexed { i, app ->
                        AppTile(
                            app = app,
                            width = 170.dp,
                            externalFocus = if (i == 0) firstDockFocus else null,
                            onClick = { launchApp(app) },
                            onLongClick = { fr -> showAppMenu(app, fr) },
                        )
                    }
                }
            }

            Spacer(Modifier.height(28.dp))
            SectionTitle("所有應用程式（${visibleApps.size}）")
            AppGrid(visibleApps, onClick = ::launchApp, onLongClick = ::showAppMenu)
            Spacer(Modifier.height(40.dp))
        }

        when (val o = overlay) {
            is MenuOverlay -> key(o) { GlassMenu(o.title, o.subtitle, o.actions, focusIndex = o.focusIndex) }
            is InputOverlay -> key(o) { GlassInput(o.title, o.hint, o.initial, o.onSubmit, ::closeOverlay) }
            null -> Unit
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    GText(
        text,
        Modifier.padding(start = 8.dp, bottom = 10.dp),
        size = 17.sp,
        weight = FontWeight.SemiBold,
        color = Color.White.copy(alpha = 0.85f),
    )
}

@Composable
private fun TopBar(
    home: HomeContext,
    onInputs: () -> Unit,
    onNetwork: () -> Unit,
    onSettings: () -> Unit,
    inputsButton: FocusRequester,
    settingsButton: FocusRequester,
) {
    val context = LocalContext.current
    // 時鐘自己每秒更新；HomeContext 只每分鐘更新一次
    val now by androidx.compose.runtime.produceState(java.time.LocalDateTime.now()) {
        while (true) {
            value = java.time.LocalDateTime.now()
            delay(1000L - System.currentTimeMillis() % 1000L)
        }
    }
    val is24h = android.text.format.DateFormat.is24HourFormat(context)
    val time = now.format(DateTimeFormatter.ofPattern(if (is24h) "HH:mm" else "h:mm", Locale.TAIWAN))
    val ampm = if (is24h) null else if (now.hour < 12) "上午" else "下午"
    val date = now.format(DateTimeFormatter.ofPattern("M月d日 EEEE", Locale.TAIWAN))

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.Bottom) {
                if (ampm != null) {
                    GText(ampm, Modifier.padding(end = 6.dp, bottom = 12.dp), size = 20.sp, weight = FontWeight.Light)
                }
                GText(time, size = 64.sp, weight = FontWeight.Thin)
            }
            GText("$date・${home.lunar.fullText}", size = 20.sp, color = Color.White.copy(alpha = 0.8f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            GlassPill("訊號源", focusRequester = inputsButton, onClick = onInputs)
            GlassPill("網路", onClick = onNetwork)
            GlassPill("設定", focusRequester = settingsButton, onClick = onSettings)
        }
    }
}

/** 農民曆卡＋天氣卡。 */
@Composable
private fun InfoCards(home: HomeContext) {
    val l = home.lunar
    val dim = Color.White.copy(alpha = 0.65f)
    Row(Modifier.fillMaxWidth().height(160.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        GlassSurface(Modifier.weight(1.5f).fillMaxSize(), shape = RoundedCornerShape(32.dp)) {
            Row(Modifier.padding(horizontal = 26.dp, vertical = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    GText(home.now.dayOfMonth.toString(), size = 64.sp, weight = FontWeight.ExtraLight)
                    GText(l.dayText, size = 18.sp, color = dim)
                }
                Spacer(Modifier.width(24.dp))
                Column {
                    GText(
                        "${l.ganZhiYear}${l.zodiac}年・${l.monthText}${l.dayText}",
                        size = 19.sp,
                        weight = FontWeight.Medium,
                    )
                    Spacer(Modifier.height(4.dp))
                    GText(
                        home.highlight ?: "下個節氣：${l.nextSolarTerm}（${l.nextSolarTermDate.monthValue}/${l.nextSolarTermDate.dayOfMonth}）",
                        size = 15.sp,
                        color = if (home.highlight != null) Color(0xFFFFD58A) else dim,
                    )
                    Spacer(Modifier.height(8.dp))
                    GText("宜　" + l.yi.take(5).joinToString(" ").ifBlank { "—" }, size = 15.sp, color = dim)
                    GText("忌　" + l.ji.take(5).joinToString(" ").ifBlank { "—" }, size = 15.sp, color = dim)
                    GText("${l.chong}　${l.sha}", size = 14.sp, color = dim)
                }
            }
        }
        GlassSurface(Modifier.weight(1f).fillMaxSize(), shape = RoundedCornerShape(32.dp)) {
            val w = home.weather
            Column(Modifier.padding(horizontal = 26.dp, vertical = 20.dp)) {
                GText(home.city.name, size = 16.sp, color = dim)
                if (w == null) {
                    Spacer(Modifier.height(8.dp))
                    GText(if (home.online) "天氣載入中…" else "目前離線", size = 20.sp)
                } else {
                    Row(verticalAlignment = Alignment.Bottom) {
                        GText("${w.temp.toInt()}°", size = 52.sp, weight = FontWeight.ExtraLight)
                        Spacer(Modifier.width(12.dp))
                        GText(w.label, Modifier.padding(bottom = 10.dp), size = 20.sp, weight = FontWeight.Medium)
                    }
                    GText(
                        "最高 ${w.tempMax.toInt()}°・最低 ${w.tempMin.toInt()}°・降雨 ${w.rainChance}%",
                        size = 15.sp,
                        color = dim,
                    )
                }
            }
        }
    }
}

@Composable
private fun AppGrid(
    apps: List<AppEntry>,
    onClick: (AppEntry) -> Unit,
    onLongClick: (AppEntry, FocusRequester) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = 5
        val gap = 22.dp
        val tileWidth = (maxWidth - gap * (columns - 1)) / columns
        Column(verticalArrangement = Arrangement.spacedBy(gap)) {
            apps.chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    row.forEach { app ->
                        key(app.packageName) {
                            AppTile(
                                app = app,
                                width = tileWidth,
                                onClick = { onClick(app) },
                                onLongClick = { fr -> onLongClick(app, fr) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AppTile(
    app: AppEntry,
    width: Dp,
    externalFocus: FocusRequester? = null,
    onClick: () -> Unit,
    onLongClick: (FocusRequester) -> Unit,
) {
    val own = remember { FocusRequester() }
    val requester = externalFocus ?: own
    FocusScale(
        Modifier.width(width),
        focusedScale = 1.10f,
        focusRequester = requester,
        onClick = onClick,
        onLongClick = { onLongClick(requester) },
    ) { focus ->
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            val shape = RoundedCornerShape(22.dp)
            GlassSurface(
                Modifier.fillMaxWidth().aspectRatio(16f / 9f),
                shape = shape,
                highlight = focus,
                blur = false,
            ) {
                when {
                    app.banner != null -> Image(
                        app.banner,
                        contentDescription = app.label,
                        modifier = Modifier.fillMaxSize().clip(shape),
                        contentScale = ContentScale.Crop,
                    )
                    app.icon != null -> Image(
                        app.icon,
                        contentDescription = app.label,
                        modifier = Modifier.align(Alignment.Center).size(56.dp),
                    )
                }
                if (!app.isTvApp) {
                    GText(
                        "手機版",
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                            .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                        size = 11.sp,
                        color = Color.White.copy(alpha = 0.85f),
                    )
                }
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.White.copy(alpha = 0.10f * focus)),
                )
            }
            Spacer(Modifier.height(8.dp))
            GText(
                app.label,
                size = 14.sp,
                weight = if (focus > 0.5f) FontWeight.SemiBold else FontWeight.Normal,
                color = Color.White.copy(alpha = 0.55f + 0.45f * focus),
            )
        }
    }
}
