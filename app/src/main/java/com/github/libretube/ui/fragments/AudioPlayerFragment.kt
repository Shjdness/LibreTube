package com.github.libretube.ui.fragments

import android.annotation.SuppressLint
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.DateUtils
import android.view.View
import androidx.activity.BackEventCompat
import androidx.activity.OnBackPressedCallback
import androidx.constraintlayout.motion.widget.MotionLayout
import androidx.constraintlayout.motion.widget.TransitionAdapter
import androidx.core.math.MathUtils.clamp
import androidx.core.os.bundleOf
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.commit
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import com.github.libretube.R
import com.github.libretube.api.JsonHelper
import com.github.libretube.api.obj.ChapterSegment
import com.github.libretube.constants.IntentData
import com.github.libretube.databinding.FragmentAudioPlayerBinding
import com.github.libretube.enums.PlayerCommand
import com.github.libretube.extensions.navigateVideo
import com.github.libretube.extensions.parcelable
import com.github.libretube.extensions.normalize
import com.github.libretube.extensions.seekBy
import com.github.libretube.extensions.toID
import com.github.libretube.extensions.togglePlayPauseState
import com.github.libretube.extensions.updateIfChanged
import com.github.libretube.helpers.AudioHelper
import com.github.libretube.helpers.BackgroundHelper
import com.github.libretube.helpers.ClipboardHelper
import com.github.libretube.helpers.ImageHelper
import com.github.libretube.helpers.NavigationHelper
import com.github.libretube.helpers.NetworkHelper
import com.github.libretube.helpers.PlayerHelper
import com.github.libretube.helpers.ThemeHelper
import com.github.libretube.parcelable.PlayerData
import com.github.libretube.services.AudioPlaybackService
import com.github.libretube.ui.activities.AbstractPlayerHostActivity
import com.github.libretube.ui.extensions.getSystemInsets
import com.github.libretube.ui.extensions.setOnBackPressed
import com.github.libretube.ui.interfaces.AudioPlayerOptions
import com.github.libretube.ui.listeners.AudioPlayerThumbnailListener
import com.github.libretube.ui.models.ChaptersViewModel
import com.github.libretube.ui.models.CommonPlayerViewModel
import com.github.libretube.ui.sheets.ChaptersBottomSheet
import com.github.libretube.ui.sheets.PlaybackOptionsSheet
import com.github.libretube.ui.sheets.PlayingQueueSheet
import com.github.libretube.ui.sheets.SleepTimerSheet
import com.github.libretube.ui.sheets.VideoOptionsBottomSheet
import com.github.libretube.util.DataSaverMode
import com.github.libretube.util.DeArrowUtil
import kotlinx.coroutines.Job
import com.github.libretube.util.PlayingQueue
import kotlinx.coroutines.launch
import kotlin.math.absoluteValue

@UnstableApi
class AudioPlayerFragment : Fragment(R.layout.fragment_audio_player), AudioPlayerOptions {
    private var _binding: FragmentAudioPlayerBinding? = null
    val binding get() = _binding!!

    private lateinit var audioHelper: AudioHelper
    private val activity get() = context as AbstractPlayerHostActivity
    private val viewModel: CommonPlayerViewModel by activityViewModels()
    private val chaptersModel: ChaptersViewModel by activityViewModels()

    // for the transition
    private var transitionStartId = 0
    private var transitionEndId = 0

    private val handler = Handler(Looper.getMainLooper())
    private val refreshPosition = Runnable { updateSeekBar() }
    private var isPaused = !PlayerHelper.playAutomatically

    private var playerController: MediaController? = null
    private var artworkJob: Job? = null
    private var brandingJob: Job? = null
    private var brandedTitle: String? = null

    @SuppressLint("ClickableViewAccessibility")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _binding = FragmentAudioPlayerBinding.bind(view)
        super.onViewCreated(view, savedInstanceState)
        audioHelper = AudioHelper(requireContext())

        // manually apply additional padding for edge-to-edge compatibility
        activity.getSystemInsets()?.let { systemBars ->
            with(binding.audioPlayerMain) {
                setPadding(
                    paddingLeft,
                    paddingTop + systemBars.top,
                    paddingRight,
                    paddingBottom + systemBars.bottom
                )
            }
        }

        initializeTransitionLayout()
        val owner = viewLifecycleOwner
        BackgroundHelper.connect(requireContext()) { controller ->
            if (_binding == null || !owner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.INITIALIZED)) {
                controller.release()
            } else {
                playerController = controller
                handleServiceConnection()
                requireArguments().parcelable<PlayerData>(IntentData.playerData)?.let { request ->
                    requireArguments().remove(IntentData.playerData)
                    startQueue(request)
                }
            }
        }

        // select the title TV in order for it to automatically scroll
        binding.title.isSelected = true
        binding.uploader.isSelected = true

        binding.title.setOnLongClickListener {
            ClipboardHelper.save(requireContext(), text = binding.title.text.toString())
            true
        }

        binding.minimizePlayer.setOnClickListener {
            activity.minimizePlayerContainerLayout()
            binding.playerMotionLayout.transitionToEnd()
        }

        binding.autoPlay.isChecked = PlayerHelper.autoPlayEnabled
        binding.autoPlay.setOnCheckedChangeListener { _, isChecked ->
            PlayerHelper.autoPlayEnabled = isChecked
        }

        binding.prev.setOnClickListener {
            skipTrack(PlayerCommand.PLAY_PREVIOUS_VIDEO)
        }

        binding.next.setOnClickListener {
            skipTrack(PlayerCommand.PLAY_NEXT_VIDEO)
        }

        binding.rewindBTN.setOnClickListener {
            playerController?.seekBy(-PlayerHelper.seekIncrement)
        }
        binding.forwardBTN.setOnClickListener {
            playerController?.seekBy(PlayerHelper.seekIncrement)
        }

        childFragmentManager.setFragmentResultListener(
            PlayingQueueSheet.PLAYING_QUEUE_REQUEST_KEY,
            viewLifecycleOwner
        ) { _, args ->
            playerController?.navigateVideo(
                args.getString(IntentData.videoId) ?: return@setFragmentResultListener
            )
        }
        binding.openQueue.setOnClickListener {
            PlayingQueueSheet().show(childFragmentManager)
        }

        binding.playbackOptions.setOnClickListener {
            playerController?.let {
                PlaybackOptionsSheet(it)
                    .show(childFragmentManager)
            }
        }

        binding.trackOptions.setOnClickListener { onLongTap() }
        binding.sleepTimer.setOnClickListener {
            SleepTimerSheet().show(childFragmentManager)
        }

        childFragmentManager.setFragmentResultListener(
            ChaptersBottomSheet.SEEK_TO_POSITION_REQUEST_KEY,
            viewLifecycleOwner
        ) { _, bundle ->
            playerController?.seekTo(bundle.getLong(IntentData.currentPosition))
        }

        chaptersModel.chaptersLiveData.observe(viewLifecycleOwner) { chapters ->
            _binding?.openChapters?.isVisible = !chapters.isNullOrEmpty()
        }

        binding.openChapters.setOnClickListener {
            ChaptersBottomSheet()
                .apply {
                    arguments = bundleOf(
                        IntentData.duration to playerController?.duration?.div(1000)
                    )
                }
                .show(childFragmentManager)
        }

        binding.miniPlayerClose.setOnClickListener {
            killFragment(true)
        }

        val listener = AudioPlayerThumbnailListener(requireContext(), this)
        binding.thumbnail.setOnTouchListener(listener)

        binding.playPause.setOnClickListener {
            playerController?.togglePlayPauseState()
        }

        binding.miniPlayerPause.setOnClickListener {
            playerController?.togglePlayPauseState()
        }

        binding.showMore.setOnClickListener {
            onLongTap()
        }

        // update the currently shown volume
        binding.volumeProgressBar.let { bar ->
            bar.progress = (audioHelper.deviceVolume * bar.max).toInt().coerceIn(0, bar.max)
        }

        if (!PlayerHelper.playAutomatically) updatePlayPauseButton()

        updateChapterIndex()

        val onBackPressedCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                binding.audioPlayerContainer.isClickable = false
                binding.playerMotionLayout.transitionToEnd()
                activity.minimizePlayerContainerLayout()
                activity.requestOrientationChange()
            }

            override fun handleOnBackProgressed(backEvent: BackEventCompat) {
                binding.playerMotionLayout.progress = backEvent.progress
            }

            override fun handleOnBackCancelled() {
                binding.playerMotionLayout.transitionToStart()
            }
        }
        setOnBackPressed(onBackPressedCallback)

        binding.miniPlayerControls.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
            if (viewModel.isMiniPlayerVisible.value == true) activity.setMiniPlayerSpace(view.height)
        }
        viewModel.isMiniPlayerVisible.observe(viewLifecycleOwner) { isMiniPlayerVisible ->
            // re-add the callback on top of the back pressed dispatcher listeners stack,
            // so that it's the first one to become called while the full player is visible
            if (!isMiniPlayerVisible) {
                onBackPressedCallback.remove()
                setOnBackPressed(onBackPressedCallback)
            }

            // if the player is minimized, the fragment behind the player should handle the event
            onBackPressedCallback.isEnabled = isMiniPlayerVisible != true
            activity.setMiniPlayerSpace(if (isMiniPlayerVisible) binding.miniPlayerControls.height else 0)
        }
    }

    private fun killFragment(stopPlayer: Boolean) {
        viewModel.isMiniPlayerVisible.value = false

        if (stopPlayer) playerController?.sendCustomCommand(
            AudioPlaybackService.stopServiceCommand,
            Bundle.EMPTY
        )
        playerController?.release()
        playerController = null

        binding.playerMotionLayout.transitionToEnd()
        activity.supportFragmentManager.commit {
            remove(this@AudioPlayerFragment)
        }
    }

    fun startQueue(playerData: PlayerData): Boolean {
        val controller = playerController ?: return false
        controller.sendCustomCommand(
            AudioPlaybackService.startServiceCommand,
            bundleOf(IntentData.playerData to playerData)
        )
        return true
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun initializeTransitionLayout() {
        activity.setPlayerContainerProgress(0f)

        binding.playerMotionLayout.addTransitionListener(object : TransitionAdapter() {
            override fun onTransitionChange(
                motionLayout: MotionLayout?,
                startId: Int,
                endId: Int,
                progress: Float
            ) {
                activity.setPlayerContainerProgress(progress.absoluteValue)
                transitionEndId = endId
                transitionStartId = startId
            }

            override fun onTransitionCompleted(motionLayout: MotionLayout?, currentId: Int) {
                if (currentId == transitionEndId) {
                    viewModel.isMiniPlayerVisible.value = true
                    activity.minimizePlayerContainerLayout()
                } else if (currentId == transitionStartId) {
                    viewModel.isMiniPlayerVisible.value = false
                    activity.maximizePlayerContainerLayout()
                }
            }
        })

        if (arguments?.getBoolean(IntentData.minimizeByDefault, false) != true) {
            binding.playerMotionLayout.progress = 1f
            binding.playerMotionLayout.transitionToStart()
        } else {
            binding.playerMotionLayout.progress = 0f
            binding.playerMotionLayout.transitionToEnd()
        }
    }

    /**
     * Load the information from a new stream into the UI
     */
    private fun updateStreamInfo(metadata: MediaMetadata) {
        val binding = _binding ?: return

        artworkJob?.cancel()
        brandingJob?.cancel()
        brandedTitle = null
        updateQueueControls()
        updateTrackControls()

        binding.uploader.text = metadata.artist
        binding.uploader.setOnClickListener {
            val uploaderId = metadata.composer?.toString() ?: return@setOnClickListener
            NavigationHelper.navigateChannel(requireContext(), uploaderId)
        }

        showArtworkPlaceholder()
        val localAudio = metadata.extras?.getBoolean(IntentData.localAudio) == true
        metadata.artworkUri?.let { updateThumbnailAsync(it, localAudio) }
        val id = metadata.extras?.getString(IntentData.videoId)
        if (id != null && !localAudio && metadata.artworkUri?.scheme?.startsWith("http") == true &&
            NetworkHelper.isNetworkAvailable(requireContext()) && !DataSaverMode.isEnabled(requireContext())
        ) {
            brandingJob = viewLifecycleOwner.lifecycleScope.launch {
                val (title, thumbnail) = DeArrowUtil.deArrowVideoId(id) ?: return@launch
                if (playerController?.mediaMetadata?.extras?.getString(IntentData.videoId) != id) return@launch
                brandedTitle = title
                updateTrackControls()
                thumbnail?.let { updateThumbnailAsync(Uri.parse(it), localAudio = false) }
            }
        }
        initializeSeekBar()
    }

    private fun showArtworkPlaceholder() {
        val binding = _binding ?: return
        binding.thumbnail.setImageResource(R.drawable.ic_launcher_monochrome)
        binding.thumbnail.setColorFilter(ThemeHelper.getThemeColor(
            requireContext(), androidx.appcompat.R.attr.colorPrimary
        ))
        binding.thumbnail.isVisible = true
        binding.miniPlayerThumbnail.setImageResource(R.drawable.ic_launcher_monochrome)
    }

    private fun updateThumbnailAsync(thumbnailUri: Uri, localAudio: Boolean) {
        artworkJob?.cancel()
        val remote = thumbnailUri.scheme == "http" || thumbnailUri.scheme == "https"
        if (remote && (localAudio || DataSaverMode.isEnabled(requireContext()) ||
                !NetworkHelper.isNetworkAvailable(requireContext()))
        ) return

        artworkJob = viewLifecycleOwner.lifecycleScope.launch {
            val bitmap = ImageHelper.getImage(requireContext(), thumbnailUri) ?: return@launch
            val views = _binding ?: return@launch
            views.thumbnail.setColorFilter(Color.TRANSPARENT)
            views.thumbnail.setImageBitmap(bitmap)
            views.miniPlayerThumbnail.setImageBitmap(bitmap)
        }
    }

    private fun initializeSeekBar() {
        binding.timeBar.clearOnChangeListeners()
        binding.timeBar.addOnChangeListener { _, value, fromUser ->
            if (fromUser) playerController?.seekTo(value.toLong() * 1000)
        }
        updateSeekBar()
    }

    /**
     * Update the position, duration and text views belonging to the seek bar
     */
    private fun skipTrack(command: PlayerCommand) {
        playerController?.sendCustomCommand(
            AudioPlaybackService.runPlayerActionCommand,
            bundleOf(command.name to true)
        )
    }

    private fun updateQueueControls() {
        val views = _binding ?: return
        val id = playerController?.mediaMetadata?.extras?.getString(IntentData.videoId)
        views.prev.isEnabled = PlayingQueue.adjacentIds(id, reverse = true).isNotEmpty()
        views.next.isEnabled = PlayingQueue.adjacentIds(id).isNotEmpty()
    }

    private fun updateTrackControls() {
        val binding = _binding ?: return
        val controller = playerController ?: return
        val loading = !controller.isPlaying &&
            (controller.playlistMetadata.title != null ||
                (controller.playWhenReady && controller.playbackState == Player.STATE_BUFFERING))
        val trackTitle = brandedTitle ?: controller.mediaMetadata.title
        val pendingTitle = controller.playlistMetadata.title ?: trackTitle
        val error = controller.playlistMetadata.extras?.getString("audio_error")
        val title = when {
            controller.isPlaying -> trackTitle
            error != null -> if (trackTitle.isNullOrBlank()) error else "$trackTitle · $error"
            loading && pendingTitle != null -> {
                val status = when {
                    controller.playlistMetadata.title != null -> R.string.audio_resolving
                    controller.mediaMetadata.extras?.getBoolean(IntentData.localAudio) == true -> R.string.audio_reading_local
                    else -> R.string.audio_buffering
                }
                getString(status, pendingTitle)
            }
            else -> trackTitle
        }
        binding.progress.isVisible = loading
        if (binding.title.text != title) binding.title.text = title
        if (binding.miniPlayerTitle.text != title) binding.miniPlayerTitle.text = title
    }

    private fun updateSeekBar() {
        val binding = _binding ?: return
        handler.removeCallbacks(refreshPosition)
        val duration = playerController?.duration?.takeIf { it > 0 } ?: let {
            // if there's no duration available, clear everything
            binding.timeBar.value = 0f
            binding.duration.text = ""
            binding.currentPosition.text = ""
            handler.postDelayed(refreshPosition, 100)
            return
        }
        val currentPosition = playerController?.currentPosition?.toFloat() ?: 0f

        // set the text for the indicators
        binding.duration.text = DateUtils.formatElapsedTime(duration / 1000)
        binding.currentPosition.text = DateUtils.formatElapsedTime(
            (currentPosition / 1000).toLong()
        )

        // update the time bar current value and maximum value
        val end = (duration / 1000f).coerceAtLeast(1f)
        if (binding.timeBar.value > end) binding.timeBar.value = end
        binding.timeBar.valueTo = end
        binding.timeBar.value = clamp(
            currentPosition / 1000,
            binding.timeBar.valueFrom,
            binding.timeBar.valueTo
        )

        handler.postDelayed(refreshPosition, 200)
    }

    private fun updatePlayPauseButton() {
        playerController?.let {
            val binding = _binding ?: return

            val iconRes = PlayerHelper.getPlayPauseActionIcon(it)
            binding.playPause.setIconResource(iconRes)
            binding.miniPlayerPause.setImageResource(iconRes)
        }
    }

    private var queueControlsJob: Job? = null

    private fun handleServiceConnection() {
        queueControlsJob?.cancel()
        queueControlsJob = viewLifecycleOwner.lifecycleScope.launch {
            PlayingQueue.revision.collect { updateQueueControls() }
        }
        playerController?.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                super.onIsPlayingChanged(isPlaying)

                updatePlayPauseButton()
                updateTrackControls()
                isPaused = !isPlaying
            }

            override fun onPlaybackStateChanged(playbackState: Int) = updateTrackControls()

            override fun onPlaylistMetadataChanged(mediaMetadata: MediaMetadata) = updateTrackControls()

            override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                super.onMediaMetadataChanged(mediaMetadata)

                updateStreamInfo(mediaMetadata)
                // JSON-encode as work-around for https://github.com/androidx/media/issues/564
                val chapters: List<ChapterSegment>? =
                    mediaMetadata.extras?.getString(IntentData.chapters)?.let {
                        JsonHelper.json.decodeFromString(it)
                    }
                chaptersModel.chaptersLiveData.value = chapters
            }
        })
        playerController?.mediaMetadata?.let { updateStreamInfo(it) }
        // JSON-encode as work-around for https://github.com/androidx/media/issues/564
        chaptersModel.chaptersLiveData.value =
            playerController?.mediaMetadata?.extras?.getString(IntentData.chapters)?.let {
                JsonHelper.json.decodeFromString(it)
            }

        updatePlayPauseButton()
        initializeSeekBar()
    }

    override fun onDestroyView() {
        (context as? AbstractPlayerHostActivity)?.setMiniPlayerSpace(0)
        viewModel.isMiniPlayerVisible.value = false
        handler.removeCallbacksAndMessages(null)
        playerController?.release()
        playerController = null
        _binding = null
        super.onDestroyView()
    }

    override fun onSingleTap() {
        playerController?.togglePlayPauseState()
    }

    override fun onLongTap() {
        val current = PlayingQueue.getCurrent() ?: return
        VideoOptionsBottomSheet()
            .apply {
                arguments = bundleOf(IntentData.streamItem to current)
            }
            .show(childFragmentManager)
    }

    override fun onSwipe(distanceY: Float) {
        if (!PlayerHelper.swipeGestureEnabled) return

        binding.volumeControls.isVisible = true
        updateVolume(distanceY)
    }

    override fun onSwipeEnd() {
        if (!PlayerHelper.swipeGestureEnabled) return

        binding.volumeControls.isGone = true
    }

    private fun updateVolume(distance: Float) {
        val bar = binding.volumeProgressBar
        binding.volumeControls.apply {
            if (isGone) {
                isVisible = true
                // Volume could be changed using other mediums, sync progress
                // bar with new value.
                bar.progress = (audioHelper.deviceVolume * bar.max).toInt().coerceIn(0, bar.max)
            }
        }

        if (bar.progress == 0) {
            binding.volumeImageView.setImageResource(
                when {
                    distance > 0 -> R.drawable.ic_volume_up
                    else -> R.drawable.ic_volume_off
                }
            )
        }
        bar.incrementProgressBy(distance.toInt() / 3)
        audioHelper.deviceVolume = bar.progress.toFloat() / bar.max

        binding.volumeTextView.text = "${bar.progress.normalize(0, bar.max, 0, 100)}"
    }

    private fun updateChapterIndex() {
        if (_binding == null) return
        handler.postDelayed(this::updateChapterIndex, 100)

        val currentIndex =
            PlayerHelper.getCurrentChapterIndex(
                playerController?.currentPosition ?: return,
                chaptersModel.chapters
            )
        chaptersModel.currentChapterIndex.updateIfChanged(currentIndex ?: return)
    }
}
