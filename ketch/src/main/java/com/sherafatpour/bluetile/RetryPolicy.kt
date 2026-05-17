package com.sherafatpour.bluetile

data class RetryPolicy(
    val maxRetries: Int = 3,
    val backoffDelayInMs: Long = 10_000L,
    val backoffPolicy: BTDownloaderBackoffPolicy = BTDownloaderBackoffPolicy.EXPONENTIAL
)

enum class BTDownloaderBackoffPolicy {
    LINEAR,
    EXPONENTIAL
}
