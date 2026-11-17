package com.github.libretube.helpers

import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import com.github.libretube.R
import androidx.core.content.ContextCompat
import com.github.libretube.constants.IntentData
import com.github.libretube.db.DatabaseHolder
import com.github.libretube.db.obj.DownloadItem
import com.github.libretube.db.obj.DownloadWithItems
import com.github.libretube.enums.PlaylistType
import com.github.libretube.parcelable.DownloadData
import com.github.libretube.services.DownloadService
import com.github.libretube.services.PlaylistDownloadEnqueueService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.div

object DownloadHelper {
    const val AUDIO_DIR = "audio"
    const val THUMBNAIL_DIR = "thumbnail"
    const val DOWNLOAD_CHUNK_SIZE = 8L * 1024
    const val DEFAULT_TIMEOUT = 15 * 1000
    const val MAX_CONCURRENT_DOWNLOADS = 6

    fun getDownloadDir(context: Context, path: String): Path {
        val storageDir =
            try {
                context.getExternalFilesDir(null)!!
            } catch (e: Exception) {
                context.filesDir
            }
        return (storageDir.toPath() / path).createDirectories()
    }

    fun startDownloadService(context: Context, downloadData: DownloadData? = null) {
        val intent = Intent(context, DownloadService::class.java)
            .putExtra(IntentData.downloadData, downloadData)

        requestForegroundDownload(context, intent)
    }

    fun DownloadItem.getNotificationId(): Int {
        return Int.MAX_VALUE - id
    }

    fun downloadAudio(context: Context, videoId: String) {
        startDownloadService(context, DownloadData(videoId))
    }

    fun downloadAudioBatch(context: Context, ids: List<String>) {
        val videoIds = ids.distinct()
        if (videoIds.isEmpty()) return
        val intent = Intent(context, DownloadService::class.java)
            .setAction(DownloadService.ACTION_ENQUEUE_AUDIO_BATCH)
            .putStringArrayListExtra(DownloadService.EXTRA_AUDIO_IDS, ArrayList(videoIds))
        requestForegroundDownload(context, intent)
    }

    fun requestForegroundDownload(context: Context, intent: Intent) {
        try {
            ContextCompat.startForegroundService(context, intent)
        } catch (e: IllegalStateException) {
            Log.w("DownloadHelper", "Android did not allow the download service to start", e)
            ContextCompat.getMainExecutor(context).execute {
                Toast.makeText(context, R.string.download_start_unavailable, Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun downloadPlaylistAudio(context: Context, playlistId: String, playlistName: String, playlistType: PlaylistType) {
        val intent = Intent(context, PlaylistDownloadEnqueueService::class.java)
            .putExtra(IntentData.playlistId, playlistId)
            .putExtra(IntentData.playlistName, playlistName)
            .putExtra(IntentData.playlistType, playlistType)
        requestForegroundDownload(context, intent)
    }

    suspend fun getDownloadedAudioQueue(videoIds: List<String>): List<DownloadWithItems> =
        withContext(Dispatchers.IO) {
            val downloads = DatabaseHolder.Database.downloadDao().getAll()
                .filter { it.finishedAudio != null }
                .associateBy { it.download.videoId }
            videoIds.mapNotNull(downloads::get)
        }

    suspend fun deleteDownloadIncludingFiles(downloadWithItems: DownloadWithItems) {
        val download = downloadWithItems.download
        val items = downloadWithItems.downloadItems

        items.forEach {
            it.path.deleteIfExists()
        }
        runCatching {
            download.thumbnailPath?.deleteIfExists()
        }

        withContext(Dispatchers.IO) {
            DatabaseHolder.Database.downloadDao().deleteDownload(download)
        }
    }
}
