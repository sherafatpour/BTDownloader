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

        if (entity.status != Status.SCHEDULED.toString() || entity.scheduledAtEpochMs <= 0L) {
            return Result.success()
        }

        if (entity.scheduledAtEpochMs > System.currentTimeMillis()) {
            return Result.retry()
        }

        downloadDao.update(
            entity.copy(
                status = Status.QUEUED.toString(),
                uuid = "",
                userAction = UserAction.START.toString(),
                lastModified = System.currentTimeMillis()
            )
        )

        DownloadWorkCoordinator.scheduleQueuedDownloads(
            downloadDao = downloadDao,
            workManager = WorkManager.getInstance(context.applicationContext),
            downloadConfig = downloadConfig,
            notificationConfig = notificationConfig
        )
        return Result.success()
    }
}
