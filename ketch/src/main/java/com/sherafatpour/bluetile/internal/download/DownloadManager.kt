package com.sherafatpour.bluetile.internal.download

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.await
import com.sherafatpour.bluetile.DownloadConfig
import com.sherafatpour.bluetile.DownloadConstraints
import com.sherafatpour.bluetile.DownloadModel
import com.sherafatpour.bluetile.BTDownloaderBackoffPolicy
import com.sherafatpour.bluetile.BTDownloaderNetworkType
import com.sherafatpour.bluetile.DownloadChecksum
import com.sherafatpour.bluetile.DownloadChecksumAlgorithm
import com.sherafatpour.bluetile.DownloadError
import com.sherafatpour.bluetile.DownloadPriority
import com.sherafatpour.bluetile.Logger
import com.sherafatpour.bluetile.NotificationConfig
import com.sherafatpour.bluetile.RetryPolicy
import com.sherafatpour.bluetile.Status
import com.sherafatpour.bluetile.internal.database.DownloadDao
import com.sherafatpour.bluetile.internal.database.DownloadEntity
import com.sherafatpour.bluetile.internal.notification.DownloadNotificationManager
import com.sherafatpour.bluetile.internal.utils.DownloadConst
import com.sherafatpour.bluetile.internal.utils.FileUtil
import com.sherafatpour.bluetile.internal.utils.FileUtil.deleteDownloadFiles
import com.sherafatpour.bluetile.internal.utils.UserAction
import com.sherafatpour.bluetile.internal.utils.WorkUtil
import com.sherafatpour.bluetile.internal.utils.WorkUtil.removeNotification
import com.sherafatpour.bluetile.internal.utils.toDownloadModel
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
import java.util.UUID

internal class DownloadManager(
    private val context: Context,
    private val downloadDao: DownloadDao,
    private val workManager: WorkManager,
    private val downloadConfig: DownloadConfig,
    private val notificationConfig: NotificationConfig,
    private val logger: Logger
) {
    private val preemptedByManualStart = mutableMapOf<Int, Int>()
    private val connectivityManager = context.getSystemService(ConnectivityManager::class.java)

    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        logger.log(
            msg = "Exception in DownloadManager Scope: ${throwable.message}"
        )
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob() + exceptionHandler)
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            scope.launch { scheduleQueuedDownloads() }
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            if (networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                scope.launch { scheduleQueuedDownloads() }
            }
        }
    }

    init {
        registerNetworkCallback()

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
                                    resumePreemptedIfAny(downloadEntity.id)
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
                                    resumePreemptedIfAny(downloadEntity.id)
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
                                    resumePreemptedIfAny(downloadEntity.id)
                                }
                                scheduleQueuedDownloads()
                            }

                            WorkInfo.State.BLOCKED -> {} // no use case
                        }
                    }
                }
        }
    }

    private fun registerNetworkCallback() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                connectivityManager?.registerDefaultNetworkCallback(networkCallback)
            } else {
                val request = NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build()
                connectivityManager?.registerNetworkCallback(request, networkCallback)
            }
        }.onFailure {
            logger.log(msg = "Unable to register network callback: ${it.message}")
        }
    }

    private suspend fun download(downloadRequest: DownloadRequest) {
        val effectiveRequest = if (downloadRequest.autoRenameIfExists) {
            val resolvedFileName = FileUtil.resolveAvailableFileName(downloadRequest.path, downloadRequest.fileName)
            if (resolvedFileName == downloadRequest.fileName) {
                downloadRequest
            } else {
                downloadRequest.copy(
                    fileName = resolvedFileName,
                    id = FileUtil.getUniqueId(downloadRequest.url, downloadRequest.path, resolvedFileName)
                )
            }
        } else {
            downloadRequest
        }
        val isFutureScheduled = effectiveRequest.scheduledAtEpochMs?.let { it > System.currentTimeMillis() } == true
        val requestedInitialStatus = if (isFutureScheduled) {
            Status.SCHEDULED.toString()
        } else {
            Status.QUEUED.toString()
        }

        // Checks if download id already present in database
        val existingEntity = downloadDao.find(effectiveRequest.id)
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
                    url = effectiveRequest.url,
                    path = effectiveRequest.path,
                    fileName = effectiveRequest.fileName,
                    tag = effectiveRequest.tag,
                    headersJson = WorkUtil.hashMapToJson(effectiveRequest.headers),
                    notificationParameter = effectiveRequest.notificationParameter,
                    notificationTitle = effectiveRequest.notificationTitle,
                    metaData = effectiveRequest.metaData,
                    userAction = UserAction.START.toString(),
                    status = if (shouldQueueAgain) requestedInitialStatus else existingEntity.status,
                    uuid = if (shouldQueueAgain) "" else existingEntity.uuid,
                    totalBytes = if (shouldResetProgress) 0 else existingEntity.totalBytes,
                    downloadedBytes = if (shouldResetProgress) 0 else existingEntity.downloadedBytes,
                    speedInBytePerMs = 0f,
                    eTag = if (shouldResetProgress) "" else existingEntity.eTag,
                    failureReason = if (shouldQueueAgain) "" else existingEntity.failureReason,
                    runAttemptCount = if (shouldQueueAgain) 0 else existingEntity.runAttemptCount,
                    priority = effectiveRequest.priority.value,
                    networkType = effectiveRequest.constraints.networkType.toString(),
                    requiresCharging = effectiveRequest.constraints.requiresCharging,
                    requiresBatteryNotLow = effectiveRequest.constraints.requiresBatteryNotLow,
                    requiresStorageNotLow = effectiveRequest.constraints.requiresStorageNotLow,
                    maxRetries = effectiveRequest.retryPolicy.maxRetries,
                    backoffDelayInMs = effectiveRequest.retryPolicy.backoffDelayInMs,
                    backoffPolicy = effectiveRequest.retryPolicy.backoffPolicy.toString(),
                    scheduledAtEpochMs = effectiveRequest.scheduledAtEpochMs ?: 0L,
                    checksumAlgorithm = effectiveRequest.checksum?.algorithm?.name.orEmpty(),
                    checksumValue = effectiveRequest.checksum?.value.orEmpty(),
                    errorType = if (shouldQueueAgain) DownloadError.NONE.toString() else existingEntity.errorType,
                    autoRenameIfExists = effectiveRequest.autoRenameIfExists,
                    lastModified = System.currentTimeMillis()
                )
            )
        } else {
            downloadDao.insert(
                DownloadEntity(
                    url = effectiveRequest.url,
                    path = effectiveRequest.path,
                    fileName = effectiveRequest.fileName,
                    tag = effectiveRequest.tag,
                    id = effectiveRequest.id,
                    headersJson = WorkUtil.hashMapToJson(effectiveRequest.headers),
                    notificationParameter = effectiveRequest.notificationParameter,
                    notificationTitle = effectiveRequest.notificationTitle,
                    timeQueued = System.currentTimeMillis(),
                    status = requestedInitialStatus,
                    uuid = "",
                    lastModified = System.currentTimeMillis(),
                    userAction = UserAction.START.toString(),
                    metaData = effectiveRequest.metaData,
                    priority = effectiveRequest.priority.value,
                    networkType = effectiveRequest.constraints.networkType.toString(),
                    requiresCharging = effectiveRequest.constraints.requiresCharging,
                    requiresBatteryNotLow = effectiveRequest.constraints.requiresBatteryNotLow,
                    requiresStorageNotLow = effectiveRequest.constraints.requiresStorageNotLow,
                    maxRetries = effectiveRequest.retryPolicy.maxRetries,
                    backoffDelayInMs = effectiveRequest.retryPolicy.backoffDelayInMs,
                    backoffPolicy = effectiveRequest.retryPolicy.backoffPolicy.toString(),
                    scheduledAtEpochMs = effectiveRequest.scheduledAtEpochMs ?: 0L,
                    checksumAlgorithm = effectiveRequest.checksum?.algorithm?.name.orEmpty(),
                    checksumValue = effectiveRequest.checksum?.value.orEmpty(),
                    autoRenameIfExists = effectiveRequest.autoRenameIfExists
                )
            )
            if (!effectiveRequest.autoRenameIfExists) {
                deleteDownloadFiles(effectiveRequest.path, effectiveRequest.fileName)
            } else {
                FileUtil.deleteFileIfExists(effectiveRequest.path, FileUtil.getTempFileName(effectiveRequest.fileName))
            }
        }

        val savedEntity = downloadDao.find(effectiveRequest.id) ?: return
        if (isFutureScheduled && savedEntity.status == Status.SCHEDULED.toString() && savedEntity.uuid.isEmpty()) {
            DownloadWorkCoordinator.enqueueSchedule(
                downloadEntity = savedEntity,
                downloadDao = downloadDao,
                workManager = workManager,
                downloadConfig = downloadConfig,
                notificationConfig = notificationConfig
            )
        } else {
            scheduleQueuedDownloads()
        }
    }

    private fun String.isActiveDownloadStatus(): Boolean {
        return this == Status.QUEUED.toString() ||
            this == Status.SCHEDULED.toString() ||
            this == Status.STARTED.toString() ||
            this == Status.PROGRESS.toString()
    }

    private suspend fun scheduleQueuedDownloads() {
        val result = DownloadWorkCoordinator.scheduleQueuedDownloads(
            downloadDao = downloadDao,
            workManager = workManager,
            downloadConfig = downloadConfig,
            notificationConfig = notificationConfig
        )
        if (result.hasPendingDownloads) {
            DownloadWorkCoordinator.enqueueQueueDrain(
                workManager = workManager,
                downloadConfig = downloadConfig,
                notificationConfig = notificationConfig
            )
        }
    }

    private fun DownloadEntity.toChecksum(): DownloadChecksum? {
        if (checksumAlgorithm.isBlank() || checksumValue.isBlank()) return null
        val algorithm = DownloadChecksumAlgorithm.entries.find { it.name == checksumAlgorithm } ?: return null
        return DownloadChecksum(algorithm, checksumValue)
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
                    priority = com.sherafatpour.bluetile.DownloadPriority.entries.find { it.value == downloadEntity.priority }
                        ?: com.sherafatpour.bluetile.DownloadPriority.NORMAL,
                    constraints = DownloadConstraints(
                        networkType = BTDownloaderNetworkType.entries.find { it.name == downloadEntity.networkType }
                            ?: BTDownloaderNetworkType.CONNECTED,
                        requiresCharging = downloadEntity.requiresCharging,
                        requiresBatteryNotLow = downloadEntity.requiresBatteryNotLow,
                        requiresStorageNotLow = downloadEntity.requiresStorageNotLow
                    ),
                    retryPolicy = RetryPolicy(
                        maxRetries = downloadEntity.maxRetries,
                        backoffDelayInMs = downloadEntity.backoffDelayInMs,
                        backoffPolicy = BTDownloaderBackoffPolicy.entries.find { it.name == downloadEntity.backoffPolicy }
                            ?: BTDownloaderBackoffPolicy.EXPONENTIAL
                    ),
                    scheduledAtEpochMs = downloadEntity.scheduledAtEpochMs.takeIf { it > 0L },
                    checksum = downloadEntity.toChecksum(),
                    autoRenameIfExists = downloadEntity.autoRenameIfExists
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
        workManager.cancelUniqueWork(DownloadWorkCoordinator.downloadWorkName(id)).await()
        workManager.cancelUniqueWork(DownloadWorkCoordinator.scheduleWorkName(id)).await()
        val latest = downloadDao.find(id)
        if (latest?.userAction == UserAction.CANCEL.toString() && latest.status.isActiveDownloadStatus()) {
            downloadDao.update(
                latest.copy(
                    status = Status.CANCELLED.toString(),
                    uuid = "",
                    lastModified = System.currentTimeMillis()
                )
            )
            deleteDownloadFiles(latest.path, latest.fileName)
        }
        scheduleQueuedDownloads()
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
        workManager.cancelUniqueWork(DownloadWorkCoordinator.downloadWorkName(id)).await()
        workManager.cancelUniqueWork(DownloadWorkCoordinator.scheduleWorkName(id)).await()
        val latest = downloadDao.find(id)
        if (latest?.userAction == UserAction.PAUSE.toString() && latest.status.isActiveDownloadStatus()) {
            downloadDao.update(
                latest.copy(
                    status = Status.PAUSED.toString(),
                    uuid = "",
                    lastModified = System.currentTimeMillis()
                )
            )
        }
        scheduleQueuedDownloads()
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
                    priority = com.sherafatpour.bluetile.DownloadPriority.entries.find { it.value == downloadEntity.priority }
                        ?: com.sherafatpour.bluetile.DownloadPriority.NORMAL,
                    constraints = DownloadConstraints(
                        networkType = BTDownloaderNetworkType.entries.find { it.name == downloadEntity.networkType }
                            ?: BTDownloaderNetworkType.CONNECTED,
                        requiresCharging = downloadEntity.requiresCharging,
                        requiresBatteryNotLow = downloadEntity.requiresBatteryNotLow,
                        requiresStorageNotLow = downloadEntity.requiresStorageNotLow
                    ),
                    retryPolicy = RetryPolicy(
                        maxRetries = downloadEntity.maxRetries,
                        backoffDelayInMs = downloadEntity.backoffDelayInMs,
                        backoffPolicy = BTDownloaderBackoffPolicy.entries.find { it.name == downloadEntity.backoffPolicy }
                            ?: BTDownloaderBackoffPolicy.EXPONENTIAL
                    ),
                    scheduledAtEpochMs = downloadEntity.scheduledAtEpochMs.takeIf { it > 0L },
                    checksum = downloadEntity.toChecksum(),
                    autoRenameIfExists = downloadEntity.autoRenameIfExists
                )
            )
        }
    }

    private suspend fun setPriority(id: Int, priority: DownloadPriority) {
        val downloadEntity = downloadDao.find(id) ?: return
        downloadDao.update(
            downloadEntity.copy(
                priority = priority.value,
                lastModified = System.currentTimeMillis()
            )
        )
        scheduleQueuedDownloads()
    }

    private suspend fun startNow(id: Int) {
        val target = downloadDao.find(id) ?: return

        val targetPriority = DownloadPriority.IMMEDIATE
        if (target.scheduledAtEpochMs > 0L &&
            target.uuid.isEmpty() &&
            target.status in listOf(Status.SCHEDULED.toString(), Status.QUEUED.toString())
        ) {
            workManager.cancelUniqueWork(DownloadWorkCoordinator.scheduleWorkName(id)).await()
        }
        if (target.status == Status.STARTED.toString() || target.status == Status.PROGRESS.toString()) {
            setPriority(id, targetPriority)
            return
        }

        val activeDownloads = downloadDao.getAllEntity().filter {
            it.id != id &&
                it.uuid.isNotEmpty() &&
                it.status in listOf(
                    Status.SCHEDULED.toString(),
                    Status.STARTED.toString(),
                    Status.PROGRESS.toString()
                )
        }

        val runningCount = activeDownloads.size
        if (runningCount >= downloadConfig.maxConcurrentDownloads) {
            pickPreemptionCandidate(activeDownloads)?.let { candidate ->
                logger.log(
                    msg = "Manual start preemption: Pausing ID ${candidate.id} to start ID $id now."
                )
                preemptedByManualStart[id] = candidate.id
                pause(candidate.id)
            }
        }

        val updatedTarget = downloadDao.find(id) ?: return
        downloadDao.update(
            updatedTarget.copy(
                priority = targetPriority.value,
                userAction = UserAction.START.toString(),
                status = if (updatedTarget.status == Status.PAUSED.toString() ||
                    updatedTarget.status == Status.FAILED.toString() ||
                    updatedTarget.status == Status.SCHEDULED.toString()
                ) {
                    Status.QUEUED.toString()
                } else {
                    updatedTarget.status
                },
                uuid = if (updatedTarget.status == Status.PAUSED.toString() ||
                    updatedTarget.status == Status.FAILED.toString() ||
                    updatedTarget.status == Status.SCHEDULED.toString()
                ) {
                    ""
                } else {
                    updatedTarget.uuid
                },
                lastModified = System.currentTimeMillis()
            )
        )
        scheduleQueuedDownloads()
    }

    private suspend fun resumePreemptedIfAny(manualStartId: Int) {
        val pausedId = preemptedByManualStart.remove(manualStartId) ?: return
        val pausedEntity = downloadDao.find(pausedId) ?: return
        if (pausedEntity.status == Status.PAUSED.toString()) {
            logger.log(msg = "Resuming preempted download ID $pausedId after manual-start ID $manualStartId.")
            resume(pausedId)
        }
    }

    private fun pickPreemptionCandidate(activeDownloads: List<DownloadEntity>): DownloadEntity? {
        return activeDownloads.minWithOrNull(
            compareBy<DownloadEntity> { it.priority }
                .thenByDescending { progressPercent(it) }
                .thenByDescending { it.timeQueued }
        )
    }

    private fun progressPercent(entity: DownloadEntity): Int {
        return if (entity.totalBytes > 0L) {
            ((entity.downloadedBytes * 100L) / entity.totalBytes).toInt()
        } else {
            0
        }
    }

    private suspend fun cleanupIncompleteDownloads(olderThanMs: Long) {
        val cutoff = System.currentTimeMillis() - olderThanMs
        downloadDao.getAllEntity()
            .filter { entity ->
                entity.lastModified <= cutoff &&
                    entity.status in listOf(
                        Status.QUEUED.toString(),
                        Status.SCHEDULED.toString(),
                        Status.STARTED.toString(),
                        Status.PROGRESS.toString(),
                        Status.PAUSED.toString(),
                        Status.FAILED.toString()
                    )
            }
            .forEach { entity ->
                cancel(entity.id)
                deleteDownloadFiles(entity.path, entity.fileName)
                downloadDao.remove(entity.id)
                removeNotification(context, entity.id)
                removeNotification(context, entity.id + 1)
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

    suspend fun resumeAwait(id: Int) {
        resume(id)
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

    suspend fun cancelAwait(id: Int) {
        cancel(id)
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

    suspend fun pauseAwait(id: Int) {
        pause(id)
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

    suspend fun retryAwait(id: Int) {
        retry(id)
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

    fun setPriorityAsync(id: Int, priority: DownloadPriority) {
        scope.launch {
            setPriority(id, priority)
        }
    }

    fun cleanupIncompleteDownloadsAsync(olderThanMs: Long) {
        scope.launch {
            cleanupIncompleteDownloads(olderThanMs)
        }
    }

    fun startNowAsync(id: Int) {
        scope.launch {
            startNow(id)
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
