package tw.liuli.launcher.data

import android.content.Context
import android.content.Intent
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager

/** 一個訊號源（HDMI、AV、電視直播…）。 */
data class TvSource(
    val id: String,
    val label: String,
    val type: Int,
    val isPassthrough: Boolean,
    val servicePackage: String,
) {
    val typeTag: String
        get() = when (type) {
            TvInputInfo.TYPE_HDMI -> "HDMI"
            TvInputInfo.TYPE_COMPOSITE -> "AV"
            TvInputInfo.TYPE_COMPONENT -> "色差"
            TvInputInfo.TYPE_VGA -> "VGA"
            TvInputInfo.TYPE_DVI -> "DVI"
            TvInputInfo.TYPE_DISPLAY_PORT -> "DP"
            TvInputInfo.TYPE_TUNER -> "電視"
            else -> "輸入"
        }
}

object TvSources {

    /**
     * 列出電視的訊號源。只取最上層（HDMI 埠本身），不含掛在埠下的 CEC 裝置。
     * Chromecast 這類電視盒通常回傳空清單。
     */
    fun list(context: Context): List<TvSource> {
        val manager = context.getSystemService(Context.TV_INPUT_SERVICE) as? TvInputManager
            ?: return emptyList()
        val inputs = runCatching { manager.tvInputList }.getOrDefault(emptyList())
        return inputs
            .filter { it.parentId == null && !it.isHidden(context) }
            .map { info ->
                TvSource(
                    id = info.id,
                    label = (info.loadCustomLabel(context) ?: info.loadLabel(context)).toString(),
                    type = info.type,
                    isPassthrough = info.isPassthroughInput,
                    servicePackage = info.serviceInfo.packageName,
                )
            }
            .sortedWith(compareBy<TvSource>({ order(it.type) }, { it.label }))
    }

    /** 切換到指定訊號源。回傳是否成功送出。 */
    fun open(context: Context, source: TvSource): Boolean {
        val uri = if (source.isPassthrough) {
            TvContract.buildChannelUriForPassthroughInput(source.id)
        } else {
            TvContract.buildChannelsUriForInput(source.id)
        }
        val view = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { context.startActivity(view) }.isSuccess) return true

        // 有些機型沒有處理這個 URI 的電視 App，退而開啟提供該訊號源的 App
        val fallback = context.packageManager.getLaunchIntentForPackage(source.servicePackage)
            ?: context.packageManager.getLeanbackLaunchIntentForPackage(source.servicePackage)
            ?: return false
        return runCatching {
            context.startActivity(fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.isSuccess
    }

    private fun order(type: Int) = when (type) {
        TvInputInfo.TYPE_TUNER -> 0
        TvInputInfo.TYPE_HDMI -> 1
        TvInputInfo.TYPE_COMPOSITE, TvInputInfo.TYPE_COMPONENT -> 2
        else -> 3
    }
}
