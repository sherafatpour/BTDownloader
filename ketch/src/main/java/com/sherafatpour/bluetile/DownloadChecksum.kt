package com.sherafatpour.bluetile

/**
 * Expected checksum for a completed download.
 *
 * BTDownloader verifies the checksum after the temporary `.bt` file has been
 * renamed to the final file. A mismatch marks the download as failed and the
 * corrupt final file is removed.
 */
data class DownloadChecksum(
    val algorithm: DownloadChecksumAlgorithm,
    val value: String
)

/**
 * Hash algorithms supported by BTDownloader checksum verification.
 */
enum class DownloadChecksumAlgorithm(val messageDigestName: String) {
    MD5("MD5"),
    SHA256("SHA-256")
}
