package com.github.libretube.repo

import android.util.Log
import com.github.libretube.db.obj.DownloadItem
import com.github.libretube.extensions.TAG
import com.github.libretube.helpers.DownloadHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.BufferedSink
import okio.IOException
import java.time.Duration
import kotlin.io.path.fileSize
import kotlin.math.min

/**
 * Download from RAW HTTP stream.
 */
class RawByteStreamDownloadProvider(val url: HttpUrl) : DownloadProvider {
    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(Duration.ofMillis(DownloadHelper.DEFAULT_TIMEOUT.toLong()))
            .readTimeout(Duration.ofMillis(DownloadHelper.DEFAULT_TIMEOUT.toLong()))
            .retryOnConnectionFailure(true)
            .build()
    }

    override suspend fun downloadNextChunk(
        item: DownloadItem,
        sink: BufferedSink,
    ): DownloadProgressResult {
        return withContext(Dispatchers.IO) {
            val start = item.path.fileSize()
            val end = min(if (item.downloadSize > 0) item.downloadSize - 1 else Long.MAX_VALUE,
                start + BYTES_PER_REQUEST - 1)
            if (end < start) return@withContext DownloadProgressResult.DownloadComplete
            val request = Request.Builder().url(url).header("Range", "bytes=$start-$end").build()
            try {
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful || (start > 0 && response.code != 206)) {
                        return@use DownloadProgressResult.Failed
                    }
                    val contentRange = response.header("Content-Range")
                    if (response.code == 206 && contentRange?.substringAfter("bytes ")?.substringBefore("-")?.toLongOrNull() != start) {
                        return@use DownloadProgressResult.Failed
                    }
                    val total = contentRange?.substringAfterLast("/")?.toLongOrNull()
                        ?: item.downloadSize.takeIf { it > 0 }
                    val bytes = sink.writeAll(response.body.source())
                    sink.flush()
                    when {
                        bytes <= 0L -> DownloadProgressResult.Failed
                        response.code == 200 || (total != null && start + bytes >= total) -> DownloadProgressResult.DownloadComplete
                        else -> DownloadProgressResult.Progressed(bytes)
                    }
                }
            } catch (e: IOException) {
                Log.w(TAG(), "Audio download request failed", e)
                DownloadProgressResult.Failed
            }
        }
    }

    companion object {
        // maximum working tested chunk size is 3MB, the 512MB value here is from NewPipe
        private const val BYTES_PER_REQUEST = 512 * 1024L
    }
}