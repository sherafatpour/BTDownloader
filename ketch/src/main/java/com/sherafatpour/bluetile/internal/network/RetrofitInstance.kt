package com.sherafatpour.bluetile.internal.network

import com.sherafatpour.bluetile.internal.utils.DownloadConst
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit

internal object RetrofitInstance {

    fun getDownloadService(
        connectTimeOutInMs: Long = DownloadConst.DEFAULT_VALUE_CONNECT_TIMEOUT_MS,
        readTimeOutInMs: Long = DownloadConst.DEFAULT_VALUE_READ_TIMEOUT_MS
    ): DownloadService {
        return Retrofit
            .Builder()
            .baseUrl(DownloadConst.BASE_URL)
            .client(
                OkHttpClient
                    .Builder()
                    .followRedirects(true)
                    .followSslRedirects(true)
                    .connectTimeout(connectTimeOutInMs, TimeUnit.MILLISECONDS)
                    .readTimeout(readTimeOutInMs, TimeUnit.MILLISECONDS)
                    .build()
            )
            .build()
            .create(DownloadService::class.java)
    }
}
