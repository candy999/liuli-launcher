package tw.liuli.launcher

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import tw.liuli.launcher.context.Cities
import tw.liuli.launcher.context.ContextParams
import tw.liuli.launcher.context.rememberHomeContext
import tw.liuli.launcher.ui.glass.Backdrop
import tw.liuli.launcher.ui.glass.GlassLevel
import tw.liuli.launcher.ui.glass.LiquidWallpaper
import tw.liuli.launcher.ui.glass.LocalBackdrop
import tw.liuli.launcher.ui.glass.detectGlassLevel
import tw.liuli.launcher.ui.glass.rememberWallpaperPhase
import tw.liuli.launcher.ui.home.HomeScreen
import tw.liuli.launcher.wallpaper.LauncherSettings
import tw.liuli.launcher.wallpaper.WallpaperKey
import tw.liuli.launcher.wallpaper.WallpaperPacks
import tw.liuli.launcher.wallpaper.WebWallpaper

class MainActivity : ComponentActivity() {

    /** 已在主畫面時又按 Home 的次數，用來觸發「回到頂端」。 */
    private val homePresses = mutableIntStateOf(0)

    /** 外部（按鍵服務）要求的動作：序號＋動作名稱。 */
    private val externalAction = mutableStateOf(0 to null as String?)

    /** 主畫面收到的遙控器按鍵，轉送給網頁桌布。 */
    private val keyEvents = MutableSharedFlow<WallpaperKey>(extraBufferCapacity = 32)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleAction(intent)
        val level = detectGlassLevel(this)
        setContent {
            LiuliApp(level, homePresses.intValue, externalAction.value, keyEvents)
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

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val action = when (event.action) {
            KeyEvent.ACTION_DOWN -> "down"
            KeyEvent.ACTION_UP -> "up"
            else -> null
        }
        if (action != null) {
            keyEvents.tryEmit(
                WallpaperKey(
                    key = KeyEvent.keyCodeToString(event.keyCode).removePrefix("KEYCODE_"),
                    code = event.keyCode,
                    action = action,
                    long = event.repeatCount > 0,
                ),
            )
        }
        return super.dispatchKeyEvent(event)
    }

    companion object {
        const val EXTRA_ACTION = "tw.liuli.launcher.ACTION"
        const val ACTION_INPUTS = "inputs"
    }
}

@Composable
fun LiuliApp(
    level: GlassLevel,
    homePresses: Int,
    externalAction: Pair<Int, String?>,
    keyEvents: SharedFlow<WallpaperKey>,
) {
    val context = LocalContext.current
    val settings = remember { LauncherSettings(context) }
    var packsVersion by remember { mutableIntStateOf(0) }

    val source = settings.wallpaper
    val baseUrl = remember(source, packsVersion) { WallpaperPacks.baseUrl(context, source) }
    var webFailed by remember(baseUrl) { mutableStateOf(false) }
    val isWeb = baseUrl != null && !webFailed
    val snapshot = remember(baseUrl) { mutableStateOf<ImageBitmap?>(null) }

    val home by rememberHomeContext(Cities.byId(settings.cityId))
    val metrics = context.resources.displayMetrics
    val glassName = level.name.lowercase()
    val params = remember(home, glassName) {
        ContextParams.toParams(home, metrics.widthPixels, metrics.heightPixels, glassName)
    }

    val phase = rememberWallpaperPhase(animate = level == GlassLevel.HIGH && !isWeb)
    val backdrop = remember(level, phase, isWeb, snapshot) {
        Backdrop(level, phase, isLiquid = !isWeb, snapshot = if (isWeb) snapshot else null)
    }

    CompositionLocalProvider(LocalBackdrop provides backdrop) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            if (isWeb && baseUrl != null) {
                key(baseUrl) {
                    WebWallpaper(
                        baseUrl = baseUrl,
                        params = params,
                        keys = if (settings.forwardKeys) keyEvents else null,
                        snapshot = if (level != GlassLevel.LOW) snapshot else null,
                        onFailed = { webFailed = true },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            } else {
                LiquidWallpaper(phase)
            }
            HomeScreen(
                homePresses = homePresses,
                externalAction = externalAction,
                home = home,
                settings = settings,
                onPacksChanged = { packsVersion++ },
            )
        }
    }
}

object BuildConfigInfo {
    fun versionName(context: Context): String =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
            .getOrNull() ?: ""
}
