package com.github.libretube.services

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent.FLAG_CANCEL_CURRENT
import android.app.PendingIntent.FLAG_UPDATE_CURRENT
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationCompat.Builder
import androidx.core.app.PendingIntentCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.getSystemService
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.github.libretube.LibreTubeApp.Companion.DOWNLOAD_CHANNEL_NAME
import com.github.libretube.R
import com.github.libretube.api.MediaServiceRepository
import com.github.libretube.api.obj.PipedStream
import com.github.libretube.api.obj.Streams
import com.github.libretube.constants.IntentData
import com.github.libretube.db.DatabaseHolder.Database
import com.github.libretube.db.obj.Download
import com.github.libretube.db.obj.DownloadChapter
import com.github.libretube.db.obj.DownloadItem
import com.github.libretube.db.obj.PendingAudioDownload
import com.github.libretube.enums.FileType
import com.github.libretube.enums.NotificationId
import com.github.libretube.enums.PlaylistType
import com.github.libretube.extensions.TAG
import com.github.libretube.extensions.formatAsFileSize
import com.github.libretube.extensions.getWhileDigit
import com.github.libretube.extensions.parcelableExtra
import com.github.libretube.extensions.toLocalDate
import com.github.libretube.extensions.toastFromMainDispatcher
import com.github.libretube.helpers.DownloadHelper
import com.github.libretube.helpers.DownloadHelper.getNotificationId
import com.github.libretube.helpers.ImageHelper
import com.github.libretube.helpers.NetworkHelper
import com.github.libretube.obj.DownloadStatus
import com.github.libretube.parcelable.DownloadData
import com.github.libretube.receivers.NotificationReceiver
import com.github.libretube.receivers.NotificationReceiver.Companion.ACTION_DOWNLOAD_PAUSE
import com.github.libretube.receivers.NotificationReceiver.Companion.ACTION_DOWNLOAD_RESUME
import com.github.libretube.receivers.NotificationReceiver.Companion.ACTION_DOWNLOAD_STOP
import com.github.libretube.repo.DownloadProgressResult
import com.github.libretube.repo.DownloadProvider
import com.github.libretube.repo.RawByteStreamDownloadProvider
import com.github.libretube.repo.SabrDownloadProvider
import com.github.libretube.ui.activities.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okio.buffer
import okio.sink
import java.net.HttpURLConnection
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.Executors
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.NonCancellable
import kotlin.coroutines.cancellation.CancellationException
import kotlin.io.path.createFile
import kotlin.io.path.deleteIfExists
import kotlin.io.path.div
import kotlin.io.path.fileSize
import kotlin.io.path.exists

/**
 * Download service with custom implementation of downloading using [HttpURLConnection].
 */
class DownloadService : LifecycleService() {
    private val binder = LocalBinder()
    private val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val workerJob = SupervisorJob().apply { invokeOnCompletion { dispatcher.close() } }
    private val workerScope = CoroutineScope(dispatcher + workerJob)

    private lateinit var notificationManager: NotificationManager
    private lateinit var summaryNotification: Notification

    private val runningIds = mutableSetOf<Int>()
    private val activeIds = ConcurrentHashMap.newKeySet<Int>()
    private val startRequests = AtomicInteger()
    private val latestStartId = AtomicInteger()
    private val streamLookups = Semaphore(3)
    private val waitingIds = linkedSetOf<Int>()
    private val _downloadFlow = MutableSharedFlow<Pair<Int, DownloadStatus>>()
    val downloadFlow: SharedFlow<Pair<Int, DownloadStatus>> = _downloadFlow

    /**
     * Cache that contains all the already-loaded video info.
     */
    private val pendingAudio = mutableSetOf<String>()
    private val cachedStreamsInfo: MutableMap<String, Streams> = mutableMapOf()

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService()!!
        summaryNotification = Builder(this, DOWNLOAD_CHANNEL_NAME)
            .setSmallIcon(R.drawable.ic_launcher_lockscreen)
            .setContentTitle(getString(R.string.downloading))
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setGroup(DOWNLOAD_NOTIFICATION_GROUP)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setGroupSummary(true)
            .build()
        getSystemService<ConnectivityManager>()?.registerDefaultNetworkCallback(networkCallback)
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            if (NetworkHelper.isNetworkMetered(this@DownloadService)) activeIds.toList().forEach(::pause)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        // A bound instance can receive another start after its previous queue went idle.
        // Acknowledge each start before dispatching database or network work.
        try {
            notifyForeground()
        } catch (e: RuntimeException) {
            Log.w(TAG(), "Could not start foreground audio downloads", e)
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        if (!IS_DOWNLOAD_RUNNING) {
            IS_DOWNLOAD_RUNNING = true
            sendBroadcast(Intent(ACTION_SERVICE_STARTED).setPackage(packageName))
        }
        val downloadId = intent?.getIntExtra("id", -1) ?: -1
        startRequests.incrementAndGet()
        latestStartId.set(startId)
        workerScope.launch {
            try {
                when (intent?.action) {
                    ACTION_DOWNLOAD_RESUME -> queueDownload(downloadId)
                    ACTION_DOWNLOAD_PAUSE -> pauseDownload(downloadId)
                    ACTION_DOWNLOAD_STOP -> stopDownload(downloadId)
                    ACTION_RESUME_ALL -> resumeAll()
                    ACTION_ENQUEUE_AUDIO_BATCH -> submitAudio(intent.getStringArrayListExtra(EXTRA_AUDIO_IDS).orEmpty())
                    else -> intent?.parcelableExtra<DownloadData>(IntentData.downloadData)?.let {
                        submitAudio(listOf(it.videoId))
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                reportDownloadFailure(e, downloadId)
            } finally {
                startRequests.decrementAndGet()
                stopServiceIfDone()
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun submitAudio(ids: List<String>) {
        val videoIds = ids.filter(VIDEO_ID::matches).distinct()
        if (videoIds.isEmpty()) return
        Database.downloadDao().insertPendingAudio(videoIds.map { PendingAudioDownload(it) })
        processPendingAudio(videoIds)
    }

    private suspend fun processPendingAudio(ids: List<String>) {
        for (batch in ids.chunked(12)) {
            coroutineScope {
                batch.forEach { id ->
                    launch {
                        try {
                            enqueueAudio(id)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Database.downloadDao().failPendingAudio(id, e.localizedMessage.orEmpty().take(300))
                            reportDownloadFailure(e, -1)
                        }
                    }
                }
            }
        }
    }

    private suspend fun enqueueAudio(videoId: String) {
        if (!pendingAudio.add(videoId)) return
        try {
            Database.downloadDao().failPendingAudio(videoId, "")
            val previous = Database.downloadDao().findById(videoId)?.downloadItems.orEmpty()
                .filter { it.type == FileType.AUDIO }
            if (previous.any { it.isFinished }) {
                Database.downloadDao().removePendingAudio(videoId)
                return
            }
            val existing = previous.firstOrNull { it.path.exists() }
            if (existing != null) {
                Database.downloadDao().removePendingAudio(videoId)
                if (!isDownloading(existing.id)) queueDownload(existing.id)
                return
            }

            val streams = loadStreamsInfo(videoId) ?: return
            if (!Database.downloadDao().hasPendingAudio(videoId)) return
            Database.downloadDao().namePendingAudio(videoId, streams.title)
            val audio = streams.audioStreams.maxByOrNull(::audioBitrate)
            if (audio == null || streams.isLive) {
                Database.downloadDao().failPendingAudio(videoId, getString(R.string.audio_stream_unavailable))
                toastFromMainDispatcher(R.string.unknown_error)
                return
            }
            storeVideoMetadata(videoId, streams)
            if (!Database.downloadDao().hasPendingAudio(videoId)) return
            previous.forEach { Database.downloadDao().deleteDownloadItemById(it.id) }
            start(audio.toDownloadItem(FileType.AUDIO, videoId))
            Database.downloadDao().removePendingAudio(videoId)
        } finally {
            pendingAudio.remove(videoId)
        }
    }

    private suspend fun loadStreamsInfo(videoId: String): Streams? {
        if (cachedStreamsInfo.contains(videoId))
            return cachedStreamsInfo[videoId]

        val streams = try {
            streamLookups.withPermit {
                withContext(Dispatchers.IO) { MediaServiceRepository.instance.getStreams(videoId, includeDislikes = false) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG(), e.stackTraceToString())
            Database.downloadDao().failPendingAudio(videoId, e.localizedMessage.orEmpty().take(300))
            toastFromMainDispatcher(e.localizedMessage.orEmpty())
            return null
        }

        cachedStreamsInfo[videoId] = streams
        return streams
    }

    private suspend fun storeVideoMetadata(videoId: String, streams: Streams) {
        val thumbnailTargetPath = getDownloadPath(DownloadHelper.THUMBNAIL_DIR, videoId)

        val download = Download(
            videoId,
            streams.title,
            streams.description,
            streams.uploader,
            streams.duration,
            streams.uploadTimestamp?.toLocalDate(),
            thumbnailTargetPath,
            streams.uploaderUrl,
            streams.views,
            streams.likes,
            streams.dislikes,
        )
        if (Database.downloadDao().insertDownload(download) == -1L) return

        for (chapter in streams.chapters) {
            val downloadChapter = DownloadChapter(
                videoId = videoId,
                name = chapter.title,
                start = chapter.start,
                thumbnailUrl = chapter.image
            )
            Database.downloadDao().insertDownloadChapter(downloadChapter)
        }

        // asynchronously load the remaining metadata
        // this allows the main thread to already start the actual download items (i.e. video/audio)
        // while the thumbnail is loaded in the background
        workerScope.launch(Dispatchers.IO) {
                downloadExtraVideoMetadata(videoId, streams.thumbnailUrl, thumbnailTargetPath)
        }
    }

    /**
     * Download the thumbnail for the given [videoId].
     */
    private suspend fun downloadExtraVideoMetadata(
        videoId: String,
        thumbnailUrl: String,
        thumbnailTargetPath: Path
    ) {
        coroutineScope {
            launch {
                try {
                    ImageHelper.downloadImage(
                        this@DownloadService,
                        thumbnailUrl,
                        thumbnailTargetPath
                    )
                } catch (e: Exception) {
                    Log.e(TAG(), "failed to download image $thumbnailUrl")
                    Log.e(TAG(), e.stackTraceToString())
                }
            }
        }
    }

    /**
     * Download file and emit [DownloadStatus] to the collectors of [downloadFlow]
     * and notification.
     */
    @SuppressLint("UnsafeOptInUsageError")
    private suspend fun selectFormatAndDownloadFile(item: DownloadItem) {
        val streams = loadStreamsInfo(item.videoId) ?: return
        if (item.type != FileType.AUDIO) return
        if (!isDownloading(item.id)) return
        val selectedStream = checkNotNull(selectMatchingStream(streams, item)) {
            "The selected audio track is unavailable"
        }
        val url = selectedStream.url
        val provider = if (url?.startsWith("http") == true) {
            RawByteStreamDownloadProvider(url.toHttpUrl())
        } else {
            // A new SABR session starts with an initialization segment.
            // Restart from byte zero so the file contains exactly one initialization segment.
            if (item.path.fileSize() > 0) {
                item.path.sink(StandardOpenOption.TRUNCATE_EXISTING).close()
                item.currentDownloadPositionMillis = null
                Database.downloadDao().updateDownloadItem(item)
            }
            SabrDownloadProvider(item, streams, selectedStream)
        }
        downloadFile(item, provider)
    }

    /**
     * Starts and progresses until the download is canceled or finished.
     *
     * You should probably not call this directly, call [selectFormatAndDownloadFile].
     */
    private suspend fun downloadFile(
        item: DownloadItem,
        downloadProvider: DownloadProvider,
    ) {
        val notificationBuilder = getNotificationBuilder(item)
        setResumeNotification(notificationBuilder, item)

        val sink = item.path.sink(StandardOpenOption.APPEND).buffer()
        var totalRead = item.path.fileSize()
        var numberOfTries = 0
        try {
            while (isDownloading(item.id)) {
                when (val result = downloadProvider.downloadNextChunk(item, sink)) {
                    DownloadProgressResult.DownloadComplete -> {
                        sink.flush()
                        val actualSize = item.path.fileSize()
                        if (downloadProvider is RawByteStreamDownloadProvider && item.downloadSize > 0 && actualSize != item.downloadSize) {
                            throw java.io.IOException("Completed audio size does not match its content length")
                        }
                        item.downloadSize = actualSize
                        if (item.downloadSize <= 0L) error("Empty audio download")
                        Database.downloadDao().updateDownloadItem(item)
                        setPauseNotification(notificationBuilder, item, true)
                        _downloadFlow.emit(item.id to DownloadStatus.Completed)
                        return
                    }
                    DownloadProgressResult.Failed -> {
                        if (numberOfTries < MAX_SEGMENT_RETRIES) {
                            // try to download segment again after a short delay
                            delay(200)
                            numberOfTries++
                        } else {
                            reportDownloadFailure(java.io.IOException(getString(R.string.download_retry_failed)), item.id)
                            setPauseNotification(notificationBuilder, item, false)
                            pauseDownload(item.id)
                            break
                        }
                    }
                    is DownloadProgressResult.Progressed -> {
                        numberOfTries = 0
                        totalRead += result.bytes
                        _downloadFlow.emit(
                            item.id to DownloadStatus.Progress(
                                result.bytes,
                                totalRead,
                                item.downloadSize
                            )
                        )
                        updateNotification(notificationBuilder, item, totalRead.toInt())
                    }
                }
            }
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { sink.close() }
        }

        if (!item.isFinished) _downloadFlow.emit(item.id to DownloadStatus.Paused)
    }

    private suspend fun reportDownloadFailure(error: Exception, id: Int) {
        if (id >= 0) {
            val dao = Database.downloadDao()
            dao.findDownloadItemById(id)?.let { item ->
                val title = dao.findById(item.videoId)?.download?.title.orEmpty()
                dao.insertPendingAudio(listOf(PendingAudioDownload(item.videoId, title)))
                dao.failPendingAudio(item.videoId, error.localizedMessage.orEmpty().take(300))
            }
        }
        Log.e(TAG(), "Audio download failed", error)
        val message = error.localizedMessage ?: getString(R.string.unknown_error)
        toastFromMainDispatcher(getString(R.string.download) + ": " + message)
        if (id >= 0) _downloadFlow.emit(id to DownloadStatus.Error(message, error))
    }

    private fun startNextEnqueueDownload() {
        while (runningIds.size < DownloadHelper.MAX_CONCURRENT_DOWNLOADS && waitingIds.isNotEmpty()) {
            val id = waitingIds.firstOrNull { it !in runningIds } ?: break
            waitingIds.remove(id)
            runningIds.add(id)
            activeIds.add(id)
            workerScope.launch {
                try {
                    val item = Database.downloadDao().findDownloadItemById(id) ?: return@launch
                    if (!item.isFinished && isDownloading(id)) selectFormatAndDownloadFile(item)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    reportDownloadFailure(e, id)
                } finally {
                    runningIds.remove(id)
                    activeIds.remove(id)
                    startNextEnqueueDownload()
                    stopServiceIfDone()
                }
            }
        }
    }

    private fun updateNotification(
        notificationBuilder: Builder,
        item: DownloadItem,
        totalRead: Int
    ) {
        notificationBuilder
            .setContentText(
                totalRead.formatAsFileSize() + " / " +
                        item.downloadSize.formatAsFileSize()
            )
            .setProgress(
                item.downloadSize.toInt(),
                totalRead,
                false
            )
        notificationManager.notify(
            item.getNotificationId(),
            notificationBuilder.build()
        )
    }

    private suspend fun start(item: DownloadItem) {
        item.path = getDownloadPath(DownloadHelper.AUDIO_DIR, item.fileName)
            .apply { deleteIfExists() }.createFile()
        item.id = Database.downloadDao().insertDownloadItem(item).toInt()
        queueDownload(item.id)
    }

    private fun queueDownload(id: Int) {
        if (id < 0 || isDownloading(id)) return
        waitingIds.add(id)
        startNextEnqueueDownload()
    }

    fun pause(id: Int) {
        workerScope.launch {
            pauseDownload(id)
            stopServiceIfDone()
        }
    }

    private suspend fun pauseDownload(id: Int) {
        waitingIds.remove(id)
        activeIds.remove(id)
        _downloadFlow.emit(id to DownloadStatus.Paused)
    }

    private suspend fun resumeAll() {
        val dao = Database.downloadDao()
        val incomplete = dao.getAll().flatMap { it.downloadItems }
            .filter { it.type == FileType.AUDIO && !it.isFinished }
            .map { it.videoId }
        dao.getPendingPlaylists().forEach { pending ->
            val type = PlaylistType.entries.find { it.name == pending.playlistType }
            if (type == null) dao.removePendingPlaylist(pending.playlistId, pending.playlistType)
            else DownloadHelper.downloadPlaylistAudio(this, pending.playlistId, pending.name, type)
        }
        submitAudio(dao.getPendingAudioIds() + incomplete)
    }

    private suspend fun stopDownload(id: Int) {
        waitingIds.remove(id)
        activeIds.remove(id)
        val item = Database.downloadDao().findDownloadItemById(id) ?: return
        notificationManager.cancel(item.getNotificationId())
        Database.downloadDao().deleteDownloadItemById(id)
        item.path.deleteIfExists()
        _downloadFlow.emit(id to DownloadStatus.Stopped)
    }

    private fun stopServiceIfDone() {
        if (startRequests.get() != 0 || waitingIds.isNotEmpty() || runningIds.isNotEmpty()) return
        val finishedStartId = latestStartId.get()
        lifecycleScope.launch(Dispatchers.Main.immediate) {
            if (startRequests.get() == 0 && latestStartId.get() == finishedStartId &&
                stopSelfResult(finishedStartId)
            ) leaveForeground()
        }
    }

    private fun selectMatchingStream(streams: Streams, item: DownloadItem): PipedStream? {
        return streams.audioStreams.filter {
            it.format == item.format && it.quality == item.quality && it.audioTrackLocale == item.language
        }.maxByOrNull(::audioBitrate)
    }

    private fun audioBitrate(stream: PipedStream): Int =
        stream.bitrate?.takeIf { it > 0 } ?: ((stream.quality.getWhileDigit() ?: 0) * 1000)

    /**
     * Check whether the file downloading or not.
     */
    fun isDownloading(id: Int): Boolean {
        return id in activeIds
    }

    private fun notifyForeground() {
        ServiceCompat.startForeground(
            this, NotificationId.DOWNLOAD_IN_PROGRESS.id, summaryNotification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            }
        )
    }

    private fun getNotificationBuilder(item: DownloadItem): Builder {
        val intent = Intent(this@DownloadService, MainActivity::class.java)
            .putExtra(IntentData.OPEN_DOWNLOADS, true)
        val activityIntent = PendingIntentCompat
            .getActivity(this@DownloadService, 0, intent, FLAG_CANCEL_CURRENT, false)

        return Builder(this, DOWNLOAD_CHANNEL_NAME)
            .setContentTitle("[${item.type}] ${item.fileName}")
            .setProgress(0, 0, true)
            .setOngoing(true)
            .setContentIntent(activityIntent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY)
            .setGroup(DOWNLOAD_NOTIFICATION_GROUP)
    }

    private fun setResumeNotification(
        notificationBuilder: Builder,
        item: DownloadItem
    ) {
        notificationBuilder
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setWhen(System.currentTimeMillis())
            .setOngoing(true)
            .clearActions()
            .addAction(getPauseAction(item.id))
            .addAction(getStopAction(item.id))

        notificationManager.notify(item.getNotificationId(), notificationBuilder.build())
    }

    private fun setPauseNotification(
        notificationBuilder: Builder,
        item: DownloadItem,
        isCompleted: Boolean = false
    ) {
        notificationBuilder
            .setProgress(0, 0, false)
            .setOngoing(false)
            .clearActions()

        if (isCompleted) {
            notificationBuilder
                .setSmallIcon(R.drawable.ic_done)
                .setContentText(getString(R.string.download_completed))
        } else {
            notificationBuilder
                .setSmallIcon(R.drawable.ic_pause)
                .setContentText(getString(R.string.download_paused))
                .addAction(getResumeAction(item.id))
                .addAction(getStopAction(item.id))
        }
        notificationManager.notify(item.getNotificationId(), notificationBuilder.build())
    }

    private fun getResumeAction(id: Int): NotificationCompat.Action {
        val intent = Intent(this, NotificationReceiver::class.java)
            .setAction(ACTION_DOWNLOAD_RESUME)
            .putExtra("id", id)

        return NotificationCompat.Action.Builder(
            R.drawable.ic_play,
            getString(R.string.resume),
            PendingIntentCompat.getBroadcast(this, id, intent, FLAG_UPDATE_CURRENT, false)
        ).build()
    }

    private fun getPauseAction(id: Int): NotificationCompat.Action {
        val intent = Intent(this, NotificationReceiver::class.java)
            .setAction(ACTION_DOWNLOAD_PAUSE)
            .putExtra("id", id)

        return NotificationCompat.Action.Builder(
            R.drawable.ic_pause,
            getString(R.string.pause),
            PendingIntentCompat.getBroadcast(this, id, intent, FLAG_UPDATE_CURRENT, false)
        ).build()
    }

    private fun getStopAction(id: Int): NotificationCompat.Action {
        val intent = Intent(this, NotificationReceiver::class.java).apply {
            action = ACTION_DOWNLOAD_STOP
            putExtra("id", id)
        }

        // the request code must differ from the one of the pause/resume action
        val requestCode = Int.MAX_VALUE / 2 - id
        return NotificationCompat.Action.Builder(
            R.drawable.ic_stop,
            getString(R.string.stop),
            PendingIntentCompat.getBroadcast(this, requestCode, intent, FLAG_UPDATE_CURRENT, false)
        ).build()
    }

    /**
     * Get a [Path] from the corresponding download directory and the file name
     */
    private fun getDownloadPath(directory: String, fileName: String): Path {
        return DownloadHelper.getDownloadDir(this, directory) / fileName
    }

    private fun leaveForeground() {
        if (!IS_DOWNLOAD_RUNNING) return
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
        IS_DOWNLOAD_RUNNING = false
        sendBroadcast(Intent(ACTION_SERVICE_STOPPED).setPackage(packageName))
    }

    override fun onDestroy() {
        leaveForeground()
        activeIds.clear()
        getSystemService<ConnectivityManager>()?.unregisterNetworkCallback(networkCallback)
        workerScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    inner class LocalBinder : Binder() {
        fun getService(): DownloadService = this@DownloadService
    }

    companion object {
        private const val DOWNLOAD_NOTIFICATION_GROUP = "download_notification_group"
        const val ACTION_SERVICE_STARTED =
            "com.github.libretube.services.DownloadService.ACTION_SERVICE_STARTED"
        const val ACTION_SERVICE_STOPPED =
            "com.github.libretube.services.DownloadService.ACTION_SERVICE_STOPPED"
        const val ACTION_RESUME_ALL =
            "com.github.libretube.services.DownloadService.ACTION_RESUME_ALL"

        const val ACTION_ENQUEUE_AUDIO_BATCH = "com.github.libretube.services.DownloadService.ENQUEUE_AUDIO_BATCH"
        const val EXTRA_AUDIO_IDS = "audioIds"

        private val VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")
        private const val MAX_SEGMENT_RETRIES = 3
        @Volatile var IS_DOWNLOAD_RUNNING = false
            private set
    }
}
