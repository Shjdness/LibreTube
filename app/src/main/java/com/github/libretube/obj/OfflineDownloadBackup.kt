package com.github.libretube.obj

import com.github.libretube.api.obj.StreamItem
import kotlinx.serialization.Serializable

@Serializable
data class OfflineDownloadBackup(
    val track: StreamItem,
    val fileName: String,
    val size: Long,
    val format: String? = null,
    val quality: String? = null,
)
