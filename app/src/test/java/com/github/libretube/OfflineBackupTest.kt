package com.github.libretube

import com.github.libretube.api.JsonHelper
import com.github.libretube.api.obj.StreamItem
import com.github.libretube.db.obj.BookmarkSnapshot
import com.github.libretube.helpers.OfflineBackupHelper
import com.github.libretube.obj.BackupFile
import com.github.libretube.obj.OfflineDownloadBackup
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class OfflineBackupTest {
    @Test(expected = java.io.IOException::class)
    fun rejectsArchiveBeforeExtractingWhenStorageIsInsufficient() {
        val temp = Files.createTempDirectory("backup-space-test").toFile()
        try {
            OfflineBackupHelper.read(ByteArrayInputStream(archive("audio/$id")), temp) { 2L }
        } finally { temp.deleteRecursively() }
    }

    @Test(expected = IllegalArgumentException::class)
    fun limitsPlainJsonManifestSize() {
        val temp = Files.createTempDirectory("backup-json-test").toFile()
        val input = object : java.io.InputStream() {
            private var remaining = 32 * 1024 * 1024 + 1
            override fun read(): Int = if (remaining-- > 0) 32 else -1
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (remaining <= 0) return -1
                val count = minOf(length, remaining)
                java.util.Arrays.fill(buffer, offset, offset + count, 32.toByte())
                remaining -= count
                return count
            }
        }
        try { OfflineBackupHelper.read(input, temp) }
        finally { temp.deleteRecursively() }
    }
    private val id = "QAoffline01"
    private fun archive(entryName: String, expectedSize: Long = 4): ByteArray {
        val backup = BackupFile(downloads = listOf(OfflineDownloadBackup(
            StreamItem(url = id, title = "Saved audio"), "audio.webm", expectedSize)))
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use {
            it.putNextEntry(ZipEntry("manifest.json"))
            it.write(JsonHelper.json.encodeToString(backup).toByteArray())
            it.closeEntry()
            it.putNextEntry(ZipEntry(entryName))
            it.write(byteArrayOf(1, 2, 3, 4))
            it.closeEntry()
        }
        return output.toByteArray()
    }

    @Test fun oldJsonBackupStillLoads() {
        val temp = Files.createTempDirectory("backup-test").toFile()
        try {
            val backup = OfflineBackupHelper.read(ByteArrayInputStream("{\"version\":1}".toByteArray()), temp)
            assertEquals(emptyList<BookmarkSnapshot>(), backup.bookmarkSnapshots)
        } finally { temp.deleteRecursively() }
    }

    @Test fun archiveRestoresAudioWithExactLength() {
        val temp = Files.createTempDirectory("backup-test").toFile()
        try {
            val backup = OfflineBackupHelper.read(ByteArrayInputStream(archive("audio/$id")), temp)
            assertEquals(1, backup.downloads?.size)
            assertArrayEquals(byteArrayOf(1, 2, 3, 4), java.io.File(temp, id).readBytes())
        } finally { temp.deleteRecursively() }
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsTraversalEntry() {
        val temp = Files.createTempDirectory("backup-test").toFile()
        try { OfflineBackupHelper.read(ByteArrayInputStream(archive("audio/../escape")), temp) }
        finally { temp.deleteRecursively() }
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsAudioThatExceedsRecordedSize() {
        val temp = Files.createTempDirectory("backup-test").toFile()
        try { OfflineBackupHelper.read(ByteArrayInputStream(archive("audio/$id", 2)), temp) }
        finally { temp.deleteRecursively() }
    }
}
