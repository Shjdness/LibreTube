package com.github.libretube.db.dao

import androidx.room.Dao
import androidx.room.Transaction
import androidx.room.Upsert
import androidx.room.Query
import androidx.room.Update
import com.github.libretube.db.obj.BookmarkSnapshot
import com.github.libretube.db.obj.TrackPublicationDate
import com.github.libretube.db.obj.PlaylistBookmark

@Dao
interface PlaylistBookmarkDao {
    @Query("SELECT * FROM BookmarkSnapshot")
    suspend fun getSnapshots(): List<BookmarkSnapshot>

    @Query("SELECT * FROM playlistBookmark")
    suspend fun getAll(): List<PlaylistBookmark>

    @Query("SELECT * FROM playlistBookmark WHERE playlistId = :playlistId LIMIT 1")
    suspend fun findById(playlistId: String): PlaylistBookmark?

    @Upsert
    suspend fun insert(bookmark: PlaylistBookmark)

    @Upsert
    suspend fun insertAll(bookmarks: List<PlaylistBookmark>)

    @Query("SELECT * FROM BookmarkSnapshot WHERE playlistId = :playlistId")
    suspend fun getSnapshot(playlistId: String): BookmarkSnapshot?

    @Upsert
    suspend fun upsertSnapshot(snapshot: BookmarkSnapshot)

    @Transaction
    suspend fun saveSnapshot(snapshot: BookmarkSnapshot, bookmark: PlaylistBookmark) {
        if (!includes(snapshot.playlistId)) return
        val existing = getSnapshot(snapshot.playlistId)
        if (existing != null && ((!snapshot.complete &&
                (existing.complete || existing.trackCount >= snapshot.trackCount)) ||
                (snapshot.trackCount == 0 && existing.trackCount > 0))
        ) return
        update(bookmark)
        upsertSnapshot(snapshot)
    }

    @Query("SELECT * FROM TrackPublicationDate WHERE videoId IN (:videoIds)")
    suspend fun publicationDates(videoIds: List<String>): List<TrackPublicationDate>

    @Upsert
    suspend fun savePublicationDate(date: TrackPublicationDate)

    @Update
    suspend fun update(playlistBookmark: PlaylistBookmark)

    @Query("DELETE FROM playlistBookmark WHERE playlistId = :playlistId")
    suspend fun deleteById(playlistId: String)

    @Query("SELECT EXISTS(SELECT * FROM playlistBookmark WHERE playlistId= :playlistId)")
    suspend fun includes(playlistId: String): Boolean

    @Query("DELETE FROM playlistBookmark")
    suspend fun deleteAll()
}
