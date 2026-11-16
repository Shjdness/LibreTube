package com.github.libretube.helpers

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Process
import androidx.core.content.getSystemService
import androidx.core.os.bundleOf
import androidx.fragment.app.commitNow
import androidx.fragment.app.replace
import com.github.libretube.NavDirections
import com.github.libretube.R
import com.github.libretube.constants.IntentData
import com.github.libretube.enums.PlaylistType
import com.github.libretube.db.obj.DownloadWithItems
import com.github.libretube.extensions.toID
import com.github.libretube.obj.ChannelIdentifier
import com.github.libretube.parcelable.PlayerData
import com.github.libretube.ui.activities.AbstractPlayerHostActivity
import com.github.libretube.ui.activities.MainActivity
import com.github.libretube.ui.activities.ZoomableImageActivity
import com.github.libretube.ui.base.BaseActivity
import com.github.libretube.ui.fragments.AudioPlayerFragment
import com.github.libretube.util.PlayingQueue

object NavigationHelper {
    fun navigateChannel(context: Context, channelUrlOrId: String?) {
        if (channelUrlOrId == null) return

        // navigating to channels is only supported in the main activity, not in the no internet activity
        val activity = ContextHelper.tryUnwrapActivity<MainActivity>(context) ?: return
        activity.navController.navigate(NavDirections.openChannel(ChannelIdentifier.Id(channelUrlOrId.toID())))
        try {
            // minimize player if currently expanded
            activity.minimizePlayerContainerLayout()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun navigateDownloadedAudio(context: Context, videoId: String, queue: List<DownloadWithItems>, queueName: String? = null) {
        val downloaded = queue.filter { it.finishedAudio != null }.map { it.download.toStreamItem() }
        if (downloaded.none { it.url?.toID() == videoId }) return
        PlayingQueue.setStreams(downloaded)
        navigateVideo(context, PlayerData(videoId, keepQueue = true, isOffline = true, queueName = queueName))
    }

    @SuppressLint("UnsafeOptInUsageError")
    fun navigateVideo(context: Context, playerData: PlayerData) {
        val activity = ContextHelper.unwrapActivity<AbstractPlayerHostActivity>(context)
        val reused = activity.runOnAudioPlayerFragment {
            if (!startQueue(playerData)) return@runOnAudioPlayerFragment false
            binding.playerMotionLayout.transitionToStart()
            true
        }
        if (reused) return

        openAudioPlayerFragment(context, minimizeByDefault = true, initialTrack = playerData)
    }

    fun navigatePlaylist(context: Context, playlistUrlOrId: String?, playlistType: PlaylistType) {
        if (playlistUrlOrId == null) return

        val activity = ContextHelper.unwrapActivity<MainActivity>(context)
        activity.navController.navigate(
            NavDirections.openPlaylist(playlistUrlOrId.toID(), playlistType)
        )
    }

    /**
     * Start the audio player fragment
     */
    fun openAudioPlayerFragment(
        context: Context,
        minimizeByDefault: Boolean = false,
        initialTrack: PlayerData? = null
    ) {
        val activity = ContextHelper.unwrapActivity<BaseActivity>(context)
        activity.supportFragmentManager.commitNow {
            val args = bundleOf(
                IntentData.minimizeByDefault to minimizeByDefault,
                IntentData.playerData to initialTrack
            )
            replace<AudioPlayerFragment>(R.id.container, args = args)
        }
    }

    /**
     * Open a large, zoomable image preview
     */
    fun openImagePreview(context: Context, url: String) {
        val intent = Intent(context, ZoomableImageActivity::class.java)
        intent.putExtra(IntentData.bitmapUrl, url)
        context.startActivity(intent)
    }

    /**
     * Needed due to different MainActivity Aliases because of the app icons
     */
    fun restartMainActivity(context: Context) {
        // kill player notification
        context.getSystemService<NotificationManager>()!!.cancelAll()
        // start a new Intent of the app
        val pm = context.packageManager
        val intent = pm.getLaunchIntentForPackage(context.packageName)
        intent?.flags = Intent.FLAG_ACTIVITY_CLEAR_TASK
        context.startActivity(intent)
        // kill the old application
        Process.killProcess(Process.myPid())
    }
}
