package com.github.libretube.util

import com.github.libretube.api.JsonHelper
import com.github.libretube.api.obj.StreamItem
import com.github.libretube.db.obj.AudioTimeMark
import com.github.libretube.obj.AudioTrack
import org.junit.Assert.*
import org.junit.Test

class AudioModelTest {
    @Test fun networkMetadataRoundTripsThroughStableAudioModel() {
        val stream = StreamItem(url = "https://www.youtube.com/watch?v=QAoffline01", title = "Long audio title",
            uploaderName = "Author", uploaderUrl = "https://www.youtube.com/channel/UCauthor",
            views = 123, duration = 90, uploadedDate = "2026-11-25", uploaded = 1234, shortDescription = "Description")
        val track = AudioTrack.fromStream(stream)!!
        assertEquals("QAoffline01", track.id)
        assertEquals("UCauthor", track.authorId)
        val restored = track.toStreamItem()
        assertEquals(stream.title, restored.title)
        assertEquals(stream.views, restored.views)
        assertEquals(stream.duration, restored.duration)
        assertEquals(stream.uploadedDate, restored.uploadedDate)
        assertEquals(track, AudioTrack.fromStream(restored))
        assertNull(AudioTrack.fromStream(StreamItem(title = "Missing identifier")))
    }

    @Test fun oldPlaybackSnapshotDefaultsNewPauseState() {
        val legacy = """{"trackId":"QAoffline01","positionMs":45000,"localOnly":true,"queueName":"Saved","tracks":[{"url":"QAoffline01","title":"First"}]}"""
        val state = JsonHelper.json.decodeFromString(PlaybackStateStore.State.serializer(), legacy)
        assertEquals(0L, state.pausedAt)
        assertEquals(45000L, state.positionMs)
        assertEquals("First", state.tracks.single().title)
    }

    @Test fun timestampLabelsAndQueueGenerationsSurviveSerialization() {
        val mark = AudioTimeMark("QAoffline01", 12345, "Chapter one", "Title", "Author")
        val json = JsonHelper.json.encodeToString(AudioTimeMark.serializer(), mark)
        assertEquals(mark, JsonHelper.json.decodeFromString(AudioTimeMark.serializer(), json))
        val snapshot = PlaybackStateStore.QueueSnapshot("revision-12", listOf(AudioTrack("QAoffline01", "Title")))
        val saved = JsonHelper.json.encodeToString(PlaybackStateStore.QueueSnapshot.serializer(), snapshot)
        assertEquals(snapshot, JsonHelper.json.decodeFromString(PlaybackStateStore.QueueSnapshot.serializer(), saved))
    }

    @Test fun movingCurrentSelectionDoesNotRewriteQueueContent() {
        val first = StreamItem(url = "QAoffline01", title = "First")
        val second = StreamItem(url = "QAoffline02", title = "Second")
        PlayingQueue.setStreams(listOf(first, second))
        val revision = PlayingQueue.contentRevision.value
        PlayingQueue.updateCurrent(second)
        assertEquals(revision, PlayingQueue.contentRevision.value)
        PlayingQueue.move(1, 0)
        assertTrue(PlayingQueue.contentRevision.value > revision)
        PlayingQueue.clear()
    }
}
