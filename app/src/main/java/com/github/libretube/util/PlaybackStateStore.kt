package com.github.libretube.util

import android.content.Context
import androidx.core.content.edit
import com.github.libretube.api.JsonHelper
import com.github.libretube.api.obj.StreamItem
import kotlinx.serialization.Serializable

object PlaybackStateStore {
    @Serializable
    data class State(
        val trackId: String,
        val positionMs: Long,
        val localOnly: Boolean,
        val queueName: String?,
        val tracks: List<StreamItem>,
        val playlistId: String? = null,
        val playlistNextPage: String? = null,
    )

    private fun preferences(context: Context) =
        context.getSharedPreferences("libreplayer_playback", Context.MODE_PRIVATE)

    fun save(context: Context, state: State) {
        preferences(context).edit { putString("queue", JsonHelper.json.encodeToString(State.serializer(), state)) }
    }

    fun load(context: Context): State? = preferences(context).getString("queue", null)?.let {
        runCatching { JsonHelper.json.decodeFromString(State.serializer(), it) }.getOrNull()
    }

    fun clear(context: Context) {
        preferences(context).edit { remove("queue") }
    }
}
