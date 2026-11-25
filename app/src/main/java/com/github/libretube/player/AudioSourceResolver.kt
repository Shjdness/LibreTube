package com.github.libretube.player

import android.content.Context
import android.util.Log
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.FileDataSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.github.libretube.api.MediaServiceRepository
import com.github.libretube.api.obj.Streams
import com.github.libretube.db.DatabaseHolder.Database
import com.github.libretube.extensions.setMetadata
import com.github.libretube.extensions.toAndroidUri
import com.github.libretube.helpers.NetworkHelper
import com.github.libretube.helpers.PlayerHelper
import com.github.libretube.obj.AudioTrack
import com.github.libretube.player.manifest.SabrManifest
import com.github.libretube.util.YoutubeHlsPlaylistParser
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@UnstableApi
class AudioSourceResolver(context: Context) {
    private val context = context.applicationContext
    data class ResolvedAudio(val track: AudioTrack, val source: MediaSource, val local: Boolean)
    suspend fun resolve(id: String, allowNetwork: Boolean = true): ResolvedAudio? {
        val start = SystemClock.elapsedRealtime()
        return try {
            val completed = withContext(Dispatchers.IO) {
                val download = com.github.libretube.repo.AudioLibraryRepository.localAudio(id) ?: return@withContext null
                download.finishedAudio?.let { download to it }
            }
            if (completed != null) {
                val (local, audio) = completed
                val item = MediaItem.Builder().setMediaId(id).setUri(audio.path.toAndroidUri()).setMetadata(local).build()
                val source = ProgressiveMediaSource.Factory(FileDataSource.Factory()).createMediaSource(item)
                return ResolvedAudio(AudioTrack.fromStream(local.download.toStreamItem())!!.copy(localFile = audio.path.toString()), source, local = true)
            }
            if (!allowNetwork || !NetworkHelper.isNetworkAvailable(context)) return null
            val streams = MediaServiceRepository.instance.getStreams(id, includeDislikes = false)
            val source = onlineSource(id, streams) ?: return null
            ResolvedAudio(AudioTrack.fromStream(streams.toStreamItem(id))!!, source, local = false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Could not resolve audio for $id", e)
            null
        } finally {
            Log.d(TAG, "Resolved or rejected $id in ${SystemClock.elapsedRealtime() - start}ms")
        }
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
                androidx.media3.exoplayer.dash.DashMediaSource.Factory(DefaultDataSource.Factory(context))
                    .createMediaSource(createMediaItem(videoId, manifest, MimeTypes.APPLICATION_MPD, streams))
            }
            !streams.isLive && httpTracks.isNotEmpty() -> {
                val audio = httpTracks.maxBy { it.bitrate ?: 0 }
                val factory = ProgressiveMediaSource.Factory(DefaultDataSource.Factory(context))
                factory.createMediaSource(createMediaItem(videoId, audio.url!!.toUri(), audio.mimeType, streams))
            }
            streams.hls != null -> {
                val factory = HlsMediaSource.Factory(DefaultDataSource.Factory(context))
                    .setPlaylistParserFactory(YoutubeHlsPlaylistParser.Factory())
                val item = createMediaItem(videoId, streams.hls.toUri(), MimeTypes.APPLICATION_M3U8, streams)
                factory.createMediaSource(item)
            }
            else -> null
        }
    }

    private fun createMediaItem(videoId: String, uri: android.net.Uri, mimeType: String?, streams: Streams): MediaItem =
        MediaItem.Builder().setMediaId(videoId).setUri(uri).setMimeType(mimeType).setMetadata(streams, videoId).build()

    private companion object { const val TAG = "AudioSourceResolver" }
}
