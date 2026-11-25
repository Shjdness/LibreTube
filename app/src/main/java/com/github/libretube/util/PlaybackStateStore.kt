package com.github.libretube.util

import com.github.libretube.extensions.toID
import android.content.Context
import androidx.core.content.edit
import com.github.libretube.api.JsonHelper
import com.github.libretube.api.obj.StreamItem
import com.github.libretube.obj.AudioTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.util.UUID

object PlaybackStateStore {
    @Serializable
    data class State(
        val trackId: String,
        val positionMs: Long,
        val localOnly: Boolean,
        val queueName: String?,
        val tracks: List<StreamItem>,
        val playlistId: String? = null,
        val playlistNextPage: String? = null,
        val pausedAt: Long = 0,
    )

    @Serializable
    data class QueueSnapshot(val generation: String, val tracks: List<AudioTrack>)

    @Serializable
    data class Progress(val generation: String, val trackId: String, val positionMs: Long,
        val localOnly: Boolean, val queueName: String?, val playlistId: String?, val playlistNextPage: String?, val pausedAt: Long = 0)

    private data class Write(val context: Context, val state: State, val generation: String, val epoch: Long)
    val changes = kotlinx.coroutines.flow.MutableStateFlow(0L)
    private val lock = Any()
    private var epoch = 0L
    private var revision = Long.MIN_VALUE
    private var generation = ""
    private var cached: State? = null
    private var cleared = false
    private val writes = Channel<Write>(Channel.CONFLATED)

    init {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            var writtenGeneration = ""
            for (write in writes) {
                try {
                    val queueJson = if (writtenGeneration != write.generation) {
                        JsonHelper.json.encodeToString(QueueSnapshot.serializer(),
                            QueueSnapshot(write.generation, write.state.tracks.mapNotNull(AudioTrack::fromStream)))
                    } else null
                    val state = write.state
                    val progress = Progress(write.generation, state.trackId, state.positionMs, state.localOnly,
                        state.queueName, state.playlistId, state.playlistNextPage, state.pausedAt)
                    val progressJson = JsonHelper.json.encodeToString(Progress.serializer(), progress)
                    synchronized(lock) {
                        if (write.epoch == epoch) {
                            preferences(write.context).edit(commit = true) {
                                if (queueJson != null) putString("queue_v2", queueJson)
                                putString("progress_v2", progressJson)
                                remove("queue")
                            }
                            writtenGeneration = write.generation
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.w("PlaybackStateStore", "Could not save playback state", e)
                }
            }
        }
    }

    private fun preferences(context: Context) =
        context.getSharedPreferences("libreplayer_playback", Context.MODE_PRIVATE)

    fun save(context: Context, trackId: String, positionMs: Long, localOnly: Boolean, name: String?,
             playlistId: String?, nextPage: String?, pausedAt: Long, queueRevision: Long, tracks: () -> List<StreamItem>) {
        synchronized(lock) {
            val queue = if (cached == null || revision != queueRevision) {
                tracks()
            } else cached!!.tracks
            if (queue.none { it.url?.toID() == trackId }) return
            if (cached == null || revision != queueRevision) {
                generation = UUID.randomUUID().toString()
                revision = queueRevision
            }
            val state = State(trackId, positionMs, localOnly, name, queue, playlistId, nextPage, pausedAt)
            val changed = cached?.trackId != state.trackId
            cached = state
            if (changed) changes.value++
            cleared = false
            writes.trySend(Write(context.applicationContext, state, generation, epoch))
        }
    }

    fun load(context: Context): State? = synchronized(lock) {
        if (cleared) return@synchronized null
        cached?.let { return@synchronized it }
        val prefs = preferences(context)
        val modern = runCatching {
            val queue = JsonHelper.json.decodeFromString(QueueSnapshot.serializer(),
                prefs.getString("queue_v2", null) ?: return@runCatching null)
            val progress = JsonHelper.json.decodeFromString(Progress.serializer(),
                prefs.getString("progress_v2", null) ?: return@runCatching null)
            if (queue.generation != progress.generation) return@runCatching null
            State(progress.trackId, progress.positionMs, progress.localOnly, progress.queueName,
                queue.tracks.map(AudioTrack::toStreamItem), progress.playlistId, progress.playlistNextPage, progress.pausedAt)
        }.getOrNull()
        modern ?: prefs.getString("queue", null)?.let {
            runCatching { JsonHelper.json.decodeFromString(State.serializer(), it) }.getOrNull()
        }
    }

    fun clear(context: Context) = synchronized(lock) {
        epoch++
        revision = Long.MIN_VALUE
        cached = null
        cleared = true
        changes.value++
        preferences(context).edit(commit = true) { remove("queue"); remove("queue_v2"); remove("progress_v2") }
    }
}
