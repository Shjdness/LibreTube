package com.github.libretube.ui.extensions

import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.github.libretube.R
import com.github.libretube.databinding.AudioLibraryControlsBinding
import com.github.libretube.db.DatabaseHolder.Database
import com.github.libretube.helpers.PreferenceHelper
import com.github.libretube.helpers.BackgroundHelper
import com.github.libretube.repo.AudioLibraryRepository
import com.github.libretube.services.AudioPlaybackService
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
fun Fragment.bindAudioLibraryControls(controls: AudioLibraryControlsBinding,
    onGroup: (Set<String>?) -> Unit, onModeChanged: () -> Unit = {}) {
    var updating = false
    var refreshJob: Job? = null
    fun refresh() {
        refreshJob?.cancel()
        refreshJob = viewLifecycleOwner.lifecycleScope.launch {
            val group = AudioLibraryRepository.selectedGroup()
            updating = true
            controls.offlineOnly.isChecked = AudioLibraryRepository.offlineOnly
            controls.groupFilter.text = group?.first ?: getString(R.string.audio_all_groups)
            updating = false
            onGroup(group?.second)
            onModeChanged()
        }
    }
    controls.offlineOnly.setOnCheckedChangeListener { _, checked ->
        if (!updating) {
            PreferenceHelper.putBoolean(AudioLibraryRepository.OFFLINE_ONLY, checked)
            val appContext = requireContext().applicationContext
            if (AudioPlaybackService.active) BackgroundHelper.connect(appContext) { controller ->
                val command = controller.sendCustomCommand(AudioPlaybackService.runPlayerActionCommand,
                    androidx.core.os.bundleOf("offline_mode_changed" to true))
                command.addListener({ controller.release() },
                    androidx.core.content.ContextCompat.getMainExecutor(appContext))
            }
        }
    }
    controls.groupFilter.setOnClickListener {
        viewLifecycleOwner.lifecycleScope.launch {
            val groups = withContext(Dispatchers.IO) { Database.subscriptionGroupsDao().getAll() }
            val names = listOf("") + groups.map { it.name }
            val labels = listOf(getString(R.string.audio_all_groups)) + groups.map { it.name }
            MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.audio_group_filter)
                .setSingleChoiceItems(labels.toTypedArray(),
                    names.indexOf(PreferenceHelper.getString(AudioLibraryRepository.GROUP_FILTER, "")).coerceAtLeast(0)) { dialog, index ->
                    PreferenceHelper.putString(AudioLibraryRepository.GROUP_FILTER, names[index])
                    dialog.dismiss()
                }.setNegativeButton(R.string.cancel, null).show()
        }
    }
    val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == AudioLibraryRepository.GROUP_FILTER || key == AudioLibraryRepository.OFFLINE_ONLY) refresh()
    }
    PreferenceHelper.settings.registerOnSharedPreferenceChangeListener(listener)
    viewLifecycleOwner.lifecycle.addObserver(object : DefaultLifecycleObserver {
        override fun onDestroy(owner: LifecycleOwner) {
            PreferenceHelper.settings.unregisterOnSharedPreferenceChangeListener(listener)
            refreshJob?.cancel()
        }
    })
    viewLifecycleOwner.lifecycleScope.launch {
        Database.subscriptionGroupsDao().observeAll().collect { refresh() }
    }
    refresh()
}
