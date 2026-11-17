package com.github.libretube.ui.fragments

import android.annotation.SuppressLint
import android.content.res.Configuration
import android.os.Bundle
import android.os.Parcelable
import android.text.format.DateUtils
import android.util.Log
import android.view.View
import androidx.core.os.bundleOf
import androidx.core.text.parseAsHtml
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.appcompat.app.AlertDialog
import com.github.libretube.R
import com.github.libretube.api.MediaServiceRepository
import com.github.libretube.api.PlaylistsHelper
import com.github.libretube.api.obj.Playlist
import com.github.libretube.api.obj.StreamItem
import com.github.libretube.constants.IntentData
import com.github.libretube.db.DatabaseHolder.Database
import com.github.libretube.db.obj.TrackPublicationDate
import com.github.libretube.extensions.toLocalDate
import com.github.libretube.helpers.NetworkHelper
import com.github.libretube.constants.PreferenceKeys
import com.github.libretube.databinding.FragmentPlaylistBinding
import com.github.libretube.enums.PlaylistType
import com.github.libretube.enums.menuoption.PlayListSortModeBottomSheetItem
import com.github.libretube.extensions.TAG
import com.github.libretube.extensions.dpToPx
import com.github.libretube.extensions.setOnDismissListener
import com.github.libretube.extensions.toID
import com.github.libretube.extensions.toastFromMainDispatcher
import com.github.libretube.helpers.DownloadHelper
import com.github.libretube.helpers.NavigationHelper
import com.github.libretube.helpers.PreferenceHelper
import com.github.libretube.parcelable.PlayerData
import com.github.libretube.ui.adapters.PlaylistAdapter
import com.github.libretube.ui.adapters.PlaylistItem
import com.github.libretube.ui.base.BaseActivity
import com.github.libretube.ui.base.DynamicLayoutManagerFragment
import com.github.libretube.ui.extensions.addOnBottomReachedListener
import com.github.libretube.ui.models.CommonPlayerViewModel
import com.github.libretube.ui.models.PlaylistViewModel
import com.github.libretube.ui.sheets.BaseBottomSheet
import com.github.libretube.ui.sheets.PlaylistOptionsBottomSheet
import com.github.libretube.util.PlayingQueue
import com.github.libretube.util.TextUtils
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PlaylistFragment : DynamicLayoutManagerFragment(R.layout.fragment_playlist) {
    private var _binding: FragmentPlaylistBinding? = null
    private val binding get() = _binding!!
    private val args by navArgs<PlaylistFragmentArgs>()

    // general playlist information
    private lateinit var playlistId: String
    private var currentPlaylist: Playlist? = null
    private var playlistName: String? = null
    private var playlistType = PlaylistType.PUBLIC

    // runtime variables
    private var playlistFeed = mutableListOf<StreamItem>()
    private var playlistAdapter: PlaylistAdapter? = null
    private var nextPage: String? = null
    private var refreshJob: Job? = null
    private var datesJob: Job? = null
    private val triedDates = mutableSetOf<String>()
    private var dateSourceUnavailable = false
    private var snapshotComplete = false
    private var selectedAuthor: String? = null
    private var isBookmarked = false

    // view models
    private val commonPlayerViewModel: CommonPlayerViewModel by activityViewModels()
    private val playlistViewModel: PlaylistViewModel by activityViewModels()
    private var selectedSortOrder = PreferenceHelper.getInt(PreferenceKeys.PLAYLIST_SORT_ORDER, 0)
        set(value) {
            PreferenceHelper.putInt(PreferenceKeys.PLAYLIST_SORT_ORDER, value)
            field = value
        }
    private val sortOptions = PlayListSortModeBottomSheetItem.entries

    private var recyclerViewState: Parcelable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        playlistId = args.playlistId
        playlistType = args.playlistType
        selectedAuthor = savedInstanceState?.getString("playlistAuthor")
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("playlistAuthor", selectedAuthor)
    }

    override fun setLayoutManagers(gridItems: Int) {
        val view = _binding?.playlistRecView ?: return
        if (view.layoutManager == null) view.layoutManager = LinearLayoutManager(view.context)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _binding = FragmentPlaylistBinding.bind(view)
        super.onViewCreated(view, savedInstanceState)

        val localPlaylist = playlistType != PlaylistType.PUBLIC
        val adapter = PlaylistAdapter(playlistId) { track ->
            val items = visibleTracks().map { it.item }
            if (track in items) startPlayback(items, track)
        }
        playlistAdapter = adapter
        binding.playlistRecView.adapter = adapter
        binding.playlistProgress.isVisible = true
        binding.bookmark.isVisible = !localPlaylist
        binding.bookmark.setOnClickListener { toggleBookmark() }
        binding.playAll.setOnClickListener { startPlayback(visibleTracks().map { it.item }) }
        binding.shuffle.setOnClickListener { startPlayback(visibleTracks().map { it.item }.shuffled()) }
        binding.authorFilter.setOnClickListener { chooseAuthor() }
        binding.sortBTN.isVisible = true
        binding.sortBTN.setOnClickListener {
            BaseBottomSheet().apply {
                setItems(sortOptions) { index ->
                    selectedSortOrder = index
                    showPlaylistVideos()
                }
            }.show(childFragmentManager)
        }
        binding.optionsMenu.setOnClickListener { showOptions() }
        binding.playlistRecView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) loadVisibleDates()
            }
        })
        binding.snapshotStatus.setOnClickListener {
            if (!localPlaylist && refreshJob?.isActive != true) {
                dateSourceUnavailable = false
                refreshPublicPlaylist()
            }
        }
        commonPlayerViewModel.isMiniPlayerVisible.observe(viewLifecycleOwner) {
            binding.playlistRecView.updatePadding(bottom = if (it) 64f.dpToPx() else 0)
        }
        playlistViewModel.searchQuery.observe(viewLifecycleOwner) { showPlaylistVideos() }
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                Database.downloadDao().observeDownloads()
                    .map { items ->
                        items.filter { it.finishedAudio != null }.mapTo(mutableSetOf()) { it.download.videoId } to
                            items.mapNotNull { item ->
                                item.download.uploadDate?.let { item.download.videoId to it.toString() }
                            }.toMap()
                    }
                    .distinctUntilChanged()
                    .flowOn(Dispatchers.IO)
                    .collect { (downloadIds, dates) ->
                        adapter.setDownloadedAudioIds(downloadIds)
                        adapter.setPublicationDates(dates)
                    }
            }
        }

        if (localPlaylist) {
            binding.playlistRecView.setOnDismissListener(::removeFromPlaylist)
        } else {
            binding.playlistRecView.addOnBottomReachedListener {
                if (!isBookmarked && refreshJob?.isActive != true) fetchPublicPage()
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            safelyLoad {
                if (localPlaylist) {
                    display(PlaylistsHelper.getPlaylist(playlistId, playlistType), complete = true)
                } else {
                    val bookmark = Database.playlistBookmarkDao().findById(playlistId)
                    isBookmarked = bookmark != null
                    updateBookmarkButton()
                    val cached = PlaylistsHelper.getBookmarkSnapshot(playlistId)
                    when {
                        cached != null -> display(cached.first, cached.second)
                        bookmark != null -> {
                            val downloaded = PlaylistsHelper.getCachedPlaylist(playlistId)
                            display(Playlist(
                                name = bookmark.playlistName,
                                uploader = bookmark.uploader,
                                uploaderUrl = bookmark.uploaderUrl,
                                videos = bookmark.videos,
                                description = downloaded?.description,
                                relatedStreams = downloaded?.relatedStreams.orEmpty()
                            ), complete = false)
                        }
                        else -> PlaylistsHelper.getCachedPlaylist(playlistId)?.let { display(it, complete = false) }
                    }
                    refreshPublicPlaylist()
                }
            }
        }
    }

    override fun onDestroyView() {
        recyclerViewState = _binding?.playlistRecView?.layoutManager?.onSaveInstanceState()
        playlistAdapter = null
        _binding = null
        super.onDestroyView()
    }

    private fun display(playlist: Playlist, complete: Boolean) {
        val firstDisplay = currentPlaylist == null || !binding.playlistRecView.isVisible
        currentPlaylist = playlist
        playlistFeed = playlist.relatedStreams.toMutableList()
        nextPage = playlist.nextpage
        snapshotComplete = complete
        playlistName = playlist.name
        if (complete && selectedAuthor != null && playlistFeed.none { authorKey(it) == selectedAuthor }) {
            selectedAuthor = null
        }
        binding.playlistAppBar.isVisible = true
        binding.playlistRecView.isVisible = true
        binding.playlistProgress.isGone = true
        binding.playlistName.text = playlist.name
        binding.playlistInfo.text = getChannelAndVideoString(playlist, playlist.videos)
        binding.playlistInfo.setOnClickListener {
            NavigationHelper.navigateChannel(requireContext(), playlist.uploaderUrl)
        }
        binding.playlistDescription.text = playlist.description?.parseAsHtml()
        binding.playlistDescription.isGone = playlist.description.isNullOrBlank()
        showPlaylistVideos(firstDisplay)
    }

    private suspend fun safelyLoad(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG(), "Could not load playlist $playlistId", e)
        } finally {
            _binding?.playlistProgress?.isGone = true
            if (currentPlaylist == null) {
                _binding?.playlistAppBar?.isVisible = true
                _binding?.snapshotStatus?.apply {
                    isVisible = true
                    setText(R.string.bookmark_retry)
                }
            }
        }
    }

    private fun refreshPublicPlaylist() {
        if (!NetworkHelper.isNetworkAvailable(requireContext())) {
            binding.playlistProgress.isGone = true
            if (currentPlaylist == null) {
                binding.playlistAppBar.isVisible = true
                binding.snapshotStatus.isVisible = true
                binding.snapshotStatus.setText(R.string.bookmark_retry)
            }
            return
        }
        refreshJob = viewLifecycleOwner.lifecycleScope.launch {
            safelyLoad {
                val first = withTimeoutOrNull(20_000) {
                    PlaylistsHelper.getPlaylist(playlistId, PlaylistType.PUBLIC)
                } ?: return@safelyLoad
                if (first.relatedStreams.isEmpty() &&
                    (first.videos > 0 || playlistFeed.isNotEmpty() || first.nextpage != null)
                ) return@safelyLoad
                if (isBookmarked) {
                    cacheBookmark(first)
                } else {
                    PlaylistsHelper.cachePlaylistForOffline(playlistId, first, first.nextpage == null)
                    display(first, first.nextpage == null)
                }
            }
        }
    }

    private suspend fun cacheBookmark(first: Playlist) {
        var page = first.nextpage
        val tracks = first.relatedStreams.toMutableList()
        val seenPages = mutableSetOf<String>()
        PlaylistsHelper.saveBookmarkSnapshot(playlistId, first, page == null)
        if (page == null || (!snapshotComplete && tracks.size >= playlistFeed.size)) {
            display(first, page == null)
        }
        while (page != null && seenPages.add(page)) {
            val token = page
            val source = withTimeoutOrNull(20_000) {
                MediaServiceRepository.instance.getPlaylistNextPage(playlistId, token)
            } ?: break
            if (source.relatedStreams.isEmpty()) break
            tracks.addAll(source.relatedStreams)
            page = source.nextpage
        }
        if (tracks.size == first.relatedStreams.size) return
        val updated = first.copy(relatedStreams = tracks, nextpage = page)
        PlaylistsHelper.saveBookmarkSnapshot(playlistId, updated, page == null)
        if (page == null || (!snapshotComplete && tracks.size >= playlistFeed.size)) {
            display(updated, page == null)
        }
    }

    private fun fetchPublicPage() {
        val token = nextPage ?: return
        refreshJob = viewLifecycleOwner.lifecycleScope.launch {
            safelyLoad {
                val response = withTimeoutOrNull(20_000) {
                    MediaServiceRepository.instance.getPlaylistNextPage(playlistId, token)
                } ?: return@safelyLoad
                if (response.relatedStreams.isEmpty() || response.nextpage == token) return@safelyLoad
                val updated = currentPlaylist?.copy(
                    relatedStreams = playlistFeed + response.relatedStreams,
                    nextpage = response.nextpage,
                ) ?: return@safelyLoad
                PlaylistsHelper.cachePlaylistForOffline(playlistId, updated, response.nextpage == null)
                display(updated, response.nextpage == null)
            }
        }
    }

    private fun updateBookmarkButton() {
        binding.bookmark.setIconResource(
            if (isBookmarked) R.drawable.ic_bookmark else R.drawable.ic_bookmark_outlined
        )
        binding.bookmark.contentDescription = getString(
            if (isBookmarked) R.string.remove_bookmark_action else R.string.bookmark
        )
    }

    private fun toggleBookmark() {
        val playlist = currentPlaylist ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            binding.bookmark.isEnabled = false
            try {
                refreshJob?.cancel()
                val dao = Database.playlistBookmarkDao()
                if (isBookmarked) {
                    dao.deleteById(playlistId)
                    isBookmarked = false
                } else {
                    dao.insert(playlist.toPlaylistBookmark(playlistId))
                    isBookmarked = true
                    PlaylistsHelper.saveBookmarkSnapshot(playlistId, playlist, snapshotComplete)
                    refreshPublicPlaylist()
                }
                updateBookmarkButton()
                showPlaylistVideos()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG(), "Could not update playlist bookmark", e)
                context?.toastFromMainDispatcher(R.string.unknown_error)
            } finally {
                _binding?.bookmark?.isEnabled = true
            }
        }
    }

    private fun showOptions() {
        val playlist = currentPlaylist ?: return
        val sheet = PlaylistOptionsBottomSheet()
        sheet.arguments = bundleOf(
            IntentData.playlistId to playlistId,
            IntentData.playlistName to playlistName.orEmpty(),
            IntentData.playlistType to playlistType,
            IntentData.playlistThumbnail to playlist.thumbnailUrl,
        )
        val manager = (requireActivity() as BaseActivity).supportFragmentManager
        manager.setFragmentResultListener(
            PlaylistOptionsBottomSheet.PLAYLIST_OPTIONS_REQUEST_KEY, viewLifecycleOwner
        ) { _, result ->
            result.getString(IntentData.playlistDescription)?.let {
                playlist.description = it
                binding.playlistDescription.text = it
                binding.playlistDescription.isVisible = it.isNotBlank()
            }
            result.getString(IntentData.playlistName)?.let {
                binding.playlistName.text = it
                playlistName = it
            }
            if (result.getBoolean(IntentData.playlistTask)) findNavController().popBackStack()
        }
        sheet.show(manager)
    }

    private fun startPlayback(items: List<StreamItem>, clicked: StreamItem? = null) {
        if (items.isEmpty()) return
        val context = requireContext()
        viewLifecycleOwner.lifecycleScope.launch {
            val offline = !NetworkHelper.isNetworkAvailable(context)
            val eligible = if (offline) {
                val ids = items.mapNotNull { it.url?.toID() }
                val ready = DownloadHelper.getDownloadedAudioQueue(ids).mapTo(mutableSetOf()) { it.download.videoId }
                items.filter { it.url?.toID() in ready }
            } else items
            val first = (clicked ?: eligible.firstOrNull())?.takeIf { it in eligible }
            val id = first?.url?.toID()
            if (id == null) {
                context.toastFromMainDispatcher(R.string.audio_not_downloaded)
                return@launch
            }
            PlayingQueue.setStreams(eligible)
            NavigationHelper.navigateVideo(context, PlayerData(
                id, playlistId = playlistId, keepQueue = true, queueName = playlistName, isOffline = offline
            ))
        }
    }

    private fun authorKey(item: StreamItem): String =
        item.uploaderUrl?.takeIf { it.isNotBlank() }?.toID()?.let { "id:$it" }
            ?: "name:" + item.uploaderName.orEmpty().trim().lowercase()

    private fun authorName(item: StreamItem): String =
        item.uploaderName?.takeIf { it.isNotBlank() } ?: getString(R.string.unknown_playlist_author)

    private fun chooseAuthor() {
        val authors = playlistFeed.distinctBy(::authorKey)
            .sortedBy { authorName(it).lowercase() }
        val sameName = authors.groupingBy { authorName(it).lowercase() }.eachCount()
        val labels = authors.map {
            val name = authorName(it)
            if (sameName[name.lowercase()] == 1) name else "$name · ${it.uploaderUrl?.toID().orEmpty()}"
        }
        val keys = listOf(null) + authors.map(::authorKey)
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.filter_playlist_author)
            .setSingleChoiceItems(
                (listOf(getString(R.string.all_playlist_authors)) + labels).toTypedArray(),
                keys.indexOf(selectedAuthor)
            ) { dialog, index ->
                selectedAuthor = keys[index]
                showPlaylistVideos()
                dialog.dismiss()
            }.show()
    }

    private fun visibleTracks(): List<PlaylistItem> {
        val query = playlistViewModel.searchQuery.value.orEmpty()
        val filtered = playlistFeed.mapIndexed(::indexedItem).filter { (item, _) ->
            (selectedAuthor == null || authorKey(item) == selectedAuthor) &&
                (item.title.orEmpty().contains(query, ignoreCase = true) ||
                    item.uploaderName.orEmpty().contains(query, ignoreCase = true))
        }
        return when (selectedSortOrder) {
            1 -> filtered.reversed()
            2 -> filtered.sortedBy { it.item.duration }
            3 -> filtered.sortedByDescending { it.item.duration }
            4 -> filtered.sortedBy { it.item.title?.lowercase() }
            5 -> filtered.sortedByDescending { it.item.title?.lowercase() }
            6 -> filtered.sortedBy { it.item.uploaderName.orEmpty().lowercase() }
            7 -> filtered.sortedByDescending { it.item.uploaderName.orEmpty().lowercase() }
            else -> filtered
        }
    }

    private fun indexedItem(index: Int, item: StreamItem) = PlaylistItem(item, index)

    private fun showPlaylistVideos(restoreScroll: Boolean = false) {
        val playlist = currentPlaylist ?: return
        val items = visibleTracks()
        playlistAdapter?.submitList(items) {
            if (restoreScroll) _binding?.playlistRecView?.layoutManager?.onRestoreInstanceState(recyclerViewState)
            loadVisibleDates()
        }
        binding.sortBTN.text = sortOptions.getOrElse(selectedSortOrder) { sortOptions.first() }.title.getText(requireContext())
        val author = playlistFeed.firstOrNull { authorKey(it) == selectedAuthor }
        binding.authorFilter.text = author?.let(::authorName) ?: getString(R.string.all_playlist_authors)
        binding.authorFilter.contentDescription = getString(R.string.filter_playlist_author) + ", " + binding.authorFilter.text
        binding.authorFilter.isEnabled = playlistFeed.isNotEmpty()
        binding.playAll.isEnabled = items.isNotEmpty()
        binding.shuffle.isEnabled = items.isNotEmpty()
        binding.nothingHere.isVisible = items.isEmpty() && (snapshotComplete || playlist.videos <= 0)
        binding.nothingHere.setText(if (!snapshotComplete && playlistFeed.isEmpty()) R.string.bookmark_retry else R.string.emptyList)
        binding.snapshotStatus.isVisible = !snapshotComplete
        binding.snapshotStatus.text = getString(R.string.bookmark_partial, playlistFeed.size)
        updatePlaylistDuration(items)
    }

    private fun loadVisibleDates() {
        val adapter = playlistAdapter ?: return
        val manager = _binding?.playlistRecView?.layoutManager as? LinearLayoutManager ?: return
        val first = manager.findFirstVisibleItemPosition().coerceAtLeast(0)
        val ids = adapter.currentList.drop(first).take(10).mapNotNull { (track, _) ->
            if (track.publicationDate() == null) track.url?.toID() else null
        }.distinct()
        if (ids.isEmpty()) return

        datesJob?.cancel()
        datesJob = viewLifecycleOwner.lifecycleScope.launch {
            safelyLoad {
                val dao = Database.playlistBookmarkDao()
                val saved = dao.publicationDates(ids).associate { it.videoId to it.publicationDate }
                adapter.setPublicationDates(saved)
                if (dateSourceUnavailable || !NetworkHelper.isNetworkAvailable(requireContext())) return@safelyLoad
                for (id in ids) {
                    if (adapter.hasPublicationDate(id) || id in triedDates || !ID_PATTERN.matches(id)) continue
                    val streams = try {
                        withTimeoutOrNull(8_000) { MediaServiceRepository.instance.getStreams(id) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.d(TAG(), "Publication date unavailable for $id", e)
                        null
                    }
                    if (streams == null) {
                        dateSourceUnavailable = true
                        break
                    }
                    triedDates.add(id)
                    val date = streams.uploadTimestamp?.toLocalDate()?.toString() ?: continue
                    dao.savePublicationDate(TrackPublicationDate(id, date))
                    adapter.setPublicationDates(mapOf(id to date))
                }
            }
        }
    }

    @SuppressLint("StringFormatInvalid")
    private fun removeFromPlaylist(position: Int) {
        val adapter = playlistAdapter ?: return
        val item = adapter.currentList.getOrNull(position) ?: return
        val id = item.item.url?.toID() ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val removed = withContext(Dispatchers.IO) {
                    PlaylistsHelper.removeFromPlaylist(playlistId, id, item.originalPlaylistIndex)
                }
                if (!removed) return@launch
                refreshLocalPlaylist()
                val title = TextUtils.limitTextToLength(item.item.title.orEmpty(), 50)
                Snackbar.make(binding.root, getString(R.string.successfully_removed_from_playlist, title), Snackbar.LENGTH_LONG)
                    .setAction(R.string.undo) { restorePlaylistItem(item.item) }
                    .show()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG(), "Playlist item removal failed", e)
                context?.toastFromMainDispatcher(R.string.unknown_error)
                if (position in adapter.currentList.indices) adapter.notifyItemChanged(position)
            }
        }
    }

    private fun restorePlaylistItem(item: StreamItem) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                if (PlaylistsHelper.addToPlaylist(playlistId, item)) refreshLocalPlaylist()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG(), "Playlist item restoration failed", e)
                context?.toastFromMainDispatcher(R.string.unknown_error)
            }
        }
    }

    private suspend fun refreshLocalPlaylist() {
        val playlist = withContext(Dispatchers.IO) { PlaylistsHelper.getPlaylist(playlistId, PlaylistType.PRIVATE) }
        display(playlist, complete = true)
    }

    private fun getChannelAndVideoString(playlist: Playlist, count: Int): String {
        if (count < 0) return playlist.uploader.orEmpty()
        if (playlist.uploader == null) return getString(R.string.videoCount, count)

        return getString(R.string.uploaderAndVideoCount, playlist.uploader, count)
    }

    @SuppressLint("SetTextI18n")
    private fun updatePlaylistDuration(updatedList: List<PlaylistItem>) {
        val totalDuration = updatedList.sumOf { it.item.duration ?: 0 }
        binding.playlistDuration.text = DateUtils.formatElapsedTime(totalDuration) +
                if (!snapshotComplete && updatedList.isNotEmpty()) "+" else ""
    }

    private companion object {
        val ID_PATTERN = Regex("[A-Za-z0-9_-]{11}")
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // manually restore the recyclerview state due to https://github.com/material-components/material-components-android/issues/3473
        _binding?.playlistRecView?.layoutManager?.onRestoreInstanceState(recyclerViewState)
    }
}
