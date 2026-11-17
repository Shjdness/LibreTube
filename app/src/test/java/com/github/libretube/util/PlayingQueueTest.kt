package com.github.libretube.util

import com.github.libretube.api.obj.StreamItem
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayingQueueTest {
    @After
    fun resetQueue() { PlayingQueue.clear() }

    @Test
    fun selectionKeepsOrderAndWrapsOnlyWhenRepeating() {
        PlayingQueue.setStreams(listOf("QAoffline01", "QAoffline02", "QAoffline03").map { StreamItem(url = it, title = it) })
        assertEquals(listOf("QAoffline03"), PlayingQueue.adjacentIds("QAoffline02", repeatAll = false))
        assertEquals(listOf("QAoffline03", "QAoffline01"), PlayingQueue.adjacentIds("QAoffline02", repeatAll = true))
        assertEquals(listOf("QAoffline01"), PlayingQueue.adjacentIds("QAoffline02", reverse = true, repeatAll = false))
        assertEquals(emptyList<String>(), PlayingQueue.adjacentIds("QAoffline03", repeatAll = false))
    }

    @Test
    fun unknownCurrentDoesNotJumpToOppositeEdge() {
        PlayingQueue.setStreams(listOf(StreamItem(url = "QAoffline01", title = "First")))
        assertEquals(emptyList<String>(), PlayingQueue.adjacentIds("QAmissing01", reverse = true, repeatAll = true))
        assertEquals(emptyList<String>(), PlayingQueue.adjacentIds(null, repeatAll = false))
    }

    @Test
    fun reorderingKeepsThePlayingTrackSelected() {
        val first = StreamItem(url = "QAoffline01", title = "First")
        val second = StreamItem(url = "QAoffline02", title = "Second")
        PlayingQueue.setStreams(listOf(first, second))
        PlayingQueue.updateCurrent(first)
        PlayingQueue.setStreams(listOf(second, first), keepCurrent = true)
        assertEquals(first, PlayingQueue.getCurrent())
        assertEquals(listOf("QAoffline02"), PlayingQueue.adjacentIds(first.url, reverse = true, repeatAll = false))
    }
}
