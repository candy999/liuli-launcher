package tw.liuli.launcher

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import tw.liuli.launcher.ui.glass.Backdrop
import tw.liuli.launcher.ui.glass.GlassLevel
import tw.liuli.launcher.ui.glass.LiquidWallpaper
import tw.liuli.launcher.ui.glass.LocalBackdrop
import tw.liuli.launcher.ui.glass.detectGlassLevel
import tw.liuli.launcher.ui.glass.rememberWallpaperPhase
import tw.liuli.launcher.ui.home.HomeScreen

class MainActivity : ComponentActivity() {

    /** 已在主畫面時又按 Home 的次數，用來觸發「回到頂端」。 */
    private val homePresses = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val level = detectGlassLevel(this)
        setContent {
            LiuliApp(level, homePresses.intValue)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.hasCategory(Intent.CATEGORY_HOME)) homePresses.intValue++
    }
}

@Composable
fun LiuliApp(level: GlassLevel, homePresses: Int) {
    val phase = rememberWallpaperPhase(animate = level == GlassLevel.HIGH)
    val backdrop = remember(level, phase) { Backdrop(level, phase) }
    CompositionLocalProvider(LocalBackdrop provides backdrop) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            LiquidWallpaper(phase)
            HomeScreen(homePresses)
        }
    }
}

object BuildConfigInfo {
    fun versionName(context: Context): String =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
            .getOrNull() ?: ""
}
