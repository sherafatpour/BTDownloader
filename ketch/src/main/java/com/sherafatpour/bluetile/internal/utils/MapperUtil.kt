package com.sherafatpour.bluetile.internal.utils

import com.sherafatpour.bluetile.DownloadModel
import com.sherafatpour.bluetile.DownloadPriority
import com.sherafatpour.bluetile.Status
import com.sherafatpour.bluetile.internal.database.DownloadEntity

// Mapper function to convert DownloadEntity to DownloadModel
internal fun DownloadEntity.toDownloadModel() =
    DownloadModel(
        url = url,
        path = path,
        fileName = fileName,
        tag = tag,
        id = id,
        headers = WorkUtil.jsonToHashMap(headersJson),
        timeQueued = timeQueued,
        status = Status.entries.find { it.name == status } ?: Status.DEFAULT,
        total = totalBytes,
        progress = if (totalBytes.toInt() != 0) ((downloadedBytes * 100) / totalBytes).toInt() else 0,
        speedInBytePerMs = speedInBytePerMs,
        lastModified = lastModified,
        eTag = eTag,
        metaData = metaData,
        failureReason = failureReason,
        notificationTitle = notificationTitle,
        notificationParameter = notificationParameter,
        priority = DownloadPriority.entries.find { it.value == priority } ?: DownloadPriority.NORMAL,
        runAttemptCount = runAttemptCount,
        maxRetries = maxRetries
    )
