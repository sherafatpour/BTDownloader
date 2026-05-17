package com.sherafatpour.bluetile

data class DownloadConstraints(
    val networkType: BTDownloaderNetworkType = BTDownloaderNetworkType.CONNECTED,
    val requiresCharging: Boolean = false,
    val requiresBatteryNotLow: Boolean = false,
    val requiresStorageNotLow: Boolean = false
)

enum class BTDownloaderNetworkType {
    ANY,
    CONNECTED,
    UNMETERED,
    NOT_ROAMING
}
