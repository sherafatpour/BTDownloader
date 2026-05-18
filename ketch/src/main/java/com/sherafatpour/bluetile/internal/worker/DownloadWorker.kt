package com.sherafatpour.bluetile.internal.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.sherafatpour.bluetile.DownloadConfig
import com.sherafatpour.bluetile.DownloadError
import com.sherafatpour.bluetile.NotificationConfig
import com.sherafatpour.bluetile.Status
import com.sherafatpour.bluetile.internal.database.DatabaseInstance
import com.sherafatpour.bluetile.internal.download.DownloadTask
import com.sherafatpour.bluetile.internal.download.ApiResponseHeaderChecker
import com.sherafatpour.bluetile.internal.download.DownloadWorkCoordinator
import com.sherafatpour.bluetile.internal.network.RetrofitInstance
import com.sherafatpour.bluetile.internal.notification.DownloadNotificationManager
import com.sherafatpour.bluetile.internal.utils.NotificationConst
import com.sherafatpour.bluetile.internal.utils.DownloadConst
import com.sherafatpour.bluetile.internal.utils.ExceptionConst
import com.sherafatpour.bluetile.internal.utils.FileUtil
import com.sherafatpour.bluetile.internal.utils.UserAction
import com.sherafatpour.bluetile.internal.utils.WorkUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import java.io.File
import java.io.IOException

internal class DownloadWorker(
    private val context: Context,
    private val workerParameters: WorkerParameters
) :
    CoroutineWorker(context, workerParameters) {

    companion object {
        private const val MAX_PERCENT = 100
    }

    private var downloadNotificationManager: DownloadNotificationManager? = null
    private val downloadDao = DatabaseInstance.getInstance(context).downloadDao()

    override suspend fun doWork(): Result {

        val downloadRequest =
            WorkUtil.jsonToDownloadRequest(
                inputData.getString(DownloadConst.KEY_DOWNLOAD_REQUEST)
                    ?: return Result.failure(
                        workDataOf(ExceptionConst.KEY_EXCEPTION to ExceptionConst.EXCEPTION_FAILED_DESERIALIZE)
                    )
            )

        val downloadConfig =
            WorkUtil.jsonToDownloadConfig(
                inputData.getString(DownloadConst.KEY_DOWNLOAD_CONFIG) ?: ""
            )

        val notificationConfig =
            WorkUtil.jsonToNotificationConfig(
                inputData.getString(DownloadConst.KEY_NOTIFICATION_CONFIG) ?: ""
            )

        val id = downloadRequest.id
        val url = downloadRequest.url
        val dirPath = downloadRequest.path
        val finalFileName = downloadRequest.fileName
        val tempFileName = FileUtil.getTempFileName(finalFileName)
        val headers = downloadRequest.headers.toMutableMap()
        val tag = downloadRequest.tag
        val notificationTitle = downloadRequest.notificationTitle
        val notificationParameter = downloadRequest.notificationParameter

        if (notificationConfig.enabled) {
            downloadNotificationManager = DownloadNotificationManager(
                context = context,
                notificationConfig = notificationConfig,
                requestId = id,
                fileName = notificationTitle.ifEmpty { finalFileName },
                notificationParameter = notificationParameter
            )
        }

        val downloadService = RetrofitInstance.getDownloadService(
            downloadConfig.connectTimeOutInMs,
            downloadConfig.readTimeOutInMs
        )

        return try {
            markAsStarted(id)
            setForegroundSafely(downloadNotificationManager?.sendUpdateNotification())

            val latestETag = runCatching {
                ApiResponseHeaderChecker(downloadRequest.url, downloadService, headers)
                    .getHeaderValue(DownloadConst.ETAG_HEADER)
            }.getOrNull().orEmpty()

            val existingETag = downloadDao.find(id)?.eTag ?: ""

            if (latestETag.isNotEmpty() && latestETag != existingETag) {
                FileUtil.deleteFileIfExists(path = dirPath, name = tempFileName)
                downloadDao.find(id)?.copy(
                    eTag = latestETag,
                    lastModified = System.currentTimeMillis()
                )?.let { downloadDao.update(it) }
            }

            var progressPercentage = 0

            val totalLength = DownloadTask(
                url = url,
                path = dirPath,
                fileName = tempFileName,
                downloadService = downloadService
            ).download(
                headers = headers,
                speedLimitBytesPerSecond = downloadConfig.speedLimitBytesPerSecond,
                onStart = { length ->
                    if (!FileUtil.hasEnoughFreeSpace(
                            path = dirPath,
                            expectedBytes = length,
                            bufferBytes = downloadConfig.freeSpaceBufferBytes
                        )
                    ) {
                        throw IOException("Not enough free space for $finalFileName")
                    }

                    downloadDao.find(id)?.copy(
                        totalBytes = length,
                        status = Status.STARTED.toString(),
                        failureReason = "",
                        errorType = DownloadError.NONE.toString(),
                        runAttemptCount = runAttemptCount + 1,
                        lastModified = System.currentTimeMillis()
                    )?.let { downloadDao.update(it) }

                    setProgress(
                        workDataOf(
                            DownloadConst.KEY_STATE to DownloadConst.STARTED
                        )
                    )
                },
                onProgress = { downloadedBytes, length, speed ->

                    val progress = if (length != 0L) {
                        ((downloadedBytes * 100) / length).toInt()
                    } else {
                        0
                    }

                    if (progressPercentage != progress || length == 0L) {

                        progressPercentage = progress

                        downloadDao.find(id)?.copy(
                            downloadedBytes = downloadedBytes,
                            speedInBytePerMs = speed,
                            status = Status.PROGRESS.toString(),
                            failureReason = "",
                            errorType = DownloadError.NONE.toString(),
                            runAttemptCount = runAttemptCount + 1,
                            lastModified = System.currentTimeMillis()
                        )?.let { downloadDao.update(it) }

                    }

                    setProgress(
                        workDataOf(
                            DownloadConst.KEY_STATE to DownloadConst.PROGRESS,
                            DownloadConst.KEY_PROGRESS to progress
                        )
                    )
                    downloadNotificationManager?.sendUpdateNotification(
                        progress = progress,
                        speedInBPerMs = speed,
                        length = length,
                        update = true
                    )?.let { setForegroundSafely(it) }
                }
            )

            val renamed = FileUtil.renameTempFile(
                tempFileName = tempFileName,
                finalFileName = finalFileName,
                downloadsDirectory = File(dirPath)
            )
            if (!renamed) {
                throw IOException("Failed to rename temporary download file to $finalFileName")
            }

            downloadRequest.checksum?.let { checksum ->
                val actualChecksum = FileUtil.checksum(
                    path = dirPath,
                    fileName = finalFileName,
                    algorithm = checksum.algorithm.messageDigestName
                )
                if (!actualChecksum.equals(checksum.value, ignoreCase = true)) {
                    FileUtil.deleteFileIfExists(path = dirPath, name = finalFileName)
                    throw ChecksumMismatchException(
                        "Checksum mismatch for $finalFileName. Expected ${checksum.value}, got $actualChecksum"
                    )
                }
            }

            downloadDao.find(id)?.copy(
                totalBytes = totalLength,
                status = Status.SUCCESS.toString(),
                uuid = "",
                failureReason = "",
                errorType = DownloadError.NONE.toString(),
                lastModified = System.currentTimeMillis()
            )?.let { downloadDao.update(it) }

            downloadNotificationManager?.sendDownloadSuccessNotification(totalLength, notificationParameter)

            scheduleNextQueuedDownload(downloadConfig, notificationConfig)

            Result.success()
        } catch (e: Exception) {
            val shouldRetry = e !is CancellationException && runAttemptCount < downloadRequest.retryPolicy.maxRetries
            withContext(NonCancellable) {
                if (e is CancellationException) {
                    if (downloadDao.find(id)?.userAction == UserAction.PAUSE.toString()) {

                        downloadDao.find(id)?.copy(
                            status = Status.PAUSED.toString(),
                            uuid = "",
                            lastModified = System.currentTimeMillis()
                        )?.let { downloadDao.update(it) }
                        val downloadEntity = downloadDao.find(id)
                        if (downloadEntity != null) {
                            val currentProgress = if (downloadEntity.totalBytes != 0L) {
                                ((downloadEntity.downloadedBytes * MAX_PERCENT) / downloadEntity.totalBytes).toInt()
                            } else {
                                0
                            }
                            downloadNotificationManager?.sendDownloadPausedNotification(
                                currentProgress = currentProgress
                            )
                        }

                    } else {

                        downloadDao.find(id)?.copy(
                            status = Status.CANCELLED.toString(),
                            uuid = "",
                            lastModified = System.currentTimeMillis()
                        )?.let { downloadDao.update(it) }
                        FileUtil.deleteFileIfExists(dirPath, tempFileName)
                        downloadNotificationManager?.sendDownloadCancelledNotification()

                    }
                } else {

                    val failedEntity = downloadDao.find(id)
                    failedEntity?.copy(
                            status = if (shouldRetry) Status.SCHEDULED.toString() else Status.FAILED.toString(),
                            uuid = if (shouldRetry) failedEntity.uuid else "",
                            failureReason = e.message ?: e::class.java.simpleName,
                            errorType = e.toDownloadError().toString(),
                            runAttemptCount = runAttemptCount + 1,
                            lastModified = System.currentTimeMillis()
                    )?.let { downloadDao.update(it) }
                    val downloadEntity = downloadDao.find(id)
                    if (downloadEntity != null && !shouldRetry) {
                        val currentProgress = if (downloadEntity.totalBytes != 0L) {
                            ((downloadEntity.downloadedBytes * MAX_PERCENT) / downloadEntity.totalBytes).toInt()
                        } else {
                            0
                        }
                        downloadNotificationManager?.sendDownloadFailedNotification(
                            currentProgress = currentProgress
                        )
                    }
                }
            }
            if (shouldRetry) {
                return Result.retry()
            }
            scheduleNextQueuedDownload(downloadConfig, notificationConfig)
            Result.failure(
                workDataOf(ExceptionConst.KEY_EXCEPTION to e.message)
            )
        }

    }

    private suspend fun scheduleNextQueuedDownload(
        downloadConfig: DownloadConfig,
        notificationConfig: NotificationConfig
    ) {
        DownloadWorkCoordinator.scheduleQueuedDownloads(
            downloadDao = downloadDao,
            workManager = WorkManager.getInstance(context.applicationContext),
            downloadConfig = downloadConfig,
            notificationConfig = notificationConfig
        )
    }

    private suspend fun markAsStarted(id: Int) {
        downloadDao.find(id)?.copy(
            status = Status.STARTED.toString(),
            failureReason = "",
            errorType = DownloadError.NONE.toString(),
            runAttemptCount = runAttemptCount + 1,
            lastModified = System.currentTimeMillis()
        )?.let { downloadDao.update(it) }

        setProgress(
            workDataOf(
                DownloadConst.KEY_STATE to DownloadConst.STARTED
            )
        )
    }

    private suspend fun setForegroundSafely(foregroundInfo: ForegroundInfo?) {
        if (foregroundInfo == null) return
        runCatching {
            setForeground(foregroundInfo)
        }.onFailure {
            Log.w(NotificationConst.LOG_TAG, "Unable to show foreground download notification.", it)
        }
    }

    private fun Throwable.toDownloadError(): DownloadError {
        return when (this) {
            is ChecksumMismatchException -> DownloadError.CHECKSUM
            is java.net.SocketTimeoutException,
            is java.net.UnknownHostException,
            is java.net.ConnectException -> DownloadError.NETWORK
            is HttpException -> DownloadError.SERVER
            is IOException -> DownloadError.STORAGE
            is CancellationException -> DownloadError.CANCELLED
            else -> DownloadError.UNKNOWN
        }
    }

    private class ChecksumMismatchException(message: String) : IOException(message)

}
