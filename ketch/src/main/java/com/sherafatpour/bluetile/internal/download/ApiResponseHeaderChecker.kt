package com.sherafatpour.bluetile.internal.download

import com.sherafatpour.bluetile.internal.network.DownloadService

internal class ApiResponseHeaderChecker(
    private val url: String,
    private val downloadService: DownloadService,
    private val headers: Map<String, String> = emptyMap()
) {
    suspend fun getHeaderValue(
        header: String
    ): String? {
        val response = downloadService.getHeadersOnly(url, headers)
        return response.headers().get(header)
    }

}
