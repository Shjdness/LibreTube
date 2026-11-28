package com.github.libretube.helpers

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.content.FileProvider
import com.github.libretube.R
import com.github.libretube.repo.AudioLibraryRepository
import com.github.libretube.util.AudioFileFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID

/** Owns only newly-created export documents and isolated, temporary share copies. */
object AudioFileExport {
    data class Source(val path: Path, val size: Long, val modified: Long, val name: String, val format: AudioFileFormat)
    data class Result(val uri: Uri, val name: String, val mime: String, val verified: Boolean)
    class Failure(val reason: Int, val residue: Boolean = false, cause: Throwable? = null) : IOException(cause)
    private val mutex = Mutex()
    private fun ledger(context: Context) = context.getSharedPreferences("audio_export_pending", Context.MODE_PRIVATE)
    private fun pending(context: Context) = ledger(context).getStringSet("uris", emptySet()).orEmpty().toSet()
    fun isPending(context: Context, uri: Uri) = uri.toString() in pending(context)
    private fun record(context: Context, uri: Uri, add: Boolean) {
        val values = pending(context).toMutableSet().apply { if (add) add(uri.toString()) else remove(uri.toString()) }
        check(ledger(context).edit().putStringSet("uris", values).commit()) { "Could not persist export ownership" }
    }

    suspend fun source(id: String): Source = withContext(Dispatchers.IO) {
        val record = AudioLibraryRepository.localAudio(id) ?: throw Failure(R.string.audio_export_unavailable)
        val audio = record.finishedAudio ?: throw Failure(R.string.audio_export_unavailable)
        val header = audio.path.toFile().inputStream().use { val bytes = ByteArray(64 * 1024); bytes.copyOf(it.read(bytes).coerceAtLeast(0)) }
        val format = AudioFileFormat.detect(header) ?: throw Failure(R.string.audio_export_format_error)
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(audio.path.toString())
            val tracks = (0 until extractor.trackCount).map { extractor.getTrackFormat(it) }
            if (tracks.none { it.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true } ||
                tracks.any { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }) {
                throw Failure(R.string.audio_export_format_error)
            }
            extractor.selectTrack(tracks.indexOfFirst { it.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true })
            if (extractor.readSampleData(java.nio.ByteBuffer.allocate(256 * 1024), 0) <= 0)
                throw Failure(R.string.audio_export_format_error)
        } catch (e: Failure) { throw e }
        catch (e: Exception) { throw Failure(R.string.audio_export_format_error, cause = e) }
        finally { extractor.release() }
        Source(audio.path, audio.downloadSize, Files.getLastModifiedTime(audio.path).toMillis(),
            AudioFileFormat.fileName(record.download.uploader, record.download.title, id, format.extension), format)
    }

    private fun unchanged(source: Source): Boolean = runCatching {
        Files.isRegularFile(source.path) && Files.size(source.path) == source.size &&
            Files.getLastModifiedTime(source.path).toMillis() == source.modified
    }.getOrDefault(false)

    internal suspend fun copy(source: Source, output: java.io.OutputStream, progress: (Long) -> Unit): ByteArray {
        if (!unchanged(source)) throw Failure(R.string.audio_export_changed)
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        var reported = -1L
        source.path.toFile().inputStream().use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                output.write(buffer, 0, count)
                digest.update(buffer, 0, count)
                total += count
                if (total > source.size) throw Failure(R.string.audio_export_changed)
                val percent = total * 100 / source.size
                if (percent != reported) { reported = percent; progress(total) }
            }
        }
        output.flush()
        if (total != source.size || !unchanged(source)) throw Failure(R.string.audio_export_changed)
        return digest.digest()
    }

    private suspend fun verify(context: Context, uri: Uri, source: Source, expected: ByteArray): Boolean {
        val input = try { context.contentResolver.openInputStream(uri) } catch (_: Exception) { null } ?: return false
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        try {
            input.use {
                val buffer = ByteArray(128 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = it.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > source.size) throw Failure(R.string.audio_export_verify_error)
                    digest.update(buffer, 0, count)
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Failure) { throw e }
        catch (e: IOException) { throw Failure(R.string.audio_export_verify_error, cause = e) }
        if (total != source.size || !digest.digest().contentEquals(expected)) throw Failure(R.string.audio_export_verify_error)
        return true
    }

    private fun release(context: Context, uri: Uri) {
        runCatching { context.contentResolver.releasePersistableUriPermission(uri,
            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
    }
    private fun delete(context: Context, uri: Uri): Boolean = runCatching {
        if (DocumentsContract.isDocumentUri(context, uri)) DocumentsContract.deleteDocument(context.contentResolver, uri)
        else context.contentResolver.delete(uri, null, null) > 0
    }.getOrDefault(false)

    private fun displayName(context: Context, uri: Uri, fallback: String): String = runCatching {
        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }.getOrNull()?.takeIf(String::isNotBlank) ?: fallback

    private fun emptyDestination(context: Context, uri: Uri): Boolean {
        val size = runCatching {
            context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)?.use {
                if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null
            }
        }.getOrNull()
        if (size != null && size >= 0L) return size == 0L
        return runCatching { context.contentResolver.openInputStream(uri)?.use { it.read() == -1 } == true }.getOrDefault(false)
    }

    suspend fun save(context: Context, source: Source, uri: Uri, progress: (Long) -> Unit): Result = withContext(Dispatchers.IO) {
        mutex.withLock {
            var complete = false
            var registered = false
            try {
                if (!emptyDestination(context, uri)) throw Failure(R.string.audio_export_existing)
                // Only URIs returned by ACTION_CREATE_DOCUMENT enter this ownership ledger.
                record(context, uri, true)
                registered = true
                val hash = requireNotNull(context.contentResolver.openOutputStream(uri, "wt")).use { copy(source, it, progress) }
                progress(-1)
                val verified = verify(context, uri, source, hash)
                currentCoroutineContext().ensureActive()
                record(context, uri, false)
                complete = true
                Result(uri, displayName(context, uri, source.name), source.format.mime, verified)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) {
                val cleaned = !registered || delete(context, uri)
                if (cleaned && registered) runCatching { record(context, uri, false) }
                val causes = generateSequence<Throwable>(e) { it.cause }.take(10).toList()
                val reason = (e as? Failure)?.reason ?: when {
                    causes.any { it is SecurityException } -> R.string.audio_export_permission
                    causes.any { it.message?.contains("ENOSPC", ignoreCase = true) == true ||
                        it.message?.contains("No space left", ignoreCase = true) == true } -> R.string.audio_export_no_space
                    else -> R.string.audio_export_write_error
                }
                throw Failure(reason, !cleaned, e)
            } finally {
                withContext(NonCancellable) {
                    if (!complete && pending(context).contains(uri.toString()) && delete(context, uri))
                        runCatching { record(context, uri, false) }
                    if (!isPending(context, uri)) release(context, uri)
                }
            }
        }
    }

    suspend fun share(context: Context, source: Source, progress: (Long) -> Unit): Result = withContext(Dispatchers.IO) {
        val root = File(context.cacheDir, "audio-share")
        val directory = File(root, UUID.randomUUID().toString()).apply { check(mkdirs()) }
        val file = File(directory, source.name)
        var complete = false
        try {
            val hash = file.outputStream().use { output -> copy(source, output, progress) }
            currentCoroutineContext().ensureActive()
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            progress(-1)
            check(verify(context, uri, source, hash))
            complete = true
            Result(uri, source.name, source.format.mime, true)
        } finally {
            if (!complete) { file.delete(); directory.delete() }
        }
    }

    suspend fun cleanup(context: Context): Int = withContext(Dispatchers.IO) {
        mutex.withLock {
            val uris = pending(context)
            for (value in uris) {
                val uri = Uri.parse(value)
                if (delete(context, uri)) record(context, uri, false)
                if (!isPending(context, uri)) release(context, uri)
            }
            val root = File(context.cacheDir, "audio-share")
            // Never follow directories or links supplied by a document provider.
            root.listFiles().orEmpty().filter { it.isDirectory && !Files.isSymbolicLink(it.toPath()) &&
                System.currentTimeMillis() - it.lastModified() > 24 * 60 * 60 * 1000L }.forEach { folder ->
                folder.listFiles().orEmpty().filter { it.isFile && !Files.isSymbolicLink(it.toPath()) }.forEach { it.delete() }
                folder.delete()
            }
            uris.size
        }
    }
}
