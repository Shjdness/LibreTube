package com.github.libretube.helpers

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.room.withTransaction
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import androidx.work.ExistingPeriodicWorkPolicy
import com.github.libretube.R
import com.github.libretube.api.JsonHelper
import com.github.libretube.constants.PreferenceKeys
import com.github.libretube.db.DatabaseHolder.Database
import com.github.libretube.extensions.TAG
import com.github.libretube.extensions.toastFromMainDispatcher
import com.github.libretube.obj.BackupFile
import com.github.libretube.obj.PreferenceItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Backup and restore the preferences
 */
object BackupHelper {
    /**
     * Write a [BackupFile] containing the database content as well as the preferences
     */
    @OptIn(ExperimentalSerializationApi::class)
    suspend fun createAdvancedBackup(context: Context, uri: Uri, backupFile: BackupFile, includeAudio: Boolean = false) {
        try {
            requireNotNull(context.contentResolver.openOutputStream(uri)).use { outputStream ->
                OfflineBackupHelper.write(context, outputStream, backupFile, includeAudio)
            }
            context.toastFromMainDispatcher(R.string.backup_creation_success)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG(), "Error while writing backup: $e")
            context.toastFromMainDispatcher(R.string.backup_creation_failed)
        }
    }

    /**
     * Restore data from a [BackupFile]
     */
    @OptIn(ExperimentalSerializationApi::class)
    suspend fun restoreAdvancedBackup(context: Context, uri: Uri) = withContext(Dispatchers.IO) {
        val staging = Files.createTempDirectory(context.cacheDir.toPath(), "restore-").toFile()
        val created = mutableListOf<File>()
        var committed = false
        try {
            val backupFile = requireNotNull(context.contentResolver.openInputStream(uri)).use {
                OfflineBackupHelper.read(it, staging)
            }
            val playlistIds = mutableMapOf<String, String>()
            Database.withTransaction {

                Database.watchHistoryDao().insertAll(backupFile.watchHistory.orEmpty())
                Database.searchHistoryDao().insertAll(backupFile.searchHistory.orEmpty())
                Database.watchPositionDao().insertAll(backupFile.watchPositions.orEmpty())
                Database.localSubscriptionDao().insertAll(backupFile.subscriptions.orEmpty())
                Database.customInstanceDao().insertAll(backupFile.customInstances.orEmpty())
                Database.playlistBookmarkDao().insertAll(backupFile.playlistBookmarks.orEmpty())
                Database.subscriptionGroupsDao().insertAll(backupFile.groups.orEmpty())
                backupFile.bookmarkSnapshots.orEmpty().forEach {
                    if (Database.playlistBookmarkDao().includes(it.playlistId)) {
                        val existing = Database.playlistBookmarkDao().getSnapshot(it.playlistId)
                        if (existing == null || (!existing.complete && it.trackCount >= existing.trackCount)) {
                            Database.playlistBookmarkDao().upsertSnapshot(it)
                        }
                    }
                }

                val existingPlaylists = Database.localPlaylistsDao().getAll()
                backupFile.localPlaylists?.forEach {
                    val matching = existingPlaylists.firstOrNull { existing ->
                        existing.playlist.name == it.playlist.name &&
                            existing.videos.map { item -> item.videoId } == it.videos.map { item -> item.videoId }
                    }
                    if (matching != null) {
                        playlistIds[it.playlist.id.toString()] = matching.playlist.id.toString()
                        return@forEach
                    }
                    // the playlist will be created with an id of 0, so that Room will auto generate a
                    // new playlist id to avoid conflicts with existing local playlists
                    val playlistId = Database.localPlaylistsDao().createPlaylist(it.playlist.copy(id = 0))
                    playlistIds[it.playlist.id.toString()] = playlistId.toString()
                    it.videos.forEach { playlistItem ->
                        playlistItem.playlistId = playlistId.toInt()
                        Database.localPlaylistsDao().addPlaylistVideo(playlistItem.copy(id = 0))
                    }
                }
                Database.downloadDao().insertPendingAudio(backupFile.pendingAudio.orEmpty())
                backupFile.pendingPlaylists.orEmpty().forEach {
                    val id = if (it.playlistType == "PUBLIC") it.playlistId else playlistIds[it.playlistId]
                    if (id != null) Database.downloadDao().insertPendingPlaylist(it.copy(playlistId = id))
                }
                OfflineBackupHelper.restoreDownloads(context, backupFile.downloads.orEmpty(), staging, created)
            }
            committed = true

            val preferences = backupFile.preferences?.map { item ->
                if (item.key == "library_pins_0") item.copy(value = kotlinx.serialization.json.JsonPrimitive(
                    item.value.content.split(',').mapNotNull(playlistIds::get).joinToString(",")
                )) else item
            }
            restorePreferences(context, preferences)
        } finally {
            if (!committed) created.forEach { it.delete() }
            staging.listFiles()?.forEach { it.delete() }
            staging.delete()
        }
    }

    /**
     * Restore the shared preferences from a backup file
     */
    private fun restorePreferences(context: Context, preferences: List<PreferenceItem>?) {
        if (preferences.isNullOrEmpty()) return

        PreferenceManager.getDefaultSharedPreferences(context).edit(commit = true) {
            // clear the previous settings
            clear()

            // decide for each preference which type it is and save it to the preferences
            preferences.forEach { (key, jsonValue) ->
                val value = if (jsonValue.isString) {
                    jsonValue.content
                } else {
                    jsonValue.booleanOrNull
                        ?: jsonValue.intOrNull
                        ?: jsonValue.longOrNull
                        ?: jsonValue.floatOrNull
                }
                when (value) {
                    is Boolean -> putBoolean(key, value)
                    is Float -> putFloat(key, value)
                    is Long -> putLong(key, value)
                    is Int -> {
                        // integer preferences such as the start fragment
                        if (key == PreferenceKeys.START_FRAGMENT || "_color" in key.orEmpty()) {
                            putInt(key, value)
                        } else {
                            putLong(key, value.toLong())
                        }
                    }

                    is String -> {
                        if (
                            key == PreferenceKeys.HOME_TAB_CONTENT ||
                            key == PreferenceKeys.SELECTED_FEED_FILTERS
                        ) {
                            putStringSet(key, value.split(",").toSet())
                        } else {
                            putString(key, value)
                        }
                    }
                }
            }
        }

        // re-schedule the notification worker as some settings related to it might have changed
        NotificationHelper.enqueueWork(context, ExistingPeriodicWorkPolicy.UPDATE)
    }
}
