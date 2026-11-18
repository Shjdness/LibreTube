package com.github.libretube.repo

import androidx.room.withTransaction
import com.github.libretube.extensions.toID
import com.github.libretube.api.obj.Playlist
import com.github.libretube.constants.PreferenceKeys
import com.github.libretube.helpers.PreferenceHelper
import com.github.libretube.api.obj.Playlists
import com.github.libretube.api.obj.StreamItem
import com.github.libretube.api.obj.Subscription
import com.github.libretube.api.obj.WatchHistoryEntry
import com.github.libretube.api.obj.WatchHistoryEntryMetadata
import com.github.libretube.db.DatabaseHolder.Database
import com.github.libretube.db.obj.LocalPlaylist
import com.github.libretube.db.obj.LocalSubscription
import com.github.libretube.db.obj.PlaylistBookmark
import com.github.libretube.db.obj.SubscriptionGroup
import com.github.libretube.db.obj.WatchPosition
import com.github.libretube.enums.WatchHistoryStatus

class LocalUserDataRepository : UserDataRepository {
    private val WATCH_HISTORY_PAGE_SIZE = 30

    override suspend fun getPlaylist(playlistId: String): Playlist {
        val relation = Database.localPlaylistsDao().getAll()
            .first { it.playlist.id.toString() == playlistId }

        return Playlist(
            name = relation.playlist.name,
            description = relation.playlist.description,
            thumbnailUrl = relation.playlist.thumbnailUrl,
            videos = relation.videos.size,
            relatedStreams = relation.videos.map { it.toStreamItem() }
        )
    }

    override suspend fun getPlaylists(): List<Playlists> {
        recoverCachedSyncedPlaylists()
        return Database.localPlaylistsDao().getAll().map { relation ->
            Playlists(
                id = relation.playlist.id.toString(),
                name = relation.playlist.name,
                shortDescription = relation.playlist.description,
                thumbnail = relation.playlist.thumbnailUrl,
                videos = relation.videos.size.toLong()
            )
        }
    }


    // Older sync builds cached downloaded remote playlists. Copy those entries into
    // the local library in one transaction so an interrupted upgrade can safely retry.
    private suspend fun recoverCachedSyncedPlaylists() {
        val legacy = PreferenceHelper.getString(PreferenceKeys.SYNC_SERVER_TYPE, "none")
        if (legacy != "piped" && legacy != "libretube") return
        Database.withTransaction {
            val playlists = Database.localPlaylistsDao()
            val existing = playlists.getAll().toMutableList()
            val downloads = Database.downloadDao()
            for (cached in downloads.getDownloadPlaylists()) {
                val remoteId = cached.downloadPlaylist.playlistId
                if (!REMOTE_PLAYLIST_ID.matches(remoteId) || cached.downloadVideos.isEmpty()) continue
                val title = cached.downloadPlaylist.title.ifBlank { remoteId }
                val tracks = cached.downloadVideos
                val ids = tracks.map { it.videoId }
                if (existing.any { it.playlist.name == title && it.videos.map { item -> item.videoId }.containsAll(ids) }) continue
                val inserted = playlists.createPlaylist(LocalPlaylist(name = title,
                    description = cached.downloadPlaylist.description)).toInt()
                val items = tracks.map { it.toStreamItem().toLocalPlaylistItem(inserted.toString()) }
                items.forEach { playlists.addPlaylistVideo(it) }
                existing.add(com.github.libretube.db.obj.LocalPlaylistWithVideos(
                    LocalPlaylist(id = inserted, name = title, description = cached.downloadPlaylist.description), items))
            }
        }
        PreferenceHelper.putString(PreferenceKeys.SYNC_SERVER_TYPE, "none")
    }

    override suspend fun addToPlaylist(playlistId: String, vararg videos: StreamItem): Boolean {
        val localPlaylist = Database.localPlaylistsDao().getAll()
            .first { it.playlist.id.toString() == playlistId }

        for (video in videos) {
            val localPlaylistItem = video.toLocalPlaylistItem(playlistId)

            val existingVideo = Database.localPlaylistsDao()
                .getPlaylistVideo(playlistId, localPlaylistItem.videoId)
            if (existingVideo != null) {
                // update existing video metadata
                localPlaylistItem.id = existingVideo.id
                Database.localPlaylistsDao().updatePlaylistVideo(localPlaylistItem)
                continue
            }

            // add the new video to the database
            Database.localPlaylistsDao().addPlaylistVideo(localPlaylistItem)

            val playlist = localPlaylist.playlist
            if (playlist.thumbnailUrl.isEmpty()) {
                // set the new playlist thumbnail URL
                localPlaylistItem.thumbnailUrl?.let {
                    playlist.thumbnailUrl = it
                    Database.localPlaylistsDao().updatePlaylist(playlist)
                }
            }
        }

        return true
    }

    override suspend fun renamePlaylist(playlistId: String, newName: String): Boolean {
        val playlist = Database.localPlaylistsDao().getAll()
            .first { it.playlist.id.toString() == playlistId }.playlist
        playlist.name = newName
        Database.localPlaylistsDao().updatePlaylist(playlist)

        return true
    }

    override suspend fun changePlaylistDescription(playlistId: String, newDescription: String): Boolean {
        val playlist = Database.localPlaylistsDao().getAll()
            .first { it.playlist.id.toString() == playlistId }.playlist
        playlist.description = newDescription
        Database.localPlaylistsDao().updatePlaylist(playlist)

        return true
    }

    override suspend fun removeFromPlaylist(playlistId: String, videoId: String, index: Int): Boolean {
        val dao = Database.localPlaylistsDao()
        val item = dao.getPlaylistVideo(playlistId, videoId) ?: return false
        dao.removePlaylistVideo(item)
        return true
    }

    override suspend fun createPlaylist(playlistName: String): String {
        val playlist = LocalPlaylist(name = playlistName, thumbnailUrl = "")
        return Database.localPlaylistsDao().createPlaylist(playlist).toString()
    }

    override suspend fun deletePlaylist(playlistId: String): Boolean {
        Database.localPlaylistsDao().deletePlaylistById(playlistId)
        Database.localPlaylistsDao().deletePlaylistItemsByPlaylistId(playlistId)

        return true
    }

    override suspend fun subscribe(
        channelId: String, name: String, uploaderAvatar: String?, verified: Boolean
    ) {
        val localSubscription = LocalSubscription(
            channelId = channelId,
            name = name,
            avatar = uploaderAvatar,
            verified = verified
        )

        Database.localSubscriptionDao().insert(localSubscription)
    }

    override suspend fun unsubscribe(channelId: String) {
        Database.localSubscriptionDao().deleteById(channelId)
    }

    override suspend fun isSubscribed(channelId: String): Boolean {
        return Database.localSubscriptionDao().includes(channelId)
    }

    override suspend fun getSubscriptions(): List<Subscription> {
        // load all channels that have not been fetched yet
        val unfinished = Database.localSubscriptionDao().getChannelsWithoutMetaInfo()
        runCatching {
            importSubscriptions(unfinished.map { it.channelId })
        }

        return Database.localSubscriptionDao().getAll().map {
            Subscription(
                url = it.channelId,
                name = it.name.orEmpty(),
                avatar = it.avatar,
                verified = it.verified
            )
        }
    }

    override suspend fun getSubscriptionChannelIds(): List<String> {
        return Database.localSubscriptionDao().getAll().map { it.channelId }
    }

    override suspend fun submitSubscriptionChannelInfosChanged(subscriptions: List<Subscription>) {
        Database.localSubscriptionDao().updateAll(subscriptions.map {
            LocalSubscription(
                it.url,
                it.name,
                it.avatar,
                it.verified
            )
        })
    }

    override suspend fun saveSubscriptionGroup(
        originalName: String?, name: String, channelIds: List<String>
    ): Boolean = Database.withTransaction {
        val dao = Database.subscriptionGroupsDao()
        val original = originalName?.let {
            checkNotNull(dao.getByName(it)) { "Subscription group no longer exists" }
        }
        if (name != originalName && dao.exists(name)) return@withTransaction false
        val index = original?.index ?: ((dao.getAll().maxOfOrNull { it.index } ?: -1) + 1)
        val updated = SubscriptionGroup(name, channelIds.map(String::toID).distinct(), index)
        if (name == originalName) dao.updateGroup(updated)
        else {
            dao.createGroup(updated)
            if (originalName != null) {
                dao.deleteGroup(originalName)
                if (PreferenceHelper.getString(PreferenceKeys.SELECTED_CHANNEL_GROUP_KEY, "") == "group:" + originalName) {
                    PreferenceHelper.putString(PreferenceKeys.SELECTED_CHANNEL_GROUP_KEY, "group:" + name)
                }
            }
        }
        true
    }

    override suspend fun reorderSubscriptionGroups(names: List<String>) = Database.withTransaction {
        val dao = Database.subscriptionGroupsDao()
        val groups = dao.getAll().associateBy { it.name }
        (names.distinct().mapNotNull(groups::get) + groups.values.filter { it.name !in names })
            .forEachIndexed { index, group ->
                if (group.index != index) dao.updateGroup(group.copy(index = index))
            }
    }

    override suspend fun deleteSubscriptionGroup(subscriptionGroupId: String) {
        Database.subscriptionGroupsDao()
            .deleteGroup(subscriptionGroupId)
    }

    override suspend fun getSubscriptionGroup(subscriptionGroupId: String): SubscriptionGroup {
        return Database.subscriptionGroupsDao().getByName(subscriptionGroupId)!!
    }

    override suspend fun getSubscriptionGroups(): List<SubscriptionGroup> {
        return Database.subscriptionGroupsDao().getAll()
            .sortedBy { it.index }
    }

    override suspend fun updateChannelGroups(
        channelId: String, editedNames: Set<String>, selectedNames: Set<String>
    ) = Database.withTransaction {
        val dao = Database.subscriptionGroupsDao()
        val id = channelId.toID()
        for (group in dao.getAll().filter { it.name in editedNames }) {
            val members = group.channels.map(String::toID).toMutableSet()
            val changed = if (group.name in selectedNames) members.add(id) else members.remove(id)
            if (changed) dao.updateGroup(group.copy(channels = members.toList()))
        }
    }

    override suspend fun addToWatchHistory(watchHistoryEntry: WatchHistoryEntry) {
        val watchHistoryItem = watchHistoryEntry.video.toWatchHistoryItem(watchHistoryEntry.metadata.videoId)
        Database.watchHistoryDao().insert(watchHistoryItem)

        // create watch position
        updateWatchHistoryEntry(watchHistoryEntry.metadata)
    }

    override suspend fun updateWatchHistoryEntry(metadata: WatchHistoryEntryMetadata) {
        metadata.positionMillis?.let {
            Database.watchPositionDao().insert(WatchPosition(videoId = metadata.videoId, position = it))
        }
    }

    override suspend fun removeFromWatchHistory(videoId: String) {
        Database.watchHistoryDao().deleteByVideoId(videoId)
        Database.watchPositionDao().deleteByVideoId(videoId)
    }

    override suspend fun getWatchHistory(
        pageSize: Int,
        cursor: Any?,
        watchedState: WatchHistoryStatus
    ): Pair<List<WatchHistoryEntry>, Any?> {
        val rows = Database.watchHistoryDao().getPage(
            limit = pageSize,
            cursor = cursor as? Long ?: Long.MAX_VALUE,
            watched = watchedState.isWatched,
            absoluteWatchedThresholdSeconds = ABSOLUTE_WATCHED_THRESHOLD,
            relativeWatchedThreshold = RELATIVE_WATCHED_THRESHOLD
        )

        val nextCursor = rows.lastOrNull()?.rowId?.minus(1)?.takeIf { rows.size == pageSize }
        return rows.map { it.toWatchHistoryEntry() } to nextCursor
    }

    override suspend fun getFromWatchHistory(videoId: String): WatchHistoryEntry? {
        val historyRow = Database.watchHistoryDao().findById(videoId) ?: return null
        return historyRow.toWatchHistoryEntry()
    }

    override suspend fun clearWatchHistory() {
        Database.watchHistoryDao().deleteAll()
    }

    override suspend fun getPlaylistBookmarks(): List<PlaylistBookmark> {
        return Database.playlistBookmarkDao().getAll()
    }

    override suspend fun getPlaylistBookmark(playlistId: String): PlaylistBookmark? {
        return Database.playlistBookmarkDao().findById(playlistId)
    }

    override suspend fun createPlaylistBookmark(playlist: PlaylistBookmark) {
        Database.playlistBookmarkDao().insert(playlist)
    }

    override suspend fun deletePlaylistBookmark(playlistId: String) {
        Database.playlistBookmarkDao().deleteById(playlistId)
    }

    companion object {
        private val REMOTE_PLAYLIST_ID = Regex("[0-9a-fA-F]{8}-(?:[0-9a-fA-F]{4}-){3}[0-9a-fA-F]{12}")

        // can only mark as watched if less than 60s remaining
        const val ABSOLUTE_WATCHED_THRESHOLD = 60.0f

        // can only mark as watched if at least 75% watched
        const val RELATIVE_WATCHED_THRESHOLD = 0.75f
    }
}