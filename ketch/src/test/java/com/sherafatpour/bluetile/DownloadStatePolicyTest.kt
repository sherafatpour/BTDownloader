package com.sherafatpour.bluetile

import com.sherafatpour.bluetile.internal.download.DownloadStatePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadStatePolicyTest {

    @Test
    fun pauseResumeContinuesFromPartialByKeepingResumeAsStateTransition() {
        assertEquals(
            DownloadStatePolicy.StartNowAction.RESUME_FROM_PAUSED,
            DownloadStatePolicy.startNowActionFor(Status.PAUSED.toString())
        )
    }

    @Test
    fun resumeEmitsNonPausedStateByTransitioningToQueued() {
        val initial = DownloadStatePolicy.initialStatusForRequest(isFutureScheduled = false)
        assertEquals(Status.QUEUED.toString(), initial)
    }

    @Test
    fun startNowOnPausedDoesNotRestart() {
        assertEquals(
            DownloadStatePolicy.StartNowAction.RESUME_FROM_PAUSED,
            DownloadStatePolicy.startNowActionFor(Status.PAUSED.toString())
        )
    }

    @Test
    fun startNowOnScheduledStartsImmediately() {
        assertEquals(
            DownloadStatePolicy.StartNowAction.START_IMMEDIATELY,
            DownloadStatePolicy.startNowActionFor(Status.SCHEDULED.toString())
        )
    }

    @Test
    fun immediateDownloadIsNotScheduled() {
        assertFalse(DownloadStatePolicy.isFutureScheduled(null, nowMs = 1_000L))
        assertEquals(0L, DownloadStatePolicy.normalizedScheduledAt(9_999L, isFutureScheduled = false))
        assertEquals(Status.QUEUED.toString(), DownloadStatePolicy.initialStatusForRequest(false))
    }

    @Test
    fun queuedAppearsOnlyWhenCapacityIsFull() {
        assertEquals(0, DownloadStatePolicy.availableSlots(maxConcurrentDownloads = 3, activeCount = 3))
        assertTrue(DownloadStatePolicy.availableSlots(maxConcurrentDownloads = 3, activeCount = 2) > 0)
    }

    @Test
    fun startNowTerminalStatusesAreNoOp() {
        assertEquals(
            DownloadStatePolicy.StartNowAction.NO_OP_TERMINAL,
            DownloadStatePolicy.startNowActionFor(Status.SUCCESS.toString())
        )
        assertEquals(
            DownloadStatePolicy.StartNowAction.NO_OP_TERMINAL,
            DownloadStatePolicy.startNowActionFor(Status.FAILED.toString())
        )
        assertEquals(
            DownloadStatePolicy.StartNowAction.NO_OP_TERMINAL,
            DownloadStatePolicy.startNowActionFor(Status.CANCELLED.toString())
        )
    }
}
