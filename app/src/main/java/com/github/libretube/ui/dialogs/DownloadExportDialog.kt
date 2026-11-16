package com.github.libretube.ui.dialogs

import android.app.Dialog
import android.net.Uri
import android.os.Bundle
import androidx.core.net.toUri
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.fragment.app.DialogFragment
import com.github.libretube.R
import com.github.libretube.constants.IntentData
import com.github.libretube.db.DatabaseHolder
import com.github.libretube.extensions.toAndroidUri
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.loadingindicator.LoadingIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.lifecycleScope
import kotlin.io.path.inputStream

class DownloadExportDialog : DialogFragment() {
    private suspend fun exportDownload(videoId: String, outputUri: Uri) {
        val resolver = requireContext().contentResolver
        val audio = checkNotNull(DatabaseHolder.Database.downloadDao().findById(videoId)?.finishedAudio)
        withContext(Dispatchers.IO) {
            checkNotNull(resolver.openOutputStream(outputUri)).use { output ->
                audio.path.inputStream().use { input -> input.copyTo(output) }
            }
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val videoId = requireArguments().getString(IntentData.videoId)!!
        val outputUri = requireArguments().getString(IntentData.outputUri)!!.toUri()

        // prevent user from closing dialog
        isCancelable = false

        val loadingIndicator = LoadingIndicator(requireContext())
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.exporting)
            .setView(loadingIndicator)
            .setPositiveButton(R.string.okay, null)
            .setCancelable(false)
            .show()
            .also {
                it.getButton(Dialog.BUTTON_POSITIVE)?.isGone = true
            }

        lifecycleScope.launch {
            val success = runCatching { exportDownload(videoId, outputUri) }.isSuccess
            dialog.setTitle(if (success) R.string.exportsuccess else R.string.unknown_error)
            loadingIndicator.isGone = true
            dialog.getButton(Dialog.BUTTON_POSITIVE)?.isVisible = true
        }

        return dialog
    }
}