package com.sherafatpour.bluetile.internal.network

import com.sherafatpour.bluetile.internal.utils.DownloadConst
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit

internal object RetrofitInstance {

    private val lock = Any()
    private val services = mutableMapOf<String, DownloadService>()

    fun getDownloadService(
        connectTimeOutInMs: Long = DownloadConst.DEFAULT_VALUE_CONNECT_TIMEOUT_MS,
        readTimeOutInMs: Long = DownloadConst.DEFAULT_VALUE_READ_TIMEOUT_MS
    ): DownloadService {
        val key = "$connectTimeOutInMs:$readTimeOutInMs"
        synchronized(lock) {
            return services.getOrPut(key) {
                Retrofit
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
    }
}
