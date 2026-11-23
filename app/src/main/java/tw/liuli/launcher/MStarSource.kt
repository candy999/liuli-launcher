package tw.liuli.launcher

import android.util.Log

/**
 * 實驗性修復（Android 5 專用版）：MStar 晶片的電視冷開機後，晶片的「訊號源」會停在上次的類比電視，
 * 原廠桌面一出現就會把訊號源切到「多媒體（Storage）」，把硬體解碼層交給 App 用。
 * 開機直接進琉璃時沒有人做這一步，外部 App 播影片會和類比雪花打架。
 *
 * 啟動外部 App 前呼叫 [ensureStorage]：有 MStar 的系統介面、而且訊號源不是 Storage 時才切過去。
 * 不是 MStar 的電視找不到這些類別，第一次就放棄、之後什麼都不做；系統不准呼叫（要系統簽章）時也一樣直接放棄。
 */
object MStarSource {
    private const val TAG = "LiuliMStar"

    @Volatile private var gaveUp = false

    fun ensureStorage() {
        if (gaveUp) return
        try {
            if (!intApi() && !enumApi()) gaveUp = true
        } catch (t: Throwable) {
            Log.w(TAG, "切換 MStar 訊號源失敗，之後不再嘗試", t)
            gaveUp = true
        }
    }

    private fun manager(): Any? {
        val c = try {
            Class.forName("com.mstar.android.tv.TvCommonManager")
        } catch (_: Throwable) {
            return null
        }
        return c.getMethod("getInstance").invoke(null)
    }

    /** 較新的 MStar SDK：訊號源是 int 常數。 */
    private fun intApi(): Boolean {
        val m = manager() ?: return false
        val c = m.javaClass
        val storage = runCatching { c.getField("INPUT_SOURCE_STORAGE").getInt(null) }.getOrNull() ?: return false
        val cur = c.getMethod("getCurrentTvInputSource").invoke(m) as Int
        if (cur != storage) {
            c.getMethod("setInputSource", Int::class.javaPrimitiveType).invoke(m, storage)
            Log.i(TAG, "訊號源 $cur → Storage($storage)")
        }
        return true
    }

    /** 較舊的 MStar SDK：訊號源是 EnumInputSource。 */
    private fun enumApi(): Boolean {
        val m = manager() ?: return false
        val e = runCatching {
            Class.forName("com.mstar.android.tvapi.common.vo.TvOsType\$EnumInputSource")
        }.getOrNull() ?: return false
        val storage = e.getField("E_INPUT_SOURCE_STORAGE").get(null)
        val c = m.javaClass
        val cur = runCatching { c.getMethod("getCurrentInputSource").invoke(m) }.getOrNull()
        if (cur != storage) {
            c.getMethod("setInputSource", e).invoke(m, storage)
            Log.i(TAG, "訊號源 $cur → $storage")
        }
        return true
    }
}
