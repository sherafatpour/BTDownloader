package com.sherafatpour.bluetile.sample

import com.sherafatpour.bluetile.BTDownloaderNetworkType
import com.sherafatpour.bluetile.DownloadPriority
import com.sherafatpour.bluetile.DownloadModel

data class DownloadManagerUiState(
    val downloads: List<DownloadModel> = emptyList(),
    val isLoading: Boolean = true,
    val showAddDialog: Boolean = false
)

data class NewDownloadInput(
    val url: String = "",
    val fileName: String = "",
    val priority: DownloadPriority = DownloadPriority.NORMAL,
    val networkType: BTDownloaderNetworkType = BTDownloaderNetworkType.CONNECTED,
    val requiresCharging: Boolean = false,
    val scheduleAfterMinutes: Int = 0,
    val scheduledAtEpochMs: Long? = null
)

sealed interface DownloadIntent {
    data class Pause(val id: Int) : DownloadIntent
    data class Resume(val id: Int) : DownloadIntent
    data class Retry(val id: Int) : DownloadIntent
    data class Cancel(val id: Int) : DownloadIntent
    data class Delete(val id: Int) : DownloadIntent
    data class StartNow(val id: Int) : DownloadIntent
    data object PauseAll : DownloadIntent
    data object ResumeAll : DownloadIntent
    data object ClearCompleted : DownloadIntent
    data object EnqueueQuick : DownloadIntent
    data object ScheduleQuick : DownloadIntent
    data object ShowAddDialog : DownloadIntent
    data object HideAddDialog : DownloadIntent
    data class EnqueueCustom(val input: NewDownloadInput) : DownloadIntent
}
