package com.github.libretube.ui.sheets

import android.os.Bundle
import androidx.core.os.bundleOf
import androidx.fragment.app.setFragmentResult
import androidx.media3.common.util.UnstableApi
import com.github.libretube.api.obj.StreamItem
import com.github.libretube.db.DatabaseHolder.Database
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.github.libretube.constants.IntentData
import com.github.libretube.enums.ShareObjectType
import com.github.libretube.enums.menuoption.DownloadListMenuOption
import com.github.libretube.extensions.parcelable
import com.github.libretube.extensions.toID
import com.github.libretube.helpers.NavigationHelper
import com.github.libretube.obj.ShareData
import com.github.libretube.ui.dialogs.AudioFileDialog
import com.github.libretube.ui.dialogs.ShareDialog
import com.github.libretube.util.PlayingQueue

@UnstableApi
class DownloadOptionsBottomSheet : BaseBottomSheet() {
    private lateinit var videoId: String

    override fun onCreate(savedInstanceState: Bundle?) {
        val streamItem = arguments?.parcelable<StreamItem>(IntentData.streamItem)!!
        videoId = streamItem.url!!.toID()
        val options = mutableListOf(
            DownloadListMenuOption.PlayOnBackground,
            DownloadListMenuOption.Share,
            DownloadListMenuOption.Delete,
            DownloadListMenuOption.Export,
            DownloadListMenuOption.ShareFile,
        )

        val isSelectedVideoCurrentlyPlaying = PlayingQueue.getCurrent()?.url?.toID() == videoId
        if (!isSelectedVideoCurrentlyPlaying && PlayingQueue.isNotEmpty()) {
            options += DownloadListMenuOption.PlayNext
            options += DownloadListMenuOption.AddToQueue
        }

        setItems(options) { which ->
            when (options[which]) {
                DownloadListMenuOption.PlayOnBackground -> {
                    lifecycleScope.launch {
                        val item = Database.downloadDao().findById(videoId) ?: return@launch
                        NavigationHelper.navigateDownloadedAudio(requireContext(), videoId, listOf(item))
                    }
                }

                DownloadListMenuOption.Share -> {
                    val shareData = ShareData(title = streamItem.title, previewImageUrl = streamItem.thumbnail)
                    val bundle = bundleOf(
                        IntentData.id to videoId,
                        IntentData.shareObjectType to ShareObjectType.VIDEO,
                        IntentData.shareData to shareData
                    )
                    val newShareDialog = ShareDialog()
                    newShareDialog.arguments = bundle
                    newShareDialog.show(parentFragmentManager, null)
                }

                DownloadListMenuOption.Delete -> {
                    setFragmentResult(DELETE_DOWNLOAD_REQUEST_KEY, bundleOf())
                }

                DownloadListMenuOption.PlayNext -> {
                    PlayingQueue.addAsNext(streamItem)
                }

                DownloadListMenuOption.AddToQueue -> {
                    PlayingQueue.add(streamItem)
                }

                DownloadListMenuOption.Export -> AudioFileDialog.show(parentFragmentManager, videoId)
                DownloadListMenuOption.ShareFile -> AudioFileDialog.show(parentFragmentManager, videoId, share = true)
            }
        }

        super.onCreate(savedInstanceState)
    }

    companion object {
        const val DELETE_DOWNLOAD_REQUEST_KEY = "delete_download_request_key"
    }
}
