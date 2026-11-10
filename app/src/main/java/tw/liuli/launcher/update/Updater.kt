package tw.liuli.launcher.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 手動更新：只看「正式發布」的版本（GitHub Releases 的 latest，不含預覽版 nightly）。
 *
 * 全部都是使用者自己按才做——不會自動檢查、不會自動下載、更不會自動安裝（有些人就想保持舊的狀態）：
 * 1. 按「檢查更新」：讀最新正式版的資訊、下載 APK（約 2 MB），讀出它的「版本碼」和目前安裝的比
 *    （不比版本名稱：預覽版叫 0.1.93、正式版叫 1.0.0，名稱看起來新的其實可能是更舊的程式）
 * 2. 真的比較新才出現「安裝」；再按一次才交給系統的安裝畫面，系統還會再問一次確認
 * 簽章和目前安裝的相同（同一把金鑰），所以是直接覆蓋更新、資料都保留。
 */
object Updater {
    sealed class State {
        object Idle : State()
        object Checking : State()
        /** [newerInstalled]：目前安裝的比正式版還新（例如預覽版）。 */
        data class UpToDate(val tag: String, val newerInstalled: Boolean) : State()
        data class Available(val tag: String, val versionName: String, val file: File) : State()
        object Installing : State()
        data class Error(val message: String) : State()
    }

    var state by mutableStateOf<State>(State.Idle)
        private set

    private const val LATEST = "https://api.github.com/repos/candy999/liuli-launcher/releases/latest"
    const val PROJECT_URL = "https://github.com/candy999/liuli-launcher"
    private const val ACTION_STATUS = "tw.liuli.launcher.UPDATE_STATUS"

    fun statusText(currentVersion: String): String = when (val s = state) {
        State.Idle -> "目前 $currentVersion"
        State.Checking -> "檢查中…"
        is State.UpToDate -> if (s.newerInstalled) "已是最新" else "已是最新 ${s.tag}"
        is State.Available -> "有新版 ${s.versionName}"
        State.Installing -> "交給系統安裝中…"
        is State.Error -> s.message
    }

    suspend fun check(context: Context) {
        if (state == State.Checking) return
        state = State.Checking
        state = withContext(Dispatchers.IO) {
            runCatching {
                val json = JSONObject(get(LATEST))
                val tag = json.optString("tag_name")
                val assets = json.getJSONArray("assets")
                val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
                    .firstOrNull { it.optString("name").endsWith(".apk") } ?: return@runCatching State.Error("正式版沒有附 APK")
                val dir = File(context.cacheDir, "update").apply { mkdirs() }
                // 只留這一個版本的檔案（舊的下載刪掉，不佔空間）
                dir.listFiles()?.filter { it.name != "$tag.apk" }?.forEach { it.delete() }
                val file = File(dir, "$tag.apk")
                if (!file.exists() || file.length() != apk.optLong("size")) download(apk.getString("browser_download_url"), file)
                val pm = context.packageManager
                val info = pm.getPackageArchiveInfo(file.path, 0) ?: return@runCatching State.Error("下載的檔案無法讀取")
                val mine = pm.getPackageInfo(context.packageName, 0)
                val theirs = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
                val ours = if (Build.VERSION.SDK_INT >= 28) mine.longVersionCode else @Suppress("DEPRECATION") mine.versionCode.toLong()
                if (theirs > ours) {
                    State.Available(tag, info.versionName ?: tag, file)
                } else {
                    file.delete()
                    State.UpToDate(tag, ours > theirs)
                }
            }.getOrElse { State.Error("檢查失敗：" + (it.message ?: it.javaClass.simpleName).take(40)) }
        }
    }

    /** 使用者按了「安裝」：交給系統的套件安裝程式（系統會再顯示確認畫面）。 */
    fun install(context: Context) {
        val s = state as? State.Available ?: return
        state = runCatching {
            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            params.setAppPackageName(context.packageName)
            val id = installer.createSession(params)
            installer.openSession(id).use { session ->
                session.openWrite("liuli.apk", 0, s.file.length()).use { out ->
                    s.file.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                val pi = PendingIntent.getBroadcast(
                    context, id, Intent(ACTION_STATUS).setPackage(context.packageName), flags,
                )
                session.commit(pi.intentSender)
            }
            State.Installing
        }.getOrElse { State.Error("無法安裝：" + (it.message ?: it.javaClass.simpleName).take(40)) }
    }

    /** 系統安裝程式回報的結果（需要使用者確認時，打開系統的確認畫面）。 */
    class StatusReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    @Suppress("DEPRECATION") val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                    if (confirm != null) context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                PackageInstaller.STATUS_SUCCESS -> Unit // 安裝完成後系統會重新啟動琉璃
                else -> state = State.Error(
                    "沒有安裝：" + (intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "已取消").take(40),
                )
            }
        }
    }

    private fun get(url: String): String {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10000; readTimeout = 15000
            setRequestProperty("Accept", "application/vnd.github+json")
        }
        try {
            if (c.responseCode !in 200..299) error("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }

    private fun download(url: String, to: File) {
        val tmp = File(to.path + ".part")
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10000; readTimeout = 30000; instanceFollowRedirects = true
        }
        try {
            if (c.responseCode !in 200..299) error("下載 HTTP ${c.responseCode}")
            c.inputStream.use { inp -> tmp.outputStream().use { inp.copyTo(it) } }
        } finally { c.disconnect() }
        if (!tmp.renameTo(to)) error("無法儲存下載檔")
    }
}
