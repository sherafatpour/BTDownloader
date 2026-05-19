package com.sherafatpour.bluetile.internal.download

import com.sherafatpour.bluetile.Status

internal object DownloadStatePolicy {

    enum class StartNowAction {
        RESUME_FROM_PAUSED,
        PRIORITIZE_ACTIVE,
        START_IMMEDIATELY,
        NO_OP_TERMINAL
    }

    fun isFutureScheduled(scheduledAtEpochMs: Long?, nowMs: Long = System.currentTimeMillis()): Boolean {
        return scheduledAtEpochMs?.let { it > nowMs } == true
    }

    fun initialStatusForRequest(isFutureScheduled: Boolean): String {
        return if (isFutureScheduled) Status.SCHEDULED.toString() else Status.QUEUED.toString()
    }

    fun normalizedScheduledAt(scheduledAtEpochMs: Long?, isFutureScheduled: Boolean): Long {
        return if (isFutureScheduled) scheduledAtEpochMs ?: 0L else 0L
    }

    fun startNowActionFor(status: String): StartNowAction {
        return when (status) {
            Status.PAUSED.toString() -> StartNowAction.RESUME_FROM_PAUSED
            Status.STARTED.toString(), Status.PROGRESS.toString() -> StartNowAction.PRIORITIZE_ACTIVE
            Status.QUEUED.toString(), Status.SCHEDULED.toString(), Status.DEFAULT.toString() -> StartNowAction.START_IMMEDIATELY
            Status.SUCCESS.toString(), Status.FAILED.toString(), Status.CANCELLED.toString() -> StartNowAction.NO_OP_TERMINAL
            else -> StartNowAction.NO_OP_TERMINAL
        }
    }

    fun availableSlots(maxConcurrentDownloads: Int, activeCount: Int): Int {
        return (maxConcurrentDownloads - activeCount).coerceAtLeast(0)
    }
}
