package com.github.libretube.db.obj

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

@Entity(foreignKeys = [ForeignKey(
    entity = PlaylistBookmark::class,
    parentColumns = ["playlistId"],
    childColumns = ["playlistId"],
    onDelete = ForeignKey.CASCADE
)])
data class BookmarkSnapshot(
    @PrimaryKey val playlistId: String,
    val playlistJson: String,
    val complete: Boolean,
    val trackCount: Int,
)
