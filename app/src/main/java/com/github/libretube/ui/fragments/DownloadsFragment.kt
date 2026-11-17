package com.github.libretube.ui.fragments

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.os.Parcelable
import android.view.View
import android.view.ViewGroup.MarginLayoutParams
import androidx.activity.OnBackPressedCallback
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.libretube.R
import com.github.libretube.constants.PreferenceKeys
import com.github.libretube.databinding.FragmentDownloadContentBinding
import com.github.libretube.db.DatabaseHolder.Database
import com.github.libretube.db.obj.DownloadWithItems
import com.github.libretube.extensions.ceilHalf
import com.github.libretube.extensions.dpToPx
import com.github.libretube.extensions.formatAsFileSize
import com.github.libretube.helpers.DownloadHelper
import com.github.libretube.helpers.NavigationHelper
import com.github.libretube.helpers.PreferenceHelper
import com.github.libretube.obj.DownloadStatus
import com.github.libretube.obj.bottomsheetitem.BottomSheetItem
import com.github.libretube.obj.bottomsheetitem.BottomSheetItemTitle
import com.github.libretube.receivers.NotificationReceiver.Companion.ACTION_DOWNLOAD_RESUME
import com.github.libretube.services.DownloadService
import com.github.libretube.ui.adapters.DownloadsAdapter
import com.github.libretube.ui.base.DynamicLayoutManagerFragment
import com.github.libretube.ui.extensions.setOnBackPressed
import com.github.libretube.ui.models.CommonPlayerViewModel
import com.github.libretube.ui.models.DownloadsViewModel
import com.github.libretube.ui.sheets.BaseBottomSheet
import com.github.libretube.ui.viewholders.DownloadsViewHolder
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.parcelize.Parcelize

import android.content.BroadcastReceiver
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.ListAdapter
import com.github.libretube.ui.adapters.callbacks.DiffUtilItemCallback

@Parcelize
sealed class DownloadSortingOrder(
    @get:StringRes @field:StringRes
    val titleStringRes: Int,
    @get:DrawableRes @field:DrawableRes
    val drawableRes: Int,
    val ordinal: Int,
) : BottomSheetItem(
    title = BottomSheetItemTitle.StringResource(titleStringRes),
    drawable = drawableRes,
), Parcelable {
    companion object {
        // A sort option can be the first class loaded when the download screen opens.
        val entries by lazy {
            listOf(Oldest, Newest, Alphabetic, Duration, Channel, Size)
        }
    }

    data object Oldest : DownloadSortingOrder(
        titleStringRes = R.string.least_recent,
        drawableRes = R.drawable.ic_sort_by_oldest,
        ordinal = 1,
    )

    data object Newest : DownloadSortingOrder(
        titleStringRes = R.string.most_recent,
        drawableRes = R.drawable.ic_sort_by_newest,
        ordinal = 2,
    )

    data object Alphabetic : DownloadSortingOrder(
        titleStringRes = R.string.alphabetic,
        drawableRes = R.drawable.ic_sort_by_alpha,
        ordinal = 3,
    )

    data object Duration : DownloadSortingOrder(
        titleStringRes = R.string.duration,
        drawableRes = R.drawable.ic_time_outlined,
        ordinal = 4,
    )

    data object Channel : DownloadSortingOrder(
        titleStringRes = R.string.sort_channel,
        drawableRes = R.drawable.ic_person,
        ordinal = 5,
    )

    data object Size : DownloadSortingOrder(
        titleStringRes = R.string.sort_size,
        drawableRes = R.drawable.ic_sort_by_file_size,
        ordinal = 6,
    )
}


@SuppressLint("UnsafeOptInUsageError")
class DownloadsFragment : DynamicLayoutManagerFragment(R.layout.fragment_download_content) {
    private var _binding: FragmentDownloadContentBinding? = null
    private val binding get() = _binding!!
    private val downloadsModel: DownloadsViewModel by activityViewModels()
    private val playerModel: CommonPlayerViewModel by activityViewModels()
    private lateinit var audioAdapter: DownloadsAdapter
    private lateinit var folderAdapter: DownloadFolderAdapter
    private lateinit var folderBack: OnBackPressedCallback
    private var items = emptyList<DownloadWithItems>()
    private var channel: String? = null
    private var sortIndex = 0
    private var service: DownloadService? = null
    private var bound = false
    private var progressJob: Job? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val connected = (binder as? DownloadService.LocalBinder)?.getService() ?: return
            service = connected
            progressJob?.cancel()
            progressJob = viewLifecycleOwner.lifecycleScope.launch {
                connected.downloadFlow.collect { (id, status) -> updateProgress(id, status) }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            progressJob?.cancel()
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                DownloadService.ACTION_SERVICE_STARTED -> bindDownloadService()
                DownloadService.ACTION_SERVICE_STOPPED -> {
                    unbindDownloadService()
                    if (_binding != null) render()
                }
            }
        }
    }

    override fun setLayoutManagers(gridItems: Int) {
        _binding?.downloadsRecView?.layoutManager = GridLayoutManager(context, gridItems.ceilHalf())
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _binding = FragmentDownloadContentBinding.bind(view)
        super.onViewCreated(view, savedInstanceState)

        channel = savedInstanceState?.getString("channel")
        sortIndex = PreferenceHelper.getInt(PreferenceKeys.SELECTED_DOWNLOAD_SORT_TYPE, DownloadSortingOrder.Oldest.ordinal)
            .coerceIn(DownloadSortingOrder.entries.indices)
        folderBack = setOnBackPressed {
            channel = null
            render()
        }
        audioAdapter = DownloadsAdapter(requireContext(), ::toggleDownload) { item ->
            NavigationHelper.navigateDownloadedAudio(requireContext(), item.download.videoId, fullChannelQueue(item.channelKey), channelName(item))
        }
        folderAdapter = DownloadFolderAdapter { key ->
            channel = key
            render()
            binding.downloadsRecView.scrollToPosition(0)
        }
        binding.channelFilter.setOnClickListener {
            channel = null
            render()
        }
        binding.sortType.setOnClickListener {
            BaseBottomSheet().setItems(DownloadSortingOrder.entries) { index ->
                sortIndex = index
                PreferenceHelper.putInt(PreferenceKeys.SELECTED_DOWNLOAD_SORT_TYPE, index)
                render()
            }.show(childFragmentManager)
        }
        binding.resumeAll.setOnClickListener {
            DownloadHelper.requestForegroundDownload(requireContext(),
                Intent(requireContext(), DownloadService::class.java).setAction(DownloadService.ACTION_RESUME_ALL))
        }
        binding.deleteAll.setOnClickListener { showDeleteAllDialog() }
        binding.shuffleAll.setOnClickListener {
            val queue = shelfItems().filter { it.finishedAudio != null }.shuffled()
            val first = queue.firstOrNull() ?: return@setOnClickListener
            NavigationHelper.navigateDownloadedAudio(requireContext(), first.download.videoId, queue, channel?.let { channelName(first) })
        }
        playerModel.isMiniPlayerVisible.observe(viewLifecycleOwner) { visible ->
            binding.shuffleAll.updateLayoutParams<MarginLayoutParams> {
                bottomMargin = (if (visible) 64f else 16f).dpToPx()
            }
        }
        downloadsModel.searchQuery.observe(viewLifecycleOwner) { render() }
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                Database.downloadDao().observeDownloads().collect { downloads ->
                    items = downloads.filter { it.audioItem != null }
                    if (items.none { it.channelKey == channel }) channel = null
                    render()
                }
            }
        }
        render()
    }

    private fun render() {
        val query = downloadsModel.searchQuery.value.orEmpty().trim()
        val showFolders = channel == null && query.isEmpty()
        folderBack.isEnabled = channel != null
        binding.channelFilter.text = items.firstOrNull { it.channelKey == channel }?.let { channelName(it) }
        binding.channelFilter.isVisible = channel != null
        binding.sortType.isVisible = !showFolders
        val order = DownloadSortingOrder.entries[sortIndex]
        binding.sortType.text = order.title.getText(requireContext())

        val displayed = items.filter { item ->
            (channel == null || item.channelKey == channel) &&
                (item.download.title.contains(query, true) || item.download.uploader.contains(query, true))
        }
        val nextAdapter = if (showFolders) folderAdapter else audioAdapter
        if (binding.downloadsRecView.adapter !== nextAdapter) binding.downloadsRecView.adapter = nextAdapter
        if (showFolders) {
            folderAdapter.submitList(items.groupBy { it.channelKey }.map { (key, tracks) ->
                DownloadFolder(key, channelName(tracks.first()), tracks.size)
            }.sortedBy { it.name.lowercase() })
        } else {
            audioAdapter.submitList(sortDownloads(displayed, order))
        }
        binding.downloadsEmpty.isVisible = displayed.isEmpty()
        binding.deleteAll.isVisible = !showFolders && displayed.isNotEmpty()
        binding.shuffleAll.isVisible = !showFolders && displayed.any { it.finishedAudio != null }
        binding.resumeAll.isVisible = items.any { it.audioItem?.isFinished == false }
    }

    private fun shelfItems() = sortDownloads(items.filter { channel == null || it.channelKey == channel }, DownloadSortingOrder.entries[sortIndex])

    private fun fullChannelQueue(key: String) = sortDownloads(items.filter { it.channelKey == key }, DownloadSortingOrder.entries[sortIndex])

    private fun channelName(item: DownloadWithItems) =
        item.download.uploader.ifBlank { getString(R.string.unknown) }

    private fun toggleDownload(download: DownloadWithItems): Boolean {
        val item = download.audioItem?.takeUnless { it.isFinished } ?: return false
        val connected = service
        if (connected == null) {
            DownloadHelper.downloadAudio(requireContext(), download.download.videoId)
            return true
        }
        return if (connected.isDownloading(item.id)) {
            connected.pause(item.id)
            false
        } else {
            DownloadHelper.requestForegroundDownload(requireContext(),
                Intent(requireContext(), DownloadService::class.java)
                    .setAction(ACTION_DOWNLOAD_RESUME)
                    .putExtra("id", item.id))
            true
        }
    }

    private fun updateProgress(id: Int, status: DownloadStatus) {
        if (_binding == null || binding.downloadsRecView.adapter !== audioAdapter) return
        val position = audioAdapter.currentList.indexOfFirst { it.audioItem?.id == id }
        if (position < 0) return

        val row = binding.downloadsRecView.findViewHolderForAdapterPosition(position) as? DownloadsViewHolder
        row?.binding?.apply {
            when (status) {
                DownloadStatus.Paused -> resumePauseBtn.setImageResource(R.drawable.ic_download)
                DownloadStatus.Completed -> downloadOverlay.isGone = true
                DownloadStatus.Stopped -> Unit
                is DownloadStatus.Error -> resumePauseBtn.setImageResource(R.drawable.ic_restart)
                is DownloadStatus.Progress -> {
                    downloadOverlay.isVisible = true
                    resumePauseBtn.setImageResource(R.drawable.ic_pause)
                    if (!progressBar.isIndeterminate) progressBar.incrementProgressBy(status.progress.toInt())
                    fileSize.text = progressBar.progress.formatAsFileSize()
                }
            }
        }
        if (status is DownloadStatus.Completed) {
            audioAdapter.notifyItemChanged(position)
            render()
        }
    }

    private fun showDeleteAllDialog() {
        var onlyWatched = false
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.delete_all)
            .setMultiChoiceItems(arrayOf(getString(R.string.delete_only_watched_videos)), null) { _, _, selected ->
                onlyWatched = selected
            }
            .setPositiveButton(R.string.okay) { _, _ -> audioAdapter.deleteAllDownloads(onlyWatched) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    fun bindDownloadService() {
        val context = context ?: return
        if (bound || _binding == null) return
        bound = context.bindService(Intent(context, DownloadService::class.java), connection, 0)
    }

    private fun unbindDownloadService() {
        if (bound) requireContext().unbindService(connection)
        bound = false
        service = null
        progressJob?.cancel()
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply {
            addAction(DownloadService.ACTION_SERVICE_STARTED)
            addAction(DownloadService.ACTION_SERVICE_STOPPED)
        }
        ContextCompat.registerReceiver(requireContext(), receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        if (DownloadService.IS_DOWNLOAD_RUNNING) bindDownloadService()
    }

    override fun onStop() {
        requireContext().unregisterReceiver(receiver)
        unbindDownloadService()
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("channel", channel)
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    private fun sortDownloads(items: List<DownloadWithItems>, order: DownloadSortingOrder) = when (order) {
        DownloadSortingOrder.Oldest -> items
        DownloadSortingOrder.Newest -> items.reversed()
        DownloadSortingOrder.Alphabetic -> items.sortedBy { it.download.title.lowercase() }
        DownloadSortingOrder.Duration -> items.sortedBy { it.download.duration }
        DownloadSortingOrder.Channel -> items.sortedBy { it.download.uploader.lowercase() }
        DownloadSortingOrder.Size -> items.sortedBy { it.audioItem?.downloadSize }
    }
}

private data class DownloadFolder(val key: String, val name: String, val count: Int)

private class DownloadFolderAdapter(private val open: (String) -> Unit) :
    ListAdapter<DownloadFolder, DownloadFolderAdapter.Holder>(DiffUtilItemCallback()) {
    class Holder(val title: TextView) : RecyclerView.ViewHolder(title)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder = Holder(
        LayoutInflater.from(parent.context).inflate(R.layout.download_channel_row, parent, false) as TextView
    )

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val folder = getItem(position)
        holder.title.text = holder.title.resources.getString(R.string.download_channel_count, folder.name, folder.count)
        holder.title.setOnClickListener { open(folder.key) }
    }
}
