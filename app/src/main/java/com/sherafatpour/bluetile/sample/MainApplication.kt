package com.sherafatpour.bluetile.sample

import android.app.Application
import com.sherafatpour.bluetile.BTDownloader
import com.sherafatpour.bluetile.NotificationConfig

class MainApplication: Application() {

    lateinit var ketch: BTDownloader

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
