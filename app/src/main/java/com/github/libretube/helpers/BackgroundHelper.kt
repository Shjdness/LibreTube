package com.github.libretube.helpers

import android.content.ComponentName
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.github.libretube.R
import com.github.libretube.extensions.toastFromMainThread
import com.github.libretube.services.AudioPlaybackService
import com.google.common.util.concurrent.FutureCallback
import com.google.common.util.concurrent.Futures

@androidx.annotation.OptIn(UnstableApi::class)
object BackgroundHelper {
    fun connect(context: Context, onConnected: (MediaController) -> Unit) {
        val appContext = context.applicationContext
        val token = SessionToken(appContext, ComponentName(appContext, AudioPlaybackService::class.java))
        val future = MediaController.Builder(appContext, token).buildAsync()
        Futures.addCallback(future, object : FutureCallback<MediaController> {
            override fun onSuccess(result: MediaController?) {
                if (result != null) onConnected(result)
            }

            override fun onFailure(error: Throwable) {
                appContext.toastFromMainThread(R.string.audio_stream_unavailable)
            }
        }, ContextCompat.getMainExecutor(appContext))
    }

    fun setVolume(context: Context, volume: Float) {
        if (!AudioPlaybackService.active) return
        connect(context) { controller ->
            controller.volume = volume.coerceIn(0f, 1f)
            controller.release()
        }
    }
}
