package com.marcorx6.library.data

/** Pure network gate shared by the worker and JVM unit tests. */
internal object DownloadNetworkPolicy {
    fun canStart(wifiOnly: Boolean, activeNetworkIsWifi: Boolean): Boolean =
        !wifiOnly || activeNetworkIsWifi
}
