package com.github.libretube.services

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import androidx.core.net.toUri
import androidx.core.os.bundleOf
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.FileDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.github.libretube.R
import com.github.libretube.api.MediaServiceRepository
import com.github.libretube.api.SubscriptionHelper
import com.github.libretube.api.obj.StreamItem
import com.github.libretube.api.obj.Streams
import com.github.libretube.constants.IntentData
import com.github.libretube.db.DatabaseHelper
import com.github.libretube.db.DatabaseHolder.Database
import com.github.libretube.enums.PlayerCommand
import com.github.libretube.enums.PlayerEvent
import com.github.libretube.extensions.parcelable
import com.github.libretube.extensions.parcelableExtra
import com.github.libretube.extensions.setMetadata
import com.github.libretube.extensions.toAndroidUri
import com.github.libretube.extensions.toID
import com.github.libretube.extensions.toastFromMainThread
import com.github.libretube.extensions.updateParameters
import com.github.libretube.helpers.NetworkHelper
import com.github.libretube.helpers.PlayerHelper
import com.github.libretube.parcelable.PlayerData
import com.github.libretube.player.SabrMediaSource
import com.github.libretube.player.manifest.SabrManifest
import com.github.libretube.repo.UserDataRepositoryHelper
import com.github.libretube.ui.activities.MainActivity
import com.github.libretube.util.DeArrowUtil
import com.github.libretube.util.DefaultTrackSelectorWithAudioQualitySupport
import com.github.libretube.util.NowPlayingNotification
import com.github.libretube.util.PlaybackStateStore
import com.github.libretube.util.PlayingQueue
import com.github.libretube.util.YoutubeHlsPlaylistParser
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@UnstableApi
class AudioPlaybackService : MediaLibraryService(), MediaLibrarySession.Callback {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var player: ExoPlayer
    private lateinit var trackSelector: DefaultTrackSelectorWithAudioQualitySupport
    private var session: MediaLibrarySession? = null
    private var sourceJob: Job? = null
    private var checkpointJob: Job? = null
    private var currentId: String? = null
    private var localOnly = false
    private var queueName: String? = null
    private var playlistId: String? = null
    private var playlistNextPage: String? = null
    private var explicitlyStopped = false
    private var switchingSource = false
    private var sourceGeneration = 0

    override fun onCreate() {
        super.onCreate()
        active = true
        val notification = NowPlayingNotification(this)
        notification.notificationIntent = Intent(this, MainActivity::class.java)
            .putExtra(IntentData.maximizePlayer, true)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        setMediaNotificationProvider(notification)
        trackSelector = DefaultTrackSelectorWithAudioQualitySupport(this)
        trackSelector.updateParameters {
            setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
            setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        }
        player = PlayerHelper.createPlayer(this, trackSelector)
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                checkpointJob?.cancel()
                if (isPlaying) {
                    checkpointJob = scope.launch {
                        while (isActive) {
                            checkpoint()
                            delay(15_000)
                        }
                    }
                } else checkpoint()
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK) checkpoint()
            }

            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_READY -> {
                        val played = PlayingQueue.getCurrent()
                        checkpoint()
                        if (played != null && PlayerHelper.watchHistoryEnabled && player.playWhenReady) {
                            val entry = played.toWatchHistoryEntry(player.currentPosition)
                            scope.launch(Dispatchers.IO) {
                                runCatching { UserDataRepositoryHelper.userDataRepository.addToWatchHistory(entry) }
                            }
                        }
                    }
                    Player.STATE_ENDED -> {
                        checkpoint()
                        if (PlayingQueue.repeatMode == Player.REPEAT_MODE_ONE) {
                            player.seekTo(0)
                            player.play()
                        } else if (PlayerHelper.autoPlayEnabled) {
                            playAdjacent()
                        }
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.w("AudioPlaybackService", "Audio source failed", error)
                toastFromMainThread(R.string.audio_stream_unavailable)
                checkpoint()
            }
        })
        session = MediaLibrarySession.Builder(this, QueuePlayer(player), this)
            .setId("libreplayer-audio")
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = session

    override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
        val commands = super.onConnect(session, controller)
        return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
            .setAvailableSessionCommands(commands.availableSessionCommands.buildUpon()
                .add(startServiceCommand).add(stopServiceCommand).add(runPlayerActionCommand).build())
            .setAvailablePlayerCommands(commands.availablePlayerCommands.buildUpon()
                .add(Player.COMMAND_SEEK_TO_NEXT).add(Player.COMMAND_SEEK_TO_PREVIOUS).build())
            .build()
    }

    override fun onCustomCommand(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        customCommand: SessionCommand,
        args: Bundle
    ): ListenableFuture<SessionResult> {
        when (customCommand) {
            startServiceCommand -> {
                val request = args.parcelable<PlayerData>(IntentData.playerData)
                val id = request?.videoId?.toID()
                if (request == null || id == null || !VIDEO_ID.matches(id)) {
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_BAD_VALUE))
                }
                explicitlyStopped = false
                checkpoint()
                localOnly = request.isOffline
                queueName = request.queueName
                playlistId = request.playlistId
                playlistNextPage = request.playlistNextPage
                if (!request.keepQueue) PlayingQueue.clear()
                request.playlistNextPage?.let { page ->
                    request.playlistId?.let { PlayingQueue.appendPlaylistPages(it, page) }
                }
                launchSource(listOf(id), autoPlay = !request.resumePaused && PlayerHelper.playAutomatically,
                    explicitPosition = request.resumePositionMs ?: request.timestamp.takeIf { it > 0 }?.times(1000),
                    request = request)
            }
            stopServiceCommand -> stopPlayback()
            runPlayerActionCommand -> when {
                args.containsKey(PlayerCommand.SKIP_SILENCE.name) ->
                    player.skipSilenceEnabled = args.getBoolean(PlayerCommand.SKIP_SILENCE.name)
                args.containsKey(PlayerCommand.SET_AUDIO_ROLE_FLAGS.name) -> trackSelector.updateParameters {
                    setPreferredAudioRoleFlags(args.getInt(PlayerCommand.SET_AUDIO_ROLE_FLAGS.name))
                }
                args.containsKey(PlayerCommand.SET_AUDIO_LANGUAGE.name) -> trackSelector.updateParameters {
                    setPreferredAudioLanguage(args.getString(PlayerCommand.SET_AUDIO_LANGUAGE.name))
                }
                args.containsKey(PlayerCommand.PLAY_VIDEO_BY_ID.name) -> {
                    args.getString(PlayerCommand.PLAY_VIDEO_BY_ID.name)?.toID()?.let { launchSource(listOf(it)) }
                }
                args.containsKey(PlayerCommand.PLAY_NEXT_VIDEO.name) -> playAdjacent()
            }
            else -> return super.onCustomCommand(session, controller, customCommand, args)
        }
        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
    }

    private fun playAdjacent(reverse: Boolean = false) {
        val candidates = PlayingQueue.adjacentIds(currentId, reverse)
        if (candidates.isNotEmpty()) launchSource(candidates)
        else if (PlayingQueue.repeatMode == Player.REPEAT_MODE_ALL && PlayingQueue.size() == 1 && currentId != null) {
            player.seekTo(0)
            player.play()
        }
    }

    private fun launchSource(
        candidates: List<String>,
        autoPlay: Boolean = true,
        explicitPosition: Long? = null,
        request: PlayerData? = null
    ) {
        val generation = ++sourceGeneration
        sourceJob?.cancel()
        checkpoint()
        switchingSource = true
        player.pause()
        sourceJob = scope.launch {
            try {
                for (id in candidates) {
                    val local = withContext(Dispatchers.IO) { Database.downloadDao().findById(id) }
                    val audio = local?.finishedAudio
                    if (local != null && audio != null) {
                        val item = MediaItem.Builder().setUri(audio.path.toAndroidUri()).setMetadata(local).build()
                        val source = ProgressiveMediaSource.Factory(FileDataSource.Factory()).createMediaSource(item)
                        activate(id, local.download.toStreamItem(), source, explicitPosition, autoPlay, local = true)
                        return@launch
                    }
                    if (localOnly || !NetworkHelper.isNetworkAvailable(this@AudioPlaybackService)) continue
                    val streams = try {
                        withContext(Dispatchers.IO) {
                            MediaServiceRepository.instance.getStreams(id).let { DeArrowUtil.deArrowStreams(it, id) }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w("AudioPlaybackService", "Could not resolve audio for " + id, e)
                        continue
                    }
                    val source = onlineSource(id, streams) ?: continue
                    val stream = streams.toStreamItem(id)
                    if (request != null && !request.keepQueue) {
                        PlayingQueue.updateQueue(stream, request.playlistId, request.channelId)
                    }
                    scope.launch(Dispatchers.IO) { runCatching { SubscriptionHelper.submitFeedItemChange(stream.toFeedItem()) } }
                    activate(id, stream, source, explicitPosition, autoPlay, local = false)
                    return@launch
                }
                toastFromMainThread(if (localOnly || !NetworkHelper.isNetworkAvailable(this@AudioPlaybackService))
                    R.string.audio_not_downloaded else R.string.audio_stream_unavailable)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("AudioPlaybackService", "Unable to open audio", e)
                toastFromMainThread(R.string.audio_stream_unavailable)
            } finally {
                if (generation == sourceGeneration) switchingSource = false
            }
        }
    }

    private suspend fun activate(
        id: String, track: StreamItem, source: MediaSource, explicitPosition: Long?, autoPlay: Boolean, local: Boolean
    ) {
        val position = explicitPosition ?: if (PlayerHelper.watchPositionsAudio) {
            withContext(Dispatchers.IO) { DatabaseHelper.getWatchPosition(id) }
                ?.takeUnless { DatabaseHelper.isVideoWatched(it, track.duration) } ?: 0L
        } else 0L
        currentId = id
        PlayingQueue.updateCurrent(track)
        player.setWakeMode(if (local) C.WAKE_MODE_LOCAL else C.WAKE_MODE_NETWORK)
        player.playlistMetadata = MediaMetadata.Builder().setExtras(bundleOf(IntentData.videoId to id)).build()
        player.setMediaSource(source, position.coerceAtLeast(0))
        player.playWhenReady = autoPlay
        switchingSource = false
        player.prepare()
        checkpoint()
    }

    private fun checkpoint() {
        val id = currentId ?: return
        if (explicitlyStopped || switchingSource) return
        PlaybackStateStore.save(this, PlaybackStateStore.State(id, player.currentPosition.coerceAtLeast(0),
            localOnly, queueName, PlayingQueue.getStreams(), playlistId, playlistNextPage))
        if (PlayerHelper.watchPositionsAudio) PlayerHelper.saveWatchPosition(player, id)
    }

    private fun stopPlayback() {
        explicitlyStopped = true
        sourceJob?.cancel()
        checkpointJob?.cancel()
        PlaybackStateStore.clear(this)
        player.stop()
        player.clearMediaItems()
        PlayingQueue.clear()
        currentId = null
        pauseAllPlayersAndStopSelf()
    }

    override fun onMediaButtonEvent(session: MediaSession, controller: MediaSession.ControllerInfo, intent: Intent): Boolean {
        val event = intent.parcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT) ?: return false
        val handled = when (event.keyCode) {
            KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_STOP,
            KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> true
            else -> false
        }
        if (!handled) return super.onMediaButtonEvent(session, controller, intent)
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_MEDIA_NEXT -> playAdjacent()
                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> playAdjacent(reverse = true)
                KeyEvent.KEYCODE_MEDIA_STOP -> stopPlayback()
                KeyEvent.KEYCODE_MEDIA_REWIND -> PlayerHelper.handlePlayerAction(player, PlayerEvent.Rewind)
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> PlayerHelper.handlePlayerAction(player, PlayerEvent.Forward)
            }
        }
        return true
    }

    override fun onDestroy() {
        checkpoint()
        scope.cancel()
        session?.release()
        session = null
        player.release()
        PlayingQueue.clear()
        active = false
        super.onDestroy()
    }

    private inner class QueuePlayer(player: Player) : ForwardingPlayer(player) {
        override fun hasNextMediaItem() = PlayingQueue.hasNext()
        override fun hasPreviousMediaItem() = PlayingQueue.hasPrev()
        override fun seekToNext() = playAdjacent()
        override fun seekToPrevious() = playAdjacent(reverse = true)
        override fun getAvailableCommands(): Player.Commands = super.getAvailableCommands().buildUpon()
            .addAll(COMMAND_SEEK_TO_PREVIOUS, COMMAND_SEEK_TO_NEXT).build()
        override fun isCommandAvailable(command: Int): Boolean =
            command == COMMAND_SEEK_TO_NEXT || command == COMMAND_SEEK_TO_PREVIOUS || super.isCommandAvailable(command)
    }

    private fun onlineSource(videoId: String, streams: Streams): MediaSource? {
        val httpTracks = streams.audioStreams.filter { it.url?.startsWith("http") == true }
        val dashTracks = httpTracks.filter {
            it.initStart != null && it.initEnd != null && it.indexStart != null && (it.indexEnd ?: 0) > 0 &&
                it.codec != null && it.mimeType != null && it.bitrate != null
        }

        return when {
            !streams.isLive && streams.audioStreams.isNotEmpty() &&
                streams.serverAbrStreamingUrl != null && streams.videoPlaybackUstreamerConfig != null -> {
                val factory = SabrMediaSource.Factory(SabrManifest(videoId, streams))
                val item = createMediaItem(videoId, streams.serverAbrStreamingUrl.toUri(), "application/vnd.yt-ump", streams)
                factory.createMediaSource(item)
            }
            !streams.isLive && dashTracks.isNotEmpty() -> {
                val manifest = PlayerHelper.createDashSource(streams.copy(audioStreams = dashTracks))
                androidx.media3.exoplayer.dash.DashMediaSource.Factory(DefaultDataSource.Factory(this))
                    .createMediaSource(createMediaItem(videoId, manifest, MimeTypes.APPLICATION_MPD, streams))
            }
            !streams.isLive && httpTracks.isNotEmpty() -> {
                val audio = httpTracks.maxBy { it.bitrate ?: 0 }
                val factory = ProgressiveMediaSource.Factory(DefaultDataSource.Factory(this))
                factory.createMediaSource(createMediaItem(videoId, audio.url!!.toUri(), audio.mimeType, streams))
            }
            streams.hls != null -> {
                val factory = HlsMediaSource.Factory(DefaultDataSource.Factory(this))
                    .setPlaylistParserFactory(YoutubeHlsPlaylistParser.Factory())
                val item = createMediaItem(videoId, streams.hls.toUri(), MimeTypes.APPLICATION_M3U8, streams)
                factory.createMediaSource(item)
            }
            else -> null
        }
    }

    private fun createMediaItem(videoId: String, uri: android.net.Uri, mimeType: String?, streams: Streams): MediaItem =
        MediaItem.Builder().setUri(uri).setMimeType(mimeType).setMetadata(streams, videoId).build()

    companion object {
        private val VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")
        @Volatile var active = false
            private set
        val startServiceCommand = SessionCommand("start_service_action", Bundle.EMPTY)
        val stopServiceCommand = SessionCommand("stop_service_action", Bundle.EMPTY)
        val runPlayerActionCommand = SessionCommand("run_player_command_action", Bundle.EMPTY)
    }
}
