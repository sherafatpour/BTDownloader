package com.ketch

data class RetryPolicy(
    val maxRetries: Int = 3,
    val backoffDelayInMs: Long = 10_000L,
    val backoffPolicy: KetchBackoffPolicy = KetchBackoffPolicy.EXPONENTIAL
)

enum class KetchBackoffPolicy {
    LINEAR,
    EXPONENTIAL
}
