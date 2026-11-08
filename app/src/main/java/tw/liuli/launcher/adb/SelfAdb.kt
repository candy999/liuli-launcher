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
