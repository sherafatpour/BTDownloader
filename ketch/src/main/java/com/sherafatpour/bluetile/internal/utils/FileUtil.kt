package com.sherafatpour.bluetile.internal.utils

import android.os.Environment
import android.webkit.URLUtil
import java.io.File
import java.math.BigInteger
import java.security.MessageDigest
import java.util.UUID

internal object FileUtil {
    private const val TEMP_EXTENSION = ".bt"

    fun getFileNameFromUrl(url: String): String {
        val guessFileName = URLUtil.guessFileName(url, null, null)
        return UUID.randomUUID().toString() + "-" + guessFileName
    }

    fun getDefaultDownloadPath(): String {
        return Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).path
    }

    fun getUniqueId(url: String, dirPath: String, fileName: String): Int {
        val string = url + File.separator + dirPath + File.separator + fileName
        val hash: ByteArray = try {
            MessageDigest.getInstance("MD5").digest(string.toByteArray(charset("UTF-8")))
        } catch (e: Exception) {
            return getUniqueIdFallback(url, dirPath, fileName)
        }
        return BigInteger(1, hash).mod(BigInteger.valueOf(Int.MAX_VALUE.toLong())).toInt()
    }

    private fun getUniqueIdFallback(url: String, dirPath: String, fileName: String): Int {
        return ((url.hashCode() * 31 + dirPath.hashCode()) * 31 + fileName.hashCode()) and Int.MAX_VALUE
    }

    fun deleteFileIfExists(path: String, name: String) {
        val file = File(path, name)
        if (file.exists()) {
            file.delete()
        }
    }

    fun deleteDownloadFiles(path: String, finalFileName: String) {
        deleteFileIfExists(path, finalFileName)
        deleteFileIfExists(path, getTempFileName(finalFileName))
    }

    fun renameTempFile(tempFileName: String, finalFileName: String, downloadsDirectory: File): Boolean {
        val tempFile = File(downloadsDirectory, tempFileName)
        if (!tempFile.exists()) return false

        val finalFile = File(downloadsDirectory, finalFileName)
        if (finalFile.exists() && !finalFile.delete()) return false

        return tempFile.renameTo(finalFile)
    }

    fun resolveAvailableFileName(path: String, fileName: String): String {
        val directory = File(path)
        val candidate = File(directory, fileName)
        if (!candidate.exists() && !File(directory, getTempFileName(fileName)).exists()) return fileName

        val baseName = fileName.substringBeforeLast(".", fileName)
        val extension = fileName.substringAfterLast(".", "")
        var index = 1
        while (true) {
            val nextName = if (extension.isBlank()) {
                "$baseName ($index)"
            } else {
                "$baseName ($index).$extension"
            }
            if (!File(directory, nextName).exists() && !File(directory, getTempFileName(nextName)).exists()) {
                return nextName
            }
            index++
        }
    }

    fun hasEnoughFreeSpace(path: String, expectedBytes: Long, bufferBytes: Long): Boolean {
        if (expectedBytes <= 0L) return true
        val directory = File(path)
        return directory.usableSpace >= expectedBytes + bufferBytes
    }

    fun checksum(path: String, fileName: String, algorithm: String): String {
        val digest = MessageDigest.getInstance(algorithm)
        File(path, fileName).inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var read = input.read(buffer)
            while (read >= 0) {
                digest.update(buffer, 0, read)
                read = input.read(buffer)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun getTempFileName(fileName: String): String {
        return if (fileName.contains(".")) {
            fileName.substringBeforeLast(".") + TEMP_EXTENSION
        } else {
            fileName + TEMP_EXTENSION
        }
    }

}
