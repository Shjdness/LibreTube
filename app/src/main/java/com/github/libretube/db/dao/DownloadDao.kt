package com.github.libretube.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import com.github.libretube.db.obj.Download
import com.github.libretube.db.obj.DownloadChapter
import com.github.libretube.db.obj.DownloadItem
import com.github.libretube.db.obj.DownloadPlaylist
import com.github.libretube.db.obj.DownloadPlaylistVideosCrossRef
import com.github.libretube.db.obj.DownloadPlaylistWithDownload
import com.github.libretube.db.obj.DownloadPlaylistWithDownloadWithItems
import com.github.libretube.db.obj.DownloadWithItems
import com.github.libretube.db.obj.PendingAudioDownload
import com.github.libretube.db.obj.PendingPlaylistDownload

@Dao
interface DownloadDao {
    @androidx.room.Upsert
    suspend fun updateAudioStatus(status: com.github.libretube.db.obj.AudioDownloadStatus)

    @Query("SELECT * FROM AudioDownloadStatus WHERE videoId = :id")
    suspend fun audioStatus(id: String): com.github.libretube.db.obj.AudioDownloadStatus?

    @Query("SELECT * FROM AudioDownloadStatus")
    suspend fun audioStatuses(): List<com.github.libretube.db.obj.AudioDownloadStatus>

    @Query("SELECT videoId FROM AudioDownloadStatus WHERE ready = 1")
    fun observeReadyAudioIds(): Flow<List<String>>

    @Transaction
    @Query("SELECT * FROM download WHERE videoId NOT IN (SELECT videoId FROM AudioDownloadStatus WHERE ready = 1)")
    suspend fun getIncompleteAudio(): List<DownloadWithItems>

    @Query("SELECT path FROM downloadItem")
    suspend fun getDownloadPaths(): List<String>

    @Query("SELECT * FROM PendingAudioDownload ORDER BY rowid")
    fun observePendingAudio(): Flow<List<PendingAudioDownload>>

    @Query("SELECT * FROM PendingAudioDownload ORDER BY rowid")
    suspend fun getPendingAudio(): List<PendingAudioDownload>

    @Query("SELECT EXISTS(SELECT 1 FROM PendingAudioDownload WHERE videoId = :id)")
    suspend fun hasPendingAudio(id: String): Boolean

    @Query("SELECT * FROM PendingPlaylistDownload ORDER BY rowid")
    fun observePendingPlaylists(): Flow<List<PendingPlaylistDownload>>

    @Query("SELECT * FROM PendingPlaylistDownload WHERE playlistId = :id AND playlistType = :type")
    suspend fun getPendingPlaylist(id: String, type: String): PendingPlaylistDownload?

    @Update
    suspend fun updatePendingPlaylist(item: PendingPlaylistDownload)

    @Query("UPDATE PendingAudioDownload SET title = :title WHERE videoId = :id AND title = ''")
    suspend fun namePendingAudio(id: String, title: String)

    @Query("UPDATE PendingAudioDownload SET error = :error WHERE videoId = :id")
    suspend fun failPendingAudio(id: String, error: String)

    @Transaction
    @Query("SELECT * FROM download")
    suspend fun getAll(): List<DownloadWithItems>

    @Transaction
    @Query("SELECT * FROM download")
    fun observeDownloads(): Flow<List<DownloadWithItems>>

    @Transaction
    @Query("SELECT * FROM download WHERE videoId = :videoId")
    suspend fun getDownloadById(videoId: String): DownloadWithItems?

    @Transaction
    @Query("SELECT * FROM download WHERE videoId = :videoId")
    suspend fun findById(videoId: String): DownloadWithItems?

    @Transaction
    @Query("SELECT * FROM download WHERE videoId IN (:ids)")
    suspend fun getByIds(ids: List<String>): List<DownloadWithItems>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPendingAudio(items: List<PendingAudioDownload>)

    @Query("SELECT videoId FROM PendingAudioDownload ORDER BY rowid")
    suspend fun getPendingAudioIds(): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPendingPlaylist(playlist: PendingPlaylistDownload)

    @Query("SELECT * FROM PendingPlaylistDownload ORDER BY rowid")
    suspend fun getPendingPlaylists(): List<PendingPlaylistDownload>

    @Query("DELETE FROM PendingPlaylistDownload WHERE playlistId = :id AND playlistType = :type")
    suspend fun removePendingPlaylist(id: String, type: String)

    @Query("SELECT (SELECT COUNT(*) FROM PendingAudioDownload) + (SELECT COUNT(*) FROM PendingPlaylistDownload)")
    fun observePendingAudioCount(): Flow<Int>

    @Query("DELETE FROM PendingAudioDownload WHERE videoId = :videoId")
    suspend fun removePendingAudio(videoId: String)

    @Query("SELECT EXISTS (SELECT * FROM download WHERE videoId = :videoId)")
    suspend fun exists(videoId: String): Boolean

    @Query("SELECT videoId FROM download WHERE videoId IN (:videoIds)")
    suspend fun getDownloadedVideoIds(videoIds: List<String>): List<String>

    suspend fun areVideosDownloaded(videoIds: List<String>): List<Boolean> =
        getDownloadedVideoIds(videoIds).toHashSet().let { ids -> videoIds.map(ids::contains) }

    @Query("SELECT * FROM downloaditem WHERE id = :id")
    suspend fun findDownloadItemById(id: Int): DownloadItem?

    @Query("DELETE FROM downloaditem WHERE id = :id")
    suspend fun deleteDownloadItemById(id: Int)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertDownload(download: Download): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDownloadChapter(downloadChapter: DownloadChapter)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDownloadItem(downloadItem: DownloadItem): Long

    @Update(onConflict = OnConflictStrategy.REPLACE)
    suspend fun updateDownloadItem(downloadItem: DownloadItem)

    @Transaction
    @Delete
    suspend fun deleteDownload(download: Download)

    @Transaction
    @Query("SELECT * FROM downloadPlaylist")
    suspend fun getDownloadPlaylists(): List<DownloadPlaylistWithDownload>

    @Transaction
    @Query("SELECT * FROM downloadPlaylist WHERE playlistId = :playlistId")
    suspend fun getDownloadPlaylistByIdIncludingItems(playlistId: String): DownloadPlaylistWithDownloadWithItems?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylist(downloadPlaylist: DownloadPlaylist)

    /**
     * Connect a [DownloadPlaylist] to a [Download] to link the playlist to the video.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPlaylistVideoConnections(crossRefs: List<DownloadPlaylistVideosCrossRef>)

    @Transaction
    suspend fun cachePlaylist(playlist: DownloadPlaylist, links: List<DownloadPlaylistVideosCrossRef>, replaceLinks: Boolean) {
        insertPlaylist(playlist)
        if (replaceLinks) clearPlaylistVideoConnections(playlist.playlistId)
        insertPlaylistVideoConnections(links)
    }

    @Query("DELETE FROM downloadPlaylistVideosCrossRef WHERE playlistId = :playlistId")
    suspend fun clearPlaylistVideoConnections(playlistId: String)


}
