package com.github.libretube

import com.github.libretube.db.obj.DownloadItem
import com.github.libretube.enums.FileType
import com.github.libretube.repo.DownloadProgressResult
import com.github.libretube.repo.RawByteStreamDownloadProvider
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okio.buffer
import okio.sink
import org.junit.Assert.*
import org.junit.Test
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.util.concurrent.atomic.AtomicInteger

class RawAudioDownloadTest {
    private val audio = ByteArray(20_000) { (it % 251).toByte() }

    @Test fun interruptedRangeCanRetryWithoutDuplicatingBufferedBytes() = runBlocking {
        val calls = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/audio") { exchange ->
            exchange.responseHeaders.add("Content-Range", "bytes 0-19999/20000")
            exchange.sendResponseHeaders(206, audio.size.toLong())
            runCatching {
                exchange.responseBody.use { it.write(audio, 0, if (calls.getAndIncrement() == 0) 9_000 else audio.size) }
            }
            exchange.close()
        }
        server.start()
        val path = Files.createTempFile("audio-retry-", ".bin")
        try {
            val item = DownloadItem(type = FileType.AUDIO, videoId = "QAoffline01", fileName = "audio.bin",
                path = path, downloadSize = audio.size.toLong())
            val provider = RawByteStreamDownloadProvider("http://127.0.0.1:${server.address.port}/audio".toHttpUrl(),
                OkHttpClient.Builder().retryOnConnectionFailure(false).build())
            path.sink(StandardOpenOption.APPEND).buffer().use { sink ->
                assertSame(DownloadProgressResult.Failed, provider.downloadNextChunk(item, sink))
                assertEquals(0L, Files.size(path))
                assertEquals(0L, sink.buffer.size)
                assertSame(DownloadProgressResult.DownloadComplete, provider.downloadNextChunk(item, sink))
            }
            assertArrayEquals(audio, Files.readAllBytes(path))
            assertTrue(item.isFinished)
        } finally {
            server.stop(0)
            Files.deleteIfExists(path)
        }
    }

    @Test fun ignoredRangeRestartsAFullResponseSafely() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/audio") { exchange ->
            exchange.sendResponseHeaders(200, audio.size.toLong())
            exchange.responseBody.use { it.write(audio) }
        }
        server.start()
        val path = Files.createTempFile("audio-full-", ".bin")
        Files.write(path, audio.copyOf(9_000))
        try {
            val item = DownloadItem(type = FileType.AUDIO, videoId = "QAoffline01", fileName = "audio.bin",
                path = path, downloadSize = audio.size.toLong())
            val provider = RawByteStreamDownloadProvider("http://127.0.0.1:${server.address.port}/audio".toHttpUrl())
            path.sink(StandardOpenOption.APPEND).buffer().use { sink ->
                assertSame(DownloadProgressResult.DownloadComplete, provider.downloadNextChunk(item, sink))
            }
            assertArrayEquals(audio, Files.readAllBytes(path))
        } finally {
            server.stop(0)
            Files.deleteIfExists(path)
        }
    }

    @Test fun oversizedFilesAreNotMarkedCompleted() {
        val path = Files.createTempFile("audio-size-", ".bin")
        try {
            Files.write(path, ByteArray(101))
            val item = DownloadItem(type = FileType.AUDIO, videoId = "QAoffline01", fileName = "audio.bin",
                path = path, downloadSize = 100)
            assertFalse(item.isFinished)
        } finally { Files.deleteIfExists(path) }
    }
}
