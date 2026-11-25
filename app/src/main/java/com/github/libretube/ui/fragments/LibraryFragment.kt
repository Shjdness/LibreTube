package com.github.libretube.ui.fragments

import android.os.Bundle
import android.os.Parcelable
import android.view.View
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.libretube.ui.extensions.bindAudioLibraryControls
import com.github.libretube.R
import com.github.libretube.api.PlaylistsHelper
import com.github.libretube.api.obj.Playlists
import com.github.libretube.constants.IntentData
import com.github.libretube.constants.PreferenceKeys
import com.github.libretube.databinding.FragmentLibraryBinding
import com.github.libretube.db.obj.PlaylistBookmark
import com.github.libretube.enums.PlaylistType
import com.github.libretube.helpers.PreferenceHelper
import com.github.libretube.extensions.toastFromMainDispatcher
import com.github.libretube.repo.UserDataRepositoryHelper
import com.github.libretube.ui.adapters.PlaylistBookmarkAdapter
import com.github.libretube.ui.adapters.PlaylistsAdapter
import com.github.libretube.ui.base.DynamicLayoutManagerFragment
import com.github.libretube.ui.dialogs.CreatePlaylistDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LibraryFragment : DynamicLayoutManagerFragment(R.layout.fragment_library) {
    private var _binding: FragmentLibraryBinding? = null
    private val binding get() = _binding!!
    private val playlistsAdapter = PlaylistsAdapter(PlaylistType.PRIVATE)
    private val bookmarkAdapter = PlaylistBookmarkAdapter()
    private var playlists = emptyList<Playlists>()
    private var bookmarks = emptyList<PlaylistBookmark>()
    private var refreshJob: Job? = null
    private var groupChannels: Set<String>? = null
    private var groupPlaylists: Set<String>? = null
    private var groupBookmarks: Set<String>? = null
    private val scrollStates = arrayOfNulls<Parcelable>(2)
    private val queries = arrayOf("", "")
    private var selectedTab = 0

    override fun setLayoutManagers(gridItems: Int) {
        _binding?.playlistRecView?.layoutManager = LinearLayoutManager(context)
        _binding?.bookmarksRecView?.layoutManager = LinearLayoutManager(context)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _binding = FragmentLibraryBinding.bind(view)
        super.onViewCreated(view, savedInstanceState)
        selectedTab = PreferenceHelper.getString("library_tab", "0").toIntOrNull()?.coerceIn(0, 1) ?: 0
        for (index in 0..1) {
            queries[index] = savedInstanceState?.getString("query_$index") ?: queries[index]
            @Suppress("DEPRECATION")
            val state: Parcelable? = savedInstanceState?.getParcelable("scroll_$index")
            if (state != null) scrollStates[index] = state
        }
        binding.playlistRecView.adapter = playlistsAdapter
        binding.bookmarksRecView.adapter = bookmarkAdapter
        val observer = object : RecyclerView.AdapterDataObserver() {
            override fun onChanged() = updateEmptyState()
            override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) = updateEmptyState()
            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) = updateEmptyState()
        }
        playlistsAdapter.registerAdapterDataObserver(observer)
        bookmarkAdapter.registerAdapterDataObserver(observer)
        viewLifecycleOwner.lifecycle.addObserver(object : androidx.lifecycle.DefaultLifecycleObserver {
            override fun onDestroy(owner: androidx.lifecycle.LifecycleOwner) {
                playlistsAdapter.unregisterAdapterDataObserver(observer)
                bookmarkAdapter.unregisterAdapterDataObserver(observer)
            }
        })
        binding.libraryTabs.addTab(binding.libraryTabs.newTab().setText(R.string.playlists))
        binding.libraryTabs.addTab(binding.libraryTabs.newTab().setText(R.string.bookmarks))
        binding.libraryTabs.getTabAt(selectedTab)?.select()
        binding.libraryTabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                saveScrollStates()
                selectedTab = tab.position
                PreferenceHelper.putString("library_tab", selectedTab.toString())
                binding.librarySearch.setText(queries[selectedTab])
                showTab()
            }
            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })
        binding.librarySearch.setText(queries[selectedTab])
        binding.librarySearch.doAfterTextChanged {
            queries[selectedTab] = it?.toString().orEmpty()
            renderLists()
        }
        binding.sortTV.setOnClickListener {
            val options = arrayOf(getString(R.string.creation_date), "A → Z", "Z → A")
            val key = sortKey()
            val current = PreferenceHelper.getString(key, "0").toIntOrNull()?.coerceIn(0, 2) ?: 0
            MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.tooltip_sort)
                .setSingleChoiceItems(options, current) { dialog, choice ->
                    PreferenceHelper.putString(key, choice.toString())
                    scrollStates[selectedTab] = null
                    renderLists()
                    dialog.dismiss()
                }.setNeutralButton(R.string.library_pins) { _, _ -> showPins() }
                .setNegativeButton(R.string.cancel, null).show()
        }
        binding.playlistRefresh.setOnRefreshListener { refresh() }
        binding.createPlaylist.setOnClickListener {
            CreatePlaylistDialog().show(childFragmentManager, CreatePlaylistDialog::class.java.name)
        }
        childFragmentManager.setFragmentResultListener(
            CreatePlaylistDialog.CREATE_PLAYLIST_DIALOG_REQUEST_KEY, viewLifecycleOwner
        ) { _, result -> if (result.getBoolean(IntentData.playlistTask)) refresh() }
        binding.watchHistory.isVisible = PreferenceHelper.getBoolean(PreferenceKeys.WATCH_HISTORY_TOGGLE, true)
        binding.watchHistory.setOnClickListener {
            findNavController().navigate(R.id.action_libraryFragment_to_watchHistoryFragment)
        }
        bindAudioLibraryControls(binding.audioControls, onGroup = { groupChannels = it; refresh() })
        binding.continueListening.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch { com.github.libretube.helpers.NavigationHelper.resumePlayback(requireContext()) }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                com.github.libretube.util.PlaybackStateStore.changes.collect { updateContinueListening() }
            }
        }
        showTab()
        refresh()
    }

    private fun sortKey() = if (selectedTab == 0) "library_playlist_sort" else "library_bookmark_sort"

    private fun pinnedIds(): Set<String> = PreferenceHelper.getString("library_pins_$selectedTab", "")
        .split(',').filter(String::isNotBlank).toSet()

    private fun showPins() {
        val entries = if (selectedTab == 0) playlists.map { it.id.orEmpty() to it.name.orEmpty() }
            else bookmarks.map { it.playlistId to it.playlistName.orEmpty() }
        val selected = pinnedIds().toMutableSet()
        val key = "library_pins_$selectedTab"
        MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.library_pins)
            .setMultiChoiceItems(entries.map { it.second }.toTypedArray(),
                entries.map { it.first in selected }.toBooleanArray()) { _, index, checked ->
                if (checked) selected.add(entries[index].first) else selected.remove(entries[index].first)
            }.setPositiveButton(R.string.okay) { _, _ ->
                PreferenceHelper.putString(key, selected.joinToString(","))
                renderLists()
            }.setNegativeButton(R.string.cancel, null).show()
    }

    private fun showTab() {
        binding.playlistRecView.isVisible = selectedTab == 0
        binding.bookmarksRecView.isVisible = selectedTab == 1
        binding.createPlaylist.isVisible = selectedTab == 0
        binding.playlistRefresh.setOnChildScrollUpCallback { _, _ ->
            val list = if (selectedTab == 0) binding.playlistRecView else binding.bookmarksRecView
            list.canScrollVertically(-1)
        }
        renderLists()
    }

    private fun renderLists() {
        if (_binding == null) return
        val query = queries[selectedTab].trim()
        val order = PreferenceHelper.getString(sortKey(), "0")
        if (selectedTab == 0) {
            val filtered = playlists.filter { (groupPlaylists == null || it.id in groupPlaylists!!) && it.name.orEmpty().contains(query, ignoreCase = true) }
            val sorted = when (order) {
                "1" -> filtered.sortedBy { it.name.orEmpty().lowercase() }
                "2" -> filtered.sortedByDescending { it.name.orEmpty().lowercase() }
                else -> filtered
            }
            val pins = pinnedIds()
            playlistsAdapter.submitList(sorted.sortedByDescending { it.id in pins }) { restoreScroll(0) }
        } else {
            val filtered = bookmarks.filter { (groupBookmarks == null || it.playlistId in groupBookmarks!!) && it.playlistName.orEmpty().contains(query, ignoreCase = true) }
            val sorted = when (order) {
                "1" -> filtered.sortedBy { it.playlistName.orEmpty().lowercase() }
                "2" -> filtered.sortedByDescending { it.playlistName.orEmpty().lowercase() }
                else -> filtered
            }
            val pins = pinnedIds()
            bookmarkAdapter.submitList(sorted.sortedByDescending { it.playlistId in pins }) { restoreScroll(1) }
        }
        updateEmptyState()
    }

    private fun updateEmptyState() {
        _binding?.nothingHere?.isVisible = if (selectedTab == 0) playlistsAdapter.itemCount == 0 else bookmarkAdapter.itemCount == 0
    }

    private fun restoreScroll(tab: Int) {
        if (_binding == null) return
        val list = if (tab == 0) binding.playlistRecView else binding.bookmarksRecView
        scrollStates[tab]?.let { list.layoutManager?.onRestoreInstanceState(it) }
        scrollStates[tab] = null
        updateEmptyState()
    }

    private fun saveScrollStates() {
        val current = _binding ?: return
        scrollStates[0] = current.playlistRecView.layoutManager?.onSaveInstanceState()
        scrollStates[1] = current.bookmarksRecView.layoutManager?.onSaveInstanceState()
    }

    private fun refresh() {
        refreshJob?.cancel()
        refreshJob = viewLifecycleOwner.lifecycleScope.launch {
            binding.playlistRefresh.isRefreshing = true
            try {
                val data = withContext(Dispatchers.IO) {
                    PlaylistsHelper.getPlaylists() to UserDataRepositoryHelper.userDataRepository.getPlaylistBookmarks()
                }
                val group = groupChannels
                val eligible = withContext(Dispatchers.IO) {
                    if (group == null) null else {
                        com.github.libretube.repo.AudioLibraryRepository.refreshAuthorIndex()
                        val db = com.github.libretube.db.DatabaseHolder.Database
                        val local = db.localPlaylistsDao().getAll().filter { list -> list.videos.any {
                            com.github.libretube.repo.AudioLibraryRepository.matchesGroup(it.toStreamItem(), group)
                        }}.mapTo(hashSetOf()) { it.playlist.id.toString() }
                        val bookmarks = db.playlistBookmarkDao().getSnapshots().filter { snapshot ->
                            runCatching {
                                com.github.libretube.api.JsonHelper.json.decodeFromString(
                                    com.github.libretube.api.obj.Playlist.serializer(), snapshot.playlistJson)
                                    .relatedStreams.any { com.github.libretube.repo.AudioLibraryRepository.matchesGroup(it, group) }
                            }.getOrDefault(false)
                        }.mapTo(hashSetOf()) { it.playlistId }
                        local to bookmarks
                    }
                }
                groupPlaylists = eligible?.first
                groupBookmarks = eligible?.second
                updateContinueListening()
                playlists = data.first
                bookmarks = data.second
                renderLists()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                context?.toastFromMainDispatcher(e.localizedMessage.orEmpty())
            } finally {
                _binding?.playlistRefresh?.isRefreshing = false
            }
        }
    }

    private suspend fun updateContinueListening() {
        val saved = withContext(Dispatchers.IO) { com.github.libretube.util.PlaybackStateStore.load(requireContext()) }
        val views = _binding ?: return
        views.continueListening.isVisible = saved != null
        val title = saved?.tracks?.firstOrNull { it.url?.substringAfterLast('/') == saved.trackId }?.title ?: saved?.queueName
        views.continueListening.text = getString(R.string.audio_continue_title, title.orEmpty())
    }

    override fun onSaveInstanceState(outState: Bundle) {
        saveScrollStates()
        for (index in 0..1) {
            outState.putParcelable("scroll_$index", scrollStates[index])
            outState.putString("query_$index", queries[index])
        }
        super.onSaveInstanceState(outState)
    }

    override fun onDestroyView() {
        saveScrollStates()
        binding.playlistRecView.adapter = null
        binding.bookmarksRecView.adapter = null
        _binding = null
        super.onDestroyView()
    }
}
