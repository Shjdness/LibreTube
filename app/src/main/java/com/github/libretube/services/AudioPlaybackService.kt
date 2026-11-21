package com.github.libretube.services

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
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
import com.github.libretube.util.DefaultTrackSelectorWithAudioQualitySupport
import com.github.libretube.util.NowPlayingNotification
import com.github.libretube.util.PlaybackStateStore
import com.github.libretube.util.PlayingQueue
import com.github.libretube.util.YoutubeHlsPlaylistParser
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
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
    private var nextPreparation: NextPreparation? = null
    private var automaticTransition = false
    private val failedAutomaticTracks = mutableSetOf<String>()
    private var transitionStartedAt = 0L
    private var waitingForSoundSince = 0L

    private data class ResolvedAudio(val track: StreamItem, val source: MediaSource, val local: Boolean)

    private class NextPreparation(val id: String, val task: Deferred<ResolvedAudio?>) {
        var queued: ResolvedAudio? = null
        var precedingDurationMs = 0L
    }

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
        player.setPreloadConfiguration(ExoPlayer.PreloadConfiguration(5_000_000L))
        scope.launch {
            while (isActive) {
                delay(300)
                prepareFollowingTrack()
            }
        }
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                checkpointJob?.cancel()
                if (isPlaying) {
                    if (waitingForSoundSince != 0L) {
                        Log.d(TAG, "Audio playing $currentId in ${SystemClock.elapsedRealtime() - waitingForSoundSince}ms")
                        waitingForSoundSince = 0
                    }
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

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val next = nextPreparation ?: return
                val audio = next.queued ?: return
                if (mediaItem?.mediaId != next.id) return
                nextPreparation = null
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && next.id != automaticCandidates().firstOrNull()) {
                    player.pause()
                    val previous = player.currentMediaItemIndex - 1
                    if (previous >= 0) {
                        player.seekTo(previous, next.precedingDurationMs)
                        clearNextPreparation()
                    }
                    playAdjacent(automatic = true)
                    if (PlayingQueue.repeatMode == Player.REPEAT_MODE_ONE && PlayerHelper.autoPlayEnabled) {
                        player.seekTo(0)
                        player.play()
                    }
                    return
                }
                transitionStartedAt = SystemClock.elapsedRealtime()
                waitingForSoundSince = transitionStartedAt
                automaticTransition = reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO
                onTrackSelected(next.id, audio)
                if (player.currentMediaItemIndex > 0) player.removeMediaItems(0, player.currentMediaItemIndex)
                if (player.playbackState == Player.STATE_READY) recordReady()
            }

            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_READY -> {
                        recordReady()
                    }
                    Player.STATE_ENDED -> {
                        if (switchingSource || explicitlyStopped || !player.playWhenReady) return
                        checkpoint()
                        if (PlayingQueue.repeatMode == Player.REPEAT_MODE_ONE) {
                            player.seekTo(0)
                            player.play()
                        } else if (PlayerHelper.autoPlayEnabled) {
                            playAdjacent(automatic = true)
                        }
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.w("AudioPlaybackService", "Audio source failed", error)
                val id = currentId
                if (automaticTransition && id != null) {
                    failedAutomaticTracks.add(id)
                    if (automaticCandidates().isNotEmpty()) {
                        toastFromMainThread(R.string.audio_skipping_failed)
                        playAdjacent(automatic = true)
                        return
                    }
                }
                val message = when (error.errorCode) {
                    PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> R.string.audio_local_missing
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> R.string.audio_network_failed
                    else -> R.string.audio_stream_unavailable
                }
                setPlaylistMetadata(errorMessage = getString(message))
                toastFromMainThread(message)
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
                .addAll(Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_PREVIOUS,
                    Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM).build())
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
                args.containsKey(PlayerCommand.PLAY_PREVIOUS_VIDEO.name) -> playAdjacent(reverse = true)
            }
            else -> return super.onCustomCommand(session, controller, customCommand, args)
        }
        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
    }

    private fun automaticCandidates(): List<String> {
        if (!PlayerHelper.autoPlayEnabled || PlayingQueue.repeatMode == Player.REPEAT_MODE_ONE) return emptyList()
        return PlayingQueue.adjacentIds(currentId).filterNot(failedAutomaticTracks::contains)
    }

    private fun playAdjacent(reverse: Boolean = false, automatic: Boolean = false) {
        val candidates = if (automatic) automaticCandidates() else PlayingQueue.adjacentIds(currentId, reverse).take(1)
        if (candidates.isEmpty()) {
            if (automatic && PlayingQueue.repeatMode == Player.REPEAT_MODE_ALL && PlayingQueue.size() == 1) {
                player.seekTo(0)
                player.play()
            }
            return
        }
        val next = nextPreparation
        if (!automatic && !switchingSource && next?.id == candidates.first() && next.queued != null &&
            player.mediaItemCount > player.currentMediaItemIndex + 1
        ) {
            player.seekTo(player.currentMediaItemIndex + 1, C.TIME_UNSET)
            player.play()
        } else {
            launchSource(candidates, explicitPosition = 0, automatic = automatic, reusePreparation = true)
        }
    }

    private fun clearNextPreparation(keep: Deferred<ResolvedAudio?>? = null) {
        nextPreparation?.task?.takeUnless { it === keep }?.cancel()
        nextPreparation = null
        val firstToRemove = player.currentMediaItemIndex + 1
        if (firstToRemove < player.mediaItemCount) player.removeMediaItems(firstToRemove, player.mediaItemCount)
    }

    private fun prepareFollowingTrack() {
        if (nextPreparation == null && (!player.isPlaying || player.duration <= 0 ||
                player.duration - player.currentPosition > 25_000)
        ) return
        val id = automaticCandidates().firstOrNull()
        val current = currentId
        if (id != nextPreparation?.id) clearNextPreparation()
        if (id == null || current == null || nextPreparation != null || switchingSource ||
            !player.isPlaying || player.duration <= 0 || player.duration - player.currentPosition > 25_000
        ) return

        val task = scope.async {
            val first = resolveAudio(id)
            if (first != null || localOnly || !NetworkHelper.isNetworkAvailable(this@AudioPlaybackService)) first
            else {
                delay(1500)
                resolveAudio(id)
            }
        }
        val preparation = NextPreparation(id, task)
        nextPreparation = preparation
        scope.launch {
            val audio = task.await() ?: return@launch
            if (nextPreparation !== preparation || switchingSource || currentId != current ||
                automaticCandidates().firstOrNull() != id
            ) return@launch
            preparation.queued = audio
            preparation.precedingDurationMs = player.duration.coerceAtLeast(0)
            player.addMediaSource(audio.source)
            Log.d(TAG, "Prepared following audio $id")
        }
    }

    private suspend fun resolveAudio(id: String, allowNetwork: Boolean = true): ResolvedAudio? {
        val start = SystemClock.elapsedRealtime()
        return try {
            val completed = withContext(Dispatchers.IO) {
                val download = Database.downloadDao().findById(id) ?: return@withContext null
                download.finishedAudio?.let { download to it }
            }
            if (completed != null) {
                val (local, audio) = completed
                val item = MediaItem.Builder().setMediaId(id).setUri(audio.path.toAndroidUri()).setMetadata(local).build()
                val source = ProgressiveMediaSource.Factory(FileDataSource.Factory()).createMediaSource(item)
                return ResolvedAudio(local.download.toStreamItem(), source, local = true)
            }
            if (!allowNetwork || localOnly || !NetworkHelper.isNetworkAvailable(this)) return null
            val streams = MediaServiceRepository.instance.getStreams(id, includeDislikes = false)
            val source = onlineSource(id, streams) ?: return null
            ResolvedAudio(streams.toStreamItem(id), source, local = false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Could not resolve audio for $id", e)
            null
        } finally {
            Log.d(TAG, "Resolved or rejected $id in ${SystemClock.elapsedRealtime() - start}ms")
        }
    }

    private fun launchSource(
        candidates: List<String>,
        autoPlay: Boolean = true,
        explicitPosition: Long? = null,
        request: PlayerData? = null,
        automatic: Boolean = false,
        reusePreparation: Boolean = false
    ) {
        val generation = ++sourceGeneration
        sourceJob?.cancel()
        val next = nextPreparation?.takeIf { reusePreparation && it.id == candidates.firstOrNull() }
        clearNextPreparation(next?.task)
        if (!automatic) failedAutomaticTracks.clear()
        checkpoint()
        val shouldResumeOnFailure = player.isPlaying
        switchingSource = true
        player.pause()
        transitionStartedAt = SystemClock.elapsedRealtime()
        waitingForSoundSince = transitionStartedAt
        sourceJob = scope.launch {
            try {
                val tracks = PlayingQueue.getStreams()
                val downloadedFallbackIds = if (request?.allowDownloadedFallback == true) {
                    tracks.mapNotNull { it.url?.toID() }.filterNot(candidates::contains)
                } else emptyList()
                for ((index, id) in (candidates + downloadedFallbackIds).withIndex()) {
                    val title = tracks.firstOrNull { it.url?.toID() == id }?.title ?: id
                    val audio = if (index >= candidates.size) {
                        resolveAudio(id, allowNetwork = false) ?: continue
                    } else {
                        setPlaylistMetadata(title)
                        if (next?.id == id && next.queued == null) next.task.await() else resolveAudio(id)
                    }
                    if (audio == null) {
                        if (automatic) failedAutomaticTracks.add(id)
                        continue
                    }
                    if (index > 0) toastFromMainThread(R.string.audio_skipping_failed)
                    val position = explicitPosition ?: if (!automatic && PlayerHelper.watchPositionsAudio) {
                        withContext(Dispatchers.IO) { DatabaseHelper.getWatchPosition(id) }
                            ?.takeUnless { DatabaseHelper.isVideoWatched(it, audio.track.duration) } ?: 0L
                    } else 0L
                    if (request != null && !request.keepQueue && !audio.local) {
                        PlayingQueue.updateQueue(audio.track, request.playlistId, request.channelId)
                    }
                    automaticTransition = automatic
                    onTrackSelected(id, audio)
                    player.setMediaSource(audio.source, position.coerceAtLeast(0))
                    player.playWhenReady = autoPlay
                    switchingSource = false
                    player.prepare()
                    checkpoint()
                    return@launch
                }
                val message = if (localOnly || !NetworkHelper.isNetworkAvailable(this@AudioPlaybackService))
                    R.string.audio_not_downloaded else R.string.audio_network_failed
                setPlaylistMetadata(errorMessage = getString(message))
                toastFromMainThread(message)
                if (!automatic && shouldResumeOnFailure && player.playbackState != Player.STATE_ENDED) player.play()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Unable to start audio", e)
                setPlaylistMetadata(errorMessage = getString(R.string.audio_stream_unavailable))
                toastFromMainThread(R.string.audio_stream_unavailable)
            } finally {
                next?.task?.cancel()
                if (generation == sourceGeneration && switchingSource) {
                    switchingSource = false
                    waitingForSoundSince = 0
                    if (player.playlistMetadata.title != null) setPlaylistMetadata()
                }
            }
        }
    }

    private fun onTrackSelected(id: String, audio: ResolvedAudio) {
        currentId = id
        PlayingQueue.updateCurrent(audio.track)
        player.setWakeMode(if (audio.local) C.WAKE_MODE_LOCAL else C.WAKE_MODE_NETWORK)
        setPlaylistMetadata()
        if (!audio.local) scope.launch(Dispatchers.IO) {
            runCatching { SubscriptionHelper.submitFeedItemChange(audio.track.toFeedItem()) }
        }
    }

    private fun setPlaylistMetadata(loadingTitle: String? = null, errorMessage: String? = null) {
        player.playlistMetadata = MediaMetadata.Builder()
            .setTitle(loadingTitle)
            .setExtras(bundleOf(IntentData.videoId to currentId, "audio_error" to errorMessage))
            .build()
    }

    private fun recordReady() {
        checkpoint()
        if (transitionStartedAt != 0L) {
            Log.d(TAG, "Audio ready $currentId in ${SystemClock.elapsedRealtime() - transitionStartedAt}ms")
            transitionStartedAt = 0
        }
        automaticTransition = false
        failedAutomaticTracks.clear()
        val played = PlayingQueue.getCurrent()
        if (played != null && PlayerHelper.watchHistoryEnabled && player.playWhenReady) {
            val entry = played.toWatchHistoryEntry(player.currentPosition)
            scope.launch(Dispatchers.IO) {
                runCatching { UserDataRepositoryHelper.userDataRepository.addToWatchHistory(entry) }
            }
        }
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
        waitingForSoundSince = 0
        sourceJob?.cancel()
        checkpointJob?.cancel()
        clearNextPreparation()
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
        override fun hasNextMediaItem() = PlayingQueue.adjacentIds(currentId).isNotEmpty()
        override fun hasPreviousMediaItem() = PlayingQueue.adjacentIds(currentId, reverse = true).isNotEmpty()
        override fun seekToNext() = playAdjacent()
        override fun seekToPrevious() = playAdjacent(reverse = true)
        override fun seekToNextMediaItem() = playAdjacent()
        override fun seekToPreviousMediaItem() = playAdjacent(reverse = true)
        override fun getAvailableCommands(): Player.Commands = super.getAvailableCommands().buildUpon()
            .addAll(COMMAND_SEEK_TO_PREVIOUS, COMMAND_SEEK_TO_NEXT,
                COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM, COMMAND_SEEK_TO_NEXT_MEDIA_ITEM).build()
        override fun isCommandAvailable(command: Int): Boolean = getAvailableCommands().contains(command)
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
        MediaItem.Builder().setMediaId(videoId).setUri(uri).setMimeType(mimeType).setMetadata(streams, videoId).build()

    companion object {
        private const val TAG = "AudioPlaybackService"
        private val VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")
        @Volatile var active = false
            private set
        val startServiceCommand = SessionCommand("start_service_action", Bundle.EMPTY)
        val stopServiceCommand = SessionCommand("stop_service_action", Bundle.EMPTY)
        val runPlayerActionCommand = SessionCommand("run_player_command_action", Bundle.EMPTY)
    }
}
