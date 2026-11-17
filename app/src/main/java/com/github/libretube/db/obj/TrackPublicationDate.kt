package com.github.libretube.db.obj

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity
data class TrackPublicationDate(
    @PrimaryKey val videoId: String,
    val publicationDate: String,
)
