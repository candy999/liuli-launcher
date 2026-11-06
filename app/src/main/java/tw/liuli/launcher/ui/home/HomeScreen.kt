package tw.liuli.launcher.ui.home

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
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
import tw.liuli.launcher.data.AppEntry
import tw.liuli.launcher.data.AppRepository
import tw.liuli.launcher.data.DockStore
import tw.liuli.launcher.data.TvSources
import tw.liuli.launcher.system.SystemActions
import tw.liuli.launcher.ui.glass.FocusScale
import tw.liuli.launcher.ui.glass.GText
import tw.liuli.launcher.ui.glass.GlassMenu
import tw.liuli.launcher.ui.glass.GlassPill
import tw.liuli.launcher.ui.glass.GlassSurface
import tw.liuli.launcher.ui.glass.LocalBackdrop
import tw.liuli.launcher.ui.glass.MenuAction
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 目前開著的選單。 */
private data class Overlay(
    val title: String,
    val subtitle: String?,
    val actions: List<MenuAction>,
    /** 關閉後要把焦點還給誰。 */
    val returnFocus: FocusRequester?,
)

@Composable
fun HomeScreen(homePresses: Int) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scroll = rememberScrollState()
    val dockStore = remember { DockStore(context) }

    var apps by remember { mutableStateOf<List<AppEntry>>(emptyList()) }
    var dock by remember { mutableStateOf<List<String>>(emptyList()) }
    var reloadKey by remember { mutableStateOf(0) }
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

    // 第一次載入完成時，焦點放在常用列第一個
    var initialFocusDone by remember { mutableStateOf(false) }
    LaunchedEffect(apps, dock) {
        if (!initialFocusDone && apps.isNotEmpty()) {
            delay(50)
            if (runCatching { firstDockFocus.requestFocus() }.isSuccess) initialFocusDone = true
        }
    }

    // 已在主畫面時再按 Home：關選單、捲回頂端、焦點回常用列
    LaunchedEffect(homePresses) {
        if (homePresses == 0) return@LaunchedEffect
        overlay = null
        scroll.animateScrollTo(0)
        runCatching { firstDockFocus.requestFocus() }
    }

    // 選單關閉後還原焦點
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
    }

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

    fun launchApp(app: AppEntry) {
        if (!SystemActions.launch(context, app.launchIntent)) toast("無法開啟 ${app.label}")
    }

    fun setDock(newDock: List<String>) {
        dock = newDock
        dockStore.save(newDock)
    }

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
                    if (dock.size < DockStore.MAX) setDock(dock + app.packageName) else toast("常用列已滿")
                    closeOverlay()
                })
            }
            add(MenuAction("應用程式資訊", "強制停止、清除快取") {
                closeOverlay(); SystemActions.appInfo(context, app.packageName)
            })
            add(MenuAction("解除安裝") { closeOverlay(); SystemActions.uninstall(context, app.packageName) })
        }
        overlay = Overlay(app.label, app.packageName, actions, returnTo)
    }

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
        overlay = Overlay("訊號源", "選擇要切換的輸入", actions, inputsButton)
    }

    val glassLevel = LocalBackdrop.current.level
    fun showSettings() {
        overlay = Overlay(
            "設定",
            "琉璃 Launcher ${BuildConfigInfo.versionName(context)}・玻璃效果：${glassLevel.label}",
            listOf(
                MenuAction("系統設定") { closeOverlay(); SystemActions.systemSettings(context) },
                MenuAction("網路設定") { closeOverlay(); SystemActions.networkSettings(context) },
                MenuAction("預設主畫面") { closeOverlay(); SystemActions.homeSettings(context) },
                MenuAction("重新整理應用程式") { closeOverlay(); reloadKey++ },
            ),
            settingsButton,
        )
    }

    // 主畫面按返回鍵不離開；選單開著時則關閉選單
    BackHandler(enabled = true) {
        if (overlay != null) closeOverlay()
        else scope.launch { scroll.animateScrollTo(0) }
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                // 電視過掃描安全區
                .padding(horizontal = 48.dp, vertical = 28.dp),
        ) {
            TopBar(
                onInputs = ::showInputs,
                onNetwork = { SystemActions.networkSettings(context) },
                onSettings = ::showSettings,
                inputsButton = inputsButton,
                settingsButton = settingsButton,
            )
            Spacer(Modifier.height(22.dp))
            InfoCards()
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
            SectionTitle("所有應用程式（${apps.size}）")
            AppGrid(apps, onClick = ::launchApp, onLongClick = ::showAppMenu)
            Spacer(Modifier.height(40.dp))
        }

        overlay?.let { GlassMenu(it.title, it.subtitle, it.actions) }
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
    onInputs: () -> Unit,
    onNetwork: () -> Unit,
    onSettings: () -> Unit,
    inputsButton: FocusRequester,
    settingsButton: FocusRequester,
) {
    val context = LocalContext.current
    val now by produceState(LocalDateTime.now()) {
        while (true) {
            value = LocalDateTime.now()
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
            GText(date, size = 20.sp, color = Color.White.copy(alpha = 0.8f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            GlassPill("訊號源", focusRequester = inputsButton, onClick = onInputs)
            GlassPill("網路", onClick = onNetwork)
            GlassPill("設定", focusRequester = settingsButton, onClick = onSettings)
        }
    }
}

/** M2 月曆＋農民曆、M3 天氣的位置，先放今天的日期。 */
@Composable
private fun InfoCards() {
    val today = remember { LocalDateTime.now() }
    Row(Modifier.fillMaxWidth().height(150.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        GlassSurface(Modifier.weight(1.4f).fillMaxSize(), shape = RoundedCornerShape(32.dp)) {
            Row(Modifier.padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
                GText(today.dayOfMonth.toString(), size = 72.sp, weight = FontWeight.ExtraLight)
                Spacer(Modifier.width(20.dp))
                Column {
                    GText(
                        today.format(DateTimeFormatter.ofPattern("yyyy年M月 EEEE", Locale.TAIWAN)),
                        size = 20.sp,
                        weight = FontWeight.Medium,
                    )
                    Spacer(Modifier.height(6.dp))
                    GText("農民曆・宜忌・節氣（下一階段加入）", size = 15.sp, color = Color.White.copy(alpha = 0.6f))
                }
            }
        }
        GlassSurface(Modifier.weight(1f).fillMaxSize(), shape = RoundedCornerShape(32.dp)) {
            Column(Modifier.padding(24.dp)) {
                GText("天氣", size = 20.sp, weight = FontWeight.Medium)
                Spacer(Modifier.height(6.dp))
                GText("中央氣象署預報（下一階段加入）", size = 15.sp, color = Color.White.copy(alpha = 0.6f))
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
                // 焦點時在圖片上蓋一層光澤，讓玻璃感延伸到圖塊
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
