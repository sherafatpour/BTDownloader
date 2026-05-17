package com.khush.sample

import android.app.Application
import com.ketch.BTDownloader
import com.ketch.Ketch
import com.ketch.NotificationConfig

class MainApplication: Application() {

    lateinit var ketch: Ketch

    override fun onCreate() {
        super.onCreate()
        ketch = BTDownloader.builder().setNotificationConfig(
            config = NotificationConfig(
                enabled = true,
                smallIcon = R.drawable.ic_stat_download
            )
        ).enableLogs(true).build(this)
    }

}
