package com.github.libretube.api

import com.github.libretube.api.obj.Playlist
import com.github.libretube.api.obj.Playlists
import com.github.libretube.api.obj.StreamItem
import com.github.libretube.constants.PreferenceKeys
import com.github.libretube.db.DatabaseHolder.Database
import com.github.libretube.db.obj.BookmarkSnapshot
import com.github.libretube.db.obj.DownloadPlaylist
import com.github.libretube.db.obj.DownloadPlaylistVideosCrossRef
import com.github.libretube.extensions.toID
import com.github.libretube.enums.PlaylistType
import com.github.libretube.helpers.PreferenceHelper
import com.github.libretube.obj.PipedImportPlaylist
import com.github.libretube.repo.UserDataRepository
import com.github.libretube.repo.UserDataRepositoryHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

object PlaylistsHelper {
    const val MAX_CONCURRENT_IMPORT_CALLS = 5

    @Suppress("DEPRECATION")
    private val userDataRepository: UserDataRepository get() = UserDataRepositoryHelper.userDataRepository

    suspend fun getPlaylists(): List<Playlists> = withContext(Dispatchers.IO) {
        val playlists = userDataRepository.getPlaylists()
        sortPlaylists(playlists)
    }

    private fun sortPlaylists(playlists: List<Playlists>): List<Playlists> {
        return when (
            PreferenceHelper.getString(PreferenceKeys.PLAYLISTS_ORDER, "creation_date")
        ) {
            "creation_date" -> playlists
            "creation_date_reversed" -> playlists.reversed()
            "alphabetic" -> playlists.sortedBy { it.name?.lowercase() }
            "alphabetic_reversed" -> playlists.sortedBy { it.name?.lowercase() }
                .reversed()

            else -> playlists
        }
    }

    suspend fun getPlaylist(playlistId: String, playlistType: PlaylistType): Playlist {
        // load locally stored playlists with the auth api
        return when (playlistType) {
            PlaylistType.PUBLIC -> MediaServiceRepository.instance.getPlaylist(playlistId).let { playlist ->
                if (!playlist.thumbnailUrl.isNullOrBlank()) playlist
                else playlist.copy(thumbnailUrl = playlist.relatedStreams.firstNotNullOfOrNull { it.thumbnail?.takeIf(String::isNotBlank) })
            }
            else -> userDataRepository.getPlaylist(playlistId)
        }
    }

    suspend fun cachePlaylistForOffline(playlistId: String, playlist: Playlist, replaceLinks: Boolean = true) {
        Database.downloadDao().cachePlaylist(
            DownloadPlaylist(playlistId = playlistId, title = playlist.name.orEmpty(), description = playlist.description),
            playlist.relatedStreams.mapNotNull { item ->
                item.url?.toID()?.let { DownloadPlaylistVideosCrossRef(playlistId = playlistId, videoId = it) }
            },
            replaceLinks
        )
    }

    suspend fun getCachedPlaylist(playlistId: String): Playlist? {
        val cached = Database.downloadDao().getDownloadPlaylistByIdIncludingItems(playlistId) ?: return null
        val tracks = cached.downloadVideos.filter { it.finishedAudio != null }.map { it.download.toStreamItem() }
        if (tracks.isEmpty()) return null
        return Playlist(
            name = cached.downloadPlaylist.title,
            description = cached.downloadPlaylist.description,
            thumbnailUrl = tracks.firstNotNullOfOrNull { it.thumbnail },
            videos = tracks.size,
            relatedStreams = tracks
        )
    }

    suspend fun getPlaylistForPlayback(playlistId: String, playlistType: PlaylistType, online: Boolean): Playlist? =
        withContext(Dispatchers.IO) {
            if (playlistType != PlaylistType.PUBLIC) return@withContext getPlaylist(playlistId, playlistType)
            val saved = getBookmarkSnapshot(playlistId)?.first?.takeIf { it.relatedStreams.isNotEmpty() }
                ?: getCachedPlaylist(playlistId)
            saved ?: if (online) getPlaylist(playlistId, playlistType) else null
        }

    suspend fun getBookmarkSnapshot(playlistId: String): Pair<Playlist, Boolean>? {
        val snapshot = Database.playlistBookmarkDao().getSnapshot(playlistId) ?: return null
        val playlist = runCatching { Json.decodeFromString<Playlist>(snapshot.playlistJson) }
            .getOrNull() ?: return null
        return playlist to snapshot.complete
    }

    suspend fun saveBookmarkSnapshot(playlistId: String, playlist: Playlist, complete: Boolean) {
        if (playlist.relatedStreams.isEmpty() && playlist.videos > 0) return
        // Pagination restarts from the first page whenever the remote playlist is refreshed.
        val snapshot = BookmarkSnapshot(
            playlistId, Json.encodeToString(playlist.copy(nextpage = null)), complete, playlist.relatedStreams.size
        )
        Database.playlistBookmarkDao().saveSnapshot(snapshot, playlist.toPlaylistBookmark(playlistId))
    }

    suspend fun getAllPlaylistsWithVideos(playlistIds: List<String>? = null): List<Playlist> {
        return withContext(Dispatchers.IO) {
            (playlistIds ?: getPlaylists().map { it.id!! })
                .map { async { getPlaylist(it, getPlaylistType(it)) } }
                .awaitAll()
        }
    }

    suspend fun createPlaylist(playlistName: String) =
        userDataRepository.createPlaylist(playlistName)

    suspend fun addToPlaylist(playlistId: String, vararg videos: StreamItem) =
        withContext(Dispatchers.IO) {
            userDataRepository.addToPlaylist(playlistId, *videos)
        }

    suspend fun renamePlaylist(playlistId: String, newName: String) =
        userDataRepository.renamePlaylist(playlistId, newName)

    suspend fun changePlaylistDescription(playlistId: String, newDescription: String) =
        userDataRepository.changePlaylistDescription(playlistId, newDescription)

    suspend fun removeFromPlaylist(playlistId: String, videoId: String, index: Int) =
        userDataRepository.removeFromPlaylist(playlistId, videoId, index)

    suspend fun importPlaylists(playlists: List<PipedImportPlaylist>) =
        userDataRepository.importPlaylists(playlists)

    suspend fun clonePlaylist(playlistId: String) = userDataRepository.clonePlaylist(playlistId)
    suspend fun deletePlaylist(playlistId: String) = userDataRepository.deletePlaylist(playlistId)

    // TODO: remove this and pass the type information down instead
    private fun isYouTubePlaylist(playlistId: String) = playlistId.startsWith("PL") && playlistId.length == 34
    fun getPlaylistType(playlistId: String) = if (isYouTubePlaylist(playlistId)) PlaylistType.PUBLIC else PlaylistType.PRIVATE
}
