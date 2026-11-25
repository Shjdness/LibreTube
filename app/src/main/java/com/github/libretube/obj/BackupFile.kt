package com.github.libretube.obj

import com.github.libretube.db.obj.CustomInstance
import com.github.libretube.db.obj.LocalPlaylistWithVideos
import com.github.libretube.db.obj.LocalSubscription
import com.github.libretube.db.obj.PlaylistBookmark
import com.github.libretube.db.obj.SearchHistoryItem
import com.github.libretube.db.obj.SubscriptionGroup
import com.github.libretube.db.obj.WatchHistoryItem
import com.github.libretube.db.obj.WatchPosition
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

@Serializable
@OptIn(ExperimentalSerializationApi::class)
data class BackupFile(
    //
    // some stuff for compatibility with Piped imports
    //
    val format: String = "Piped",
    val version: Int = 1,

    //
    // only compatible with LibreTube itself, database objects
    //
    var timeMarks: List<com.github.libretube.db.obj.AudioTimeMark>? = emptyList(),
    var watchHistory: List<WatchHistoryItem>? = emptyList(),
    var watchPositions: List<WatchPosition>? = emptyList(),
    var searchHistory: List<SearchHistoryItem>? = emptyList(),
    var customInstances: List<CustomInstance>? = emptyList(),
    var playlistBookmarks: List<PlaylistBookmark>? = emptyList(),
    var bookmarkSnapshots: List<com.github.libretube.db.obj.BookmarkSnapshot>? = emptyList(),
    var pendingAudio: List<com.github.libretube.db.obj.PendingAudioDownload>? = emptyList(),
    var pendingPlaylists: List<com.github.libretube.db.obj.PendingPlaylistDownload>? = emptyList(),
    var downloads: List<OfflineDownloadBackup>? = emptyList(),

    //
    // Preferences, stored as a key value map
    //
    var preferences: List<PreferenceItem>? = emptyList(),

    //
    // Database objects with compatibility for Piped imports/exports
    //
    @JsonNames("groups", "channelGroups")
    var groups: List<SubscriptionGroup>? = emptyList(),

    @JsonNames("subscriptions", "localSubscriptions")
    var subscriptions: List<LocalSubscription>? = emptyList(),

    // playlists are exported in two different formats because the formats differ too much unfortunately
    var localPlaylists: List<LocalPlaylistWithVideos>? = emptyList(),
    var playlists: List<PipedImportPlaylist>? = emptyList()
)
