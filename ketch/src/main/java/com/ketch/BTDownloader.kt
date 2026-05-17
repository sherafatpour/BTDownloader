package com.ketch

/**
 * Product-named entry point for BTDownloader.
 *
 * Ketch remains available as the binary-compatible implementation class for existing callers.
 */
object BTDownloader {
    @JvmStatic
    fun builder() = Ketch.builder()
}
