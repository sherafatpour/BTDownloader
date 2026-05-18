package com.sherafatpour.bluetile.sample

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sherafatpour.bluetile.BTDownloader
import com.sherafatpour.bluetile.BTDownloaderNetworkType
import com.sherafatpour.bluetile.DownloadConstraints
import com.sherafatpour.bluetile.DownloadPriority
import com.sherafatpour.bluetile.Status
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class DownloadManagerViewModel(
    private val btDownloader: BTDownloader,
    private val downloadPath: String
) : ViewModel() {

    private val _uiState = MutableStateFlow(DownloadManagerUiState())
    val uiState: StateFlow<DownloadManagerUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            btDownloader.observeDownloads().collect { items ->
                _uiState.update { it.copy(downloads = items, isLoading = false) }
            }
        }
    }

    fun onIntent(intent: DownloadIntent) {
        when (intent) {
            is DownloadIntent.Pause -> btDownloader.pause(intent.id)
            is DownloadIntent.Resume -> btDownloader.resume(intent.id)
            is DownloadIntent.Retry -> btDownloader.retry(intent.id)
            is DownloadIntent.Cancel -> btDownloader.cancel(intent.id)
            is DownloadIntent.Delete -> btDownloader.clearDb(intent.id)
            is DownloadIntent.StartNow -> btDownloader.startNow(intent.id)
            DownloadIntent.PauseAll -> btDownloader.pauseAll()
            DownloadIntent.ResumeAll -> btDownloader.resumeAll()
            DownloadIntent.ClearCompleted -> clearCompleted()
            DownloadIntent.EnqueueQuick -> enqueueQuick()
            DownloadIntent.ScheduleQuick -> scheduleQuick()
            DownloadIntent.ShowAddDialog -> _uiState.update { it.copy(showAddDialog = true) }
            DownloadIntent.HideAddDialog -> _uiState.update { it.copy(showAddDialog = false) }
            is DownloadIntent.EnqueueCustom -> enqueueCustom(intent.input)
        }
    }

    private fun enqueueQuick() {
        btDownloader.download(
            url = "https://www.gstatic.com/webp/gallery/1.jpg",
            path = downloadPath,
            fileName = "quick_${System.currentTimeMillis()}.jpg",
            priority = DownloadPriority.NORMAL
        )
    }

    private fun scheduleQuick() {
        btDownloader.schedule(
            url = "https://raw.githubusercontent.com/mozilla/pdf.js/master/examples/learning/helloworld.pdf",
            path = downloadPath,
            scheduledAtEpochMs = System.currentTimeMillis() + 2 * 60_000L,
            fileName = "scheduled_${System.currentTimeMillis()}.pdf",
            priority = DownloadPriority.NORMAL,
            constraints = DownloadConstraints(
                networkType = BTDownloaderNetworkType.UNMETERED,
                requiresCharging = true
            )
        )
    }

    private fun enqueueCustom(input: NewDownloadInput) {
        if (input.url.isBlank()) return
        val resolvedFileName = input.fileName.ifBlank {
            extractFileNameFromUrl(input.url) ?: "file_${System.currentTimeMillis()}"
        }
        val constraints = DownloadConstraints(
            networkType = input.networkType,
            requiresCharging = input.requiresCharging
        )
        val scheduleAt = input.scheduledAtEpochMs ?: if (input.scheduleAfterMinutes > 0) {
            System.currentTimeMillis() + input.scheduleAfterMinutes * 60_000L
        } else {
            0L
        }
        if (scheduleAt > 0L) {
            btDownloader.schedule(
                url = input.url,
                path = downloadPath,
                scheduledAtEpochMs = scheduleAt,
                fileName = resolvedFileName,
                priority = input.priority,
                constraints = constraints
            )
        } else {
            btDownloader.download(
                url = input.url,
                path = downloadPath,
                fileName = resolvedFileName,
                priority = input.priority,
                constraints = constraints
            )
        }
        _uiState.update { it.copy(showAddDialog = false) }
    }

    private fun extractFileNameFromUrl(url: String): String? {
        return runCatching {
            val raw = Uri.parse(url).lastPathSegment?.substringAfterLast('/')?.trim().orEmpty()
            if (raw.isBlank()) return null
            raw.substringBefore('?').substringBefore('#').takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private fun clearCompleted() {
        viewModelScope.launch {
            btDownloader.getAllDownloads()
                .filter { it.status == Status.SUCCESS }
                .forEach { btDownloader.clearDb(it.id) }
        }
    }
}
