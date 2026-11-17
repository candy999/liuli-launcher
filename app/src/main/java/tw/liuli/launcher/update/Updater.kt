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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 手動更新：只看「正式發布」的版本（GitHub Releases 的 latest，不含預覽版 nightly）。
 *
 * 全部都是使用者自己按才做——不會自動檢查、不會自動下載、更不會自動安裝（有些人就想保持舊的狀態）。
 * 設定 → 關於琉璃 只有一列「軟體更新」，從頭到尾都在同一列按確定：
 * 1. 檢查：讀最新正式版的資訊、下載 APK（約 2.6 MB，顯示進度），讀出它的「版本碼」和目前安裝的比
 *    （不比版本名稱：預覽版叫 0.1.93、正式版叫 1.0.0，名稱看起來新的其實可能是更舊的程式）
 * 2. 真的比較新：同一列變成「更新到 x.y.z」，再按一次交給系統的安裝畫面（系統還會再問一次確認；
 *    第一次會先請使用者允許琉璃安裝 App）
 * 3. 安裝完成後琉璃會被系統重新啟動，啟動時說一聲「已更新到 x.y.z」
 * 簽章和目前安裝的相同（同一把金鑰），所以是直接覆蓋更新、資料都保留。
 */
object Updater {
    sealed class State {
        object Idle : State()
        /** [progress]：下載進度 0..1；還不知道大小時是 null。 */
        data class Checking(val progress: Float?) : State()
        /** [newerInstalled]：目前安裝的比正式版還新（例如預覽版）。 */
        data class UpToDate(val tag: String, val newerInstalled: Boolean) : State()
        data class Available(val tag: String, val versionName: String, val file: File, val highlight: String?) : State()
        /** 已交給系統；[stalled]：等了一陣子系統畫面都沒出現或沒有回報，讓使用者可以再按一次。 */
        data class Installing(val target: Available, val stalled: Boolean) : State()
        /** [retry]：安裝失敗或取消時，同一個檔案可以直接再裝一次。 */
        data class Error(val message: String, val retry: Available? = null) : State()
    }

    var state by mutableStateOf<State>(State.Idle)
        private set

    private const val LATEST = "https://api.github.com/repos/candy999/liuli-launcher/releases/latest"
    const val PROJECT_URL = "https://github.com/candy999/liuli-launcher"
    private const val ACTION_STATUS = "tw.liuli.launcher.UPDATE_STATUS"
    private const val PREFS = "updater"
    private const val KEY_PENDING = "pending_version"
    private const val STALL_MS = 20_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var stallJob: Job? = null

    // ── 「軟體更新」這一列的三段文字：標題、說明、右側提示 ──

    fun title(): String = when (val s = state) {
        State.Idle -> "軟體更新"
        is State.Checking -> "正在檢查更新…"
        is State.UpToDate -> "已是最新版本"
        is State.Available -> "更新到 ${s.versionName}"
        is State.Installing -> "請在系統畫面按「安裝」"
        is State.Error -> if (s.retry != null) "沒有更新完成" else "檢查失敗"
    }

    fun subtitle(context: Context, current: String): String = when (val s = state) {
        State.Idle -> "目前 $current・只在你按下時檢查正式版，不會自動更新"
        is State.Checking -> s.progress?.let { "下載中 ${(it * 100).toInt()}%" } ?: "連線中…"
        is State.UpToDate ->
            if (s.newerInstalled) "目前 $current 比正式版 ${s.tag.removePrefix("v")} 還新" else "目前 $current"
        is State.Available -> s.highlight ?: "目前 $current・更新後設定都會保留"
        is State.Installing -> when {
            s.stalled -> "系統畫面沒有出現？按確定重新開啟"
            !canInstall(context) -> "第一次會先請你允許琉璃安裝 App，允許後按返回，再按「安裝」"
            else -> "安裝完成後琉璃會自動重新開啟，設定都會保留"
        }
        is State.Error -> s.message
    }

    fun hint(): String = when (val s = state) {
        State.Idle -> "按確定檢查"
        is State.Checking -> ""
        is State.UpToDate -> "按確定再檢查"
        is State.Available -> "按確定安裝"
        is State.Installing -> if (s.stalled) "按確定重試" else ""
        is State.Error -> "按確定重試"
    }

    /** 使用者在「軟體更新」這一列按了確定：依目前狀態做下一步。 */
    fun press(context: Context) {
        when (val s = state) {
            is State.Checking -> Unit
            is State.Available -> install(context, s)
            is State.Installing -> if (s.stalled) install(context, s.target)
            is State.Error -> if (s.retry != null) install(context, s.retry) else scope.launch { check(context) }
            else -> scope.launch { check(context) }
        }
    }

    /** 剛被更新過、這是新版第一次啟動：回傳新版名稱（只回傳一次）。 */
    fun consumeJustUpdated(context: Context, current: String): String? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val pending = prefs.getString(KEY_PENDING, null) ?: return null
        if (pending != current) return null // 還沒裝上（或裝的是別的版本）：先留著
        prefs.edit().remove(KEY_PENDING).apply()
        return pending
    }

    private fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()

    suspend fun check(context: Context) {
        if (state is State.Checking) return
        state = State.Checking(null)
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
                val size = apk.optLong("size")
                if (!file.exists() || file.length() != size) {
                    download(apk.getString("browser_download_url"), file, size) { state = State.Checking(it) }
                }
                val pm = context.packageManager
                val info = pm.getPackageArchiveInfo(file.path, 0) ?: return@runCatching State.Error("下載的檔案無法讀取")
                val mine = pm.getPackageInfo(context.packageName, 0)
                val theirs = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
                val ours = if (Build.VERSION.SDK_INT >= 28) mine.longVersionCode else @Suppress("DEPRECATION") mine.versionCode.toLong()
                if (theirs > ours) {
                    State.Available(tag, info.versionName ?: tag, file, highlight(json.optString("body")))
                } else {
                    file.delete()
                    State.UpToDate(tag, ours > theirs)
                }
            }.getOrElse { State.Error("檢查失敗：" + (it.message ?: it.javaClass.simpleName).take(40)) }
        }
    }

    /** 版本說明的第一個重點（第一個「- 」開頭的項目），去掉 Markdown 符號、截短成一行。 */
    private fun highlight(body: String): String? {
        val line = body.lineSequence().map { it.trim() }.firstOrNull { it.startsWith("- ") } ?: return null
        val plain = line.removePrefix("- ").replace("**", "").replace("`", "")
        val cut = plain.split('：', '，', '；', '。').first().trim()
        return cut.takeIf { it.isNotEmpty() }?.let { if (it.length > 28) it.take(27) + "…" else it }
    }

    /** 交給系統的套件安裝程式（系統會再顯示確認畫面）。 */
    private fun install(context: Context, target: State.Available) {
        stallJob?.cancel()
        val app = context.applicationContext
        state = runCatching {
            val installer = app.packageManager.packageInstaller
            // 之前沒有完成的安裝（例如系統畫面沒出現就離開了）先丟掉，避免越積越多
            installer.mySessions.forEach { runCatching { installer.abandonSession(it.sessionId) } }
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            params.setAppPackageName(app.packageName)
            val id = installer.createSession(params)
            installer.openSession(id).use { session ->
                session.openWrite("liuli.apk", 0, target.file.length()).use { out ->
                    target.file.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                // 一定要指名接收者：系統回報結果時是用這個 Intent 送廣播，
                // 只有動作名稱、沒有指名類別的話，沒有 intent-filter 的接收者收不到，確認畫面就永遠不會出現
                val intent = Intent(app, StatusReceiver::class.java).setAction(ACTION_STATUS)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                val pi = PendingIntent.getBroadcast(app, id, intent, flags)
                app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_PENDING, target.versionName).apply()
                session.commit(pi.intentSender)
            }
            State.Installing(target, stalled = false)
        }.getOrElse { State.Error("無法安裝：" + (it.message ?: it.javaClass.simpleName).take(40), target) }
        if (state is State.Installing) {
            stallJob = scope.launch {
                delay(STALL_MS)
                val s = state
                if (s is State.Installing && !s.stalled) state = s.copy(stalled = true)
            }
        }
    }

    /** 系統安裝程式回報的結果（需要使用者確認時，打開系統的確認畫面）。 */
    class StatusReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val target = (state as? State.Installing)?.target
            when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    @Suppress("DEPRECATION") val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                    if (confirm != null) context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                PackageInstaller.STATUS_SUCCESS -> Unit // 安裝完成後系統會重新啟動琉璃
                PackageInstaller.STATUS_FAILURE_ABORTED -> {
                    stallJob?.cancel()
                    state = State.Error("已取消安裝", target)
                }
                else -> {
                    stallJob?.cancel()
                    state = State.Error(
                        "沒有安裝：" + (intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "系統沒有說明原因").take(40),
                        target,
                    )
                }
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

    private fun download(url: String, to: File, size: Long, onProgress: (Float?) -> Unit) {
        val tmp = File(to.path + ".part")
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10000; readTimeout = 30000; instanceFollowRedirects = true
        }
        try {
            if (c.responseCode !in 200..299) error("下載 HTTP ${c.responseCode}")
            val total = if (size > 0) size else c.contentLengthLong
            c.inputStream.use { inp ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(32 * 1024)
                    var done = 0L
                    var lastPct = -1
                    while (true) {
                        val n = inp.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val pct = (done * 100 / total).toInt()
                            if (pct != lastPct) { lastPct = pct; onProgress(done.toFloat() / total) }
                        }
                    }
                }
            }
        } finally { c.disconnect() }
        if (!tmp.renameTo(to)) error("無法儲存下載檔")
    }
}
