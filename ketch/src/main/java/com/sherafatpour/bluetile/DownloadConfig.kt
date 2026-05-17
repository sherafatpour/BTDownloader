package com.sherafatpour.bluetile

import com.sherafatpour.bluetile.internal.utils.DownloadConst

data class DownloadConfig(
    val connectTimeOutInMs: Long = DownloadConst.DEFAULT_VALUE_CONNECT_TIMEOUT_MS,
    val readTimeOutInMs: Long = DownloadConst.DEFAULT_VALUE_READ_TIMEOUT_MS,
    val maxConcurrentDownloads: Int = 3
)
