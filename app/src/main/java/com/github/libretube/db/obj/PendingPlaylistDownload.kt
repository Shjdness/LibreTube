package com.github.libretube.db.obj

import androidx.room.Entity

@Entity(primaryKeys = ["playlistId", "playlistType"])
data class PendingPlaylistDownload(
    val playlistId: String,
    val playlistType: String,
    val name: String,
)
