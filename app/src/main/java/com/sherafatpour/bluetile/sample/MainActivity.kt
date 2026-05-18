package com.sherafatpour.bluetile.sample

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sherafatpour.bluetile.DownloadModel
import com.sherafatpour.bluetile.Status
import java.io.File

class MainActivity : ComponentActivity() {

    private lateinit var viewModel: DownloadManagerViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNotificationPermissionIfNeeded()

        val btDownloader = (applicationContext as MainApplication).btDownload
        val downloadDir = File(getExternalFilesDir(null), "btdownloader-downloads").apply { mkdirs() }
        val factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return DownloadManagerViewModel(btDownloader, downloadDir.absolutePath) as T
            }
        }
        viewModel = ViewModelProvider(this, factory)[DownloadManagerViewModel::class.java]

        setContent {
            val uiState = viewModel.uiState.collectAsStateWithLifecycle().value
            DownloadManagerScreen(
                uiState = uiState,
                onIntent = viewModel::onIntent,
                onOpenFile = ::openFile
            )
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
            Toast.makeText(this, "Notification permission requested", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openFile(download: DownloadModel) {
        if (download.status != Status.SUCCESS) return
        val file = File(download.path, download.fileName)
        if (!file.exists()) {
            Toast.makeText(this, "File not found", Toast.LENGTH_SHORT).show()
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.provider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, contentResolver.getType(uri))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { startActivity(intent) }
            .onFailure { Toast.makeText(this, "No app found to open file", Toast.LENGTH_SHORT).show() }
    }
}
