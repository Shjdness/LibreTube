package com.github.libretube.ui.fragments

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class DownloadSortingOrderTest {
    @Test
    fun firstAccessToSortOptionCanInitializeDownloadPage() {
        val oldest = DownloadSortingOrder.Oldest
        val options = DownloadSortingOrder.entries

        assertEquals(6, options.size)
        assertSame(oldest, options.first())
        assertSame(DownloadSortingOrder.Newest, options[1])
    }
}
