package com.github.libretube

import com.github.libretube.helpers.AudioFileExport
import com.github.libretube.util.AudioFileFormat
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.security.MessageDigest

class AudioFileExportTest {
    @Test fun signaturesDoNotDependOnRestoredFileNames() {
        val wave = ByteArray(44).apply { "RIFF".toByteArray().copyInto(this); "WAVE".toByteArray().copyInto(this, 8) }
        assertEquals("wav", AudioFileFormat.detect(wave)?.extension)
        assertEquals("m4a", AudioFileFormat.detect(byteArrayOf(0,0,0,24) + "ftypM4A ".toByteArray())?.extension)
        assertEquals("mp3", AudioFileFormat.detect("ID3sample".toByteArray())?.extension)
        assertEquals("opus", AudioFileFormat.detect("OggS\u0000OpusHead".toByteArray())?.extension)
        assertEquals("ogg", AudioFileFormat.detect("OggSother".toByteArray())?.extension)
        assertEquals("aac", AudioFileFormat.detect(byteArrayOf(0xff.toByte(), 0xf1.toByte()))?.extension)
        assertNull(AudioFileFormat.detect("QAoffline01".toByteArray()))
        assertNull(AudioFileFormat.detect(byteArrayOf()))
        val ebml = byteArrayOf(0x1a, 0x45, 0xdf.toByte(), 0xa3.toByte())
        assertEquals("webm", AudioFileFormat.detect(ebml + "webm".toByteArray())?.extension)
        assertEquals("mka", AudioFileFormat.detect(ebml + "matroska".toByteArray())?.extension)
        assertNull(AudioFileFormat.detect(ebml))
    }

    @Test fun portableNamesPreserveUnicodeAndRemoveUnsafeCharacters() {
        val name = AudioFileFormat.fileName("作者/频道", "标题:*?<>|\n音频", "QAoffline01", "m4a")
        assertTrue(name.startsWith("作者 频道 - 标题"))
        assertFalse(name.any { it in "/\\:*?<>|\n\r" })
        assertTrue(name.endsWith(".m4a"))
        assertEquals("QAoffline01.wav", AudioFileFormat.fileName("", "...", "QAoffline01", "wav"))
        assertEquals("Audio - CON.wav", AudioFileFormat.fileName("", "CON", "QAoffline01", "wav"))
    }

    @Test fun longNamesAreBoundedInUtf8WithoutSplittingEmoji() {
        val name = AudioFileFormat.fileName("作者".repeat(200), "🎧".repeat(200), "QAoffline01", "webm")
        assertTrue(name.toByteArray(Charsets.UTF_8).size <= 185)
        assertEquals(name, name.toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8))
    }

    private fun source(bytes: ByteArray): AudioFileExport.Source {
        val path = Files.createTempFile("audio-export-test-", ".wav")
        Files.write(path, bytes)
        return AudioFileExport.Source(path, bytes.size.toLong(), Files.getLastModifiedTime(path).toMillis(), "test.wav", AudioFileFormat("wav", "audio/wav"))
    }

    @Test fun copiesOriginalBytesAndComputesContentDigest() = runBlocking {
        val bytes = ByteArray(700_000) { (it % 251).toByte() }
        val source = source(bytes)
        try {
            val output = ByteArrayOutputStream()
            val progress = mutableListOf<Long>()
            val digest = AudioFileExport.copy(source, output) { progress.add(it) }
            assertArrayEquals(bytes, output.toByteArray())
            assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(bytes), digest)
            assertEquals(bytes.size.toLong(), progress.last())
        } finally { Files.delete(source.path) }
    }

    @Test fun rejectsAFileChangedWhileCopying() = runBlocking {
        val source = source(ByteArray(400_000))
        try {
            try {
                AudioFileExport.copy(source, ByteArrayOutputStream()) {
                    Files.write(source.path, byteArrayOf(1), java.nio.file.StandardOpenOption.APPEND)
                }
                fail("Changed source was accepted")
            } catch (_: AudioFileExport.Failure) { }
        } finally { Files.delete(source.path) }
    }

    @Test fun writeFailuresPropagateWithoutAFalseSuccess() = runBlocking {
        val source = source(ByteArray(100_000))
        try {
            val broken = object : OutputStream() { override fun write(value: Int) { throw IOException("No space") } }
            try { AudioFileExport.copy(source, broken) {}; fail("Write failure ignored") }
            catch (_: IOException) { }
        } finally { Files.delete(source.path) }
    }

    @Test fun copyingRespondsToCancellationBetweenChunks() = runBlocking {
        val source = source(ByteArray(4_000_000))
        var written = 0
        try {
            val slow = object : OutputStream() {
                override fun write(value: Int) { error("Unexpected single-byte copy") }
                override fun write(bytes: ByteArray, offset: Int, count: Int) { Thread.sleep(15); written += count }
            }
            val task = launch(Dispatchers.Default) { AudioFileExport.copy(source, slow) {} }
            delay(50)
            task.cancelAndJoin()
            assertTrue(task.isCancelled)
            assertTrue(written < source.size)
        } finally { Files.delete(source.path) }
    }
}
