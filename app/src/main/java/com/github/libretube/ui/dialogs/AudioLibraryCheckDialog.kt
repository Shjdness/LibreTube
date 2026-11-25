package com.github.libretube.ui.dialogs

import android.app.Dialog
import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.github.libretube.R
import com.github.libretube.helpers.DownloadHelper
import com.github.libretube.repo.AudioLibraryRepository
import com.github.libretube.services.DownloadService
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class AudioLibraryCheckDialog : DialogFragment() {
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val rows = ArrayAdapter<String>(requireContext(), android.R.layout.simple_list_item_1)
        rows.add(getString(R.string.audio_check_scope))
        var issues = emptyList<AudioLibraryRepository.CheckIssue>()
        val result = MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.audio_check_library)
            .setAdapter(rows, null)
            .setPositiveButton(R.string.audio_redownload_issues) { _, _ ->
                if (issues.isNotEmpty()) DownloadHelper.requestForegroundDownload(requireContext(),
                    Intent(requireContext(), DownloadService::class.java).setAction(DownloadService.ACTION_REPAIR_AUDIO)
                        .putStringArrayListExtra(DownloadService.EXTRA_AUDIO_IDS, ArrayList(issues.map { it.id })))
            }.setNegativeButton(R.string.cancel, null).create()
        result.setOnShowListener { result.getButton(Dialog.BUTTON_POSITIVE).isEnabled = false }
        lifecycleScope.launch {
            try {
                val report = AudioLibraryRepository.checkDownloads()
                if (!isAdded || !result.isShowing) return@launch
                issues = report.issues
                result.setTitle(getString(R.string.audio_check_result, report.total, issues.size))
                rows.clear()
                rows.add(getString(R.string.audio_check_scope))
                rows.addAll(issues.map {
                    val reason = when (it.reason) {
                        "missing" -> R.string.audio_check_missing
                        "size" -> R.string.audio_check_size
                        "incomplete" -> R.string.audio_check_incomplete
                        else -> R.string.audio_check_unreadable
                    }
                    it.title.ifBlank { it.id } + "\n" + getString(reason)
                })
                if (issues.isEmpty()) rows.add(getString(R.string.audio_check_ok))
                result.getButton(Dialog.BUTTON_POSITIVE).isEnabled = issues.isNotEmpty()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (isAdded) {
                    result.setTitle(R.string.unknown_error)
                    rows.add(e.localizedMessage.orEmpty())
                }
            }
        }
        return result
    }
}
