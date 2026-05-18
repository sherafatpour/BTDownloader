package com.sherafatpour.bluetile

import com.sherafatpour.bluetile.internal.utils.DownloadConst

data class DownloadConfig(
    val connectTimeOutInMs: Long = DownloadConst.DEFAULT_VALUE_CONNECT_TIMEOUT_MS,
    val readTimeOutInMs: Long = DownloadConst.DEFAULT_VALUE_READ_TIMEOUT_MS,
    val maxConcurrentDownloads: Int = 3,
    val speedLimitBytesPerSecond: Long = 0L,
    val freeSpaceBufferBytes: Long = 10L * 1024L * 1024L,
    val incompleteDownloadMaxAgeInMs: Long = 7L * 24L * 60L * 60L * 1000L
)
