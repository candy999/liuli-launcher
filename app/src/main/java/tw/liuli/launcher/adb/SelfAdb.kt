package tw.liuli.launcher.adb

import android.content.ComponentName
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tw.liuli.launcher.keys.KeyRemapService

/**
 * 透過本機網路 ADB，開啟自己的無障礙按鍵服務。
 *
 * 流程：連 127.0.0.1:5555 → 電視跳出授權對話框（使用者按一律允許一次）→
 * 把 tw.liuli.launcher/.keys.KeyRemapService 加進 enabled_accessibility_services，
 * 並開啟無障礙總開關。不覆蓋其他已啟用的服務。
 */
object SelfAdb {

    sealed class Result {
        /** 已啟用。 */
        data object Success : Result()
        /** 連得上但尚未授權：請使用者看電視畫面按「一律允許」，再試一次。 */
        data object NeedsAuthorization : Result()
        /** 連不上本機 ADB：網路 ADB（5555）沒開。 */
        data class NotConnectable(val detail: String) : Result()
        data class Failed(val detail: String) : Result()
    }

    /**
     * 效能最佳化：把琉璃整個預先編譯成機器碼（cmd package compile -m speed）。
     * 側載安裝的 App 一開始大多以直譯／JIT 執行，弱 CPU 的電視上第一次開面板會卡 0.3 秒；
     * 編譯後同一個動作約 0.05 秒。約 5～10 秒，下次啟動生效。
     */
    suspend fun compileSelf(context: Context, port: Int = 5555): Result = withContext(Dispatchers.IO) {
        val key = AdbKey.load(context)
        try {
            AdbClient("127.0.0.1", port, key).use { adb ->
                adb.connect()
                val pkg = context.packageName
                adb.setReadTimeout(180_000) // 弱機編譯可能要 10 秒以上
                val out = adb.shell("cmd package compile -m speed -f $pkg")
                if (!out.contains("Success")) return@use Result.Failed(out.trim().take(120))
                // 重新啟動：由 adb 那邊開一個脫離連線的程序，1 秒後把琉璃叫回來（琉璃自己結束後連線會斷，所以要 setsid）
                runCatching { adb.shell("setsid sh -c 'sleep 1; am start -n $pkg/.MainActivity' > /dev/null 2>&1 < /dev/null &") }
                Result.Success
            }
        } catch (e: AdbClient.AdbException) {
            if (e.message?.contains("授權") == true) Result.NeedsAuthorization
            else Result.Failed(e.message ?: "ADB 錯誤")
        } catch (e: java.net.ConnectException) {
            Result.NotConnectable("無法連線（${e.message}）")
        } catch (e: java.net.SocketTimeoutException) {
            Result.NeedsAuthorization
        } catch (e: Exception) {
            Result.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    /** 重新啟動琉璃（讓剛編譯好的機器碼生效）：結束自己，由 adb 端或鬧鐘再開一次。 */
    fun restartSelf(context: Context) {
        val intent = android.content.Intent(context, tw.liuli.launcher.MainActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = android.app.PendingIntent.getActivity(
            context, 7, intent,
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_CANCEL_CURRENT,
        )
        val am = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        val at = android.os.SystemClock.elapsedRealtime() + 1500
        // Android 12 起精確鬧鐘要權限：12 以下用精確的，以上用一般的（adb 那邊也會叫回來，這只是備援）
        if (android.os.Build.VERSION.SDK_INT < 31) am.setExact(android.app.AlarmManager.ELAPSED_REALTIME, at, pi)
        else am.set(android.app.AlarmManager.ELAPSED_REALTIME, at, pi)
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    suspend fun enableAccessibility(context: Context, port: Int = 5555): Result = withContext(Dispatchers.IO) {
        val component = ComponentName(context, KeyRemapService::class.java).flattenToString()
        val key = AdbKey.load(context)
        try {
            AdbClient("127.0.0.1", port, key).use { adb ->
                adb.connect()
                val current = adb.shell("settings get secure enabled_accessibility_services").trim()
                val services = current.takeIf { it.isNotBlank() && it != "null" }
                    ?.split(':')?.filter { it.isNotBlank() }?.toMutableList()
                    ?: mutableListOf()
                if (services.none { it.equals(component, ignoreCase = true) || it.endsWith("/.keys.KeyRemapService") }) {
                    services.add(component)
                }
                val value = services.joinToString(":")
                adb.shell("settings put secure enabled_accessibility_services $value")
                adb.shell("settings put secure accessibility_enabled 1")

                val after = adb.shell("settings get secure enabled_accessibility_services")
                if (after.contains("KeyRemapService")) Result.Success
                else Result.Failed("寫入後未生效：$after")
            }
        } catch (e: AdbClient.AdbException) {
            if (e.message?.contains("授權") == true) Result.NeedsAuthorization
            else Result.Failed(e.message ?: "ADB 錯誤")
        } catch (e: java.net.ConnectException) {
            Result.NotConnectable("無法連線（${e.message}）")
        } catch (e: java.net.SocketTimeoutException) {
            // 送出公鑰後逾時，多半是使用者還沒在電視上按允許
            Result.NeedsAuthorization
        } catch (e: Exception) {
            Result.Failed(e.message ?: e.javaClass.simpleName)
        }
    }
}
