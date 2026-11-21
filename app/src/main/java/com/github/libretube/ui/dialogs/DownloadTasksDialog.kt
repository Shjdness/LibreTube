package com.github.libretube.ui.dialogs

import android.app.Dialog
import android.content.Intent
import android.os.Bundle
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.github.libretube.R
import com.github.libretube.db.DatabaseHolder.Database
import com.github.libretube.enums.PlaylistType
import com.github.libretube.helpers.DownloadHelper
import com.github.libretube.services.DownloadService
import com.github.libretube.extensions.toastFromMainDispatcher
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DownloadTasksDialog : DialogFragment() {
    private data class Task(val id: String, val title: String, val error: String, val type: String? = null,
                            val discovered: Int = 0, val itemId: Int? = null)

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val adapter = android.widget.ArrayAdapter<String>(requireContext(), android.R.layout.simple_list_item_1)
        var tasks = emptyList<Task>()
        val result = MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.download_tasks)
            .setAdapter(adapter) { _, index -> tasks.getOrNull(index)?.let(::showActions) }
            .setNegativeButton(R.string.okay, null).create()
        lifecycleScope.launch {
            tasks = withContext(Dispatchers.IO) {
                val dao = Database.downloadDao()
                val audio = dao.getPendingAudio().associateBy { it.videoId }
                val partial = dao.getAll().filter { it.audioItem != null && it.finishedAudio == null }
                val incomplete = partial.associateBy { it.download.videoId }
                val ids = (audio.keys + incomplete.keys).distinct()
                dao.getPendingPlaylists().map { Task(it.playlistId, it.name, it.error,
                    type = it.playlistType, discovered = it.discovered) } + ids.map { id ->
                    Task(id, audio[id]?.title?.takeIf(String::isNotBlank) ?: incomplete[id]?.download?.title.orEmpty(),
                        audio[id]?.error.orEmpty(), itemId = incomplete[id]?.audioItem?.id)
                }
            }
            if (!isAdded || !result.isShowing) return@launch
            val labels = tasks.map {
                val name = it.title.ifBlank { it.id }
                val status = it.error.ifBlank { getString(R.string.download_pending) }
                if (it.type == null) "$name\n$status"
                else "$name · ${it.discovered}\n$status"
            }.toTypedArray()
            adapter.clear()
            adapter.addAll(if (labels.isEmpty()) listOf(getString(R.string.emptyList)) else labels.toList())
        }
        return result
    }

    private fun showActions(task: Task) {
        val appContext = requireContext().applicationContext
        val builder = MaterialAlertDialogBuilder(requireContext()).setTitle(task.title.ifBlank { task.id })
            .setMessage(task.error.ifBlank { getString(R.string.download_pending) })
            .setPositiveButton(R.string.resume) { _, _ ->
                if (task.type == null) DownloadHelper.downloadAudio(appContext, task.id)
                else PlaylistType.entries.find { it.name == task.type }?.let {
                    DownloadHelper.downloadPlaylistAudio(appContext, task.id, task.title, it)
                }
            }
            .setNeutralButton(if (task.itemId == null) R.string.cancel_download_task else R.string.pause) { _, _ ->
                // Use an application scope so dismissing the dialog cannot cancel the database write.
                kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                    if (task.type != null) Database.downloadDao().removePendingPlaylist(task.id, task.type)
                    else {
                        Database.downloadDao().removePendingAudio(task.id)
                        task.itemId?.let {
                            DownloadHelper.requestForegroundDownload(appContext,
                                Intent(appContext, DownloadService::class.java)
                                    .setAction(com.github.libretube.receivers.NotificationReceiver.ACTION_DOWNLOAD_PAUSE).putExtra("id", it))
                        }
                    }
                    appContext.toastFromMainDispatcher(R.string.download_task_cancelled)
                }
            }.setNegativeButton(R.string.okay, null)
        if (task.type != null && task.error.isNotBlank()) {
            builder.setNegativeButton(R.string.download_rescan) { _, _ ->
                kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                    val dao = Database.downloadDao()
                    dao.getPendingPlaylist(task.id, task.type)?.let {
                        dao.updatePendingPlaylist(it.copy(nextPage = null, discovered = 0, error = ""))
                    }
                    PlaylistType.entries.find { it.name == task.type }?.let {
                        DownloadHelper.downloadPlaylistAudio(appContext, task.id, task.title, it)
                    }
                }
            }
        }
        builder.show()
    }
}
