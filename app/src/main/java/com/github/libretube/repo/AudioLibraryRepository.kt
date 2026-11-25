package com.github.libretube.repo

import com.github.libretube.db.DatabaseHolder.Database
import com.github.libretube.db.obj.AudioDownloadStatus
import com.github.libretube.db.obj.DownloadWithItems
import com.github.libretube.api.obj.StreamItem
import com.github.libretube.extensions.toID
import com.github.libretube.helpers.PreferenceHelper
import com.github.libretube.obj.AudioTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

object AudioLibraryRepository {
    const val OFFLINE_ONLY = "audio_offline_only"
    const val GROUP_FILTER = "audio_group_filter"

    @Volatile private var knownAuthors = emptyMap<String, String>()

    suspend fun refreshAuthorIndex() = withContext(Dispatchers.IO) {
        updateAuthors(Database.downloadDao().getAll())
    }

    private fun updateAuthors(records: List<DownloadWithItems>) {
        knownAuthors = records.mapNotNull { record ->
            record.download.uploaderUrl?.toID()?.takeIf(String::isNotBlank)?.let { record.download.videoId to it }
        }.toMap()
    }

    val offlineOnly get() = PreferenceHelper.getBoolean(OFFLINE_ONLY, false)

    fun observeDownloads() = kotlinx.coroutines.flow.combine(Database.downloadDao().observeDownloads(),
        Database.downloadDao().observeReadyAudioIds()) { records, _ ->
        updateAuthors(records)
        val statuses = Database.downloadDao().audioStatuses().associateBy { it.videoId }
        records.forEach {
            it.audioUnavailable = statuses[it.download.videoId]?.reason?.isNotEmpty() == true
            it.finishedAudio
        }
        records
    }.flowOn(Dispatchers.IO)

    fun observeReadyIds() = Database.downloadDao().observeReadyAudioIds().map { it.toSet() }

    suspend fun refreshStatuses() = withContext(Dispatchers.IO) {
        val dao = Database.downloadDao()
        val statuses = dao.audioStatuses().associateBy { it.videoId }
        for (record in dao.getAll()) {
            val old = statuses[record.download.videoId]
            if (old?.reason?.isNotEmpty() == true) continue
            val ready = record.finishedAudio != null
            if (old?.ready != ready) {
                dao.updateAudioStatus(AudioDownloadStatus(record.download.videoId, ready))
            }
        }
    }

    suspend fun incompleteDownloads() = withContext(Dispatchers.IO) {
        Database.downloadDao().getIncompleteAudio().filter { it.finishedAudio == null }
    }

    suspend fun localAudio(id: String): DownloadWithItems? = withContext(Dispatchers.IO) {
        if (Database.downloadDao().audioStatus(id)?.ready == false) return@withContext null
        Database.downloadDao().findById(id)?.takeIf { it.finishedAudio != null }
    }

    suspend fun readyDownloads(ids: List<String>) = withContext(Dispatchers.IO) {
        val dao = Database.downloadDao()
        val blocked = dao.audioStatuses().filterNot { it.ready }.mapTo(hashSetOf()) { it.videoId }
        val byId = ids.distinct().chunked(500).flatMap { dao.getByIds(it) }
            .filter { it.download.videoId !in blocked && it.finishedAudio != null }.associateBy { it.download.videoId }
        ids.mapNotNull(byId::get)
    }

    data class CheckIssue(val id: String, val title: String, val reason: String)
    data class CheckResult(val total: Int, val issues: List<CheckIssue>)

    suspend fun checkDownloads(): CheckResult = withContext(Dispatchers.IO) {
        val dao = Database.downloadDao()
        val records = dao.getAll()
        val issues = mutableListOf<CheckIssue>()
        for (record in records) {
            val audio = record.audioItem
            if (com.github.libretube.services.DownloadService.IS_DOWNLOAD_RUNNING && record.finishedAudio == null) continue
            val reason = when {
                audio == null -> "incomplete"
                !java.nio.file.Files.isRegularFile(audio.path) -> "missing"
                !audio.isFinished -> "size"
                !readableAudio(audio.path.toString()) -> "unreadable"
                else -> null
            }
            dao.updateAudioStatus(AudioDownloadStatus(record.download.videoId, reason == null, reason.orEmpty()))
            if (reason != null) issues.add(CheckIssue(record.download.videoId, record.download.title, reason))
        }
        CheckResult(records.size, issues)
    }

    private fun readableAudio(path: String): Boolean {
        val extractor = android.media.MediaExtractor()
        return try {
            extractor.setDataSource(path)
            val audio = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(android.media.MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return false
            extractor.selectTrack(audio)
            extractor.readSampleData(java.nio.ByteBuffer.allocate(256 * 1024), 0) > 0
        } catch (_: Exception) {
            false
        } finally {
            extractor.release()
        }
    }

    suspend fun selectedGroup(): Pair<String, Set<String>>? = withContext(Dispatchers.IO) {
        val name = PreferenceHelper.getString(GROUP_FILTER, "")
        Database.subscriptionGroupsDao().getByName(name)?.let { group ->
            group.name to group.channels.mapTo(hashSetOf()) { it.toID() }
        }
    }

    fun matchesGroup(item: StreamItem, channels: Set<String>?): Boolean {
        if (channels == null) return true
        val track = AudioTrack.fromStream(item) ?: return false
        val author = track.authorId?.takeIf(String::isNotBlank) ?: knownAuthors[track.id]
        return author in channels
    }
}
