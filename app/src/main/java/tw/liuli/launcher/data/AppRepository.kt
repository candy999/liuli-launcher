package tw.liuli.launcher.data

import android.content.Context
import android.content.Intent
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.Collator
import java.util.Locale

data class AppEntry(
    val packageName: String,
    val label: String,
    /** 電視 App 的 16:9 橫幅；沒有時為 null，改用 [icon]。 */
    val banner: ImageBitmap?,
    val icon: ImageBitmap?,
    /** 有提供 LEANBACK_LAUNCHER 入口（可直接用遙控器操作）。 */
    val isTvApp: Boolean,
    val launchIntent: Intent,
)

object AppRepository {

    // 橫幅縮到 320x180，約 230KB／張，低記憶體電視也撐得住
    private const val BANNER_W = 320
    private const val BANNER_H = 180
    private const val ICON_SIZE = 144

    suspend fun load(context: Context): List<AppEntry> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val result = LinkedHashMap<String, AppEntry>()

        fun query(category: String) =
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(category), 0)
                .map { it to category }

        val candidates = query(Intent.CATEGORY_LEANBACK_LAUNCHER) + query(Intent.CATEGORY_LAUNCHER)

        for ((info, category) in candidates) {
            val activity = info.activityInfo ?: continue
            val pkg = activity.packageName
            if (pkg == context.packageName || result.containsKey(pkg)) continue

            val isTv = category == Intent.CATEGORY_LEANBACK_LAUNCHER
            val intent = Intent(Intent.ACTION_MAIN)
                .addCategory(category)
                .setClassName(pkg, activity.name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)

            val banner = runCatching {
                (activity.loadBanner(pm) ?: activity.applicationInfo.loadBanner(pm))
                    ?.toBitmap(BANNER_W, BANNER_H)?.asImageBitmap()
            }.getOrNull()

            val icon = if (banner == null) {
                runCatching { info.loadIcon(pm).toBitmap(ICON_SIZE, ICON_SIZE).asImageBitmap() }.getOrNull()
            } else null

            result[pkg] = AppEntry(
                packageName = pkg,
                label = info.loadLabel(pm).toString(),
                banner = banner,
                icon = icon,
                isTvApp = isTv,
                launchIntent = intent,
            )
        }

        val collator = Collator.getInstance(Locale.TAIWAN)
        // 電視 App 排前面，其餘依名稱（中文依筆畫／注音規則）排序
        result.values.sortedWith(
            compareBy<AppEntry> { !it.isTvApp }.thenComparator { a, b -> collator.compare(a.label, b.label) }
        )
    }
}

/** 常用列（Dock）的順序，存在 SharedPreferences。 */
class DockStore(context: Context) {
    private val prefs = context.getSharedPreferences("dock", Context.MODE_PRIVATE)

    fun load(): List<String>? =
        prefs.getString(KEY, null)?.split(',')?.filter { it.isNotBlank() }

    fun save(packages: List<String>) {
        prefs.edit().putString(KEY, packages.joinToString(",")).apply()
    }

    companion object {
        private const val KEY = "order"
        const val MAX = 7

        /** 第一次啟動時的預設常用列：先挑常見串流 App，不足再補。 */
        private val PREFERRED = listOf(
            "com.google.android.youtube.tv",
            "com.netflix.ninja",
            "com.disney.disneyplus",
            "com.amazon.amazonvideo.livingroom",
            "com.google.android.apps.youtube.music",
            "com.fetnet.friday.tv",
            "net.fetnet.fetvod.tv",
            "com.kktv.kktv.tv",
        )

        fun defaultFor(apps: List<AppEntry>): List<String> {
            val installed = apps.map { it.packageName }.toSet()
            val picks = PREFERRED.filter { it in installed }.toMutableList()
            for (app in apps) {
                if (picks.size >= 5) break
                if (app.isTvApp && app.packageName !in picks) picks += app.packageName
            }
            return picks.take(5)
        }
    }
}
