package tw.liuli.launcher.keys

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.view.KeyEvent
import org.json.JSONObject

/** 按鍵要做的事。encode() 的字串會存進設定。 */
sealed class KeyAction(val label: String) {
    // 琉璃
    data object LiuliHome : KeyAction("回到琉璃主畫面")
    data object Drawer : KeyAction("開啟所有 App")
    data object Inputs : KeyAction("開啟訊號源選單")
    data object Calendar : KeyAction("開啟月曆")
    data object Weather : KeyAction("開啟天氣")
    data object ClockToggle : KeyAction("螢幕小時鐘 開／關")

    // 音量（透過系統音量，接音響時也有效）
    data object VolumeUp : KeyAction("音量加大")
    data object VolumeDown : KeyAction("音量減小")
    data object Mute : KeyAction("靜音／取消靜音")

    // 媒體播放（送給正在播放的 App，例如 YouTube、Netflix）
    data class Media(val code: Int) : KeyAction(MEDIA_NAMES[code] ?: "媒體鍵 $code")

    // 系統
    data object Back : KeyAction("返回")
    data object Recents : KeyAction("最近使用的 App")
    data object Notifications : KeyAction("通知")
    data object QuickSettings : KeyAction("快速設定面板")
    data object Assistant : KeyAction("語音助理")
    data object SystemSettings : KeyAction("開啟系統設定")
    data object NetworkSettings : KeyAction("開啟網路設定")
    data object PowerDialog : KeyAction("電源選單")
    data object Screenshot : KeyAction("截圖")
    data object Block : KeyAction("停用這顆鍵")

    // 指定目標
    data class OpenApp(val pkg: String, val appLabel: String) : KeyAction("開啟 $appLabel")
    data class Source(val id: String, val sourceLabel: String) : KeyAction("切換到 $sourceLabel")

    fun encode(): String = when (this) {
        LiuliHome -> "home"
        Drawer -> "drawer"
        Inputs -> "inputs"
        Calendar -> "calendar"
        Weather -> "weather"
        ClockToggle -> "clock"
        VolumeUp -> "vol_up"
        VolumeDown -> "vol_down"
        Mute -> "mute"
        is Media -> "media:$code"
        Back -> "back"
        Recents -> "recents"
        Notifications -> "notifications"
        QuickSettings -> "quick_settings"
        Assistant -> "assistant"
        SystemSettings -> "settings"
        NetworkSettings -> "network"
        PowerDialog -> "power"
        Screenshot -> "screenshot"
        Block -> "block"
        is OpenApp -> "app:$pkg|$appLabel"
        is Source -> "source:$id|$sourceLabel"
    }

    companion object {
        private val MEDIA_NAMES = mapOf(
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE to "播放／暫停",
            KeyEvent.KEYCODE_MEDIA_NEXT to "下一個",
            KeyEvent.KEYCODE_MEDIA_PREVIOUS to "上一個",
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD to "快轉",
            KeyEvent.KEYCODE_MEDIA_REWIND to "倒轉",
            KeyEvent.KEYCODE_MEDIA_STOP to "停止播放",
        )

        /*
         * 設定畫面的分類。一定要用 get()（每次呼叫才建立）：如果寫成一般的 val，
         * 先用到某個子物件（例如 LiuliHome）時，JVM 會先初始化父類別 KeyAction 與這個 companion，
         * 而這時那個子物件本身還沒建立完成，清單裡就會放進 null（儲存綁定後讀取就閃退）。
         */
        val LIULI: List<KeyAction> get() = listOf(LiuliHome, Drawer, Inputs, Calendar, Weather, ClockToggle)
        val VOLUME: List<KeyAction> get() = listOf(VolumeUp, VolumeDown, Mute)
        val MEDIA: List<KeyAction> get() = MEDIA_NAMES.keys.map { Media(it) }
        val SYSTEM: List<KeyAction> get() = listOf(Back, Recents, Notifications, QuickSettings, Assistant, SystemSettings, NetworkSettings, PowerDialog, Screenshot)

        fun decode(raw: String): KeyAction? = when {
            raw.startsWith("app:") -> {
                val parts = raw.removePrefix("app:").split("|", limit = 2)
                OpenApp(parts[0], parts.getOrNull(1) ?: parts[0])
            }
            raw.startsWith("source:") -> {
                val parts = raw.removePrefix("source:").split("|", limit = 2)
                Source(parts[0], parts.getOrNull(1) ?: parts[0])
            }
            raw.startsWith("media:") -> raw.removePrefix("media:").toIntOrNull()?.let { Media(it) }
            else -> (LIULI + VOLUME + SYSTEM + Block).firstOrNull { it.encode() == raw }
        }
    }
}

/** 一條對應規則：某顆鍵的短按或長按。 */
data class KeyBinding(val keyCode: Int, val long: Boolean, val action: KeyAction)

/**
 * 按鍵對應設定。無障礙服務與 App 介面共用（同一個 process，直接讀 SharedPreferences）。
 */
object KeyMapStore {
    private const val PREFS = "keymap"
    private const val KEY_RULES = "rules"
    private const val KEY_GUARD = "guard_home"
    private const val KEY_PENDING = "pending_key"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(context: Context): List<KeyBinding> {
        val json = runCatching { JSONObject(prefs(context).getString(KEY_RULES, "{}") ?: "{}") }
            .getOrDefault(JSONObject())
        val result = mutableListOf<KeyBinding>()
        for (k in json.keys()) {
            val parts = k.split(":")
            val code = parts.getOrNull(0)?.toIntOrNull() ?: continue
            val action = KeyAction.decode(json.optString(k)) ?: continue
            result += KeyBinding(code, parts.getOrNull(1) == "long", action)
        }
        return result.sortedBy { it.keyCode }
    }

    fun put(context: Context, binding: KeyBinding) {
        val json = JSONObject(prefs(context).getString(KEY_RULES, "{}") ?: "{}")
        json.put(ruleKey(binding.keyCode, binding.long), binding.action.encode())
        prefs(context).edit().putString(KEY_RULES, json.toString()).apply()
    }

    fun remove(context: Context, keyCode: Int, long: Boolean) {
        val json = JSONObject(prefs(context).getString(KEY_RULES, "{}") ?: "{}")
        json.remove(ruleKey(keyCode, long))
        prefs(context).edit().putString(KEY_RULES, json.toString()).apply()
    }

    /**
     * 攔截精靈：已攔截、還沒設定功能的鍵（0＝沒有）。存起來，使用者不小心離開 App 再回來，
     * 打開設定仍然看得到攔到了哪一顆，可以繼續設定或重新攔截。
     */
    fun pending(context: Context): Int = prefs(context).getInt(KEY_PENDING, 0)
    fun setPending(context: Context, code: Int) = prefs(context).edit().putInt(KEY_PENDING, code).apply()

    /** 防止回到原廠主畫面：攔 Home 鍵、偵測原廠 launcher 出現就切回琉璃。預設開啟。 */
    fun guardHome(context: Context) = prefs(context).getBoolean(KEY_GUARD, true)
    fun setGuardHome(context: Context, on: Boolean) = prefs(context).edit().putBoolean(KEY_GUARD, on).apply()

    private fun ruleKey(code: Int, long: Boolean) = "$code:${if (long) "long" else "short"}"

    /** 無障礙服務是否已啟用。 */
    fun isServiceEnabled(context: Context): Boolean {
        val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        val me = ComponentName(context, KeyRemapService::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    /** ADB 啟用指令（部分 Google TV 的無障礙選單不列第三方服務）。 */
    fun adbEnableCommand(context: Context): String {
        val me = ComponentName(context, KeyRemapService::class.java).flattenToShortString()
        return "adb shell settings put secure enabled_accessibility_services $me"
    }

    /** 給人看的按鍵名稱。 */
    fun keyName(code: Int): String = when (code) {
        KeyEvent.KEYCODE_HOME -> "Home 主畫面鍵"
        KeyEvent.KEYCODE_BACK -> "返回鍵"
        KeyEvent.KEYCODE_DPAD_CENTER -> "確定鍵"
        KeyEvent.KEYCODE_MENU -> "選單鍵"
        KeyEvent.KEYCODE_SETTINGS -> "設定鍵"
        KeyEvent.KEYCODE_TV_INPUT -> "訊號源鍵"
        KeyEvent.KEYCODE_GUIDE -> "節目表鍵"
        KeyEvent.KEYCODE_ASSIST, KeyEvent.KEYCODE_VOICE_ASSIST, KeyEvent.KEYCODE_SEARCH -> "語音／搜尋鍵"
        KeyEvent.KEYCODE_ALL_APPS -> "所有 App 鍵"
        KeyEvent.KEYCODE_BOOKMARK -> "書籤鍵"
        KeyEvent.KEYCODE_PROG_RED -> "紅色鍵"
        KeyEvent.KEYCODE_PROG_GREEN -> "綠色鍵"
        KeyEvent.KEYCODE_PROG_YELLOW -> "黃色鍵"
        KeyEvent.KEYCODE_PROG_BLUE -> "藍色鍵"
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> "播放／暫停鍵"
        else -> KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_") + "（$code）"
    }
}
