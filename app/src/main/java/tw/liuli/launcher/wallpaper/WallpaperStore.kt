package tw.liuli.launcher.wallpaper

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * 桌布來源：
 * - Liquid：原生流動色塊
 * - Builtin：App 內建的網頁桌布（assets/wallpapers/<name>/）
 * - Url：任意網址（線上）
 * - Zip：匯入後解壓到 App 內部空間的離線網頁包
 */
sealed class WallpaperSource {
    data object Liquid : WallpaperSource()
    data class Builtin(val name: String) : WallpaperSource()
    data class Url(val url: String) : WallpaperSource()
    data class Zip(val id: String) : WallpaperSource()

    fun encode(): String = when (this) {
        Liquid -> "liquid"
        is Builtin -> "builtin:$name"
        is Url -> "url:$url"
        is Zip -> "zip:$id"
    }

    companion object {
        fun decode(raw: String?): WallpaperSource = when {
            raw == null -> Builtin("liuli-sky")
            raw == "liquid" -> Liquid
            raw.startsWith("builtin:") -> Builtin(raw.removePrefix("builtin:"))
            raw.startsWith("url:") -> Url(raw.removePrefix("url:"))
            raw.startsWith("zip:") -> Zip(raw.removePrefix("zip:"))
            else -> Liquid
        }
    }
}

/** 已安裝的離線網頁包。 */
data class WallpaperPack(val id: String, val title: String, val entry: String)

/** 啟動器設定（Compose 可觀察）。 */
class LauncherSettings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var wallpaper by mutableStateOf(WallpaperSource.decode(prefs.getString("wallpaper", null)))
        private set
    var cityId by mutableStateOf(prefs.getString("city", "taipei") ?: "taipei")
        private set
    /** 網頁桌布可以接收遙控器按鍵事件（liuli:key）。 */
    var forwardKeys by mutableStateOf(prefs.getBoolean("forward_keys", true))
        private set

    fun updateWallpaper(source: WallpaperSource) {
        wallpaper = source
        prefs.edit().putString("wallpaper", source.encode()).apply()
    }

    fun updateCity(id: String) {
        cityId = id
        prefs.edit().putString("city", id).apply()
    }

    fun updateForwardKeys(on: Boolean) {
        forwardKeys = on
        prefs.edit().putBoolean("forward_keys", on).apply()
    }
}

object WallpaperPacks {

    /** WebViewAssetLoader 的虛擬網域；所有本機桌布都從這裡載入，等同一般 https 網站。 */
    const val HOST = "appassets.androidplatform.net"
    private const val MAX_ZIP_BYTES = 200L * 1024 * 1024
    private const val MAX_ENTRIES = 5000

    fun packsDir(context: Context) = File(context.filesDir, "wallpapers").apply { mkdirs() }

    /** 給使用者用 ADB 或檔案管理員放 zip 的資料夾（不需要任何儲存權限）。 */
    fun dropDir(context: Context): File? =
        context.getExternalFilesDir("wallpapers")?.apply { mkdirs() }

    fun builtinNames(context: Context): List<String> =
        context.assets.list("wallpapers")?.filter { name ->
            context.assets.list("wallpapers/$name")?.contains("index.html") == true
        }?.sorted() ?: emptyList()

    fun installed(context: Context): List<WallpaperPack> =
        packsDir(context).listFiles()?.filter { it.isDirectory }?.mapNotNull { dir ->
            val entry = findEntry(dir) ?: return@mapNotNull null
            WallpaperPack(dir.name, readTitle(File(dir, entry)) ?: dir.name, entry)
        }?.sortedBy { it.title } ?: emptyList()

    /** 組出要載入的網址（尚未加情境參數）。 */
    fun baseUrl(context: Context, source: WallpaperSource): String? = when (source) {
        WallpaperSource.Liquid -> null
        is WallpaperSource.Builtin -> "https://$HOST/assets/wallpapers/${source.name}/index.html"
        is WallpaperSource.Url -> source.url
        is WallpaperSource.Zip -> {
            val dir = File(packsDir(context), source.id)
            findEntry(dir)?.let { "https://$HOST/local/${source.id}/$it" }
        }
    }

    /** 從網址下載 zip 並安裝。回傳新包的 id。 */
    suspend fun installFromUrl(context: Context, url: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = true
            }
            try {
                if (conn.responseCode !in 200..299) error("下載失敗（HTTP ${conn.responseCode}）")
                val name = Uri.parse(url).lastPathSegment?.removeSuffix(".zip") ?: "wallpaper"
                conn.inputStream.use { install(context, it, name) }
            } finally {
                conn.disconnect()
            }
        }
    }

    /** 安裝放在 Android/data/tw.liuli.launcher/files/wallpapers/ 的 zip。回傳成功安裝的數量。 */
    suspend fun installDropped(context: Context): Int = withContext(Dispatchers.IO) {
        val files = dropDir(context)?.listFiles { f -> f.extension.equals("zip", true) } ?: return@withContext 0
        var count = 0
        for (zip in files) {
            val ok = runCatching { zip.inputStream().use { install(context, it, zip.nameWithoutExtension) } }.isSuccess
            if (ok) {
                zip.delete()
                count++
            }
        }
        count
    }

    fun remove(context: Context, id: String) {
        File(packsDir(context), id).deleteRecursively()
    }

    private fun install(context: Context, input: InputStream, suggestedName: String): String {
        // 同名的包直接覆蓋，方便更新桌布
        val id = sanitize(suggestedName)
        val root = packsDir(context)
        val tmp = File(root, ".$id.tmp").apply { deleteRecursively(); mkdirs() }
        var total = 0L
        var entries = 0
        try {
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (++entries > MAX_ENTRIES) error("檔案數量過多")
                    val out = File(tmp, entry.name)
                    // 防止 zip slip：解壓路徑必須留在目標資料夾內
                    if (!out.canonicalPath.startsWith(tmp.canonicalPath + File.separator)) {
                        error("zip 內含不安全的路徑：${entry.name}")
                    }
                    if (entry.isDirectory) {
                        out.mkdirs()
                    } else {
                        out.parentFile?.mkdirs()
                        out.outputStream().use { o ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = zip.read(buf)
                                if (n < 0) break
                                total += n
                                if (total > MAX_ZIP_BYTES) error("解壓後超過 200MB")
                                o.write(buf, 0, n)
                            }
                        }
                    }
                }
            }
            if (findEntry(tmp) == null) error("zip 裡找不到 index.html")
            val target = File(root, id)
            target.deleteRecursively()
            if (!tmp.renameTo(target)) error("無法寫入桌布資料夾")
            return id
        } catch (e: Throwable) {
            tmp.deleteRecursively()
            throw e
        }
    }

    /** index.html 可以在 zip 根目錄，或在唯一的一層子資料夾裡（直接壓縮資料夾時常見）。 */
    private fun findEntry(dir: File): String? {
        if (File(dir, "index.html").isFile) return "index.html"
        val children = dir.listFiles()?.filter { !it.name.startsWith(".") && it.name != "__MACOSX" } ?: return null
        val only = children.singleOrNull { it.isDirectory } ?: return null
        return if (File(only, "index.html").isFile) "${only.name}/index.html" else null
    }

    private fun readTitle(file: File): String? = runCatching {
        val head = file.bufferedReader().use { r -> CharArray(4096).let { buf -> String(buf, 0, r.read(buf).coerceAtLeast(0)) } }
        Regex("<title>(.*?)</title>", RegexOption.IGNORE_CASE).find(head)?.groupValues?.get(1)?.trim()
    }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun sanitize(name: String) =
        name.lowercase().replace(Regex("[^a-z0-9._-]"), "-").trim('-', '.').ifBlank { "wallpaper" }.take(40)
}
