package com.github.libretube.ui.tools

import android.content.Context
import android.os.SystemClock
import androidx.core.os.bundleOf
import androidx.core.content.ContextCompat
import com.github.libretube.helpers.BackgroundHelper
import com.github.libretube.services.AudioPlaybackService

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
object SleepTimer {
    val timeLeftMillis: Long
        get() = (AudioPlaybackService.sleepDeadlineElapsed - SystemClock.elapsedRealtime()).coerceAtLeast(0)

    fun start(context: Context, delayInMinutes: Long) {
        if (delayInMinutes !in 1..1440 || !AudioPlaybackService.active) return
        send(context, delayInMinutes)
    }

    fun afterCurrentTrack(context: Context) {
        if (!AudioPlaybackService.active) return
        BackgroundHelper.connect(context) { controller ->
            val command = controller.sendCustomCommand(AudioPlaybackService.runPlayerActionCommand,
                bundleOf("sleep_end_track" to true))
            command.addListener({ controller.release() }, ContextCompat.getMainExecutor(context.applicationContext))
        }
    }

    fun stop(context: Context) {
        if (AudioPlaybackService.active) send(context, 0)
    }

    private fun send(context: Context, minutes: Long) {
        BackgroundHelper.connect(context) { controller ->
            val command = controller.sendCustomCommand(AudioPlaybackService.runPlayerActionCommand,
                bundleOf(AudioPlaybackService.SLEEP_TIMER_MINUTES to minutes))
            command.addListener({ controller.release() }, ContextCompat.getMainExecutor(context.applicationContext))
        }
    }
}
