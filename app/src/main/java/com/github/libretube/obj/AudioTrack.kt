package com.github.libretube.obj

import com.github.libretube.api.obj.StreamItem
import com.github.libretube.extensions.toID
import kotlinx.serialization.Serializable

@Serializable
data class AudioTrack(
    val id: String,
    val title: String,
    val author: String = "",
    val authorId: String? = null,
    val duration: Long? = null,
    val publishedDate: String? = null,
    val publishedEpoch: Long = 0,
    val artwork: String? = null,
    val description: String? = null,
    val localFile: String? = null,
    val views: Long? = null,
) {
    fun toStreamItem() = StreamItem(url = id, title = title, uploaderName = author,
        uploaderUrl = authorId?.let { "/channel/$it" }, duration = duration,
        uploadedDate = publishedDate, uploaded = publishedEpoch, thumbnail = artwork,
        shortDescription = description, views = views)

    companion object {
        fun fromStream(item: StreamItem): AudioTrack? {
            val id = item.url?.toID()?.takeIf(String::isNotBlank) ?: return null
            return AudioTrack(id, item.title.orEmpty(), item.uploaderName.orEmpty(),
                item.uploaderUrl?.toID(), item.duration, item.uploadedDate, item.uploaded,
                item.thumbnail, item.shortDescription, views = item.views)
        }
    }
}
