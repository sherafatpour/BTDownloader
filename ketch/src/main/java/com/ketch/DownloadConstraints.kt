package com.ketch

data class DownloadConstraints(
    val networkType: KetchNetworkType = KetchNetworkType.CONNECTED,
    val requiresCharging: Boolean = false,
    val requiresBatteryNotLow: Boolean = false,
    val requiresStorageNotLow: Boolean = false
)

enum class KetchNetworkType {
    ANY,
    CONNECTED,
    UNMETERED,
    NOT_ROAMING
}
