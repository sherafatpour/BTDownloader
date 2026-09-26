package com.sherafatpour.bluetile.internal.download

import com.sherafatpour.bluetile.DownloadConfig

/**
 * Decides how often a running download persists and publishes its progress.
 *
 * Every report costs a Room read, a Room write, a WorkManager progress write and a notification
 * rebuild. A progress bar has 100 steps, so reporting more often than that buys nothing and on a
 * multi-GB download it is thousands of writes competing with the download itself.
 */
internal object ProgressReportPolicy {

    const val MAX_PERCENT = 100
    const val MIN_PROGRESS_PERCENT_DELTA = 1

    data class Tuning(
        val intervalMs: Long,
        val minBytesDelta: Long
    )

    fun tuningFor(config: DownloadConfig, totalBytes: Long): Tuning {
        return when {
            totalBytes <= config.thresholdSmallBytes ->
                Tuning(config.progressIntervalSmallMs, config.minProgressBytesSmall)
            totalBytes <= config.thresholdMediumBytes ->
                Tuning(config.progressIntervalMediumMs, config.minProgressBytesMedium)
            totalBytes <= config.thresholdLargeBytes ->
                Tuning(config.progressIntervalLargeMs, config.minProgressBytesLarge)
            else ->
                Tuning(config.progressIntervalXLargeMs, config.minProgressBytesXLarge)
        }
    }

    fun percentOf(downloadedBytes: Long, totalBytes: Long): Int {
        if (totalBytes <= 0L) return 0
        return ((downloadedBytes * MAX_PERCENT) / totalBytes).toInt()
    }

    /**
     * [totalBytes] is 0 when the server sends no Content-Length. The percent then stays 0 for the
     * whole download, so the byte-delta rule is the only thing that keeps it observable.
     */
    fun shouldReport(
        hasReported: Boolean,
        percent: Int,
        lastReportedPercent: Int,
        downloadedBytes: Long,
        lastReportedBytes: Long,
        totalBytes: Long,
        minBytesDelta: Long
    ): Boolean {
        if (!hasReported) return true
        if (totalBytes > 0L && percent >= MAX_PERCENT) return true
        if (percent - lastReportedPercent >= MIN_PROGRESS_PERCENT_DELTA) return true
        return downloadedBytes - lastReportedBytes >= minBytesDelta.coerceAtLeast(1L)
    }
}
