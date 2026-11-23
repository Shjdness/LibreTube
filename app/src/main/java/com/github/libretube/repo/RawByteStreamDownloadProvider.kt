package com.github.libretube.repo

import com.github.libretube.db.obj.DownloadItem
import com.github.libretube.helpers.DownloadHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer
import okio.BufferedSink
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.time.Duration
import kotlin.io.path.fileSize
import kotlin.math.min

/** Range responses are committed only after the entire chunk has been validated. */
class RawByteStreamDownloadProvider(
    val url: HttpUrl,
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(Duration.ofMillis(DownloadHelper.DEFAULT_TIMEOUT.toLong()))
        .readTimeout(Duration.ofMillis(DownloadHelper.DEFAULT_TIMEOUT.toLong()))
        .retryOnConnectionFailure(true)
        .build(),
) : DownloadProvider {
    override suspend fun downloadNextChunk(item: DownloadItem, sink: BufferedSink): DownloadProgressResult =
        withContext(Dispatchers.IO) {
            sink.flush()
            var start = item.path.fileSize()
            if (item.downloadSize > 0 && start > item.downloadSize) {
                truncate(item, sink, 0)
                start = 0
            }
            if (item.downloadSize > 0 && start == item.downloadSize) {
                return@withContext DownloadProgressResult.DownloadComplete
            }
            val end = min(item.downloadSize.takeIf { it > 0 }?.minus(1) ?: Long.MAX_VALUE,
                start + BYTES_PER_REQUEST - 1)
            val request = Request.Builder().url(url)
                .header("Range", "bytes=$start-$end")
                .header("Accept-Encoding", "identity").build()
            var rollback = start
            try {
                httpClient.newCall(request).execute().use { response ->
                    when (response.code) {
                        206 -> {
                            val range = CONTENT_RANGE.matchEntire(response.header("Content-Range").orEmpty())
                                ?: throw IOException("Invalid Content-Range")
                            val (first, last, total) = range.destructured
                            val firstByte = first.toLongOrNull() ?: throw IOException("Invalid range start")
                            val lastByte = last.toLongOrNull() ?: throw IOException("Invalid range end")
                            val totalBytes = total.toLongOrNull() ?: throw IOException("Invalid content size")
                            if (firstByte != start || lastByte < firstByte || lastByte > end ||
                                lastByte >= totalBytes || (item.downloadSize > 0 && item.downloadSize != totalBytes)
                            ) throw IOException("Audio range or content length changed")
                            val expected = lastByte - firstByte + 1
                            val chunk = Buffer()
                            val source = response.body.source()
                            while (true) {
                                val count = source.read(chunk, min(64 * 1024L, expected - chunk.size + 1))
                                if (count == -1L) break
                                if (chunk.size > expected) throw IOException("Audio chunk exceeds its range")
                            }
                            if (chunk.size != expected) throw IOException("Incomplete audio chunk")
                            sink.writeAll(chunk)
                            sink.flush()
                            if (start + expected == totalBytes) DownloadProgressResult.DownloadComplete
                            else DownloadProgressResult.Progressed(expected)
                        }
                        200 -> {
                            // A server may ignore Range. Restart its full response at byte zero.
                            rollback = 0
                            truncate(item, sink, 0)
                            val declared = response.body.contentLength().takeIf { it >= 0 }
                            val expected = item.downloadSize.takeIf { it > 0 } ?: declared
                            if (declared != null && expected != null && declared != expected) {
                                throw IOException("Audio content length changed")
                            }
                            val source = response.body.source()
                            var written = 0L
                            val buffer = Buffer()
                            while (true) {
                                val count = source.read(buffer, 64 * 1024L)
                                if (count == -1L) break
                                written += count
                                if (expected != null && written > expected) throw IOException("Audio exceeds its content length")
                                sink.writeAll(buffer)
                            }
                            sink.flush()
                            if (written <= 0 || (expected != null && written != expected)) {
                                throw IOException("Incomplete audio response")
                            }
                            DownloadProgressResult.DownloadComplete
                        }
                        else -> DownloadProgressResult.Failed
                    }
                }
            } catch (e: IOException) {
                // Discard buffered bytes and roll back any partial disk write before retrying.
                truncate(item, sink, rollback)
                DownloadProgressResult.Failed
            }
        }

    private fun truncate(item: DownloadItem, sink: BufferedSink, size: Long) {
        sink.buffer.clear()
        FileChannel.open(item.path, StandardOpenOption.WRITE).use { it.truncate(size) }
    }

    companion object {
        private const val BYTES_PER_REQUEST = 512 * 1024L
        private val CONTENT_RANGE = Regex("bytes ([0-9]+)-([0-9]+)/([0-9]+)")
    }
}
