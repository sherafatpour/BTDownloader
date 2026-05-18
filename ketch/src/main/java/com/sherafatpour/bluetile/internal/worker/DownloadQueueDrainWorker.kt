package com.sherafatpour.bluetile.internal.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.sherafatpour.bluetile.internal.database.DatabaseInstance
import com.sherafatpour.bluetile.internal.download.DownloadWorkCoordinator
import com.sherafatpour.bluetile.internal.utils.DownloadConst
import com.sherafatpour.bluetile.internal.utils.WorkUtil

internal class DownloadQueueDrainWorker(
    private val context: Context,
    workerParameters: WorkerParameters
) : CoroutineWorker(context, workerParameters) {

    override suspend fun doWork(): Result {
        val downloadConfig = WorkUtil.jsonToDownloadConfig(
            inputData.getString(DownloadConst.KEY_DOWNLOAD_CONFIG).orEmpty()
        )
        val notificationConfig = WorkUtil.jsonToNotificationConfig(
            inputData.getString(DownloadConst.KEY_NOTIFICATION_CONFIG).orEmpty()
        )
        val result = DownloadWorkCoordinator.scheduleQueuedDownloads(
            downloadDao = DatabaseInstance.getInstance(context).downloadDao(),
            workManager = WorkManager.getInstance(context.applicationContext),
            downloadConfig = downloadConfig,
            notificationConfig = notificationConfig
        )

        return if (result.hasPendingDownloads) {
            Result.retry()
        } else {
            Result.success()
        }
    }
}
