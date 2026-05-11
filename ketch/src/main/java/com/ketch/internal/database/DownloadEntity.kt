package com.ketch.internal.database

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.ketch.DownloadPriority
import com.ketch.Status
import com.ketch.KetchNetworkType
import com.ketch.KetchBackoffPolicy
import com.ketch.internal.utils.UserAction

@Entity(
    tableName = "downloads"
)
internal data class DownloadEntity(
    var url: String = "",
    var path: String = "",
    var fileName: String = "",
    var tag: String = "",
    var notificationParameter: String = "",
    var notificationTitle: String = "",
    @PrimaryKey
    var id: Int = 0,
    var headersJson: String = "",
    var timeQueued: Long = 0,
    var status: String = Status.DEFAULT.toString(),
    var totalBytes: Long = 0,
    var downloadedBytes: Long = 0,
    var speedInBytePerMs: Float = 0f,
    var uuid: String = "",
    var lastModified: Long = 0,
    var eTag: String = "",
    var userAction: String = UserAction.DEFAULT.toString(),
    var metaData: String = "",
    var failureReason: String = "",
    var priority: Int = DownloadPriority.NORMAL.value,
    var networkType: String = KetchNetworkType.CONNECTED.toString(),
    var requiresCharging: Boolean = false,
    var requiresBatteryNotLow: Boolean = false,
    var requiresStorageNotLow: Boolean = false,
    var maxRetries: Int = 3,
    var backoffDelayInMs: Long = 10_000L,
    var backoffPolicy: String = KetchBackoffPolicy.EXPONENTIAL.toString(),
    var runAttemptCount: Int = 0
)
