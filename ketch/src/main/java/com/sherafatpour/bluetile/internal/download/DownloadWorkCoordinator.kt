package com.sherafatpour.bluetile.internal.download

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.sherafatpour.bluetile.BTDownloaderBackoffPolicy
import com.sherafatpour.bluetile.BTDownloaderNetworkType
import com.sherafatpour.bluetile.DownloadChecksum
import com.sherafatpour.bluetile.DownloadChecksumAlgorithm
import com.sherafatpour.bluetile.DownloadConfig
import com.sherafatpour.bluetile.DownloadConstraints
import com.sherafatpour.bluetile.DownloadPriority
import com.sherafatpour.bluetile.NotificationConfig
import com.sherafatpour.bluetile.RetryPolicy
import com.sherafatpour.bluetile.Status
import com.sherafatpour.bluetile.internal.database.DownloadDao
import com.sherafatpour.bluetile.internal.database.DownloadEntity
import com.sherafatpour.bluetile.internal.utils.DownloadConst
import com.sherafatpour.bluetile.internal.utils.WorkUtil
import com.sherafatpour.bluetile.internal.utils.WorkUtil.toJson
import com.sherafatpour.bluetile.internal.worker.DownloadQueueDrainWorker
import com.sherafatpour.bluetile.internal.worker.DownloadScheduleWorker
import com.sherafatpour.bluetile.internal.worker.DownloadWorker
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit

internal object DownloadWorkCoordinator {

    private val queueMutex = Mutex()

    data class QueueDispatchResult(
        val activeCount: Int,
        val availableSlots: Int,
        val enqueuedCount: Int,
        val pendingCount: Int
    ) {
        val hasPendingDownloads: Boolean = pendingCount > 0
    }

    fun downloadWorkName(id: Int): String = id.toString()

    fun scheduleWorkName(id: Int): String = "${DownloadConst.UNIQUE_SCHEDULE_WORK_PREFIX}$id"

    fun enqueueQueueDrain(
        workManager: WorkManager,
        downloadConfig: DownloadConfig,
        notificationConfig: NotificationConfig
    ) {
        val queueDrainWorkRequest = OneTimeWorkRequestBuilder<DownloadQueueDrainWorker>()
            .setInputData(
                Data.Builder()
                    .putString(DownloadConst.KEY_DOWNLOAD_CONFIG, downloadConfig.toJson())
                    .putString(DownloadConst.KEY_NOTIFICATION_CONFIG, notificationConfig.toJson())
                    .build()
            )
            .addTag(DownloadConst.TAG_QUEUE_DRAIN)
            .setBackoffCriteria(
                BackoffPolicy.LINEAR,
                DownloadConst.SCHEDULE_QUEUE_RETRY_BACKOFF_MS,
                TimeUnit.MILLISECONDS
            )
            .build()

        workManager.enqueueUniqueWork(
            DownloadConst.UNIQUE_QUEUE_DRAIN_WORK,
            ExistingWorkPolicy.KEEP,
            queueDrainWorkRequest
        )
    }

    suspend fun enqueueSchedule(
        downloadEntity: DownloadEntity,
        downloadDao: DownloadDao,
        workManager: WorkManager,
        downloadConfig: DownloadConfig,
        notificationConfig: NotificationConfig
    ) {
        val delayMs = (downloadEntity.scheduledAtEpochMs - System.currentTimeMillis()).coerceAtLeast(0L)
        val scheduleWorkRequest = OneTimeWorkRequestBuilder<DownloadScheduleWorker>()
            .setInputData(
                Data.Builder()
                    .putInt(DownloadConst.KEY_REQUEST_ID, downloadEntity.id)
                    .putString(DownloadConst.KEY_DOWNLOAD_CONFIG, downloadConfig.toJson())
                    .putString(DownloadConst.KEY_NOTIFICATION_CONFIG, notificationConfig.toJson())
                    .build()
            )
            .addTag(DownloadConst.TAG_SCHEDULED_DOWNLOAD)
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setBackoffCriteria(
                BackoffPolicy.LINEAR,
                DownloadConst.SCHEDULE_QUEUE_RETRY_BACKOFF_MS,
                TimeUnit.MILLISECONDS
            )
            .build()

        downloadDao.update(
            downloadEntity.copy(
                status = Status.SCHEDULED.toString(),
                uuid = "",
                lastModified = System.currentTimeMillis()
            )
        )
        workManager.enqueueUniqueWork(
            scheduleWorkName(downloadEntity.id),
            ExistingWorkPolicy.REPLACE,
            scheduleWorkRequest
        )
    }

    suspend fun scheduleQueuedDownloads(
        downloadDao: DownloadDao,
        workManager: WorkManager,
        downloadConfig: DownloadConfig,
        notificationConfig: NotificationConfig
    ) = queueMutex.withLock {
        val activeCount = downloadDao.countScheduledEntity(
            listOf(
                Status.SCHEDULED.toString(),
                Status.STARTED.toString(),
                Status.PROGRESS.toString()
            )
        )
        val availableSlots = (downloadConfig.maxConcurrentDownloads - activeCount).coerceAtLeast(0)
        val pendingBeforeDispatch = downloadDao.getPendingEntity(Status.QUEUED.toString())
        if (availableSlots == 0) {
            return@withLock QueueDispatchResult(
                activeCount = activeCount,
                availableSlots = 0,
                enqueuedCount = 0,
                pendingCount = pendingBeforeDispatch.size
            )
        }

        var enqueuedCount = 0
        pendingBeforeDispatch
            .take(availableSlots)
            .forEach { entity ->
                val latestEntity = downloadDao.find(entity.id) ?: return@forEach
                if (latestEntity.status == Status.QUEUED.toString() && latestEntity.uuid.isEmpty()) {
                    enqueueDownload(latestEntity, downloadDao, workManager, downloadConfig, notificationConfig)
                    enqueuedCount++
                }
            }

        QueueDispatchResult(
            activeCount = activeCount,
            availableSlots = availableSlots,
            enqueuedCount = enqueuedCount,
            pendingCount = downloadDao.getPendingEntity(Status.QUEUED.toString()).size
        )
    }

    suspend fun enqueueDownload(
        downloadEntity: DownloadEntity,
        downloadDao: DownloadDao,
        workManager: WorkManager,
        downloadConfig: DownloadConfig,
        notificationConfig: NotificationConfig
    ) {
        val downloadRequest = downloadEntity.toDownloadRequest()
        val downloadWorkRequest = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(
                Data.Builder()
                    .putString(DownloadConst.KEY_DOWNLOAD_REQUEST, downloadRequest.toJson())
                    .putString(DownloadConst.KEY_DOWNLOAD_CONFIG, downloadConfig.toJson())
                    .putString(DownloadConst.KEY_NOTIFICATION_CONFIG, notificationConfig.toJson())
                    .build()
            )
            .addTag(DownloadConst.TAG_DOWNLOAD)
            .setConstraints(downloadRequest.constraints.toWorkConstraints())
            .setBackoffCriteria(
                downloadRequest.retryPolicy.backoffPolicy.toWorkBackoffPolicy(),
                downloadRequest.retryPolicy.backoffDelayInMs,
                TimeUnit.MILLISECONDS
            )
            .build()

        downloadDao.update(
            downloadEntity.copy(
                uuid = downloadWorkRequest.id.toString(),
                status = Status.SCHEDULED.toString(),
                lastModified = System.currentTimeMillis()
            )
        )

        workManager.enqueueUniqueWork(
            downloadWorkName(downloadEntity.id),
            ExistingWorkPolicy.REPLACE,
            downloadWorkRequest
        )
    }

    private fun DownloadEntity.toDownloadRequest() =
        DownloadRequest(
            url = url,
            path = path,
            fileName = fileName,
            tag = tag,
            id = id,
            headers = WorkUtil.jsonToHashMap(headersJson),
            metaData = metaData,
            notificationParameter = notificationParameter,
            notificationTitle = notificationTitle,
            priority = DownloadPriority.entries.find { it.value == priority } ?: DownloadPriority.NORMAL,
            constraints = DownloadConstraints(
                networkType = BTDownloaderNetworkType.entries.find { it.name == networkType }
                    ?: BTDownloaderNetworkType.CONNECTED,
                requiresCharging = requiresCharging,
                requiresBatteryNotLow = requiresBatteryNotLow,
                requiresStorageNotLow = requiresStorageNotLow
            ),
            retryPolicy = RetryPolicy(
                maxRetries = maxRetries,
                backoffDelayInMs = backoffDelayInMs,
                backoffPolicy = BTDownloaderBackoffPolicy.entries.find { it.name == backoffPolicy }
                    ?: BTDownloaderBackoffPolicy.EXPONENTIAL
            ),
            scheduledAtEpochMs = scheduledAtEpochMs.takeIf { it > 0L },
            checksum = toChecksum(),
            autoRenameIfExists = autoRenameIfExists
        )

    private fun DownloadEntity.toChecksum(): DownloadChecksum? {
        if (checksumAlgorithm.isBlank() || checksumValue.isBlank()) return null
        val algorithm = DownloadChecksumAlgorithm.entries.find { it.name == checksumAlgorithm } ?: return null
        return DownloadChecksum(algorithm, checksumValue)
    }

    private fun DownloadConstraints.toWorkConstraints() =
        Constraints.Builder()
            .setRequiredNetworkType(networkType.toWorkNetworkType())
            .setRequiresCharging(requiresCharging)
            .setRequiresBatteryNotLow(requiresBatteryNotLow)
            .setRequiresStorageNotLow(requiresStorageNotLow)
            .build()

    private fun BTDownloaderNetworkType.toWorkNetworkType() =
        when (this) {
            BTDownloaderNetworkType.ANY -> NetworkType.NOT_REQUIRED
            BTDownloaderNetworkType.CONNECTED -> NetworkType.CONNECTED
            BTDownloaderNetworkType.UNMETERED -> NetworkType.UNMETERED
            BTDownloaderNetworkType.NOT_ROAMING -> NetworkType.NOT_ROAMING
        }

    private fun BTDownloaderBackoffPolicy.toWorkBackoffPolicy() =
        when (this) {
            BTDownloaderBackoffPolicy.LINEAR -> BackoffPolicy.LINEAR
            BTDownloaderBackoffPolicy.EXPONENTIAL -> BackoffPolicy.EXPONENTIAL
        }
}
