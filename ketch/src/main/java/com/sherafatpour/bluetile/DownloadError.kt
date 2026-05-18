package com.sherafatpour.bluetile

/**
 * Stable error categories exposed to consumers.
 *
 * The original throwable message is still available on [DownloadModel.failureReason].
 */
enum class DownloadError {
    NONE,
    NETWORK,
    STORAGE,
    SERVER,
    CHECKSUM,
    FILE,
    CANCELLED,
    UNKNOWN
}
