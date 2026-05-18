package com.sherafatpour.bluetile.sample

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import com.sherafatpour.bluetile.DownloadConstraints
import com.sherafatpour.bluetile.DownloadModel
import com.sherafatpour.bluetile.DownloadPriority
import com.sherafatpour.bluetile.BTDownloaderNetworkType
import com.sherafatpour.bluetile.BTDownloader
import com.sherafatpour.bluetile.BTDownloaderBackoffPolicy
import com.sherafatpour.bluetile.RetryPolicy
import com.sherafatpour.bluetile.Status
import com.sherafatpour.bluetile.sample.databinding.FragmentMainBinding
import com.sherafatpour.bluetile.sample.databinding.ItemFileBinding
import kotlinx.coroutines.launch
import java.io.File


class MainFragment : Fragment() {

    private lateinit var fragmentMainBinding: FragmentMainBinding
    private lateinit var adapter: FilesAdapter
    private lateinit var btDownload: BTDownloader
    private val downloadDir: File by lazy {
        File(requireContext().getExternalFilesDir(null), "btdownloader-downloads").apply {
            mkdirs()
        }
    }

    private val defaultConstraints = DownloadConstraints(
        networkType = BTDownloaderNetworkType.CONNECTED,
        requiresCharging = false,
        requiresBatteryNotLow = false,
        requiresStorageNotLow = false
    )

    private val defaultRetryPolicy = RetryPolicy(
        maxRetries = 2,
        backoffDelayInMs = 5_000L,
        backoffPolicy = BTDownloaderBackoffPolicy.EXPONENTIAL
    )

    companion object {
        fun newInstance(): MainFragment {
            val args = Bundle()
            val fragment = MainFragment()
            fragment.arguments = args
            return fragment
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        super.onCreateView(inflater, container, savedInstanceState)
        btDownload = (requireContext().applicationContext as MainApplication).btDownload
        fragmentMainBinding = FragmentMainBinding.inflate(inflater)
        return fragmentMainBinding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        adapter = FilesAdapter(object : FilesAdapter.FileClickListener {
            override fun onFileClick(downloadItem: DownloadModel) {
                if (downloadItem.status == Status.SUCCESS) {
                    val file = File(downloadItem.path, downloadItem.fileName)
                    if (file.exists()) {
                        val uri = this@MainFragment.context?.applicationContext?.let {
                            FileProvider.getUriForFile(
                                it,
                                it.packageName + ".provider",
                                file
                            )
                        }
                        if (uri != null) {
                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(uri, requireContext().contentResolver.getType(uri))
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            try {
                                startActivity(intent)
                            } catch (ignore: Exception) {

                            }
                        }
                    } else {
                        Toast.makeText(
                            this@MainFragment.context,
                            "Something went wrong",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }

            override fun onCancelClick(downloadItem: DownloadModel) {
                btDownload.cancel(downloadItem.id)
            }

            override fun onDownloadClick(downloadItem: DownloadModel) {
                enqueueDownload(
                    SampleDownloadRequest(
                        title = downloadItem.notificationTitle.ifEmpty { downloadItem.fileName },
                        url = downloadItem.url,
                        fileName = downloadItem.fileName,
                        tag = downloadItem.tag,
                        metaData = downloadItem.metaData,
                        notificationParameter = downloadItem.notificationParameter
                    )
                )
            }

            override fun onPauseClick(downloadItem: DownloadModel) {
                btDownload.pause(downloadItem.id)
            }

            override fun onResumeClick(downloadItem: DownloadModel) {
                btDownload.resume(downloadItem.id)
            }

            override fun onRetryClick(downloadItem: DownloadModel) {
                btDownload.retry(downloadItem.id)
            }

            override fun onDeleteClick(downloadItem: DownloadModel) {
                btDownload.clearDb(downloadItem.id)
            }

            override fun onPrioritySelected(downloadItem: DownloadModel, priority: DownloadPriority) {
                btDownload.setPriority(downloadItem.id, priority)
            }
        })
        fragmentMainBinding.recyclerView.adapter = adapter
        (fragmentMainBinding.recyclerView.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations =
            false

        fragmentMainBinding.recyclerView.layoutManager =
            LinearLayoutManager(this.context, LinearLayoutManager.VERTICAL, false)
        fragmentMainBinding.recyclerView.addItemDecoration(
            DividerItemDecoration(
                this.context,
                DividerItemDecoration.VERTICAL
            )
        )

        fragmentMainBinding.bt1.text = "Video 1"
        fragmentMainBinding.bt1.setOnClickListener {
            enqueueDownload(
                SampleDownloadRequest(
                    title = "Sample Video 1",
                    url = "https://bluetile-static.s3.ir-thr-at1.arvanstorage.ir/movies/b3811917-d1ea-4bfb-bd78-acd1f4d148c4.mp4",
                    fileName = "sample_video_1.mp4",
                    tag = "Video",
                    metaData = "158",
                    notificationParameter = "REZA2",
                    priority = DownloadPriority.HIGH
                )
            )
        }

        fragmentMainBinding.bt2.text = "Video 2"
        fragmentMainBinding.bt2.setOnClickListener {
            enqueueDownload(
                SampleDownloadRequest(
                    title = "Sample Video 2",
                    url = "https://persian1.asset.aparat.com/aparat-video/cd2bd8b3cd85d16ff91948d25166683270721300-720p.mp4?wmsAuthSign=eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJ0b2tlbiI6ImY3NmYyYzRjMTcwYjI1YWNiMGJmMTY4NDUwNjM4MDM1IiwiZXhwIjoxNzc5MDIxNzM2LCJpc3MiOiJTYWJhIElkZWEgR1NJRyJ9.Txo62_m9Re2jIibBKT4wGDwikEyfReT7Mf9djVdDwQc",
                    fileName = "sample_video_2.mp4",
                    tag = "Video",
                    metaData = "169",
                    notificationParameter = "REZA1"
                )
            )
        }

        fragmentMainBinding.bt3.text = "Video 3"
        fragmentMainBinding.bt3.setOnClickListener {
            enqueueDownload(
                SampleDownloadRequest(
                    title = "Sample Video 3",
                    url = "https://raw.githubusercontent.com/mdn/learning-area/main/html/multimedia-and-embedding/video-and-audio-content/rabbit320.mp4",
                    fileName = "sample_video_3.mp4",
                    tag = "Video",
                    metaData = "48",
                    notificationParameter = "REZA5"
                )
            )
        }

        fragmentMainBinding.bt4.text = "Image 1"
        fragmentMainBinding.bt4.setOnClickListener {
            enqueueDownload(
                SampleDownloadRequest(
                    title = "Sample Image 1",
                    url = "https://www.gstatic.com/webp/gallery/1.jpg",
                    fileName = "sample_image_1.jpg",
                    tag = "Image",
                    metaData = "1",
                    notificationParameter = "REZA6"
                )
            )
        }

        fragmentMainBinding.bt5.text = "Pdf 1"
        fragmentMainBinding.bt5.setOnClickListener {
            enqueueDownload(
                SampleDownloadRequest(
                    title = "Sample Pdf 1",
                    url = "https://raw.githubusercontent.com/mozilla/pdf.js/master/examples/learning/helloworld.pdf",
                    fileName = "sample_pdf_1.pdf",
                    tag = "Document",
                    metaData = "5",
                    notificationParameter = "REZA8"
                )
            )
        }

        fragmentMainBinding.bt6.text = "Multiple"
        fragmentMainBinding.bt6.setOnClickListener {
            listOf(
                SampleDownloadRequest(
                    title = "Rabbit sample",
                    url = "https://raw.githubusercontent.com/mdn/learning-area/main/html/multimedia-and-embedding/video-and-audio-content/rabbit320.mp4",
                    fileName = "rabbit.mp4",
                    tag = "Batch",
                    priority = DownloadPriority.HIGH
                ),
                SampleDownloadRequest(
                    title = "WebP image",
                    url = "https://www.gstatic.com/webp/gallery/2.jpg",
                    fileName = "gallery_2.jpg",
                    tag = "Batch",
                    priority = DownloadPriority.NORMAL
                ),
                SampleDownloadRequest(
                    title = "Hello PDF",
                    url = "https://raw.githubusercontent.com/mozilla/pdf.js/master/examples/learning/helloworld.pdf",
                    fileName = "hello.pdf",
                    tag = "Batch",
                    priority = DownloadPriority.LOW
                )
            ).forEach(::enqueueDownload)
        }
        fragmentMainBinding.pauseAllButton.setOnClickListener {
            btDownload.pauseAll()
        }
        fragmentMainBinding.resumeAllButton.setOnClickListener {
            btDownload.resumeAll()
        }
        fragmentMainBinding.clearCompletedButton.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                btDownload.getAllDownloads()
                    .filter { it.status == Status.SUCCESS }
                    .forEach { btDownload.clearDb(it.id) }
            }
        }
        observer()

    }

    private fun observer() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                btDownload.observeDownloads()
                    .collect {
                        adapter.submitList(it)
                    }
            }
        }
    }

    private fun enqueueDownload(request: SampleDownloadRequest) {
        val id = btDownload.download(
            url = request.url,
            path = downloadDir.absolutePath,
            fileName = request.fileName,
            tag = request.tag,
            metaData = request.metaData,
            notificationTitle = request.title,
            notificationParameter = request.notificationParameter,
            headers = hashMapOf(
                "Accept" to request.acceptHeader
            ),
            priority = request.priority,
            constraints = defaultConstraints,
            retryPolicy = defaultRetryPolicy
        )
        Toast.makeText(requireContext(), "Queued download #$id", Toast.LENGTH_SHORT).show()
    }

    private data class SampleDownloadRequest(
        val title: String,
        val url: String,
        val fileName: String,
        val tag: String,
        val metaData: String = "",
        val notificationParameter: String = "",
        val priority: DownloadPriority = DownloadPriority.NORMAL,
        val acceptHeader: String = "*/*"
    )
}


class FilesAdapter(private val listener: FileClickListener) :
    ListAdapter<DownloadModel, FilesAdapter.ViewHolder>(
        DiffCallback()
    ) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemFileBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(private val binding: ItemFileBinding) :
        RecyclerView.ViewHolder(binding.root) {
        @SuppressLint("SetTextI18n")
        fun bind(downloadModel: DownloadModel) {
            binding.fileName.text = downloadModel.fileName
            binding.status.text = if (
                downloadModel.status == Status.FAILED &&
                downloadModel.failureReason.isNotBlank()
            ) {
                "${downloadModel.status}: ${downloadModel.failureReason}"
            } else {
                downloadModel.status.toString()
            }
            binding.progressBar.progress = downloadModel.progress
            binding.progressText.text =
                downloadModel.progress.toString() + "%/" + Util.getTotalLengthText(downloadModel.total) + ", "
            binding.size.text = Util.getTimeLeftText(
                downloadModel.speedInBytePerMs,
                downloadModel.progress,
                downloadModel.total
            ) + ", " + Util.getSpeedText(downloadModel.speedInBytePerMs)
            binding.prioritySpinner.adapter = ArrayAdapter(
                binding.root.context,
                android.R.layout.simple_spinner_dropdown_item,
                DownloadPriority.entries.map { it.name }
            )
            binding.prioritySpinner.setSelection(DownloadPriority.entries.indexOf(downloadModel.priority))
            binding.prioritySpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long
                ) {
                    val selectedPriority = DownloadPriority.entries[position]
                    if (selectedPriority != downloadModel.priority) {
                        listener.onPrioritySelected(downloadModel, selectedPriority)
                    }
                }

                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }

            binding.downloadButton.setOnClickListener {
                listener.onDownloadClick(downloadModel)
            }
            binding.cancelButton.setOnClickListener {
                listener.onCancelClick(downloadModel)
            }
            binding.pauseButton.setOnClickListener {
                listener.onPauseClick(downloadModel)
            }
            binding.resumeButton.setOnClickListener {
                listener.onResumeClick(downloadModel)
            }
            binding.deleteButton.setOnClickListener {
                listener.onDeleteClick(downloadModel)
            }
            binding.retryButton.setOnClickListener {
                listener.onRetryClick(downloadModel)
            }
            binding.root.setOnClickListener {
                listener.onFileClick(downloadModel)
            }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<DownloadModel>() {
        override fun areItemsTheSame(oldItem: DownloadModel, newItem: DownloadModel): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: DownloadModel, newItem: DownloadModel): Boolean {
            return (oldItem == newItem)
        }

    }

    interface FileClickListener {
        fun onFileClick(downloadItem: DownloadModel)
        fun onCancelClick(downloadItem: DownloadModel)
        fun onDownloadClick(downloadItem: DownloadModel)
        fun onPauseClick(downloadItem: DownloadModel)
        fun onResumeClick(downloadItem: DownloadModel)
        fun onRetryClick(downloadItem: DownloadModel)
        fun onDeleteClick(downloadItem: DownloadModel)
        fun onPrioritySelected(downloadItem: DownloadModel, priority: DownloadPriority)
    }

}
