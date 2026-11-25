package com.github.libretube.ui.dialogs

import android.app.Dialog
import android.os.Bundle
import android.text.format.DateUtils
import android.widget.ArrayAdapter
import android.widget.EditText
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.github.libretube.R
import com.github.libretube.constants.IntentData
import com.github.libretube.db.DatabaseHolder.Database
import com.github.libretube.db.obj.AudioTimeMark
import com.github.libretube.helpers.BackgroundHelper
import com.github.libretube.parcelable.PlayerData
import com.github.libretube.repo.AudioLibraryRepository
import com.github.libretube.services.AudioPlaybackService
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.media3.session.MediaController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class AudioTimeMarksDialog : DialogFragment() {
    private var controller: MediaController? = null
    private var marks = emptyList<AudioTimeMark>()
    private lateinit var rows: ArrayAdapter<String>

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        rows = ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1)
        val result = MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.audio_time_marks)
            .setAdapter(rows) { _, index -> marks.getOrNull(index)?.let(::playMark) }
            .setPositiveButton(R.string.audio_add_time_mark, null)
            .setNegativeButton(R.string.cancel, null).create()
        result.setOnShowListener {
            result.getButton(Dialog.BUTTON_POSITIVE).isEnabled = controller != null
            result.getButton(Dialog.BUTTON_POSITIVE).setOnClickListener {
                val player = controller ?: return@setOnClickListener
                val id = player.mediaMetadata.extras?.getString(IntentData.videoId) ?: return@setOnClickListener
                val position = player.currentPosition.coerceAtLeast(0)
                editMark(AudioTimeMark(id, position, DateUtils.formatElapsedTime(position / 1000),
                    player.mediaMetadata.title?.toString().orEmpty(), player.mediaMetadata.artist?.toString().orEmpty()))
            }
            result.listView.setOnItemLongClickListener { _, _, index, _ ->
                val mark = marks.getOrNull(index) ?: return@setOnItemLongClickListener false
                MaterialAlertDialogBuilder(requireContext()).setTitle(mark.label)
                    .setItems(arrayOf(getString(R.string.audio_rename_mark), getString(R.string.delete))) { _, choice ->
                        if (choice == 0) editMark(mark)
                        else markTask {
                            withContext(Dispatchers.IO) { Database.audioTimeMarkDao().delete(mark) }
                            loadMarks()
                        }
                    }.show()
                true
            }
        }
        BackgroundHelper.connect(requireContext()) { connected ->
            if (!isAdded) { connected.release(); return@connect }
            controller = connected
            result.getButton(Dialog.BUTTON_POSITIVE)?.isEnabled = true
            loadMarks()
        }
        return result
    }

    private fun loadMarks() {
        val id = controller?.mediaMetadata?.extras?.getString(IntentData.videoId) ?: return
        markTask {
            val saved = withContext(Dispatchers.IO) { Database.audioTimeMarkDao().getForTrack(id) }
            if (!isAdded) return@markTask
            marks = saved
            rows.clear()
            rows.addAll(if (saved.isEmpty()) listOf(getString(R.string.emptyList)) else saved.map {
                DateUtils.formatElapsedTime(it.positionMs / 1000) + " · " + it.label
            })
        }
    }

    private fun editMark(mark: AudioTimeMark) {
        val input = EditText(requireContext()).apply { setText(mark.label); maxLines = 3 }
        MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.audio_add_time_mark).setView(input)
            .setPositiveButton(R.string.okay) { _, _ ->
                val label = input.text.toString().trim().ifBlank { DateUtils.formatElapsedTime(mark.positionMs / 1000) }
                markTask {
                    withContext(Dispatchers.IO) { Database.audioTimeMarkDao().insertAll(listOf(mark.copy(label = label))) }
                    loadMarks()
                }
            }.setNegativeButton(R.string.cancel, null).show()
    }

    private fun playMark(mark: AudioTimeMark) {
        val appContext = requireContext().applicationContext
        BackgroundHelper.connect(appContext) { player ->
            if (player.mediaMetadata.extras?.getString(IntentData.videoId) == mark.trackId) {
                player.seekTo(mark.positionMs)
                player.release()
            } else {
                val command = player.sendCustomCommand(AudioPlaybackService.startServiceCommand,
                    androidx.core.os.bundleOf(IntentData.playerData to PlayerData(mark.trackId, keepQueue = true,
                        isOffline = AudioLibraryRepository.offlineOnly, resumePositionMs = mark.positionMs)))
                command.addListener({ player.release() }, androidx.core.content.ContextCompat.getMainExecutor(appContext))
            }
        }
    }

    private fun markTask(block: suspend () -> Unit) = lifecycleScope.launch {
        try { block() }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) {
            android.util.Log.w("AudioTimeMarks", "Could not update time marks", e)
            context?.let { android.widget.Toast.makeText(it, R.string.unknown_error, android.widget.Toast.LENGTH_SHORT).show() }
        }
    }

    override fun onDestroy() {
        controller?.release()
        controller = null
        super.onDestroy()
    }
}
