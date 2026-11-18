package com.github.libretube.util

import androidx.media3.common.Player
import com.github.libretube.api.MediaServiceRepository
import com.github.libretube.api.PlaylistsHelper
import com.github.libretube.api.obj.StreamItem
import com.github.libretube.enums.PlaylistType
import com.github.libretube.extensions.move
import com.github.libretube.extensions.runCatchingIO
import com.github.libretube.extensions.toID
import com.github.libretube.helpers.PlayerHelper
import com.github.libretube.obj.ChannelIdentifier
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.Collections

object PlayingQueue {
    // queue is a synchronized list to be safely accessible from different coroutine threads
    private val queue = Collections.synchronizedList(mutableListOf<StreamItem>())
    private var currentStream: StreamItem? = null

    private val queueJobs = mutableListOf<Job>()
    private val changes = MutableStateFlow(0L)
    val revision = changes.asStateFlow()

    private fun notifyChanged() = changes.update { it + 1 }

    // wrapper around PlayerHelper#repeatMode for compatibility
    var repeatMode: Int
        get() = PlayerHelper.repeatMode
        set(value) {
            PlayerHelper.repeatMode = value
            notifyChanged()
        }

    private fun clearJobs() {
        queueJobs.forEach {
            it.cancel()
        }
        queueJobs.clear()
    }

    fun clear() = synchronized(queue) {
        clearJobs()
        queue.clear()
        currentStream = null
        notifyChanged()
    }

    /**
     * Remove all items after the current [StreamItem] from the queue
     *
     * I.e., the current and all previous streams are kept
     */
    fun clearAfterCurrent() {
        clearJobs()
        synchronized(queue) {
            val newQueue = queue.filterIndexed { index, item -> index <= currentIndex() }
            if (queue.retainAll(newQueue)) notifyChanged()
        }
    }

    /**
     * @param skipExisting Whether to skip the [streamItem] if it's already part of the queue
     */
    fun add(vararg streamItem: StreamItem, skipExisting: Boolean = false) = synchronized(queue) {
        for (stream in streamItem) {
            if ((skipExisting && contains(stream)) || stream.title.isNullOrBlank()) continue

            queue.remove(stream)
            queue.add(stream)
        }
        notifyChanged()
    }

    fun addAsNext(streamItem: StreamItem) = synchronized(queue) {
        if (currentStream == streamItem) return
        if (queue.contains(streamItem)) queue.remove(streamItem)
        queue.add((currentIndex() + 1).coerceAtMost(queue.size), streamItem)
        notifyChanged()
    }

    // return the next item, or if repeating enabled and no video left, the first one of the queue
    fun getNext(): String? = synchronized(queue) {
        val nextItem = queue.getOrNull(currentIndex() + 1)
        if (nextItem != null) return nextItem.url?.toID()

        if (repeatMode == Player.REPEAT_MODE_ALL) return queue.firstOrNull()?.url?.toID()

        return null
    }

    // return the previous item, or if repeating enabled and no video left, the last one of the queue
    fun getPrev(): String? = synchronized(queue) {
        val prevItem = queue.getOrNull(currentIndex() - 1)
        if (prevItem != null) return prevItem.url?.toID()

        if (repeatMode == Player.REPEAT_MODE_ALL) return queue.lastOrNull()?.url?.toID()

        return null
    }

    fun adjacentIds(current: String?, reverse: Boolean = false, repeatAll: Boolean = repeatMode == Player.REPEAT_MODE_ALL): List<String> = synchronized(queue) {
        val ordered = queue.mapNotNull { it.url?.toID() }.let { if (reverse) it.asReversed() else it }
        val index = ordered.indexOf(current)
        if (index < 0) return@synchronized emptyList()
        val remaining = ordered.drop(index + 1)
        if (repeatAll) remaining + ordered.take(index)
        else remaining
    }

    fun hasPrev() = getPrev() != null

    fun hasNext() = getNext() != null

    fun updateCurrent(streamItem: StreamItem) = synchronized(queue) {
        currentStream = streamItem

        if (!contains(streamItem)) add(streamItem) else notifyChanged()
    }

    fun isNotEmpty() = queue.isNotEmpty()

    fun isEmpty() = queue.isEmpty()

    fun size() = queue.size

    fun currentIndex(): Int = synchronized(queue) {
        return queue.indexOfFirst {
            it.url?.toID() == currentStream?.url?.toID()
        }.takeIf { it >= 0 } ?: 0
    }

    fun getCurrent(): StreamItem? = currentStream

    fun contains(streamItem: StreamItem) = synchronized(queue) {
        queue.any { it.url?.toID() == streamItem.url?.toID() }
    }

    // only returns a copy of the queue, no write access
    fun getStreams() = synchronized(queue) { queue.toList() }

    fun setStreams(streams: List<StreamItem>, keepCurrent: Boolean = false) = synchronized(queue) {
        clearJobs()
        queue.clear()
        if (!keepCurrent) currentStream = null
        queue.addAll(streams.distinctBy { it.url?.toID() })
        notifyChanged()
    }

    fun remove(index: Int) = synchronized(queue) {
        if (index in queue.indices) {
            queue.removeAt(index)
            notifyChanged()
        }
        return@synchronized
    }

    fun move(from: Int, to: Int) = synchronized(queue) {
        if (from in queue.indices && to in queue.indices) {
            queue.move(from, to)
            notifyChanged()
        }
    }

    /**
     * Adds a list of videos to the current queue while updating the position of the current stream
     * @param isMainList whether the videos are part of the list that initially has been used to
     * start the queue, either from a channel or playlist. If it's false, the current stream won't
     * be touched, since it's an independent list.
     */
    private fun addToQueueAsync(
        streams: List<StreamItem>, currentStreamItem: StreamItem? = null, isMainList: Boolean = true
    ) = synchronized(queue) {
        if (!isMainList) {
            add(*streams.toTypedArray(), skipExisting = true)
            return
        }
        val currentStream = currentStreamItem ?: this.currentStream
        // if the stream already got added to the queue earlier, although it's not yet
        // been found in the playlist, remove it and re-add it later
        var reAddStream = true
        if (currentStream != null && streams.any { it.url?.toID() == currentStream.url?.toID() }) {
            queue.removeAll { it.url?.toID() == currentStream.url?.toID() }
            reAddStream = false
        }
        // add all new stream items to the queue
        add(*streams.toTypedArray())

        if (currentStream != null && reAddStream) {
            // re-add the stream to the end of the queue
            updateCurrent(currentStream)
        }
    }

    fun appendPlaylistPages(playlistId: String, nextPage: String) = runCatchingIO {
        fetchMoreFromPlaylist(playlistId, nextPage, isMainList = false)
    }.let { queueJobs.add(it) }

    private suspend fun fetchMoreFromPlaylist(
        playlistId: String,
        nextPage: String?,
        isMainList: Boolean
    ) {
        var playlistNextPage = nextPage
        val loadedPages = mutableSetOf<String>()
        while (playlistNextPage != null && loadedPages.add(playlistNextPage)) {
            MediaServiceRepository.instance.getPlaylistNextPage(playlistId, playlistNextPage).run {
                addToQueueAsync(relatedStreams, isMainList = isMainList)
                playlistNextPage = this.nextpage
            }
        }
    }

    fun insertPlaylist(playlistId: String, playlistType: PlaylistType, newCurrentStream: StreamItem?) = runCatchingIO {
        val playlist = PlaylistsHelper.getPlaylist(playlistId, playlistType)
        val isMainList = newCurrentStream != null
        addToQueueAsync(playlist.relatedStreams, newCurrentStream, isMainList)
        if (playlist.nextpage == null) return@runCatchingIO
        fetchMoreFromPlaylist(playlistId, playlist.nextpage, isMainList)
    }.let { queueJobs.add(it) }

    private suspend fun fetchMoreFromChannel(channelId: String, nextPage: String?) {
        var channelNextPage = nextPage
        var pageIndex = 1
        while (channelNextPage != null && pageIndex < 10) {
            MediaServiceRepository.instance.getChannelNextPage(channelId, channelNextPage).run {
                addToQueueAsync(relatedStreams)
                channelNextPage = this.nextpage
                pageIndex++
            }
        }
    }

    private fun insertChannel(channelId: String, newCurrentStream: StreamItem) = runCatchingIO {
        val channel = MediaServiceRepository.instance.getChannel(ChannelIdentifier.Id(channelId))
        addToQueueAsync(channel.relatedStreams, newCurrentStream)
        if (channel.nextpage == null) return@runCatchingIO
        fetchMoreFromChannel(channelId, channel.nextpage)
    }.let { queueJobs.add(it) }

    fun insertByVideoId(videoId: String) = runCatchingIO {
        val streams = MediaServiceRepository.instance.getStreams(videoId.toID())
        add(streams.toStreamItem(videoId))
    }

    fun updateQueue(
        streamItem: StreamItem,
        playlistId: String?,
        channelId: String?,
    ) {
        updateCurrent(streamItem)

        if (playlistId != null) {
            val playlistType = PlaylistsHelper.getPlaylistType(playlistId)
            insertPlaylist(playlistId, playlistType, streamItem)
        } else if (channelId != null) {
            insertChannel(channelId, streamItem)
        }
    }
}
