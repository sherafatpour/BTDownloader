package com.ketch.internal.download

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.ketch.DownloadConfig
import com.ketch.DownloadConstraints
import com.ketch.DownloadModel
import com.ketch.KetchBackoffPolicy
import com.ketch.KetchNetworkType
import com.ketch.Logger
import com.ketch.NotificationConfig
import com.ketch.RetryPolicy
import com.ketch.Status
import com.ketch.internal.database.DownloadDao
import com.ketch.internal.database.DownloadEntity
import com.ketch.internal.notification.DownloadNotificationManager
import com.ketch.internal.utils.DownloadConst
import com.ketch.internal.utils.FileUtil.deleteDownloadFiles
import com.ketch.internal.utils.UserAction
import com.ketch.internal.utils.WorkUtil
import com.ketch.internal.utils.WorkUtil.removeNotification
import com.ketch.internal.utils.WorkUtil.toJson
import com.ketch.internal.utils.toDownloadModel
import com.ketch.internal.worker.DownloadWorker
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import java.util.UUID

internal class DownloadManager(
    private val context: Context,
    private val downloadDao: DownloadDao,
    private val workManager: WorkManager,
    private val downloadConfig: DownloadConfig,
    private val notificationConfig: NotificationConfig,
    private val logger: Logger
) {

    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        logger.log(
            msg = "Exception in DownloadManager Scope: ${throwable.message}"
        )
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob() + exceptionHandler)

    init {

        scope.launch {
            // Observe work infos, only for logging purpose
            workManager.getWorkInfosByTagFlow(DownloadConst.TAG_DOWNLOAD).flowOn(Dispatchers.IO)
                .collectLatest { workInfos ->
                    for (workInfo in workInfos) {
                        when (workInfo.state) {
                            WorkInfo.State.ENQUEUED -> {
                                val downloadEntity = findDownloadEntityFromUUID(workInfo.id)
                                downloadEntity?.copy(
                                    status = Status.SCHEDULED.toString(),
                                    lastModified = System.currentTimeMillis()
                                )?.let { downloadDao.update(it) }
                                logger.log(
                                    msg = "Download Queued. FileName: ${downloadEntity?.fileName}, " +
                                            "ID: ${downloadEntity?.id}"
                                )
                            }

                            WorkInfo.State.RUNNING -> {
                                val downloadEntity = findDownloadEntityFromUUID(workInfo.id)
                                if (downloadEntity?.status == Status.QUEUED.toString() ||
                                    downloadEntity?.status == Status.SCHEDULED.toString()
                                ) {
                                    downloadDao.update(
                                        downloadEntity.copy(
                                            status = Status.STARTED.toString(),
                                            lastModified = System.currentTimeMillis()
                                        )
                                    )
                                }
                                when (workInfo.progress.getString(DownloadConst.KEY_STATE)) {
                                    DownloadConst.STARTED ->
                                        logger.log(
                                            msg = "Download Started. FileName: ${downloadEntity?.fileName}, " +
                                                    "ID: ${downloadEntity?.id}, " +
                                                    "Size in bytes: ${downloadEntity?.totalBytes}"
                                        )

                                    DownloadConst.PROGRESS ->
                                        logger.log(
                                            msg = "Download in Progress. FileName: ${downloadEntity?.fileName}, " +
                                                    "ID: ${downloadEntity?.id}, " +
                                                    "Size in bytes: ${downloadEntity?.totalBytes}, " +
                                                    "downloadPercent: ${if (downloadEntity != null && downloadEntity.totalBytes.toInt() != 0) {
                                                        ((downloadEntity.downloadedBytes * 100) / downloadEntity.totalBytes).toInt()
                                                    } else {
                                                        0
                                                    }}%, " +
                                                    "downloadSpeedInBytesPerMilliSeconds: ${downloadEntity?.speedInBytePerMs} b/ms"
                                        )

                                }
                            }

                            WorkInfo.State.SUCCEEDED -> {
                                val downloadEntity = findDownloadEntityFromUUID(workInfo.id)
                                if (downloadEntity != null) {
                                    logger.log(
                                        msg = "Download Success. FileName: ${downloadEntity.fileName}, " +
                                                "ID: ${downloadEntity.id}"
                                    )
                                }
                                scheduleQueuedDownloads()
                            }

                            WorkInfo.State.FAILED -> {
                                val downloadEntity = findDownloadEntityFromUUID(workInfo.id)
                                if (downloadEntity != null) {
                                    logger.log(
                                        msg = "Download Failed. FileName: ${downloadEntity.fileName}, " +
                                                "ID: ${downloadEntity.id}, " +
                                                "Reason: ${downloadEntity.failureReason}"
                                    )
                                }
                                scheduleQueuedDownloads()
                            }

                            WorkInfo.State.CANCELLED -> {
                                val downloadEntity = findDownloadEntityFromUUID(workInfo.id)
                                if (downloadEntity?.userAction == UserAction.PAUSE.toString()) {
                                    downloadDao.update(
                                        downloadEntity.copy(
                                            status = Status.PAUSED.toString(),
                                            uuid = "",
                                            lastModified = System.currentTimeMillis()
                                        )
                                    )
                                    logger.log(
                                        msg = "Download Paused. FileName: ${downloadEntity.fileName}, " +
                                                "ID: ${downloadEntity.id}"
                                    )
                                } else if (downloadEntity?.userAction == UserAction.CANCEL.toString()) {
                                    downloadDao.update(
                                        downloadEntity.copy(
                                            status = Status.CANCELLED.toString(),
                                            uuid = "",
                                            lastModified = System.currentTimeMillis()
                                        )
                                    )
                                    logger.log(
                                        msg = "Download Cancelled. FileName: ${downloadEntity.fileName}, " +
                                                "ID: ${downloadEntity.id}"
                                    )
                                }
                                scheduleQueuedDownloads()
                            }

                            WorkInfo.State.BLOCKED -> {} // no use case
                        }
                    }
                }
        }
    }

    private suspend fun download(downloadRequest: DownloadRequest) {
        // Checks if download id already present in database
        val existingEntity = downloadDao.find(downloadRequest.id)
        if (existingEntity != null) {
            val shouldQueueAgain = !existingEntity.status.isActiveDownloadStatus()
            val shouldResetProgress = shouldQueueAgain &&
                existingEntity.status != Status.PAUSED.toString() &&
                existingEntity.status != Status.FAILED.toString()

            if (shouldQueueAgain && existingEntity.status == Status.SUCCESS.toString()) {
                deleteDownloadFiles(existingEntity.path, existingEntity.fileName)
            }

            downloadDao.update(
                existingEntity.copy(
                    url = downloadRequest.url,
                    path = downloadRequest.path,
                    fileName = downloadRequest.fileName,
                    tag = downloadRequest.tag,
                    headersJson = WorkUtil.hashMapToJson(downloadRequest.headers),
                    notificationParameter = downloadRequest.notificationParameter,
                    notificationTitle = downloadRequest.notificationTitle,
                    metaData = downloadRequest.metaData,
                    userAction = UserAction.START.toString(),
                    status = if (shouldQueueAgain) Status.QUEUED.toString() else existingEntity.status,
                    uuid = if (shouldQueueAgain) "" else existingEntity.uuid,
                    totalBytes = if (shouldResetProgress) 0 else existingEntity.totalBytes,
                    downloadedBytes = if (shouldResetProgress) 0 else existingEntity.downloadedBytes,
                    speedInBytePerMs = 0f,
                    eTag = if (shouldResetProgress) "" else existingEntity.eTag,
                    failureReason = if (shouldQueueAgain) "" else existingEntity.failureReason,
                    runAttemptCount = if (shouldQueueAgain) 0 else existingEntity.runAttemptCount,
                    priority = downloadRequest.priority.value,
                    networkType = downloadRequest.constraints.networkType.toString(),
                    requiresCharging = downloadRequest.constraints.requiresCharging,
                    requiresBatteryNotLow = downloadRequest.constraints.requiresBatteryNotLow,
                    requiresStorageNotLow = downloadRequest.constraints.requiresStorageNotLow,
                    maxRetries = downloadRequest.retryPolicy.maxRetries,
                    backoffDelayInMs = downloadRequest.retryPolicy.backoffDelayInMs,
                    backoffPolicy = downloadRequest.retryPolicy.backoffPolicy.toString(),
                    lastModified = System.currentTimeMillis()
                )
            )
        } else {
            downloadDao.insert(
                DownloadEntity(
                    url = downloadRequest.url,
                    path = downloadRequest.path,
                    fileName = downloadRequest.fileName,
                    tag = downloadRequest.tag,
                    id = downloadRequest.id,
                    headersJson = WorkUtil.hashMapToJson(downloadRequest.headers),
                    notificationParameter = downloadRequest.notificationParameter,
                    notificationTitle = downloadRequest.notificationTitle,
                    timeQueued = System.currentTimeMillis(),
                    status = Status.QUEUED.toString(),
                    uuid = "",
                    lastModified = System.currentTimeMillis(),
                    userAction = UserAction.START.toString(),
                    metaData = downloadRequest.metaData,
                    priority = downloadRequest.priority.value,
                    networkType = downloadRequest.constraints.networkType.toString(),
                    requiresCharging = downloadRequest.constraints.requiresCharging,
                    requiresBatteryNotLow = downloadRequest.constraints.requiresBatteryNotLow,
                    requiresStorageNotLow = downloadRequest.constraints.requiresStorageNotLow,
                    maxRetries = downloadRequest.retryPolicy.maxRetries,
                    backoffDelayInMs = downloadRequest.retryPolicy.backoffDelayInMs,
                    backoffPolicy = downloadRequest.retryPolicy.backoffPolicy.toString()
                )
            )
            deleteDownloadFiles(downloadRequest.path, downloadRequest.fileName)
        }

        scheduleQueuedDownloads()
    }

    private fun String.isActiveDownloadStatus(): Boolean {
        return this == Status.QUEUED.toString() ||
            this == Status.SCHEDULED.toString() ||
            this == Status.STARTED.toString() ||
            this == Status.PROGRESS.toString()
    }

    private suspend fun scheduleQueuedDownloads() {
        val scheduledCount = downloadDao.countScheduledEntity(
            listOf(
                Status.QUEUED.toString(),
                Status.SCHEDULED.toString(),
                Status.STARTED.toString(),
                Status.PROGRESS.toString()
            )
        )
        val availableSlots = (downloadConfig.maxConcurrentDownloads - scheduledCount).coerceAtLeast(0)
        if (availableSlots == 0) return

        downloadDao.getPendingEntity(Status.QUEUED.toString())
            .take(availableSlots)
            .forEach { entity ->
                enqueue(entity)
            }
    }

    private suspend fun enqueue(downloadEntity: DownloadEntity) {
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
            downloadEntity.id.toString(),
            ExistingWorkPolicy.KEEP,
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
            priority = com.ketch.DownloadPriority.entries.find { it.value == priority }
                ?: com.ketch.DownloadPriority.NORMAL,
            constraints = DownloadConstraints(
                networkType = KetchNetworkType.entries.find { it.name == networkType }
                    ?: KetchNetworkType.CONNECTED,
                requiresCharging = requiresCharging,
                requiresBatteryNotLow = requiresBatteryNotLow,
                requiresStorageNotLow = requiresStorageNotLow
            ),
            retryPolicy = RetryPolicy(
                maxRetries = maxRetries,
                backoffDelayInMs = backoffDelayInMs,
                backoffPolicy = KetchBackoffPolicy.entries.find { it.name == backoffPolicy }
                    ?: KetchBackoffPolicy.EXPONENTIAL
            )
        )

    private fun DownloadConstraints.toWorkConstraints() =
        Constraints.Builder()
            .setRequiredNetworkType(networkType.toWorkNetworkType())
            .setRequiresCharging(requiresCharging)
            .setRequiresBatteryNotLow(requiresBatteryNotLow)
            .setRequiresStorageNotLow(requiresStorageNotLow)
            .build()

    private fun KetchNetworkType.toWorkNetworkType() =
        when (this) {
            KetchNetworkType.ANY -> NetworkType.NOT_REQUIRED
            KetchNetworkType.CONNECTED -> NetworkType.CONNECTED
            KetchNetworkType.UNMETERED -> NetworkType.UNMETERED
            KetchNetworkType.NOT_ROAMING -> NetworkType.NOT_ROAMING
        }

    private fun KetchBackoffPolicy.toWorkBackoffPolicy() =
        when (this) {
            KetchBackoffPolicy.LINEAR -> BackoffPolicy.LINEAR
            KetchBackoffPolicy.EXPONENTIAL -> BackoffPolicy.EXPONENTIAL
        }

    private suspend fun resume(id: Int) {
        val downloadEntity = downloadDao.find(id)
        if (downloadEntity != null) {
            downloadDao.update(
                downloadEntity.copy(
                    userAction = UserAction.RESUME.toString(),
                    lastModified = System.currentTimeMillis()
                )
            )
            download(
                DownloadRequest(
                    url = downloadEntity.url,
                    path = downloadEntity.path,
                    fileName = downloadEntity.fileName,
                    tag = downloadEntity.tag,
                    id = downloadEntity.id,
                    headers = WorkUtil.jsonToHashMap(downloadEntity.headersJson),
                    metaData = downloadEntity.metaData,
                    notificationParameter = downloadEntity.notificationParameter,
                    notificationTitle = downloadEntity.notificationTitle,
                    priority = com.ketch.DownloadPriority.entries.find { it.value == downloadEntity.priority }
                        ?: com.ketch.DownloadPriority.NORMAL,
                    constraints = DownloadConstraints(
                        networkType = KetchNetworkType.entries.find { it.name == downloadEntity.networkType }
                            ?: KetchNetworkType.CONNECTED,
                        requiresCharging = downloadEntity.requiresCharging,
                        requiresBatteryNotLow = downloadEntity.requiresBatteryNotLow,
                        requiresStorageNotLow = downloadEntity.requiresStorageNotLow
                    ),
                    retryPolicy = RetryPolicy(
                        maxRetries = downloadEntity.maxRetries,
                        backoffDelayInMs = downloadEntity.backoffDelayInMs,
                        backoffPolicy = KetchBackoffPolicy.entries.find { it.name == downloadEntity.backoffPolicy }
                            ?: KetchBackoffPolicy.EXPONENTIAL
                    )
                )
            )
        }
    }

    private suspend fun cancel(id: Int) {
        val downloadEntity = downloadDao.find(id)
        if (downloadEntity != null) {
            downloadDao.update(
                downloadEntity.copy(
                    userAction = UserAction.CANCEL.toString(),
                    lastModified = System.currentTimeMillis()
                )
            )
            if (downloadEntity.status == Status.PAUSED.toString() ||
                downloadEntity.status == Status.FAILED.toString() ||
                (downloadEntity.status == Status.QUEUED.toString() && downloadEntity.uuid.isEmpty()) ||
                downloadEntity.status == Status.SCHEDULED.toString()
            ) { // Edge Case: When user cancel the download in pause or fail (terminating) state as work is already cancelled.

                downloadDao.find(downloadEntity.id)?.copy(
                    status = Status.CANCELLED.toString(),
                    uuid = "",
                    lastModified = System.currentTimeMillis()
                )?.let { downloadDao.update(it) }
                deleteDownloadFiles(downloadEntity.path, downloadEntity.fileName)
                DownloadNotificationManager(
                    context = context,
                    notificationConfig = notificationConfig,
                    requestId = id,
                    fileName = downloadEntity.notificationTitle,
                    notificationParameter = downloadEntity.notificationParameter,


                ).sendDownloadCancelledNotification()
            }
        }
        workManager.cancelUniqueWork(id.toString())
    }

    private suspend fun pause(id: Int) {
        val downloadEntity = downloadDao.find(id)
        if (downloadEntity != null) {
            downloadDao.update(
                downloadEntity.copy(
                    status = if (downloadEntity.uuid.isEmpty()) Status.PAUSED.toString() else downloadEntity.status,
                    userAction = UserAction.PAUSE.toString(),
                    lastModified = System.currentTimeMillis()
                )
            )
        }
        workManager.cancelUniqueWork(id.toString())
    }

    private suspend fun retry(id: Int) {
        val downloadEntity = downloadDao.find(id)
        if (downloadEntity != null) {
            downloadDao.update(
                downloadEntity.copy(
                    userAction = UserAction.RETRY.toString(),
                    lastModified = System.currentTimeMillis()
                )
            )
            download(
                DownloadRequest(
                    url = downloadEntity.url,
                    path = downloadEntity.path,
                    fileName = downloadEntity.fileName,
                    tag = downloadEntity.tag,
                    id = downloadEntity.id,
                    headers = WorkUtil.jsonToHashMap(downloadEntity.headersJson),
                    metaData = downloadEntity.metaData,
                    notificationParameter = downloadEntity.notificationParameter,
                    notificationTitle = downloadEntity.notificationTitle,
                    priority = com.ketch.DownloadPriority.entries.find { it.value == downloadEntity.priority }
                        ?: com.ketch.DownloadPriority.NORMAL,
                    constraints = DownloadConstraints(
                        networkType = KetchNetworkType.entries.find { it.name == downloadEntity.networkType }
                            ?: KetchNetworkType.CONNECTED,
                        requiresCharging = downloadEntity.requiresCharging,
                        requiresBatteryNotLow = downloadEntity.requiresBatteryNotLow,
                        requiresStorageNotLow = downloadEntity.requiresStorageNotLow
                    ),
                    retryPolicy = RetryPolicy(
                        maxRetries = downloadEntity.maxRetries,
                        backoffDelayInMs = downloadEntity.backoffDelayInMs,
                        backoffPolicy = KetchBackoffPolicy.entries.find { it.name == downloadEntity.backoffPolicy }
                            ?: KetchBackoffPolicy.EXPONENTIAL
                    )
                )
            )
        }
    }

    private suspend fun findDownloadEntityFromUUID(uuid: UUID): DownloadEntity? {
        return downloadDao.getAllEntity().find { it.uuid == uuid.toString() }
    }

    fun resumeAsync(id: Int) {
        scope.launch {
            resume(id)
        }
    }

    fun resumeAsync(tag: String) {
        scope.launch {
            downloadDao.getAllEntity().forEach {
                if (it.tag == tag) {
                    resume(it.id)
                }
            }
        }
    }

    fun resumeAllAsync() {
        scope.launch {
            downloadDao.getAllEntity().forEach {
                resume(it.id)
            }
        }
    }

    fun cancelAsync(id: Int) {
        scope.launch {
            cancel(id)
        }
    }

    fun cancelAsync(tag: String) {
        scope.launch {
            downloadDao.getAllEntity().forEach {
                if (it.tag == tag) {
                    cancel(it.id)
                }
            }
        }
    }

    fun cancelAllAsync() {
        scope.launch {
            downloadDao.getAllEntity().forEach {
                cancel(it.id)
            }
        }
    }

    fun pauseAsync(id: Int) {
        scope.launch {
            pause(id)
        }
    }

    fun pauseAsync(tag: String) {
        scope.launch {
            downloadDao.getAllEntity().forEach {
                if (it.tag == tag) {
                    pause(it.id)
                }
            }
        }
    }

    fun pauseAllAsync() {
        scope.launch {
            downloadDao.getAllEntity().forEach {
                pause(it.id)
            }
        }
    }

    fun retryAsync(id: Int) {
        scope.launch {
            retry(id)
        }
    }

    fun retryAsync(tag: String) {
        scope.launch {
            downloadDao.getAllEntity().forEach {
                if (it.tag == tag) {
                    retry(it.id)
                }
            }
        }
    }

    fun retryAllAsync() {
        scope.launch {
            downloadDao.getAllEntity().forEach {
                retry(it.id)
            }
        }
    }

    fun clearDbAsync(id: Int) {
        scope.launch {
            cancel(id)
            val downloadEntity = downloadDao.find(id)
            val path = downloadEntity?.path
            val fileName = downloadEntity?.fileName
            if (path != null && fileName != null) {
                deleteDownloadFiles(path, fileName)
            }
            removeNotification(context, id) // In progress notification
            removeNotification(context, id + 1) // Cancelled, Paused, Failed, Success notification
            downloadDao.remove(id)
        }
    }

    fun clearDbAsync(tag: String) {
        scope.launch {
            downloadDao.getAllEntityByTag(tag).forEach {
                cancel(it.id)
                val downloadEntity = downloadDao.find(it.id)
                val path = downloadEntity?.path
                val fileName = downloadEntity?.fileName
                if (path != null && fileName != null) {
                    deleteDownloadFiles(path, fileName)
                }
                downloadDao.remove(it.id)
                removeNotification(context, it.id) // In progress notification
                removeNotification(context, it.id + 1) // Cancelled, Paused, Failed, Success notification
            }
        }
    }

    fun clearAllDbAsync() {
        scope.launch {
            downloadDao.getAllEntity().forEach {
                cancel(it.id)
                val downloadEntity = downloadDao.find(it.id)
                val path = downloadEntity?.path
                val fileName = downloadEntity?.fileName
                if (path != null && fileName != null) {
                    deleteDownloadFiles(path, fileName)
                }
                removeNotification(context, it.id) // In progress notification
                removeNotification(context, it.id + 1) // Cancelled, Paused, Failed, Success notification
            }
            downloadDao.deleteAll()
        }
    }

    fun clearDbAsync(timeInMillis: Long) {
        scope.launch {
            downloadDao.getEntityTillTime(timeInMillis).forEach {
                cancel(it.id)
                val downloadEntity = downloadDao.find(it.id)
                val path = downloadEntity?.path
                val fileName = downloadEntity?.fileName
                if (path != null && fileName != null) {
                    deleteDownloadFiles(path, fileName)
                }
                downloadDao.remove(it.id)
                removeNotification(context, it.id) // In progress notification
                removeNotification(context, it.id + 1) // Cancelled, Paused, Failed, Success notification
            }
        }
    }

    fun downloadAsync(downloadRequest: DownloadRequest) {
        scope.launch {
            download(downloadRequest)
        }
    }

    fun observeDownloadById(id: Int): Flow<DownloadModel> {
        return downloadDao.getEntityByIdFlow(id).filterNotNull().distinctUntilChanged().map { entity ->
            entity.toDownloadModel()
        }
    }

    fun observeDownloadsByTag(tag: String): Flow<List<DownloadModel>> {
        return downloadDao.getAllEntityByTagFlow(tag).map { entityList ->
            entityList.map { entity ->
                entity.toDownloadModel()
            }
        }
    }

    fun observeAllDownloads(): Flow<List<DownloadModel>> {
        return downloadDao.getAllEntityFlow().map { entityList ->
            entityList.map { entity ->
                entity.toDownloadModel()
            }
        }
    }

    suspend fun getAllDownloads(): List<DownloadModel> {
        return downloadDao.getAllEntity().map { entity ->
            entity.toDownloadModel()
        }
    }


}
