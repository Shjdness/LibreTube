package com.github.libretube.util

import android.util.Log
import android.util.LruCache
import com.github.libretube.api.MediaServiceRepository
import com.github.libretube.api.obj.DeArrowContent
import com.github.libretube.constants.PreferenceKeys
import com.github.libretube.helpers.PreferenceHelper

object DeArrowUtil {
    private val cache = LruCache<String, DeArrowContent>(256)

    private fun extractTitleAndThumbnail(content: DeArrowContent): Pair<String?, String?> {
        val title = content.titles.firstOrNull { it.votes >= 0 || it.locked }?.title
        val thumbnail = content.thumbnails.firstOrNull {
            it.thumbnail != null && !it.original && (it.votes >= 0 || it.locked)
        }?.thumbnail

        return title to thumbnail
    }


    private suspend fun fetchDeArrowContent(videoId: String): DeArrowContent? {
        // prefer cached response, if available
        cache.get(videoId)?.let { return it }

        return try {
            MediaServiceRepository.instance.getDeArrowContent(videoId)
                .also { cache.put(videoId, it) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(this::class.java.name, "Failed to fetch DeArrow content: ${e.message}")
            null
        }
    }

    /**
     * If DeArrow is enabled, fetch the alternative title and thumbnail
     */
    suspend fun deArrowVideoId(videoId: String): Pair<String?, String?>? {
        if (!PreferenceHelper.getBoolean(PreferenceKeys.DEARROW, false)) return null

        val data = fetchDeArrowContent(videoId) ?: return null
        return extractTitleAndThumbnail(data)
    }
}