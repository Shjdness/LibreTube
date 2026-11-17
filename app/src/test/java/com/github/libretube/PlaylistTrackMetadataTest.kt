package com.github.libretube

import com.github.libretube.api.obj.StreamItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaylistTrackMetadataTest {
    @Test
    fun playlistAuthorsDoNotIncludeViewCounts() {
        assertNull(StreamItem(uploaderName = "3.4m views").playlistAuthorName())
        assertNull(StreamItem(uploaderName = "1.3 万次观看").playlistAuthorName())
        assertEquals("MIT OpenCourseWare", StreamItem(uploaderName = " MIT OpenCourseWare ").playlistAuthorName())
    }

    @Test
    fun publicationDatesRequireAnExplicitCalendarDate() {
        assertEquals("2009-05-07", StreamItem(uploadedDate = "2009-05-07T00:00:00").publicationDate())
        assertNull(StreamItem(uploadedDate = "2 years ago").publicationDate())
    }
}
