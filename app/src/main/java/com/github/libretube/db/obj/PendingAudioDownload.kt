package com.github.libretube.db.obj

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity
data class PendingAudioDownload(@PrimaryKey val videoId: String)
