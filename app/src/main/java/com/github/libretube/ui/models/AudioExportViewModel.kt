package com.github.libretube.ui.models

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.github.libretube.R
import com.github.libretube.helpers.AudioFileExport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AudioExportViewModel(application: Application, private val saved: SavedStateHandle) : AndroidViewModel(application) {
    sealed interface State {
        data object Preparing : State
        data class Ready(val source: AudioFileExport.Source) : State
        data class Running(val percent: Int, val checking: Boolean = false) : State
        data class Done(val result: AudioFileExport.Result) : State
        data class Error(val message: Int, val residue: Boolean = false) : State
    }
    private val changes = MutableStateFlow<State>(State.Preparing)
    val state = changes.asStateFlow()
    private var task: Job? = null
    val sharing: Boolean = saved["share"] ?: false
    var autoShared: Boolean
        get() = saved["autoShared"] ?: false
        set(value) { saved["autoShared"] = value }
    private val context get() = getApplication<Application>()

    init {
        val uri = saved.get<String>("resultUri")
        if (uri != null) {
            changes.value = State.Done(AudioFileExport.Result(Uri.parse(uri), saved["resultName"] ?: "",
                saved["resultMime"] ?: "audio/*", saved["verified"] ?: false))
        } else if (saved.get<Boolean>("running") == true) {
            saved["running"] = false
            changes.value = State.Error(R.string.audio_export_interrupted)
        } else task = viewModelScope.launch {
            try {
                val source = AudioFileExport.source(saved["trackId"] ?: "")
                changes.value = State.Ready(source)
                val selected = saved.get<String>("chosenUri")
                if (selected != null) start(source, Uri.parse(selected))
                else if (sharing) start(source, null)
            } catch (e: CancellationException) {
                val cleaned = abandonChosen()
                changes.value = State.Error(R.string.audio_export_cancelled, !cleaned)
                throw e
            } catch (e: Exception) {
                val cleaned = abandonChosen()
                changes.value = State.Error((e as? AudioFileExport.Failure)?.reason ?: R.string.audio_export_unavailable, !cleaned)
            }
        }
    }

    private suspend fun abandonChosen(): Boolean {
        val value = saved.remove<String>("chosenUri") ?: return true
        return kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
            runCatching { AudioFileExport.discardEmpty(context, Uri.parse(value)) }.getOrDefault(false)
        }
    }

    fun selectDestination(uri: Uri) {
        val current = changes.value
        if (current is State.Running || current is State.Done) return
        saved["chosenUri"] = uri.toString()
        if (current is State.Ready) start(current.source, uri)
        else if (current is State.Error) task = viewModelScope.launch {
            val cleaned = abandonChosen()
            changes.value = current.copy(residue = !cleaned)
        }
    }

    fun start(source: AudioFileExport.Source, uri: Uri?) {
        if (changes.value !is State.Ready) return
        changes.value = State.Running(0)
        saved["running"] = true
        saved.remove<String>("chosenUri")
        task = viewModelScope.launch {
            try {
                val progress: (Long) -> Unit = { count ->
                    changes.value = State.Running(if (count < 0) 100 else (count * 100 / source.size).toInt(), count < 0)
                }
                val result = if (uri != null) AudioFileExport.save(context, source, uri, progress)
                    else AudioFileExport.share(context, source, progress)
                saved["resultName"] = result.name
                saved["resultMime"] = result.mime
                saved["verified"] = result.verified
                saved["resultUri"] = result.uri.toString()
                changes.value = State.Done(result)
            } catch (e: CancellationException) {
                changes.value = State.Error(R.string.audio_export_cancelled, uri != null && AudioFileExport.isPending(context, uri))
                throw e
            } catch (e: Exception) {
                changes.value = State.Error((e as? AudioFileExport.Failure)?.reason ?: R.string.audio_export_write_error,
                    (e as? AudioFileExport.Failure)?.residue == true)
            } finally { saved["running"] = false }
        }
    }
    fun rejectTarget() { changes.value = State.Error(R.string.audio_export_permission) }
    fun cancel() { task?.cancel() }
}
