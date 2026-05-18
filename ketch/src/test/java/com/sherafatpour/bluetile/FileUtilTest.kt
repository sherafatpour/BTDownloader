package com.sherafatpour.bluetile

import com.sherafatpour.bluetile.internal.utils.FileUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class FileUtilTest {

    @Test
    fun resolveAvailableFileNameReturnsOriginalWhenNoCollision() {
        val directory = Files.createTempDirectory("btdownloader-test").toFile()

        val resolvedName = FileUtil.resolveAvailableFileName(directory.absolutePath, "movie.mp4")

        assertEquals("movie.mp4", resolvedName)
        directory.deleteRecursively()
    }

    @Test
    fun resolveAvailableFileNameAddsSuffixWhenFinalFileExists() {
        val directory = Files.createTempDirectory("btdownloader-test").toFile()
        File(directory, "movie.mp4").writeText("existing")

        val resolvedName = FileUtil.resolveAvailableFileName(directory.absolutePath, "movie.mp4")

        assertEquals("movie (1).mp4", resolvedName)
        directory.deleteRecursively()
    }

    @Test
    fun checksumReturnsExpectedSha256() {
        val directory = Files.createTempDirectory("btdownloader-test").toFile()
        File(directory, "payload.txt").writeText("btDownload")

        val checksum = FileUtil.checksum(
            path = directory.absolutePath,
            fileName = "payload.txt",
            algorithm = DownloadChecksumAlgorithm.SHA256.messageDigestName
        )

        assertEquals("845f79ca7f9a404be1661dad6dd8dcba98e2708c42df44ca03b55ba86261ab3a", checksum)
        directory.deleteRecursively()
    }

    @Test
    fun hasEnoughFreeSpaceIgnoresUnknownLength() {
        val directory = Files.createTempDirectory("btdownloader-test").toFile()

        assertTrue(FileUtil.hasEnoughFreeSpace(directory.absolutePath, expectedBytes = -1L, bufferBytes = Long.MAX_VALUE))
        directory.deleteRecursively()
    }
}
