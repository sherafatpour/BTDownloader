package com.sherafatpour.bluetile

import com.sherafatpour.bluetile.internal.utils.DownloadConst

/**
 * Tuning for the download engine.
 *
 * The `progressInterval*` / `minProgressBytes*` / `threshold*` values control how often progress is
 * persisted and published. They are size-aware because a 20 MB file and a 4 GB file need very
 * different cadences: one interval that suits a small file turns into thousands of redundant Room
 * writes on a large one.
 */
data class DownloadConfig(
    val connectTimeOutInMs: Long = DownloadConst.DEFAULT_VALUE_CONNECT_TIMEOUT_MS,
    val readTimeOutInMs: Long = DownloadConst.DEFAULT_VALUE_READ_TIMEOUT_MS,
    val maxConcurrentDownloads: Int = 3,
    val speedLimitBytesPerSecond: Long = 0L,
    val freeSpaceBufferBytes: Long = 10L * 1024L * 1024L,
    val incompleteDownloadMaxAgeInMs: Long = 7L * 24L * 60L * 60L * 1000L,
    val progressIntervalSmallMs: Long = 500L,
    val progressIntervalMediumMs: Long = 1000L,
    val progressIntervalLargeMs: Long = 3000L,
    val progressIntervalXLargeMs: Long = 5000L,
    val minProgressBytesSmall: Long = 256 * 1024L,
    val minProgressBytesMedium: Long = 1 * 1024 * 1024L,
    val minProgressBytesLarge: Long = 5 * 1024 * 1024L,
    val minProgressBytesXLarge: Long = 10 * 1024 * 1024L,
    val thresholdSmallBytes: Long = 20L * 1024 * 1024L,
    val thresholdMediumBytes: Long = 200L * 1024 * 1024L,
    val thresholdLargeBytes: Long = 2L * 1024 * 1024 * 1024L
)
