package com.sherafatpour.bluetile.internal.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.sherafatpour.bluetile.Status
import com.sherafatpour.bluetile.internal.database.DatabaseInstance
import com.sherafatpour.bluetile.internal.download.DownloadWorkCoordinator
import com.sherafatpour.bluetile.internal.utils.DownloadConst
import com.sherafatpour.bluetile.internal.utils.UserAction
import com.sherafatpour.bluetile.internal.utils.WorkUtil

internal class DownloadScheduleWorker(
    private val context: Context,
    workerParameters: WorkerParameters
) : CoroutineWorker(context, workerParameters) {

    override suspend fun doWork(): Result {
        val requestId = inputData.getInt(DownloadConst.KEY_REQUEST_ID, -1)
        if (requestId == -1) return Result.failure()

        val downloadConfig = WorkUtil.jsonToDownloadConfig(
            inputData.getString(DownloadConst.KEY_DOWNLOAD_CONFIG).orEmpty()
        )
        val notificationConfig = WorkUtil.jsonToNotificationConfig(
            inputData.getString(DownloadConst.KEY_NOTIFICATION_CONFIG).orEmpty()
        )
        val downloadDao = DatabaseInstance.getInstance(context).downloadDao()
        val entity = downloadDao.find(requestId) ?: return Result.success()
        val now = System.currentTimeMillis()
        val isWaitingForSchedule = entity.status == Status.SCHEDULED.toString() &&
            entity.uuid.isEmpty() &&
            entity.scheduledAtEpochMs > 0L
        val isPendingQueue = entity.status == Status.QUEUED.toString() && entity.uuid.isEmpty()

        if (!isWaitingForSchedule && !isPendingQueue) {
            return Result.success()
        }

        if (isWaitingForSchedule && entity.scheduledAtEpochMs > now) {
            return Result.retry()
        }

        if (isWaitingForSchedule) {
            downloadDao.update(
                entity.copy(
                    status = Status.QUEUED.toString(),
                    uuid = "",
                    userAction = UserAction.START.toString(),
                    lastModified = now
                )
            )
        }

        val workManager = WorkManager.getInstance(context.applicationContext)
        val dispatchResult = DownloadWorkCoordinator.scheduleQueuedDownloads(
            downloadDao = downloadDao,
            workManager = workManager,
            downloadConfig = downloadConfig,
            notificationConfig = notificationConfig
        )

        val latest = downloadDao.find(requestId) ?: return Result.success()
        return if (latest.status == Status.QUEUED.toString() && latest.uuid.isEmpty()) {
            DownloadWorkCoordinator.enqueueQueueDrain(
                workManager = workManager,
                downloadConfig = downloadConfig,
                notificationConfig = notificationConfig
            )
            Result.retry()
        } else {
            if (dispatchResult.hasPendingDownloads) {
                DownloadWorkCoordinator.enqueueQueueDrain(
                    workManager = workManager,
                    downloadConfig = downloadConfig,
                    notificationConfig = notificationConfig
                )
            }
            Result.success()
        }
    }
}
