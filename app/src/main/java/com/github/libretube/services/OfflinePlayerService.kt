package com.github.libretube.services

import android.content.Intent
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaItem.SubtitleConfiguration
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.FileDataSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.source.SingleSampleMediaSource
import com.github.libretube.constants.IntentData
import com.github.libretube.db.DatabaseHelper
import com.github.libretube.db.DatabaseHolder.Database
import com.github.libretube.db.obj.DownloadWithItems
import com.github.libretube.enums.FileType
import com.github.libretube.extensions.parcelable
import com.github.libretube.extensions.setMetadata
import com.github.libretube.extensions.toAndroidUri
import com.github.libretube.extensions.updateParameters
import com.github.libretube.helpers.PlayerHelper
import com.github.libretube.parcelable.PlayerData
import com.github.libretube.repo.UserDataRepositoryHelper
import com.github.libretube.ui.activities.MainActivity
import com.github.libretube.util.PlayingQueue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A service to play downloaded audio in the background
 */
@OptIn(UnstableApi::class)
open class OfflinePlayerService : AbstractPlayerService() {
    override val isOfflinePlayer: Boolean = true

    private var downloadWithItems: DownloadWithItems? = null

    private val scope = CoroutineScope(Dispatchers.Main)

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                playNextVideo()
            }

            // add video to watch history when playback starts
            if (playbackState == Player.STATE_READY && PlayerHelper.watchHistoryEnabled) {
                val watchHistoryEntry = downloadWithItems?.download?.toStreamItem()
                    ?.toWatchHistoryEntry(exoPlayer?.currentPosition)?: return

                scope.launch(Dispatchers.IO) {
                    runCatching {
                        UserDataRepositoryHelper.userDataRepository.addToWatchHistory(watchHistoryEntry)
                    }
                }
            }
        }
    }

    override suspend fun onServiceCreated(args: Bundle) {
        if (args.isEmpty) return

        val playerData = args.parcelable<PlayerData>(IntentData.playerData) ?: return
        isAudioOnlyPlayer = args.getBoolean(IntentData.audioOnly, false)

        PlayingQueue.clear()

        this.videoId = playerData.videoId ?: return
        val downloads = Database.downloadDao().getAll().associateBy { it.download.videoId }
        PlayingQueue.setStreams(playerData.offlineQueueIds.mapNotNull { id ->
            downloads[id]?.takeIf { it.finishedAudio != null }?.download?.toStreamItem()
        })

        withContext(Dispatchers.Main) {
            exoPlayer?.removeListener(playerListener)
            exoPlayer?.addListener(playerListener)
            trackSelector?.updateParameters {
                setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, isAudioOnlyPlayer)
            }
        }
    }

    override fun getIntentActivity(): Class<*> = MainActivity::class.java

    /**
     * Attempt to start an audio player with the given download items
     */
    override suspend fun startPlayback() {
        super.startPlayback()

        val downloadWithItems = withContext(Dispatchers.IO) {
            Database.downloadDao().findById(videoId)
        } ?: run {
            withContext(Dispatchers.Main) { stopSelf() }
            return
        }
        this.downloadWithItems = downloadWithItems

        PlayingQueue.updateCurrent(downloadWithItems.download.toStreamItem())

        withContext(Dispatchers.Main) {
            if (!setMediaItem(downloadWithItems)) return@withContext

            // automatically start playback when using the audio player
            exoPlayer?.playWhenReady = PlayerHelper.playAutomatically || isAudioOnlyPlayer
            exoPlayer?.prepare()

            if (watchPositionsEnabled) {
                DatabaseHelper.getWatchPosition(videoId)?.let {
                    if (!DatabaseHelper.isVideoWatched(
                            it,
                            downloadWithItems.download.duration
                        )
                    ) exoPlayer?.seekTo(it)
                }
            }
        }
    }

    private fun setMediaItem(downloadWithItems: DownloadWithItems): Boolean {
        val downloadFiles = downloadWithItems.downloadItems.filter { it.isFinished && (!isAudioOnlyPlayer || it.type == FileType.AUDIO) }

        val videoUri = downloadFiles.firstOrNull { it.type == FileType.VIDEO }?.path?.toAndroidUri()
        val audioUri = downloadFiles.firstOrNull { it.type == FileType.AUDIO }?.path?.toAndroidUri()
        val subtitleInfo = downloadFiles.firstOrNull { it.type == FileType.SUBTITLE }

        val videoSource = videoUri?.let { videoUri ->
            val videoItem = MediaItem.Builder()
                .setUri(videoUri)
                .setMetadata(downloadWithItems)
                .build()

            ProgressiveMediaSource.Factory(FileDataSource.Factory())
                .createMediaSource(videoItem)
        }

        val audioSource = audioUri?.let { audioUri ->
            val audioItem = MediaItem.Builder()
                .setUri(audioUri)
                .setMetadata(downloadWithItems)
                .build()

            ProgressiveMediaSource.Factory(FileDataSource.Factory())
                .createMediaSource(audioItem)
        }

        val subtitleSource = subtitleInfo?.let { subtitleInfo ->
            val subtitle = SubtitleConfiguration.Builder(subtitleInfo.path.toAndroidUri())
                .setMimeType(MimeTypes.APPLICATION_TTML)
                .setLanguage(subtitleInfo.language ?: "en")
                .build()

            SingleSampleMediaSource.Factory(FileDataSource.Factory())
                .createMediaSource(subtitle, C.TIME_UNSET)
        }

        var mediaSource: MediaSource? = null
        listOfNotNull(videoSource, audioSource, subtitleSource).forEach { source ->
            mediaSource =
                if (mediaSource == null) source else MergingMediaSource(mediaSource!!, source)
        }

        if (mediaSource == null || isAudioOnlyPlayer && audioSource == null) {
            stopSelf()
            return false
        }

        exoPlayer?.setMediaSource(mediaSource!!)

        trackSelector?.updateParameters {
            setPreferredTextRoleFlags(C.ROLE_FLAG_CAPTION)
            setPreferredTextLanguage(subtitleInfo?.language ?: "en")
        }
        return true
    }

    /**
     * Stop the service when app is removed from the task manager.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        onDestroy()
    }
}
