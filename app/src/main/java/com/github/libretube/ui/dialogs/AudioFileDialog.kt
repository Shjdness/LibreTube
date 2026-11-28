package com.github.libretube.ui.dialogs

import android.app.Dialog
import android.content.ClipData
import android.content.Intent
import android.os.Bundle
import android.text.format.Formatter
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.github.libretube.R
import com.github.libretube.helpers.AudioFileExport
import com.github.libretube.ui.models.AudioExportViewModel
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.launch

/** Shared, restorable UI for downloaded audio from download, playlist and player menus. */
class AudioFileDialog : DialogFragment() {
    private val model: AudioExportViewModel by viewModels()
    private var collection: kotlinx.coroutines.Job? = null
    private lateinit var message: TextView
    private lateinit var progress: LinearProgressIndicator
    private val picker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data ?: return@registerForActivityResult
        if (result.resultCode != android.app.Activity.RESULT_OK) return@registerForActivityResult
        if (uri.scheme != "content" || uri.authority.isNullOrBlank()) { model.rejectTarget(); return@registerForActivityResult }
        val flags = result.data!!.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        runCatching { requireContext().contentResolver.takePersistableUriPermission(uri, flags) }
        model.selectDestination(uri)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val padding = (24 * resources.displayMetrics.density).toInt()
        message = TextView(requireContext()).apply { id = R.id.audio_export_message }
        progress = LinearProgressIndicator(requireContext()).apply { id = R.id.audio_export_progress; max = 100 }
        val container = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding / 2, padding, padding / 2)
            addView(message)
            addView(progress, LinearLayout.LayoutParams(-1, padding))
        }
        return MaterialAlertDialogBuilder(requireContext()).setTitle(if (arguments?.getBoolean("share") == true) R.string.audio_share_file else R.string.audio_save_file).setView(container)
            .setPositiveButton(R.string.audio_export_choose, null)
            .setNeutralButton(R.string.audio_share_file, null)
            .setNegativeButton(R.string.cancel, null).create()
    }

    override fun onStart() {
        super.onStart()
        collection?.cancel()
        collection = lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) { model.state.collect(::render) } }
    }

    private fun render(state: AudioExportViewModel.State) {
        val dialog = dialog as? androidx.appcompat.app.AlertDialog ?: return
        val busy = state is AudioExportViewModel.State.Running || state is AudioExportViewModel.State.Preparing
        isCancelable = !busy
        val positive = dialog.getButton(Dialog.BUTTON_POSITIVE)
        val neutral = dialog.getButton(Dialog.BUTTON_NEUTRAL)
        val negative = dialog.getButton(Dialog.BUTTON_NEGATIVE)
        positive.visibility = android.view.View.GONE
        neutral.visibility = android.view.View.GONE
        progress.visibility = if (busy) android.view.View.VISIBLE else android.view.View.GONE
        progress.isIndeterminate = state is AudioExportViewModel.State.Preparing
        negative.setText(if (busy) R.string.cancel else R.string.audio_export_close)
        negative.setOnClickListener { if (busy) model.cancel() else dismiss() }
        when (state) {
            AudioExportViewModel.State.Preparing -> message.setText(R.string.audio_export_preparing)
            is AudioExportViewModel.State.Ready -> {
                message.text = getString(R.string.audio_export_details, state.source.name,
                    Formatter.formatFileSize(requireContext(), state.source.size))
                positive.visibility = android.view.View.VISIBLE
                positive.setText(R.string.audio_export_choose)
                positive.setOnClickListener {
                    picker.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = state.source.format.mime
                        putExtra(Intent.EXTRA_TITLE, state.source.name)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                    })
                }
            }
            is AudioExportViewModel.State.Running -> {
                progress.setProgressCompat(state.percent, false)
                message.text = if (state.checking) getString(R.string.audio_export_checking)
                    else getString(R.string.audio_export_progress_text, state.percent)
            }
            is AudioExportViewModel.State.Error -> message.text = getString(state.message) +
                if (state.residue) "\n" + getString(R.string.audio_export_residue) else ""
            is AudioExportViewModel.State.Done -> {
                val result = state.result
                message.text = getString(if (model.sharing) R.string.audio_share_ready else if (result.verified) R.string.audio_export_verified else R.string.audio_export_copied) + "\n" + result.name
                positive.visibility = android.view.View.VISIBLE
                positive.setText(R.string.audio_export_open)
                positive.setOnClickListener { open(result, false) }
                neutral.visibility = android.view.View.VISIBLE
                neutral.setOnClickListener { open(result, true) }
                if (model.sharing && !model.autoShared) { model.autoShared = true; open(result, true) }
            }
        }
    }

    private fun open(result: AudioFileExport.Result, share: Boolean) {
        val intent = if (share) Intent(Intent.ACTION_SEND).apply {
            type = result.mime
            putExtra(Intent.EXTRA_STREAM, result.uri)
            putExtra(Intent.EXTRA_SUBJECT, result.name)
        } else Intent(Intent.ACTION_VIEW).setDataAndType(result.uri, result.mime)
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        intent.clipData = ClipData.newRawUri(result.name, result.uri)
        try { startActivity(Intent.createChooser(intent, getString(if (share) R.string.audio_share_file else R.string.audio_export_open))) }
        catch (_: android.content.ActivityNotFoundException) {
            android.widget.Toast.makeText(requireContext(), R.string.audio_export_no_app, android.widget.Toast.LENGTH_LONG).show()
        } catch (_: SecurityException) {
            android.widget.Toast.makeText(requireContext(), R.string.audio_export_permission, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        fun show(manager: FragmentManager, id: String, share: Boolean = false) {
            if (manager.isStateSaved || manager.findFragmentByTag("audio_file") != null) return
            AudioFileDialog().apply { arguments = bundleOf("trackId" to id, "share" to share) }.show(manager, "audio_file")
        }
    }
}
