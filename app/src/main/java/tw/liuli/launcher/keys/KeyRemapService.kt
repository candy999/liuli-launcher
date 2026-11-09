package tw.liuli.launcher.keys

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import tw.liuli.launcher.MainActivity
import tw.liuli.launcher.system.SystemActions

/**
 * 遙控器按鍵重新對應＋主畫面保護。
 *
 * 只做三件事：
 * 1. 攔截按鍵（onKeyEvent），依使用者設定的規則改做別的事。
 * 2. 從視窗切換事件取得「前景 App 的套件名稱」，原廠主畫面跑出來時切回琉璃。
 * 3. 螢幕小時鐘：在其他 App 上層的角落顯示時間（[ClockOverlay]）。
 * 不讀取任何畫面內容（canRetrieveWindowContent=false），也不連網。
 */
class KeyRemapService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var rules: Map<Pair<Int, Boolean>, KeyAction> = emptyMap()
    private var longPending: Runnable? = null
    private var longFired = false
    private var downCode = 0
    private var lastGuardJump = 0L

    /** 螢幕小時鐘（疊在其他 App 上）。 */
    var clock: ClockOverlay? = null
        private set

    override fun onServiceConnected() {
        instance = this
        reload()
        clock = ClockOverlay(this).also { it.start() }
    }

    override fun onDestroy() {
        clock?.stop()
        clock = null
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onInterrupt() = Unit

    fun reload() {
        rules = KeyMapStore.load(this).associate { (it.keyCode to it.long) to it.action }
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode

        // 按鍵測試：每顆收到的鍵都回報給設定畫面（只看不攔，按鍵照常作用）
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            monitor?.let { m -> handler.post { m(code) } }
        }

        // 學習模式：下一顆按下的鍵交給設定畫面
        learner?.let { callback ->
            // 受保護的基本鍵（方向、確定、返回、音量、電源）一律放行：
            // 不能被改綁，也讓使用者還能用它們導航／取消設定畫面
            if (code in PROTECTED_KEYS) return false
            if (event.action == KeyEvent.ACTION_UP) {
                learner = null
                handler.post { callback(code) }
            }
            return true
        }

        val short = rules[code to false]
        val long = rules[code to true]
        val guardHome = code == KeyEvent.KEYCODE_HOME && short == null && KeyMapStore.guardHome(this)
        if (short == null && long == null && !guardHome) return false

        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0) {
                    downCode = code
                    longFired = false
                    if (long != null) {
                        val r = Runnable {
                            longFired = true
                            perform(long)
                        }
                        longPending = r
                        handler.postDelayed(r, LONG_PRESS_MS)
                    }
                }
            }
            KeyEvent.ACTION_UP -> {
                longPending?.let { handler.removeCallbacks(it) }
                longPending = null
                if (code == downCode && !longFired) {
                    when {
                        short != null -> perform(short)
                        guardHome -> perform(KeyAction.LiuliHome)
                        else -> fallback(code) // 只設了長按：短按盡量還原原本功能
                    }
                }
                longFired = false
            }
        }
        return true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg in STOCK_LAUNCHERS && KeyMapStore.guardHome(this)) {
            val now = SystemClock.elapsedRealtime()
            // 兩秒內只跳一次，避免使用者刻意打開原廠設定頁時來回彈跳
            if (now - lastGuardJump > 2000) {
                lastGuardJump = now
                openLiuli(null)
            }
        }
    }

    private fun perform(action: KeyAction) {
        when (action) {
            KeyAction.LiuliHome -> openLiuli(null)
            KeyAction.Inputs -> openLiuli(MainActivity.ACTION_INPUTS)
            KeyAction.Back -> performGlobalAction(GLOBAL_ACTION_BACK)
            KeyAction.Recents -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            KeyAction.Notifications -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
            KeyAction.SystemSettings -> SystemActions.systemSettings(this)
            KeyAction.Block -> Unit
            is KeyAction.OpenApp -> {
                val pm = packageManager
                val intent = pm.getLeanbackLaunchIntentForPackage(action.pkg) ?: pm.getLaunchIntentForPackage(action.pkg)
                if (intent != null) SystemActions.launch(this, intent)
            }
        }
    }

    private fun fallback(code: Int) {
        when (code) {
            KeyEvent.KEYCODE_BACK -> performGlobalAction(GLOBAL_ACTION_BACK)
            KeyEvent.KEYCODE_HOME -> openLiuli(null)
            // 其他鍵無法從無障礙服務「補送」，設定畫面會提醒使用者
        }
    }

    private fun openLiuli(action: String?) {
        val intent = Intent(this, MainActivity::class.java)
            .addCategory(Intent.CATEGORY_HOME)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        if (action != null) intent.putExtra(MainActivity.EXTRA_ACTION, action)
        runCatching { startActivity(intent) }
    }

    companion object {
        private const val LONG_PRESS_MS = 600L

        /** 常見的原廠電視主畫面。 */
        val STOCK_LAUNCHERS = setOf(
            "com.google.android.apps.tv.launcherx",   // Google TV
            "com.google.android.tvlauncher",          // Android TV
            "com.google.android.leanbacklauncher",    // 舊版 Android TV
            "com.amazon.tv.launcher",                 // Fire TV
            "com.mitv.tvhome.atv",                    // 小米
            "com.tcl.waterfall.overseas",             // TCL
            "com.android.tv.launcher",
        )

        /**
         * 受保護的基本鍵：不開放改綁。改綁其中任何一顆都會讓遙控器失去基本操作（無法移動、確定、返回、
         * 調音量），所以學習模式會放行這些鍵，儲存時也會拒絕。
         */
        val PROTECTED_KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_HOME,
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE,
            KeyEvent.KEYCODE_POWER, KeyEvent.KEYCODE_SLEEP, KeyEvent.KEYCODE_WAKEUP,
        )

        fun isProtected(code: Int) = code in PROTECTED_KEYS

        @Volatile
        var instance: KeyRemapService? = null
            private set

        /** 設定畫面用：下一顆按鍵會回傳給 callback（在主執行緒）。 */
        @Volatile
        var learner: ((Int) -> Unit)? = null

        /** 按鍵測試：每顆收到的按鍵都通知（在主執行緒），不影響按鍵本身。 */
        @Volatile
        var monitor: ((Int) -> Unit)? = null
    }
}
