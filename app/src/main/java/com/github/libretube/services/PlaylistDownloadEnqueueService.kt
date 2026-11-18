package com.github.libretube.services

import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.getSystemService
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.github.libretube.LibreTubeApp.Companion.PLAYLIST_DOWNLOAD_ENQUEUE_CHANNEL_NAME
import com.github.libretube.R
import com.github.libretube.api.MediaServiceRepository
import com.github.libretube.api.PlaylistsHelper
import com.github.libretube.api.obj.StreamItem
import com.github.libretube.constants.IntentData
import com.github.libretube.db.DatabaseHolder.Database
import com.github.libretube.db.obj.PendingAudioDownload
import com.github.libretube.db.obj.PendingPlaylistDownload
import com.github.libretube.enums.NotificationId
import com.github.libretube.enums.PlaylistType
import com.github.libretube.extensions.serializableExtra
import com.github.libretube.extensions.toID
import com.github.libretube.extensions.toastFromMainDispatcher
import com.github.libretube.helpers.DownloadHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.cancellation.CancellationException

class PlaylistDownloadEnqueueService : LifecycleService() {
    private val enqueueMutex = Mutex()
    private lateinit var nManager: NotificationManager

    private lateinit var playlistId: String
    private lateinit var playlistType: PlaylistType
    private var playlistName: String? = null
    private var amountOfVideos = 0
    private var amountOfVideosDone = 0

    override fun onCreate() {
        super.onCreate()

        ServiceCompat.startForeground(
            this,
            NotificationId.ENQUEUE_PLAYLIST_DOWNLOAD.id,
            buildNotification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        nManager = getSystemService()!!

        val id = intent?.getStringExtra(IntentData.playlistId)
        val type = intent?.serializableExtra<PlaylistType>(IntentData.playlistType)
        if (id == null || type == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        lifecycleScope.launch(Dispatchers.IO) {
            enqueueMutex.withLock {
                playlistId = id
                playlistName = intent?.getStringExtra(IntentData.playlistName)
                playlistType = type
                amountOfVideos = 0
                amountOfVideosDone = 0
                try {
                    val dao = Database.downloadDao()
                    dao.insertPendingPlaylist(PendingPlaylistDownload(id, type.name, playlistName.orEmpty()))
                    enqueuePlaylist()
                    dao.removePendingPlaylist(id, type.name)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e("PlaylistAudioDownload", "Unable to enqueue playlist audio", e)
                    toastFromMainDispatcher(e.localizedMessage ?: getString(R.string.unknown_error))
                } finally {
                    stopSelfResult(startId)
                }
            }
        }

        return START_NOT_STICKY
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, PLAYLIST_DOWNLOAD_ENQUEUE_CHANNEL_NAME)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle(
                getString(R.string.enqueueing_playlist_download, playlistName ?: "...")
            )
            .setProgress(amountOfVideos, amountOfVideosDone, false)
            .setOnlyAlertOnce(true)
            .build()
    }

    private suspend fun enqueuePlaylist() {
        val playlist = PlaylistsHelper.getPlaylist(playlistId, playlistType)

        PlaylistsHelper.cachePlaylistForOffline(playlistId, playlist, replaceLinks = playlist.nextpage == null)

        amountOfVideos = playlist.videos
        enqueueStreams(playlist.relatedStreams)

        var nextPage = playlist.nextpage
        // retry each api call once when fetching next pages to increase success chances
        var alreadyRetriedOnce = false
        val visitedPages = mutableSetOf<String>()

        while (nextPage != null) {
            val page = nextPage
            val playlistPage = try {
                MediaServiceRepository.instance.getPlaylistNextPage(playlistId, page)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (alreadyRetriedOnce) throw e
                alreadyRetriedOnce = true
                continue
            }
            check(visitedPages.add(page) && playlistPage.nextpage !in visitedPages) { getString(R.string.unknown_error) }

            alreadyRetriedOnce = false
            PlaylistsHelper.cachePlaylistForOffline(playlistId, playlist.copy(relatedStreams = playlistPage.relatedStreams), replaceLinks = false)
            enqueueStreams(playlistPage.relatedStreams)
            nextPage = playlistPage.nextpage
        }
    }

    private suspend fun enqueueStreams(streams: List<StreamItem>) {
        nManager.notify(NotificationId.ENQUEUE_PLAYLIST_DOWNLOAD.id, buildNotification())

        val ids = streams.mapNotNull { it.url?.toID() }.filter { VIDEO_ID.matches(it) }
        if (ids.isEmpty()) return
        // Persist the page before handing off to another service.
        Database.downloadDao().insertPendingAudio(ids.distinct().map(::PendingAudioDownload))
        DownloadHelper.downloadAudioBatch(this, ids)
        amountOfVideosDone += ids.size
        nManager.notify(NotificationId.ENQUEUE_PLAYLIST_DOWNLOAD.id, buildNotification())
    }

    private companion object {
        val VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")
    }

    override fun onDestroy() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
