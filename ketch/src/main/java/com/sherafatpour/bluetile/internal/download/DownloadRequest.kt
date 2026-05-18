package com.sherafatpour.bluetile.internal.download

import com.sherafatpour.bluetile.DownloadConstraints
import com.sherafatpour.bluetile.DownloadChecksum
import com.sherafatpour.bluetile.DownloadPriority
import com.sherafatpour.bluetile.RetryPolicy
import com.sherafatpour.bluetile.internal.utils.FileUtil.getUniqueId

internal data class DownloadRequest(
    val url: String,
    val path: String,
    val fileName: String,
    val notificationParameter: String,
    val notificationTitle: String,
    val tag: String,
    val id: Int = getUniqueId(url, path, fileName),
    val headers: HashMap<String, String> = hashMapOf(),
    val metaData: String = "",
    val priority: DownloadPriority = DownloadPriority.NORMAL,
    val constraints: DownloadConstraints = DownloadConstraints(),
    val retryPolicy: RetryPolicy = RetryPolicy(),
    val scheduledAtEpochMs: Long? = null,
    val checksum: DownloadChecksum? = null,
    val autoRenameIfExists: Boolean = false
)
