package com.github.libretube.ui.adapters

import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.recyclerview.widget.ListAdapter
import com.github.libretube.api.obj.StreamItem
import com.github.libretube.constants.IntentData
import com.github.libretube.databinding.PlaylistTrackRowBinding
import com.github.libretube.extensions.toID
import com.github.libretube.ui.adapters.callbacks.DiffUtilItemCallback
import com.github.libretube.ui.base.BaseActivity
import com.github.libretube.ui.sheets.VideoOptionsBottomSheet
import com.github.libretube.ui.sheets.VideoOptionsBottomSheet.Companion.VIDEO_OPTIONS_SHEET_REQUEST_KEY
import com.github.libretube.ui.viewholders.PlaylistViewHolder

data class PlaylistItem(
    val item: StreamItem,
    /**
     * The original index of the playlist item before sorting the feed.
     */
    val originalPlaylistIndex: Int,
)

class PlaylistAdapter(
    private val playlistId: String,
    private val onVideoClick: (StreamItem) -> Unit
) : ListAdapter<PlaylistItem, PlaylistViewHolder>(DiffUtilItemCallback(
    // the index is not relevant for whether the playlist videos are the same
    // hence only compare the videos themselves
    areItemsTheSame = { a, b -> a.item == b.item },
    areContentsTheSame = { a, b -> a.item == b.item },
)) {
    private var downloadedAudioIds = emptySet<String>()
    private val publicationDates = mutableMapOf<String, String>()

    fun hasPublicationDate(videoId: String) = videoId in publicationDates

    fun setPublicationDates(dates: Map<String, String>) {
        val changed = dates.filter { (id, date) -> publicationDates[id] != date }
        if (changed.isEmpty()) return
        publicationDates.putAll(changed)
        currentList.forEachIndexed { index, item ->
            if (item.item.url?.toID() in changed.keys) notifyItemChanged(index)
        }
    }

    fun setDownloadedAudioIds(ids: Set<String>) {
        val previous = downloadedAudioIds
        downloadedAudioIds = ids
        currentList.forEachIndexed { index, item ->
            val id = item.item.url?.toID()
            if ((id in previous) != (id in ids)) notifyItemChanged(index)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PlaylistViewHolder {
        val layoutInflater = LayoutInflater.from(parent.context)
        val binding = PlaylistTrackRowBinding.inflate(layoutInflater, parent, false)
        return PlaylistViewHolder(binding)
    }

    override fun onBindViewHolder(holder: PlaylistViewHolder, position: Int) {
        val (streamItem, _) = getItem(position)!!
        val videoId = streamItem.url?.toID()

        holder.binding.apply {
            videoTitle.text = streamItem.title
            val publicationDate = streamItem.publicationDate() ?: publicationDates[videoId]
            trackMetadata.text = listOfNotNull(
                streamItem.playlistAuthorName(),
                publicationDate,
                streamItem.duration?.takeIf { it > 0 }?.let(DateUtils::formatElapsedTime)
            ).joinToString(" · ")
            trackMetadata.isVisible = trackMetadata.text.isNotEmpty()

            root.setOnClickListener {
                onVideoClick(streamItem)
            }

            val activity = (root.context as BaseActivity)
            val fragmentManager = activity.supportFragmentManager
            val showOptions = {
                fragmentManager.setFragmentResultListener(
                    VIDEO_OPTIONS_SHEET_REQUEST_KEY,
                    activity
                ) { _, _ ->
                    val index = holder.bindingAdapterPosition
                    if (index in currentList.indices) notifyItemChanged(index)
                }
                VideoOptionsBottomSheet().apply {
                    arguments = bundleOf(
                        IntentData.streamItem to streamItem,
                        IntentData.playlistId to playlistId
                    )
                }
                    .show(fragmentManager, VideoOptionsBottomSheet::class.java.name)
            }
            trackMenu.setOnClickListener { showOptions() }
            root.setOnLongClickListener { showOptions(); true }

            downloadBadge.isVisible = videoId in downloadedAudioIds
        }
    }
}
