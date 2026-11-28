package com.github.libretube.enums.menuoption

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.github.libretube.R
import com.github.libretube.obj.bottomsheetitem.BottomSheetItem
import com.github.libretube.obj.bottomsheetitem.BottomSheetItemTitle

sealed class DownloadListMenuOption(
    @StringRes titleStringRes: Int,
    @DrawableRes drawableRes: Int,
) : BottomSheetItem(
    title = BottomSheetItemTitle.StringResource(titleStringRes),
    drawable = drawableRes,
) {
    data object PlayOnBackground :
        DownloadListMenuOption(R.string.playOnBackground, R.drawable.ic_headphones)

    data object PlayNext : DownloadListMenuOption(R.string.play_next, R.drawable.ic_playlist)
    data object AddToQueue : DownloadListMenuOption(R.string.add_to_queue, R.drawable.ic_queue)
    data object Share : DownloadListMenuOption(R.string.audio_share_link, R.drawable.ic_share)
    data object Delete : DownloadListMenuOption(R.string.delete, R.drawable.ic_delete)
    data object ShareFile : DownloadListMenuOption(R.string.audio_share_file, R.drawable.ic_share)
    data object Export : DownloadListMenuOption(R.string.audio_save_file, R.drawable.ic_export)
}