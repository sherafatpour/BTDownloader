package com.sherafatpour.bluetile

import com.sherafatpour.bluetile.internal.download.ProgressReportPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressReportPolicyTest {

    private val config = DownloadConfig()

    @Test
    fun smallFileGetsTheFastestCadence() {
        val tuning = ProgressReportPolicy.tuningFor(config, 5L * 1024 * 1024)
        assertEquals(config.progressIntervalSmallMs, tuning.intervalMs)
        assertEquals(config.minProgressBytesSmall, tuning.minBytesDelta)
    }

    @Test
    fun multiGigabyteFileGetsTheSlowestCadence() {
        val tuning = ProgressReportPolicy.tuningFor(config, 4L * 1024 * 1024 * 1024)
        assertEquals(config.progressIntervalXLargeMs, tuning.intervalMs)
        assertEquals(config.minProgressBytesXLarge, tuning.minBytesDelta)
    }

    @Test
    fun thresholdBoundaryStaysInTheLowerBand() {
        val tuning = ProgressReportPolicy.tuningFor(config, config.thresholdSmallBytes)
        assertEquals(config.progressIntervalSmallMs, tuning.intervalMs)
    }

    @Test
    fun unknownLengthReportsZeroPercentInsteadOfDividingByZero() {
        assertEquals(0, ProgressReportPolicy.percentOf(downloadedBytes = 1_000L, totalBytes = 0L))
    }

    @Test
    fun firstTickAlwaysReports() {
        assertTrue(
            ProgressReportPolicy.shouldReport(
                hasReported = false,
                percent = 0,
                lastReportedPercent = 0,
                downloadedBytes = 0L,
                lastReportedBytes = 0L,
                totalBytes = 1_000L,
                minBytesDelta = 1_000_000L
            )
        )
    }

    @Test
    fun tinyGainBelowBothThresholdsIsSuppressed() {
        assertFalse(
            ProgressReportPolicy.shouldReport(
                hasReported = true,
                percent = 40,
                lastReportedPercent = 40,
                downloadedBytes = 400_100L,
                lastReportedBytes = 400_000L,
                totalBytes = 1_000_000L,
                minBytesDelta = 1_000_000L
            )
        )
    }

    @Test
    fun onePercentGainReports() {
        assertTrue(
            ProgressReportPolicy.shouldReport(
                hasReported = true,
                percent = 41,
                lastReportedPercent = 40,
                downloadedBytes = 410_000L,
                lastReportedBytes = 400_000L,
                totalBytes = 1_000_000L,
                minBytesDelta = 1_000_000L
            )
        )
    }

    @Test
    fun completionAlwaysReportsEvenWhenTheDeltaIsTiny() {
        assertTrue(
            ProgressReportPolicy.shouldReport(
                hasReported = true,
                percent = 100,
                lastReportedPercent = 100,
                downloadedBytes = 1_000_000L,
                lastReportedBytes = 999_999L,
                totalBytes = 1_000_000L,
                minBytesDelta = 1_000_000L
            )
        )
    }

    @Test
    fun unknownLengthFallsBackToTheByteDeltaRule() {
        val args = { downloaded: Long ->
            ProgressReportPolicy.shouldReport(
                hasReported = true,
                percent = 0,
                lastReportedPercent = 0,
                downloadedBytes = downloaded,
                lastReportedBytes = 0L,
                totalBytes = 0L,
                minBytesDelta = 1_000L
            )
        }
        assertFalse(args(999L))
        assertTrue(args(1_000L))
    }

    @Test
    fun zeroMinBytesDeltaDoesNotReportOnEveryIdenticalTick() {
        assertFalse(
            ProgressReportPolicy.shouldReport(
                hasReported = true,
                percent = 0,
                lastReportedPercent = 0,
                downloadedBytes = 500L,
                lastReportedBytes = 500L,
                totalBytes = 0L,
                minBytesDelta = 0L
            )
        )
    }
}
