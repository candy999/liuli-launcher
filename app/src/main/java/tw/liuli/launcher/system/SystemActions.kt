package tw.liuli.launcher.system

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/** 開啟系統設定、單一 App 設定等系統頁面。每個方法都回傳是否成功開啟。 */
object SystemActions {

    fun launch(context: Context, intent: Intent): Boolean =
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess

    fun systemSettings(context: Context) = launch(context, Intent(Settings.ACTION_SETTINGS))

    fun networkSettings(context: Context) =
        launch(context, Intent(Settings.ACTION_WIFI_SETTINGS)) || systemSettings(context)

    fun appInfo(context: Context, packageName: String) = launch(
        context,
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)),
    )

    fun uninstall(context: Context, packageName: String) = launch(
        context,
        Intent(Intent.ACTION_DELETE, Uri.fromParts("package", packageName, null)),
    )

    /** 預設主畫面選擇器；很多 Google TV 沒有這個頁面，失敗時改開系統設定。 */
    fun homeSettings(context: Context) =
        launch(context, Intent(Settings.ACTION_HOME_SETTINGS)) || systemSettings(context)
}
