package com.github.libretube.ui.preferences

import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import com.github.libretube.R
import com.github.libretube.constants.PreferenceKeys
import com.github.libretube.db.DatabaseHolder.Database
import com.github.libretube.ui.base.BasePreferenceFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class DataManagementSettings : BasePreferenceFragment() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.account_settings, rootKey)
        findPreference<Preference>(PreferenceKeys.SEARCH_HISTORY_TOGGLE)?.setOnPreferenceChangeListener { _, enabled ->
            if (enabled == false) lifecycleScope.launch(Dispatchers.IO) { Database.searchHistoryDao().deleteAll() }
            true
        }
    }
}
