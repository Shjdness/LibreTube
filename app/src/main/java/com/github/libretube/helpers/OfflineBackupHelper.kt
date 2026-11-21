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
    fun read(input: InputStream, staging: File): BackupFile {
        val buffered = input.buffered()
        buffered.mark(4)
        val zipped = buffered.read() == 0x50 && buffered.read() == 0x4b
        buffered.reset()
        if (!zipped) return JsonHelper.json.decodeFromStream(buffered)
        ZipInputStream(buffered).use { zip ->
            require(zip.nextEntry?.name == "manifest.json") { "Invalid Libreplayer archive" }
            val manifestBytes = java.io.ByteArrayOutputStream()
            val manifestBuffer = ByteArray(8192)
            while (true) {
                val count = zip.read(manifestBuffer)
                if (count < 0) break
                require(manifestBytes.size() + count <= 32 * 1024 * 1024) { "Backup manifest is too large" }
                manifestBytes.write(manifestBuffer, 0, count)
            }
            val manifest = manifestBytes.toByteArray()
            val backup = JsonHelper.json.decodeFromString<BackupFile>(manifest.toString(Charsets.UTF_8))
            val expected = backup.downloads.orEmpty().associateBy { it.track.url?.toID() }
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
                }
            }
            require(expected.filterValues { it.size > 0 }.keys.all { it in seen }) { "Backup audio is missing" }
            return backup
        }
    }

    suspend fun restoreDownloads(context: Context, entries: List<OfflineDownloadBackup>, staging: File,
                                created: MutableList<File>) {
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
                val extension = entry.fileName.substringAfterLast('.', "audio")
                    .takeIf { it.matches(Regex("[A-Za-z0-9]{1,8}")) } ?: "audio"
                val target = File.createTempFile("$id-restored-", ".$extension",
                    DownloadHelper.getDownloadDir(context, DownloadHelper.AUDIO_DIR).toFile())
                created.add(target)
                source.inputStream().use { input -> target.outputStream().use { input.copyTo(it) } }
                dao.insertDownloadItem(DownloadItem(type = FileType.AUDIO, videoId = id,
                    fileName = target.name, path = target.toPath(), downloadSize = entry.size,
                    format = entry.format, quality = entry.quality))
                dao.removePendingAudio(id)
            } else {
                dao.insertPendingAudio(listOf(PendingAudioDownload(id, track.title.orEmpty())))
            }
        }
    }
}
