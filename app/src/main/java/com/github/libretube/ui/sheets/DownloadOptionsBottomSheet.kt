package com.github.libretube.ui.sheets

import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
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
import com.github.libretube.ui.dialogs.DownloadExportDialog
import com.github.libretube.ui.dialogs.ShareDialog
import com.github.libretube.util.PlayingQueue

@UnstableApi
class DownloadOptionsBottomSheet : BaseBottomSheet() {
    private lateinit var videoId: String

    private val exportFilePicker =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { outputUri ->
            if (outputUri == null) return@registerForActivityResult

            DownloadExportDialog().apply {
                arguments = Bundle().apply {
                    putString(IntentData.videoId, videoId)
                    putString(IntentData.outputUri, outputUri.toString())
                }
            }.show(requireActivity().supportFragmentManager, null)

            dismiss()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        val streamItem = arguments?.parcelable<StreamItem>(IntentData.streamItem)!!
        videoId = streamItem.url!!.toID()
        val options = mutableListOf(
            DownloadListMenuOption.PlayOnBackground,
            DownloadListMenuOption.Share,
            DownloadListMenuOption.Delete,
            DownloadListMenuOption.Export,
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

                DownloadListMenuOption.Export -> {
                    lifecycleScope.launch {
                        val audio = Database.downloadDao().findById(videoId)?.finishedAudio ?: return@launch
                        exportFilePicker.launch(streamItem.title.orEmpty() + "." + audio.fileName.substringAfterLast('.'))
                    }
                    // dismissing now would cause the exportFilePicker to be dropped before
                    // the file is actually chosen by the user
                    autoDismiss = false
                }
            }
        }

        super.onCreate(savedInstanceState)
    }

    companion object {
        const val DELETE_DOWNLOAD_REQUEST_KEY = "delete_download_request_key"
    }
}
