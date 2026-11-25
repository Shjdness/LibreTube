package com.github.libretube.db.obj

import androidx.room.Entity
import kotlinx.serialization.Serializable

@Serializable
@Entity(primaryKeys = ["trackId", "positionMs"])
data class AudioTimeMark(val trackId: String, val positionMs: Long, val label: String,
    val title: String = "", val author: String = "")
