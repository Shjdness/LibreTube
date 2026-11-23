package com.github.libretube.helpers

import android.content.Context
import com.github.libretube.api.JsonHelper
import com.github.libretube.db.DatabaseHolder.Database
import com.github.libretube.db.obj.Download
import com.github.libretube.db.obj.DownloadItem
import com.github.libretube.db.obj.PendingAudioDownload
import com.github.libretube.enums.FileType
import com.github.libretube.extensions.toID
import com.github.libretube.obj.BackupFile
import com.github.libretube.obj.OfflineDownloadBackup
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.io.path.inputStream
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream

/** Portable manifests never authorize a path outside the app's audio directory. */
object OfflineBackupHelper {
    private val idPattern = Regex("[A-Za-z0-9_-]{11}")

    @OptIn(ExperimentalSerializationApi::class)
    suspend fun write(context: Context, output: OutputStream, backup: BackupFile, includeAudio: Boolean) {
        if (!includeAudio) {
            JsonHelper.json.encodeToStream(backup, output)
            return
        }
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            JsonHelper.json.encodeToStream(backup, zip)
            zip.closeEntry()
            for (entry in backup.downloads.orEmpty()) {
                val id = entry.track.url?.toID() ?: continue
                if (!idPattern.matches(id) || entry.size <= 0) continue
                val audio = requireNotNull(Database.downloadDao().findById(id)?.finishedAudio) {
                    "Download was removed while backing up: $id"
                }
                check(audio.downloadSize == entry.size) { "Download changed while backing up: $id" }
                zip.putNextEntry(ZipEntry("audio/$id"))
                val copied = audio.path.inputStream().use { it.copyTo(zip) }
                check(copied == entry.size) { "Audio changed while backing up: $id" }
                zip.closeEntry()
            }
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    fun read(input: InputStream, staging: File, availableBytes: () -> Long = { staging.usableSpace }): BackupFile {
        val buffered = input.buffered()
        buffered.mark(4)
        val zipped = buffered.read() == 0x50 && buffered.read() == 0x4b
        buffered.reset()
        if (!zipped) return readManifest(buffered)
        ZipInputStream(buffered).use { zip ->
            require(zip.nextEntry?.name == "manifest.json") { "Invalid Libreplayer archive" }
            val backup = readManifest(zip)
            val expected = backup.downloads.orEmpty().associateBy { it.track.url?.toID() }
            require(expected.size == backup.downloads.orEmpty().size && expected.keys.all { it != null && idPattern.matches(it) }) {
                "Invalid or duplicate download IDs"
            }
            val required = expected.values.filter { it.size > 0 }.fold(0L) { total, item -> Math.addExact(total, item.size) }
            if (required > availableBytes()) throw IOException("Not enough free storage to restore audio")
            val seen = mutableSetOf<String>()
            while (true) {
                val entry = zip.nextEntry ?: break
                val id = entry.name.removePrefix("audio/")
                val item = expected[id]
                require(entry.name == "audio/$id" && idPattern.matches(id) && item != null &&
                    item.size > 0 && seen.add(id)) { "Invalid audio entry" }
                val target = File(staging, id)
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val count = zip.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= item.size) { "Audio exceeds recorded size" }
                        output.write(buffer, 0, count)
                    }
                    require(total == item.size) { "Incomplete backup audio" }
                    output.fd.sync()
                }
            }
            require(expected.filterValues { it.size > 0 }.keys.all { it in seen }) { "Backup audio is missing" }
            return backup
        }
    }

    private fun readManifest(input: InputStream): BackupFile {
        val bytes = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(bytes.size() + count <= 32 * 1024 * 1024) { "Backup manifest is too large" }
            bytes.write(buffer, 0, count)
        }
        return JsonHelper.json.decodeFromString<BackupFile>(bytes.toString(Charsets.UTF_8.name()))
    }

    suspend fun restoreDownloads(entries: List<OfflineDownloadBackup>, staging: File) {
        val dao = Database.downloadDao()
        for (entry in entries) {
            val id = entry.track.url?.toID() ?: continue
            if (!idPattern.matches(id)) continue
            if (dao.findById(id)?.finishedAudio != null) continue
            val track = entry.track
            dao.insertDownload(Download(videoId = id, title = track.title.orEmpty(),
                description = track.shortDescription.orEmpty(), views = track.views ?: 0,
                uploadDate = track.publicationDate()?.let { kotlinx.datetime.LocalDate.parse(it) },
                uploader = track.uploaderName.orEmpty(), uploaderUrl = track.uploaderUrl, duration = track.duration))
            val source = File(staging, id)
            if (entry.size > 0 && source.isFile && source.length() == entry.size) {
                dao.insertDownloadItem(DownloadItem(type = FileType.AUDIO, videoId = id,
                    fileName = entry.fileName.substringAfterLast('/').substringAfterLast('\\'),
                    path = source.toPath(), downloadSize = entry.size,
                    format = entry.format, quality = entry.quality))
                dao.removePendingAudio(id)
            } else {
                dao.insertPendingAudio(listOf(PendingAudioDownload(id, track.title.orEmpty())))
            }
        }
    }
}
