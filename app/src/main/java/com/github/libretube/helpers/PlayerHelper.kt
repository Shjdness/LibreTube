package com.github.libretube.helpers

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import android.util.Log
import androidx.annotation.OptIn
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import com.github.libretube.LibreTubeApp
import com.github.libretube.R
import com.github.libretube.api.obj.ChapterSegment
import com.github.libretube.api.obj.Streams
import com.github.libretube.api.obj.WatchHistoryEntryMetadata
import com.github.libretube.constants.PreferenceKeys
import com.github.libretube.db.DatabaseHelper
import com.github.libretube.enums.PlayerEvent
import com.github.libretube.enums.menuoption.RepeatModeBottomSheetItem
import com.github.libretube.extensions.TAG
import com.github.libretube.extensions.seekBy
import com.github.libretube.extensions.togglePlayPauseState
import com.github.libretube.repo.UserDataRepositoryHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.time.Clock

object PlayerHelper {
    private const val ACTION_MEDIA_CONTROL = "media_control"
    const val CONTROL_TYPE = "control_type"
    private const val MINIMUM_BUFFER_DURATION = 1000 * 10

    /**
     * Maximum playback speed supported by ExoPlayer
     */
    const val MAXIMUM_PLAYBACK_SPEED = 8f

    val repeatModes: List<RepeatModeBottomSheetItem> = RepeatModeBottomSheetItem.entries

    /**
     * Create a base64 encoded DASH stream manifest
     */
    fun createDashSource(streams: Streams): Uri {
        val manifest = DashHelper.createManifest(streams)

        // encode to base64
        val encoded = Base64.encodeToString(manifest.toByteArray(), Base64.DEFAULT)
        return "data:application/dash+xml;charset=utf-8;base64,$encoded".toUri()
    }

    private val watchPositionsPref: String
        get() = PreferenceHelper.getString(
            PreferenceKeys.WATCH_POSITIONS,
            "always"
        )

    val watchPositionsAudio: Boolean
        get() = watchPositionsPref in listOf("always", "audio")

    val watchPositionsAny: Boolean
        get() = watchPositionsAudio

    val watchHistoryEnabled: Boolean
        get() = PreferenceHelper.getBoolean(
            PreferenceKeys.WATCH_HISTORY_TOGGLE,
            true
        )

    private val bufferingGoal: Int
        get() = PreferenceHelper.getString(
            PreferenceKeys.BUFFERING_GOAL,
            "50"
        ).toInt() * 1000

    var autoPlayEnabled: Boolean
        get() = PreferenceHelper.getBoolean(
            PreferenceKeys.AUTOPLAY,
            true
        )
        set(value) {
            PreferenceHelper.putBoolean(PreferenceKeys.AUTOPLAY, value)
        }

    val seekIncrement: Long
        get() = PreferenceHelper.getString(
            PreferenceKeys.SEEK_INCREMENT,
            "10.0"
        ).toFloat()
            .roundToInt()
            .toLong() * 1000

    private val defaultPlaybackSpeed: Float
        get() = PreferenceHelper.getString(
            PreferenceKeys.PLAYBACK_SPEED,
            "1"
        ).replace("F", "").toFloat()

    val swipeGestureEnabled: Boolean
        get() = PreferenceHelper.getBoolean(
            PreferenceKeys.PLAYER_SWIPE_CONTROLS,
            true
        )

    private val skipSilence: Boolean
        get() = PreferenceHelper.getBoolean(
            PreferenceKeys.SKIP_SILENCE,
            false
        )

    val playAutomatically: Boolean
        get() = PreferenceHelper.getBoolean(
            PreferenceKeys.PLAY_AUTOMATICALLY,
            true
        )

    val localRYD: Boolean
        get() = PreferenceHelper.getBoolean(
            PreferenceKeys.LOCAL_RYD,
            true
        )

    var repeatMode: Int
        get() = PreferenceHelper.getInt(PreferenceKeys.REPEAT_MODE, Player.REPEAT_MODE_OFF)
        set(value) {
            PreferenceHelper.putInt(PreferenceKeys.REPEAT_MODE, value)
        }

    fun getIntentActionName(context: Context): String {
        return context.packageName + "." + ACTION_MEDIA_CONTROL
    }

    /**
     * Create a basic player, that is used for all types of playback situations inside the app
     */
    @OptIn(androidx.media3.common.util.UnstableApi::class)
    fun createPlayer(context: Context, trackSelector: DefaultTrackSelector): ExoPlayer {
        val dataSourceFactory = DefaultDataSource.Factory(context)
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        return ExoPlayer.Builder(context)
            .setUsePlatformDiagnostics(false)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setTrackSelector(trackSelector)
            .setHandleAudioBecomingNoisy(true)
            .setLoadControl(getLoadControl())
            .setAudioAttributes(audioAttributes, true)
            .build()
            .apply {
                loadPlaybackParams()
            }
    }

    /**
     * Get the load controls for the player (buffering, etc)
     */
    @OptIn(androidx.media3.common.util.UnstableApi::class)
    fun getLoadControl(): LoadControl {
        return DefaultLoadControl.Builder()
            // cache the last three minutes
            .setBackBuffer(1000 * 60 * 3, true)
            .setBufferDurationsMs(
                MINIMUM_BUFFER_DURATION,
                max(bufferingGoal, MINIMUM_BUFFER_DURATION),
                DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS,
                DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS
            )
            .build()
    }

    /**
     * Load playback parameters such as speed and skip silence
     */
    @OptIn(androidx.media3.common.util.UnstableApi::class)
    fun ExoPlayer.loadPlaybackParams(): ExoPlayer {
        skipSilenceEnabled = skipSilence

        playbackParameters = PlaybackParameters(defaultPlaybackSpeed, 1.0f)
        return this
    }

    /**
     * Get the name of the currently played chapter
     */
    fun getCurrentChapterIndex(currentPositionMs: Long, chapters: List<ChapterSegment>): Int? {
        val currentPositionSeconds = currentPositionMs / 1000
        return chapters
            .sortedBy { it.start }
            .indexOfLast { currentPositionSeconds >= it.start }
            .takeIf { it >= 0 }
            ?.takeIf { index ->
                val chapter = chapters[index]
                // remove the video highlight if it's already longer ago than [ChapterSegment.HIGHLIGHT_LENGTH],
                val isWithinMaxHighlightDuration =
                    (currentPositionSeconds - chapter.start) < ChapterSegment.HIGHLIGHT_LENGTH
                chapter.highlightDrawable == null || isWithinMaxHighlightDuration
            }
    }

    /**
     * Get the track type string resource corresponding to ExoPlayer role flags used for audio
     * track types.
     *
     * If the role flags doesn't have any role flags used for audio track types, the string
     * resource `unknown_audio_track_type` is returned.
     *
     * @param context   a context to get the string resources used to build the audio track type
     * @param roleFlags the ExoPlayer role flags from which the audio track type will be returned
     * @return the track type string resource corresponding to an ExoPlayer role flag or the
     * `unknown_audio_track_type` one if no role flags corresponding to the ones used for audio
     * track types is set
     */
    private fun getDisplayAudioTrackTypeFromFormat(
        context: Context,
        @C.RoleFlags roleFlags: Int
    ): String {
        // These role flags should not be set together, so the first role only take into account
        // flag which matches
        return when {
            // If the flag ROLE_FLAG_DESCRIBES_VIDEO is set, return the descriptive_audio_track
            // string resource
            roleFlags and C.ROLE_FLAG_DESCRIBES_VIDEO == C.ROLE_FLAG_DESCRIBES_VIDEO ->
                context.getString(R.string.descriptive_audio_track)

            // If the flag ROLE_FLAG_DESCRIBES_VIDEO is set, return the dubbed_audio_track
            // string resource
            roleFlags and C.ROLE_FLAG_DUB == C.ROLE_FLAG_DUB ->
                context.getString(R.string.dubbed_audio_track)

            // If the flag ROLE_FLAG_DESCRIBES_VIDEO is set, return the original_or_main_audio_track
            // string resource
            roleFlags and C.ROLE_FLAG_MAIN == C.ROLE_FLAG_MAIN ->
                context.getString(R.string.original_or_main_audio_track)

            // Return the unknown_audio_track_type string resource for any other value
            else -> context.getString(R.string.unknown_audio_track_type)
        }
    }

    /**
     * Get an audio track name from an audio format, using its language tag and its role flags.
     *
     * If the given language is `null`, the string resource `unknown_audio_language` is used
     * instead and when the given role flags have no track type value used by the app, the string
     * resource `unknown_audio_track_type` is used instead.
     *
     * @param context                   a context to get the string resources used to build the
     *                                  audio track name
     * @param audioLanguageAndRoleFlags a pair of an audio format language tag and role flags from
     *                                  which the audio track name will be built
     * @return an audio track name of an audio format language and role flags, localized according
     * to the language preferences of the user
     */
    fun getAudioTrackNameFromFormat(
        context: Context,
        audioLanguageAndRoleFlags: Pair<String?, @C.RoleFlags Int>,
    ): String {
        val audioLanguage = audioLanguageAndRoleFlags.first
        return context.getString(R.string.audio_track_format)
            .format(
                if (audioLanguage == null) {
                    context.getString(R.string.unknown_audio_language)
                } else {
                    Locale.forLanguageTag(audioLanguage)
                        .getDisplayName(Locale.getDefault())
                        .ifEmpty { context.getString(R.string.unknown_audio_language) }
                },
                getDisplayAudioTrackTypeFromFormat(context, audioLanguageAndRoleFlags.second)
            )
    }

    /**
     * Get audio languages with their role flags of supported formats from ExoPlayer track groups
     * and only the selected ones if requested.
     *
     * Duplicate audio languages with their role flags are removed.
     *
     * @param groups                 the list of [Tracks.Group]s of the current tracks played by the player
     * @param keepOnlySelectedTracks whether to get only the selected audio languages with their
     *                               role flags among the supported ones
     * @return a list of distinct audio languages with their role flags from the supported formats
     * of the given track groups and only the selected ones if requested
     */
    fun getAudioLanguagesAndRoleFlagsFromTrackGroups(
        groups: List<Tracks.Group>,
        keepOnlySelectedTracks: Boolean
    ): List<Pair<String?, @C.RoleFlags Int>> {
        // Filter unsupported tracks and keep only selected tracks if requested
        // Use a lambda expression to avoid checking on each audio format if we keep only selected
        // tracks or not
        val trackFilter = if (keepOnlySelectedTracks) {
            { group: Tracks.Group, trackIndex: Int ->
                group.isTrackSupported(trackIndex) && group.isTrackSelected(
                    trackIndex
                )
            }
        } else {
            { group: Tracks.Group, trackIndex: Int -> group.isTrackSupported(trackIndex) }
        }

        return groups.filter {
            it.type == C.TRACK_TYPE_AUDIO
        }.flatMap { group ->
            (0 until group.length).filter {
                trackFilter(group, it)
            }.map { group.getTrackFormat(it) }
        }.map { format ->
            format.language to format.roleFlags
        }.distinct()
    }

    /**
     * Check whether the given flag is set in the given bitfield.
     *
     * @param bitField a bitfield
     * @param flag     a flag to check its presence in the given bitfield
     * @return whether the given flag is set in the given bitfield
     */
    private fun isFlagSet(bitField: Int, flag: Int) = bitField and flag == flag

    /**
     * Check whether the given ExoPlayer role flags contain at least one flag used for audio
     * track types.
     *
     * ExoPlayer role flags currently used for audio track types are [C.ROLE_FLAG_DESCRIBES_VIDEO],
     * [C.ROLE_FLAG_DUB], [C.ROLE_FLAG_MAIN] and [C.ROLE_FLAG_ALTERNATE].
     *
     * @param roleFlags the ExoPlayer role flags to check, an int representing a bitfield
     * @return whether the provided ExoPlayer flags contain a flag used for audio track types
     */
    fun haveAudioTrackRoleFlagSet(@C.RoleFlags roleFlags: Int): Boolean {
        return isFlagSet(roleFlags, C.ROLE_FLAG_DESCRIBES_VIDEO) ||
                isFlagSet(roleFlags, C.ROLE_FLAG_DUB) ||
                isFlagSet(roleFlags, C.ROLE_FLAG_MAIN) ||
                isFlagSet(roleFlags, C.ROLE_FLAG_ALTERNATE)
    }

    /**
     * Get the full audio role flags of an audio track.
     *
     * Full role flags are the existing flags parsed by ExoPlayer and the flags coming from the
     * audio track type parsed from the `acont` property value of the stream manifest URL.
     *
     * The following table describes what value is parsed
     *
     * | `acont` value  | Role flag added from [ExoPlayer track role flags][C.RoleFlags] |
     * | ------------- | ------------- |
     * | `dubbed`  | [C.ROLE_FLAG_DUB]  |
     * | `descriptive`  | [C.ROLE_FLAG_DESCRIBES_VIDEO]  |
     * | `original`  | [C.ROLE_FLAG_MAIN]  |
     * | everything else  | [C.ROLE_FLAG_ALTERNATE]  |
     *
     * @param roleFlags the current role flags of the audio track
     * @param acontValue the value of the `acont` property
     * @return the full audio role flags of the audio track like described above
     */
    fun getFullAudioRoleFlags(roleFlags: Int, acontValue: String): Int {
        val acontRoleFlags = when (acontValue.lowercase()) {
            "dubbed" -> C.ROLE_FLAG_DUB
            "descriptive" -> C.ROLE_FLAG_DESCRIBES_VIDEO
            "original" -> C.ROLE_FLAG_MAIN
            // Original audio tracks without other audio track should not have the `acont` property
            // nor the `xtags` one, so the the track should be not set as the main one
            // The alternate role flag should be the most relevant flag in this case
            else -> C.ROLE_FLAG_ALTERNATE
        }

        // Add this flag to the existing ones (if it has been not already added) and return the
        // result of this operation
        return roleFlags or acontRoleFlags
    }

    @OptIn(androidx.media3.common.util.UnstableApi::class)
    fun getPlayPauseActionIcon(player: Player) = when {
        player.isPlaying -> R.drawable.ic_pause
        player.playbackState == Player.STATE_ENDED -> R.drawable.ic_restart
        else -> R.drawable.ic_play
    }

    fun saveWatchPosition(player: Player, videoId: String) {
        if (player.duration == C.TIME_UNSET || player.currentPosition in listOf(0L, C.TIME_UNSET)) {
            return
        }

        val watchHistoryEntry = WatchHistoryEntryMetadata(
            videoId = videoId,
            finished = DatabaseHelper.isVideoWatched(
                player.currentPosition,
                player.duration.div(1000)
            ),
            addedDate = Clock.System.now().toEpochMilliseconds(),
            positionMillis = player.currentPosition
        )
        CoroutineScope(Dispatchers.IO).launch {
            try {
                UserDataRepositoryHelper.userDataRepository
                    .updateWatchHistoryEntry(watchHistoryEntry)
            } catch (e: Exception) {
                Log.e(TAG(), e.toString())
            }
        }
    }

    /**
     * Handle basic [PlayerEvent]'s that can be handled by the player itself without context
     */
    fun handlePlayerAction(player: Player, playerEvent: PlayerEvent): Boolean {
        return when (playerEvent) {
            PlayerEvent.PlayPause -> {
                player.togglePlayPauseState()
                true
            }

            PlayerEvent.Forward -> {
                player.seekBy(seekIncrement)
                true
            }

            PlayerEvent.Rewind -> {
                player.seekBy(-seekIncrement)
                true
            }

            else -> false
        }
    }
}
