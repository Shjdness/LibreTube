package com.github.libretube.db.obj

import androidx.room.Embedded
import androidx.room.Relation
import com.github.libretube.api.obj.Streams
import com.github.libretube.enums.FileType
import com.github.libretube.extensions.toMillis

data class DownloadWithItems(
    @Embedded val download: Download,
    @Relation(
        parentColumn = "videoId",
        entityColumn = "videoId"
    )
    val downloadItems: List<DownloadItem>,
    @Relation(
        parentColumn = "videoId",
        entityColumn = "videoId"
    )
    val downloadChapters: List<DownloadChapter> = emptyList()
) {
    val audioItem get() = downloadItems.firstOrNull { it.type == FileType.AUDIO }
    val finishedAudio get() = downloadItems.firstOrNull { it.type == FileType.AUDIO && it.isFinished }
    val channelKey get() = download.uploaderUrl?.takeIf { it.isNotBlank() } ?: download.uploader

    fun toStreams(): Streams = Streams(
        title = download.title,
        description = download.description,
        uploaderAvatar = null,
        thumbnailUrl = download.thumbnailPath?.toUri()?.toString().orEmpty(),
        category = "",
        uploader = download.uploader,
        uploaded = download.uploadDate?.toMillis(),
        duration = download.duration ?: 0,
        uploaderUrl = download.uploaderUrl,
        uploaderVerified = false,
        chapters = downloadChapters.map { it.toChapterSegment() },
        views = download.views,
        likes = download.likes,
        dislikes = download.dislikes,
        uploaderSubscriberCount = -1
    )
}
