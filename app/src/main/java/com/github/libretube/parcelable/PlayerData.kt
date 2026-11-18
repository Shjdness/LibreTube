package com.github.libretube.parcelable

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class PlayerData(
    val videoId: String?,
    val playlistId: String? = null,
    val channelId: String? = null,
    val keepQueue: Boolean = false,
    val timestamp: Long = 0,
    val isOffline: Boolean = false,
    val playlistNextPage: String? = null,
    val queueName: String? = null,
    val resumePositionMs: Long? = null,
    val resumePaused: Boolean = false,
    val allowDownloadedFallback: Boolean = false,
) : Parcelable
