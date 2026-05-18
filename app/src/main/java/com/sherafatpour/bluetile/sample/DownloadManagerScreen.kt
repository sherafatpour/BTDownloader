package com.sherafatpour.bluetile.sample

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sherafatpour.bluetile.BTDownloaderNetworkType
import com.sherafatpour.bluetile.DownloadModel
import com.sherafatpour.bluetile.DownloadPriority
import com.sherafatpour.bluetile.Status
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private enum class DownloadTab { ALL, ACTIVE, QUEUED, SCHEDULED, COMPLETED, FAILED }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadManagerScreen(
    uiState: DownloadManagerUiState,
    onIntent: (DownloadIntent) -> Unit,
    onOpenFile: (DownloadModel) -> Unit
) {
    var selectedTab by rememberSaveable { mutableStateOf(DownloadTab.ALL) }
    val shownItems = remember(uiState.downloads, selectedTab) {
        when (selectedTab) {
            DownloadTab.ALL -> uiState.downloads
            DownloadTab.ACTIVE -> uiState.downloads.filter { it.status == Status.PROGRESS || it.status == Status.STARTED }
            DownloadTab.QUEUED -> uiState.downloads.filter { it.status == Status.QUEUED }
            DownloadTab.SCHEDULED -> uiState.downloads.filter { it.status == Status.SCHEDULED }
            DownloadTab.COMPLETED -> uiState.downloads.filter { it.status == Status.SUCCESS }
            DownloadTab.FAILED -> uiState.downloads.filter { it.status == Status.FAILED || it.status == Status.CANCELLED }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("BTDownloader") },
                actions = {
                    IconButton(onClick = { onIntent(DownloadIntent.PauseAll) }) { Icon(Icons.Default.Pause, null) }
                    IconButton(onClick = { onIntent(DownloadIntent.ResumeAll) }) { Icon(Icons.Default.PlayArrow, null) }
                    IconButton(onClick = { onIntent(DownloadIntent.ClearCompleted) }) { Icon(Icons.Default.Refresh, null) }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { onIntent(DownloadIntent.ShowAddDialog) }) {
                Icon(Icons.Default.Add, contentDescription = "Add")
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = selectedTab.ordinal) {
                DownloadTab.entries.forEach { tab ->
                    Tab(selected = selectedTab == tab, onClick = { selectedTab = tab }, text = { Text(tab.name) })
                }
            }

            if (uiState.isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Loading...") }
            } else if (shownItems.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No downloads") }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(shownItems, key = { it.id }) { item ->
                        ModernDownloadItem(
                            item = item,
                            onPauseClick = { onIntent(DownloadIntent.Pause(item.id)) },
                            onResumeClick = { onIntent(DownloadIntent.Resume(item.id)) },
                            onRetryClick = { onIntent(DownloadIntent.Retry(item.id)) },
                            onCancelClick = { onIntent(DownloadIntent.Cancel(item.id)) },
                            onDeleteClick = { onIntent(DownloadIntent.Delete(item.id)) },
                            onOpenClick = { onOpenFile(item) },
                            onStartNowClick = { onIntent(DownloadIntent.StartNow(item.id)) }
                        )
                    }
                }
            }
        }

        if (uiState.showAddDialog) {
            AddDownloadDialog(
                onDismiss = { onIntent(DownloadIntent.HideAddDialog) },
                onSubmit = { onIntent(DownloadIntent.EnqueueCustom(it)) }
            )
        }
    }
}

@Composable
private fun AddDownloadDialog(
    onDismiss: () -> Unit,
    onSubmit: (NewDownloadInput) -> Unit
) {
    val context = LocalContext.current
    var url by rememberSaveable { mutableStateOf("") }
    var fileName by rememberSaveable { mutableStateOf("") }
    var scheduleAfterMinutes by rememberSaveable { mutableStateOf("0") }
    var scheduledAtEpochMs by rememberSaveable { mutableStateOf<Long?>(null) }
    var requiresCharging by rememberSaveable { mutableStateOf(false) }
    var priorityExpanded by remember { mutableStateOf(false) }
    var networkExpanded by remember { mutableStateOf(false) }
    var selectedPriority by rememberSaveable { mutableStateOf(DownloadPriority.NORMAL) }
    var selectedNetwork by rememberSaveable { mutableStateOf(BTDownloaderNetworkType.CONNECTED) }
    val scheduledLabel = scheduledAtEpochMs?.let { formatEpoch(it) } ?: "Not set"

    fun pickDateTime() {
        val now = Calendar.getInstance()
        DatePickerDialog(
            context,
            { _, year, month, dayOfMonth ->
                val selected = Calendar.getInstance().apply {
                    set(Calendar.YEAR, year)
                    set(Calendar.MONTH, month)
                    set(Calendar.DAY_OF_MONTH, dayOfMonth)
                }
                TimePickerDialog(
                    context,
                    { _, hourOfDay, minute ->
                        selected.set(Calendar.HOUR_OF_DAY, hourOfDay)
                        selected.set(Calendar.MINUTE, minute)
                        selected.set(Calendar.SECOND, 0)
                        selected.set(Calendar.MILLISECOND, 0)
                        scheduledAtEpochMs = selected.timeInMillis
                    },
                    now.get(Calendar.HOUR_OF_DAY),
                    now.get(Calendar.MINUTE),
                    true
                ).show()
            },
            now.get(Calendar.YEAR),
            now.get(Calendar.MONTH),
            now.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Download") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = url, onValueChange = { url = it }, label = { Text("URL") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = fileName, onValueChange = { fileName = it }, label = { Text("File name (optional)") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = scheduleAfterMinutes, onValueChange = { scheduleAfterMinutes = it.filter { c -> c.isDigit() } }, label = { Text("Start after (minutes)") }, modifier = Modifier.fillMaxWidth())
                Text("Scheduled time: $scheduledLabel", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { pickDateTime() }) { Text("Pick date/time") }
                    Button(onClick = { scheduledAtEpochMs = null }) { Text("Clear") }
                }

                Box {
                    Button(onClick = { priorityExpanded = true }) { Text("Priority: ${selectedPriority.name}") }
                    DropdownMenu(expanded = priorityExpanded, onDismissRequest = { priorityExpanded = false }) {
                        DownloadPriority.entries.forEach { p ->
                            DropdownMenuItem(text = { Text(p.name) }, onClick = { selectedPriority = p; priorityExpanded = false })
                        }
                    }
                }

                Box {
                    Button(onClick = { networkExpanded = true }) { Text("Network: ${selectedNetwork.name}") }
                    DropdownMenu(expanded = networkExpanded, onDismissRequest = { networkExpanded = false }) {
                        BTDownloaderNetworkType.entries.forEach { n ->
                            DropdownMenuItem(text = { Text(n.name) }, onClick = { selectedNetwork = n; networkExpanded = false })
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = requiresCharging, onCheckedChange = { requiresCharging = it })
                    Text("Require charging")
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                onSubmit(
                    NewDownloadInput(
                        url = url.trim(),
                        fileName = fileName.trim(),
                        priority = selectedPriority,
                        networkType = selectedNetwork,
                        requiresCharging = requiresCharging,
                        scheduleAfterMinutes = scheduleAfterMinutes.toIntOrNull() ?: 0,
                        scheduledAtEpochMs = scheduledAtEpochMs
                    )
                )
            }) {
                Text("Start")
            }
        },
        dismissButton = { Button(onClick = onDismiss) { Text("Cancel") } }
    )
}

private fun formatEpoch(epochMs: Long): String {
    return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(epochMs))
}

@Composable
private fun ModernDownloadItem(
    item: DownloadModel,
    onPauseClick: () -> Unit,
    onResumeClick: () -> Unit,
    onRetryClick: () -> Unit,
    onCancelClick: () -> Unit,
    onDeleteClick: () -> Unit,
    onOpenClick: () -> Unit,
    onStartNowClick: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
        Column {
            Row(modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 16.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(imageVector = fileIcon(item.fileName), contentDescription = null, modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.size(16.dp))
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(text = item.fileName, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        text = statusText(item),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (item.status == Status.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                when (item.status) {
                    Status.PROGRESS, Status.STARTED -> IconButton(onClick = onPauseClick) { Icon(Icons.Default.Pause, null) }
                    Status.PAUSED, Status.QUEUED, Status.SCHEDULED -> IconButton(onClick = onResumeClick) { Icon(Icons.Default.PlayArrow, null) }
                    Status.FAILED -> IconButton(onClick = onRetryClick) { Icon(Icons.Default.Refresh, null) }
                    Status.SUCCESS -> IconButton(onClick = onOpenClick) { Icon(Icons.Default.FolderOpen, null) }
                    else -> Unit
                }

                Box {
                    IconButton(onClick = { menuExpanded = true }) { Icon(Icons.Default.MoreVert, null) }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        DropdownMenuItem(text = { Text("Start now") }, onClick = { onStartNowClick(); menuExpanded = false })
                        DropdownMenuItem(text = { Text("Cancel") }, onClick = { onCancelClick(); menuExpanded = false })
                        DropdownMenuItem(text = { Text("Delete", color = MaterialTheme.colorScheme.error) }, onClick = { onDeleteClick(); menuExpanded = false })
                    }
                }
            }

            when (item.status) {
                Status.PROGRESS, Status.STARTED -> LinearProgressIndicator(progress = { item.progress.coerceIn(0, 100) / 100f }, modifier = Modifier.fillMaxWidth())
                Status.PAUSED -> LinearProgressIndicator(progress = { item.progress.coerceIn(0, 100) / 100f }, modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
                Status.FAILED -> LinearProgressIndicator(progress = { 1f }, modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.error)
                else -> Unit
            }
            Spacer(modifier = Modifier.height(4.dp))
        }
    }
}

private fun statusText(item: DownloadModel): String {
    return when (item.status) {
        Status.PROGRESS, Status.STARTED -> "${item.progress}% • ${Util.getSpeedText(item.speedInBytePerMs)} • ${Util.getTotalLengthText(item.total)}"
        Status.PAUSED -> "Paused • ${item.progress}%"
        Status.FAILED -> item.failureReason.ifBlank { "Download failed" }
        Status.SUCCESS -> "Completed • ${Util.getTotalLengthText(item.total)}"
        Status.QUEUED -> "Queued..."
        Status.SCHEDULED -> if (item.scheduledAtEpochMs > System.currentTimeMillis()) "Scheduled" else "Waiting constraints"
        Status.CANCELLED -> "Cancelled"
        else -> item.status.name
    }
}

private fun fileIcon(fileName: String): ImageVector {
    val name = fileName.lowercase()
    return when {
        name.endsWith(".mp4") || name.endsWith(".mov") -> Icons.Default.VideoFile
        name.endsWith(".mp3") || name.endsWith(".wav") -> Icons.Default.MusicNote
        name.endsWith(".jpg") || name.endsWith(".png") -> Icons.Default.Image
        name.endsWith(".pdf") -> Icons.Default.PictureAsPdf
        name.endsWith(".zip") || name.endsWith(".rar") -> Icons.Default.FolderZip
        else -> Icons.AutoMirrored.Filled.Article
    }
}
