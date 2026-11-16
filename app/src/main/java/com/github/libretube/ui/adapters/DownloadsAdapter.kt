package com.github.libretube.ui.adapters

import android.annotation.SuppressLint
import android.content.Context
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.recyclerview.widget.ListAdapter
import com.github.libretube.R
import com.github.libretube.constants.IntentData
import com.github.libretube.databinding.VideoRowBinding
import com.github.libretube.db.DatabaseHelper
import com.github.libretube.db.obj.DownloadWithItems
import com.github.libretube.extensions.formatAsFileSize
import com.github.libretube.helpers.DownloadHelper
import com.github.libretube.helpers.ImageHelper
import com.github.libretube.ui.adapters.callbacks.DiffUtilItemCallback
import com.github.libretube.ui.base.BaseActivity
import com.github.libretube.ui.extensions.setWatchProgressLength
import com.github.libretube.ui.sheets.DownloadOptionsBottomSheet
import com.github.libretube.ui.sheets.DownloadOptionsBottomSheet.Companion.DELETE_DOWNLOAD_REQUEST_KEY
import com.github.libretube.ui.viewholders.DownloadsViewHolder
import com.github.libretube.util.TextUtils
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.io.path.fileSize

class DownloadsAdapter(
    private val context: Context,
    private val toggleDownload: (DownloadWithItems) -> Boolean,
    private val play: (DownloadWithItems) -> Unit
) : ListAdapter<DownloadWithItems, DownloadsViewHolder>(DiffUtilItemCallback()) {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DownloadsViewHolder {
        val binding = VideoRowBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return DownloadsViewHolder(binding)
    }

    @SuppressLint("SetTextI18n")
    override fun onBindViewHolder(holder: DownloadsViewHolder, position: Int) {
        val downloadWithItems = getItem(holder.bindingAdapterPosition)
        val download = downloadWithItems.download
        val audio = downloadWithItems.audioItem ?: return

        holder.binding.apply {
            fileSize.isVisible = true

            channelImageContainer.isGone = true
            videoTitle.text = download.title
            channelName.text = download.uploader
            videoInfo.text = download.uploadDate?.let { TextUtils.localizeDate(it) }
            watchProgress.setWatchProgressLength(download.videoId, download.duration ?: 0)

            val downloadSize = audio.downloadSize
            val currentSize = runCatching { audio.path.fileSize() }.getOrDefault(0L)

            progressBar.isIndeterminate = downloadSize <= 0L
            thumbnailDurationCard.isGone = true
            if (downloadSize <= 0L) {
                progressBar.isIndeterminate = true
            } else {
                progressBar.max = downloadSize.toInt()
                progressBar.progress = currentSize.toInt()
            }

            val totalSizeInfo = if (downloadSize > 0) {
                downloadSize.formatAsFileSize()
            } else {
                context.getString(R.string.unknown)
            }
            if (!audio.isFinished) {
                downloadOverlay.isVisible = true
                resumePauseBtn.setImageResource(R.drawable.ic_download)
                fileSize.text = "${currentSize.formatAsFileSize()} / $totalSizeInfo"
            } else {
                downloadOverlay.isGone = true
                fileSize.text = totalSizeInfo
                thumbnailDurationCard.isVisible = true
                download.duration?.let {
                    thumbnailDuration.text = DateUtils.formatElapsedTime(it)
                }
            }

            download.thumbnailPath?.let { path ->
                ImageHelper.loadImage(path.toString(), thumbnail)
            }

            progressBar.setOnClickListener {
                val isDownloading = toggleDownload(downloadWithItems)

                resumePauseBtn.setImageResource(
                    if (isDownloading) {
                        R.drawable.ic_pause
                    } else {
                        R.drawable.ic_download
                    }
                )
            }

            root.setOnClickListener {
                if (audio.isFinished) play(downloadWithItems) else toggleDownload(downloadWithItems)
            }

            root.setOnLongClickListener {
                if (!audio.isFinished) {
                    showDeleteDialog(root.context, holder.bindingAdapterPosition)
                    return@setOnLongClickListener true
                }
                val activity = root.context as BaseActivity
                val fragmentManager = activity.supportFragmentManager
                fragmentManager.setFragmentResultListener(
                    DELETE_DOWNLOAD_REQUEST_KEY,
                    activity
                ) { _, _ ->
                    // the position might have changed in the meanwhile if an other item was deleted
                    // apparently [onBindViewHolder] is only retriggered if the item changes, but
                    // not if the position changes (which would lead to IndexOutOfBounds here)
                    val realPosition = currentList.indexOf(downloadWithItems)
                    showDeleteDialog(root.context, realPosition)
                }
                DownloadOptionsBottomSheet()
                    .apply {
                        arguments = bundleOf(
                            IntentData.streamItem to download.toStreamItem()
                        )
                    }
                    .show(fragmentManager)
                true
            }
        }
    }

    fun showDeleteDialog(context: Context, position: Int) {
        val item = currentList.getOrNull(position) ?: return
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.delete)
            .setMessage(R.string.irreversible)
            .setPositiveButton(R.string.okay) { _, _ ->
                deleteDownload(item)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun deleteDownload(item: DownloadWithItems) {
        CoroutineScope(Dispatchers.IO).launch { DownloadHelper.deleteDownloadIncludingFiles(item) }
    }

    fun deleteAllDownloads(onlyDeleteWatched: Boolean) {
        val downloads = currentList.toList()
        CoroutineScope(Dispatchers.IO).launch {
            downloads.filter { item ->
                !onlyDeleteWatched || DatabaseHelper.isVideoWatched(item.download.videoId, item.download.duration ?: 0)
            }.forEach { DownloadHelper.deleteDownloadIncludingFiles(it) }
        }
    }
}
