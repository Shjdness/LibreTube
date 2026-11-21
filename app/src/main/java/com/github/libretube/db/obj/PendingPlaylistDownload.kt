package com.github.libretube.db.obj

import androidx.room.Entity
import androidx.room.ColumnInfo
import kotlinx.serialization.Serializable

@Serializable
@Entity(primaryKeys = ["playlistId", "playlistType"])
data class PendingPlaylistDownload(
    val playlistId: String,
    val playlistType: String,
    val name: String,
    @ColumnInfo(defaultValue = "NULL") val nextPage: String? = null,
    @ColumnInfo(defaultValue = "0") val discovered: Int = 0,
    @ColumnInfo(defaultValue = "''") val error: String = "",
)
