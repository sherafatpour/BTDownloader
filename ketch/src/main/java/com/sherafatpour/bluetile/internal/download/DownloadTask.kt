package com.sherafatpour.bluetile.internal.download

import com.sherafatpour.bluetile.internal.network.DownloadService
import com.sherafatpour.bluetile.internal.utils.DownloadConst
import com.sherafatpour.bluetile.internal.utils.FileUtil
import kotlinx.coroutines.delay
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

internal class DownloadTask(
    private var url: String,
    private var path: String,
    private var fileName: String,
    private val downloadService: DownloadService,
) {

    companion object {
        private const val VALUE_200 = 200
        private const val VALUE_299 = 299
        private const val TIME_TO_TRIGGER_PROGRESS = 1500L

        // A socket read returns only what has arrived, so on a slow link these are small. The
        // write buffer is the one that has to be large: it coalesces those small reads into few
        // large write(2) calls, which is what keeps multi-GB downloads off the syscall path.
        private const val READ_BUFFER_SIZE = 128 * 1024
        private const val WRITE_BUFFER_SIZE = 1024 * 1024
    }

    suspend fun download(
        headers: MutableMap<String, String> = mutableMapOf(),
        speedLimitBytesPerSecond: Long = 0L,
        progressIntervalMsProvider: (Long) -> Long = { TIME_TO_TRIGGER_PROGRESS },
        onStart: suspend (Long) -> Unit,
        onProgress: suspend (Long, Long, Float) -> Unit
    ): Long {

        var rangeStart = 0L
        val directory = File(path)
        if (!directory.exists() && !directory.mkdirs()) {
            throw IOException("Failed to create download directory: $path")
        }
        if (!directory.isDirectory) {
            throw IOException("Download path is not a directory: $path")
        }

        val file = File(directory, fileName)

        if (file.exists()) {
            rangeStart = file.length()
        }

        if (rangeStart != 0L) {
            headers[DownloadConst.RANGE_HEADER] = "bytes=$rangeStart-"
        }
        addDefaultHeaders(headers)

        var response = downloadService.getUrl(url, headers)
        if (shouldRestartDownload(responseCode = response.code(), rangeStart = rangeStart)
        ) {
            FileUtil.deleteFileIfExists(path, fileName)
            headers.remove(DownloadConst.RANGE_HEADER)
            rangeStart = 0
            response = downloadService.getUrl(url, headers)
        }

        val responseBody = response.body()

        if (response.code() !in VALUE_200..VALUE_299) {
            throw DownloadHttpException(
                response.code(),
                "HTTP ${response.code()} while downloading $fileName"
            )
        }

        if (responseBody == null) {
            throw DownloadHttpException(
                response.code(),
                "Empty response body while downloading $fileName"
            )
        }

        var totalBytes = responseBody.contentLength()

        var progressBytes = 0L

        if (totalBytes >= 0) {
            totalBytes += rangeStart
        }

        val out = FileOutputStream(file, rangeStart != 0L)
        val progressIntervalMs = progressIntervalMsProvider(totalBytes).coerceAtLeast(1L)

        responseBody.byteStream().use { inputStream ->
            BufferedOutputStream(out, WRITE_BUFFER_SIZE).use { outputStream ->

                if (rangeStart != 0L) {
                    progressBytes = rangeStart
                }

                onStart.invoke(totalBytes.coerceAtLeast(0L))

                val buffer = ByteArray(READ_BUFFER_SIZE)
                var bytes = inputStream.read(buffer)
                var tempBytes = 0L
                var progressInvokeTime = System.currentTimeMillis()
                var speed: Float

                while (bytes >= 0) {

                    outputStream.write(buffer, 0, bytes)
                    progressBytes += bytes
                    tempBytes += bytes
                    throttleIfNeeded(
                        speedLimitBytesPerSecond = speedLimitBytesPerSecond,
                        bytesWrittenInWindow = tempBytes,
                        windowStartedAt = progressInvokeTime
                    )
                    bytes = inputStream.read(buffer)
                    val finalTime = System.currentTimeMillis()
                    if (finalTime - progressInvokeTime >= progressIntervalMs) {

                        speed = tempBytes.toFloat() / ((finalTime - progressInvokeTime).toFloat())
                        tempBytes = 0L
                        progressInvokeTime = System.currentTimeMillis()
                        if (totalBytes > 0 && progressBytes > totalBytes) progressBytes = totalBytes
                        onProgress.invoke(
                            progressBytes,
                            totalBytes.coerceAtLeast(0L),
                            speed
                        )
                    }
                }
                onProgress.invoke(
                    if (totalBytes > 0) totalBytes else progressBytes,
                    totalBytes.coerceAtLeast(progressBytes),
                    0F
                )
            }
        }

        return totalBytes.coerceAtLeast(progressBytes)
    }

    private fun addDefaultHeaders(headers: MutableMap<String, String>) {
        putHeaderIfAbsent(headers, DownloadConst.ACCEPT_HEADER, "*/*")
        putHeaderIfAbsent(headers, DownloadConst.ACCEPT_ENCODING_HEADER, "identity")
        putHeaderIfAbsent(headers, DownloadConst.USER_AGENT_HEADER, "Mozilla/5.0 (Linux; Android) BTDownloader/1.0")
    }

    private fun putHeaderIfAbsent(headers: MutableMap<String, String>, key: String, value: String) {
        if (!headers.containsKey(key)) {
            headers[key] = value
        }
    }

    private fun shouldRestartDownload(responseCode: Int, rangeStart: Long): Boolean {
        return rangeStart > 0L &&
            (responseCode == DownloadConst.HTTP_RANGE_NOT_SATISFY || responseCode == DownloadConst.HTTP_OK)
    }

    private suspend fun throttleIfNeeded(
        speedLimitBytesPerSecond: Long,
        bytesWrittenInWindow: Long,
        windowStartedAt: Long
    ) {
        if (speedLimitBytesPerSecond <= 0L || bytesWrittenInWindow <= 0L) return
        val elapsedMs = System.currentTimeMillis() - windowStartedAt
        val expectedElapsedMs = (bytesWrittenInWindow * 1000L) / speedLimitBytesPerSecond
        val sleepMs = expectedElapsedMs - elapsedMs
        if (sleepMs > 0L) {
            delay(sleepMs.coerceAtMost(250L))
        }
    }
}

internal class DownloadHttpException(
    val code: Int,
    message: String
) : IOException(message)
