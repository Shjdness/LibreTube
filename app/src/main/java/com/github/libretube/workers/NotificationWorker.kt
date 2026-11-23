package com.github.libretube.workers

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/** Completes subscription polling jobs left in WorkManager by older releases. */
class NotificationWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = Result.success()
}
