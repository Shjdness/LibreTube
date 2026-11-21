package com.github.libretube.db.obj

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.ColumnInfo
import kotlinx.serialization.Serializable

@Serializable
@Entity
data class PendingAudioDownload(
    @PrimaryKey val videoId: String,
    @ColumnInfo(defaultValue = "''") val title: String = "",
    @ColumnInfo(defaultValue = "''") val error: String = "",
)
