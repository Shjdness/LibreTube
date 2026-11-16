package com.github.libretube.ui.activities

import android.content.Intent
import android.view.MenuItem
import com.github.libretube.R
import com.github.libretube.ui.base.BaseActivity
import com.github.libretube.ui.fragments.AudioPlayerFragment

abstract class AbstractPlayerHostActivity: BaseActivity() {
    abstract fun minimizePlayerContainerLayout()
    abstract fun maximizePlayerContainerLayout()
    abstract fun setPlayerContainerProgress(progress: Float)

    abstract fun clearSearchViewFocus(): Boolean


    fun runOnAudioPlayerFragment(action: AudioPlayerFragment.() -> Boolean): Boolean {
        return supportFragmentManager.fragments.filterIsInstance<AudioPlayerFragment>()
            .firstOrNull()
            ?.let(action)
            ?: false
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        return when (item.itemId) {
            R.id.action_settings -> {
                val settingsIntent = Intent(this, SettingsActivity::class.java)
                startActivity(settingsIntent)
                true
            }

            else -> super.onOptionsItemSelected(item)
        }
    }
}