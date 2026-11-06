package tw.liuli.launcher.keys

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.view.KeyEvent
import org.json.JSONObject

/** 按鍵要做的事。encode() 的字串會存進設定。 */
sealed class KeyAction(val label: String) {
    data object LiuliHome : KeyAction("回到琉璃主畫面")
    data object Back : KeyAction("返回")
    data object Inputs : KeyAction("開啟訊號源選單")
    data object SystemSettings : KeyAction("開啟系統設定")
    data object Recents : KeyAction("最近使用的 App")
    data object Notifications : KeyAction("通知")
    data object Block : KeyAction("停用這顆鍵")
    data class OpenApp(val pkg: String, val appLabel: String) : KeyAction("開啟 $appLabel")

    fun encode(): String = when (this) {
        LiuliHome -> "home"
        Back -> "back"
        Inputs -> "inputs"
        SystemSettings -> "settings"
        Recents -> "recents"
        Notifications -> "notifications"
        Block -> "block"
        is OpenApp -> "app:$pkg|$appLabel"
    }

    companion object {
        val fixed = listOf(LiuliHome, Back, Inputs, SystemSettings, Recents, Notifications, Block)

        fun decode(raw: String): KeyAction? = when {
            raw == "home" -> LiuliHome
            raw == "back" -> Back
            raw == "inputs" -> Inputs
            raw == "settings" -> SystemSettings
            raw == "recents" -> Recents
            raw == "notifications" -> Notifications
            raw == "block" -> Block
            raw.startsWith("app:") -> raw.removePrefix("app:").split("|", limit = 2)
                .let { OpenApp(it[0], it.getOrElse(1) { it[0] }) }
            else -> null
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
