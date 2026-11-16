package com.github.libretube.ui.activities

import android.content.Intent
import android.os.Bundle
import androidx.core.net.toUri
import com.github.libretube.constants.IntentData
import com.github.libretube.enums.PlaylistType
import com.github.libretube.helpers.IntentHelper
import com.github.libretube.helpers.DownloadHelper
import com.github.libretube.ui.base.BaseActivity

class DownloadActivity : BaseActivity() {
    override val isDialogActivity: Boolean = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (savedInstanceState != null) {
            finish()
            return
        }

        val intentData = intent.getStringExtra(Intent.EXTRA_TEXT)?.let {
            IntentHelper.resolveType(it.toUri())
        }
        val videoId = intentData?.getStringExtra(IntentData.videoId)
        val playlistId = intentData?.getStringExtra(IntentData.playlistId)
        when {
            videoId != null -> DownloadHelper.downloadAudio(this, videoId)
            playlistId != null -> DownloadHelper.downloadPlaylistAudio(
                this, playlistId, intent.getStringExtra(Intent.EXTRA_TITLE) ?: playlistId, PlaylistType.PUBLIC
            )
        }
        finish()
    }
}
