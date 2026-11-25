package com.github.libretube.db.obj

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

@Entity(foreignKeys = [ForeignKey(entity = Download::class, parentColumns = ["videoId"],
    childColumns = ["videoId"], onDelete = ForeignKey.CASCADE)])
data class AudioDownloadStatus(@PrimaryKey val videoId: String, val ready: Boolean, val reason: String = "")
