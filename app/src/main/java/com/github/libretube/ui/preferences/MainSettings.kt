package com.github.libretube.ui.preferences

import android.os.Bundle
import androidx.core.text.parseAsHtml
import android.text.TextUtils
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.navigation.fragment.findNavController
import androidx.preference.Preference
import com.github.libretube.R
import com.github.libretube.ui.base.BasePreferenceFragment

class MainSettings : BasePreferenceFragment() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.settings, rootKey)

        findPreference<Preference>("license_and_source")?.setOnPreferenceClickListener {
            val license = requireContext().assets.open("gpl3.html").bufferedReader().use { it.readText() }
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.license_and_source)
                .setMessage(TextUtils.concat(getString(R.string.fork_license_notice), "\n\n", license.parseAsHtml()))
                .setPositiveButton(R.string.okay, null)
                .show()
            true
        }

        listOf(
            "general" to R.id.action_global_generalSettings,
            "account" to R.id.action_global_instanceSettings,
            "appearance" to R.id.action_global_appearanceSettings,
            "player" to R.id.action_global_playerSettings,
            "audio_video" to R.id.action_global_audioVideoSettings,
            "backup_restore" to R.id.action_global_backupRestoreSettings
        ).forEach { (preferenceKey, actionId) ->
            findPreference<Preference>(preferenceKey)?.setOnPreferenceClickListener { _ ->
                findNavController().navigate(actionId)
                true
            }
        }
    }
}
