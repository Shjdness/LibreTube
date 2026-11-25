package com.github.libretube.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import androidx.room.Delete
import com.github.libretube.db.obj.AudioTimeMark

@Dao
interface AudioTimeMarkDao {
    @Query("SELECT * FROM AudioTimeMark ORDER BY trackId, positionMs")
    suspend fun getAll(): List<AudioTimeMark>

    @Query("SELECT * FROM AudioTimeMark WHERE trackId = :id ORDER BY positionMs")
    suspend fun getForTrack(id: String): List<AudioTimeMark>

    @Upsert
    suspend fun insertAll(marks: List<AudioTimeMark>)

    @Delete
    suspend fun delete(mark: AudioTimeMark)
}
