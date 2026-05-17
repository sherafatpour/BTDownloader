package com.sherafatpour.bluetile

interface Logger {
    companion object {
        const val TAG = "BTDownloaderLogs"
    }

    fun log(
        tag: String? = TAG,
        msg: String? = "",
        tr: Throwable? = null,
        type: LogType = LogType.DEBUG
    )
}

enum class LogType {
    VERBOSE,
    DEBUG,
    INFO,
    WARN,
    ERROR
}
