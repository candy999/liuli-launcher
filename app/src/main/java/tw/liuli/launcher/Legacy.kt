package tw.liuli.launcher

import android.os.Build

/**
 * Android 5.0～7.1 專用版（android5 分支）。主線只支援 Android 8.0 以上；這條分支定案後不再加功能。
 *
 * 在 Android 8 以下：背景固定純色（或純黑），不開背景模糊、細緻噪點，沒有軟體更新
 * （正式版要 Android 8 以上才裝得起來）；Android 5.0 沒有無障礙疊加層，螢幕小時鐘也關掉。
 * 裝在 Android 8 以上的機器上則和正式版一樣。
 */
object Legacy {
    /** 這台機器是 Android 8 以下：用簡化的畫面。 */
    val on: Boolean = Build.VERSION.SDK_INT < 26

    /** 螢幕小時鐘要用無障礙疊加層（Android 5.1 起）。 */
    val clockSupported: Boolean = Build.VERSION.SDK_INT >= 22

    /** 目前連上網路的那一個網路。Android 6 起直接問系統；之前從所有網路裡找「已連線、能上網」的那個。 */
    fun activeNetwork(cm: android.net.ConnectivityManager): android.net.Network? =
        if (Build.VERSION.SDK_INT >= 23) cm.activeNetwork
        else @Suppress("DEPRECATION") cm.allNetworks.firstOrNull { n ->
            cm.getNetworkInfo(n)?.isConnected == true &&
                cm.getNetworkCapabilities(n)?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        }
}
