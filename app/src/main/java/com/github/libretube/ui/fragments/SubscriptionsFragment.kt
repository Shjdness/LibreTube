package com.github.libretube.ui.fragments

import android.annotation.SuppressLint
import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import androidx.core.os.bundleOf
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.libretube.R
import com.github.libretube.api.obj.StreamItem
import com.github.libretube.constants.IntentData
import com.github.libretube.constants.PreferenceKeys
import com.github.libretube.databinding.FragmentSubscriptionsBinding
import com.github.libretube.db.DatabaseHelper
import com.github.libretube.db.DatabaseHolder.Database
import com.github.libretube.extensions.toID
import com.github.libretube.extensions.toastFromMainDispatcher
import com.github.libretube.helpers.NavigationHelper
import com.github.libretube.helpers.PreferenceHelper
import com.github.libretube.obj.SelectableOption
import com.github.libretube.parcelable.PlayerData
import com.github.libretube.repo.FeedRefresh
import com.github.libretube.repo.UserDataRepositoryHelper
import com.github.libretube.ui.adapters.VideoCardsAdapter
import com.github.libretube.ui.base.DynamicLayoutManagerFragment
import com.github.libretube.ui.models.SubscriptionsViewModel
import com.github.libretube.ui.sheets.ChannelGroupsSheet
import com.github.libretube.ui.sheets.FilterSortBottomSheet
import com.github.libretube.ui.sheets.FilterSortBottomSheet.Companion.FILTER_SORT_REQUEST_KEY
import com.github.libretube.ui.sheets.SubscriptionsBottomSheet
import com.github.libretube.util.PlayingQueue
import com.google.android.material.chip.Chip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch


class SubscriptionsFragment : DynamicLayoutManagerFragment(R.layout.fragment_subscriptions) {
    private var _binding: FragmentSubscriptionsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: SubscriptionsViewModel by activityViewModels()

    private var selectedGroup: String
        get() = PreferenceHelper.getString(PreferenceKeys.SELECTED_CHANNEL_GROUP_KEY, GROUP_ALL)
        set(value) = PreferenceHelper.putString(PreferenceKeys.SELECTED_CHANNEL_GROUP_KEY, value)

    private var rebuildingGroups = false

    private var isAppBarFullyExpanded = true

    private var feedAdapter = VideoCardsAdapter()
    private var selectedSortOrder = PreferenceHelper.getInt(PreferenceKeys.FEED_SORT_ORDER, 0)
        set(value) {
            PreferenceHelper.putInt(PreferenceKeys.FEED_SORT_ORDER, value)
            field = value
        }

    private var hideWatched =
        PreferenceHelper.getBoolean(PreferenceKeys.HIDE_WATCHED_FROM_FEED, false)
        set(value) {
            PreferenceHelper.putBoolean(PreferenceKeys.HIDE_WATCHED_FROM_FEED, value)
            field = value
        }

    private var showUpcoming =
        PreferenceHelper.getBoolean(PreferenceKeys.SHOW_UPCOMING_IN_FEED, true)
        set(value) {
            PreferenceHelper.putBoolean(PreferenceKeys.SHOW_UPCOMING_IN_FEED, value)
            field = value
        }

    override fun setLayoutManagers(gridItems: Int) {
        _binding?.subFeed?.layoutManager = GridLayoutManager(context, gridItems)
    }

    @SuppressLint("SetTextI18n")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _binding = FragmentSubscriptionsBinding.bind(view)
        super.onViewCreated(view, savedInstanceState)

        setupSortAndFilter()

        binding.subFeed.adapter = feedAdapter

        // Check if the AppBarLayout is fully expanded
        binding.subscriptionsAppBar.addOnOffsetChangedListener { _, verticalOffset ->
            isAppBarFullyExpanded = verticalOffset == 0
        }

        // Determine if the child can scroll up
        binding.subRefresh.setOnChildScrollUpCallback { _, _ ->
            !isAppBarFullyExpanded || binding.subFeed.canScrollVertically(-1)
        }

        binding.subRefresh.isEnabled = true
        binding.subProgress.isVisible = true

        if (viewModel.videoFeed.value == null) {
            viewModel.fetchFeed(requireContext(), FeedRefresh.Automatically)
        }

        // only restore the previous state (i.e. scroll position) the first time the feed is shown
        // any other feed updates are caused by manual refreshing and thus should reset the scroll
        // position to zero
        var alreadyShowedFeedOnce = false
        viewModel.videoFeed.observe(viewLifecycleOwner) { feed ->
            if (feed != null) {
                lifecycleScope.launch {
                    showFeed(!alreadyShowedFeedOnce)
                }
                alreadyShowedFeedOnce = true
            }

            feed?.firstOrNull { !it.isUpcoming }?.uploaded?.let {
                PreferenceHelper.updateLastFeedWatchedTime(it, true)
            }

            updateUngroupedVisibility()
        }

        viewModel.feedProgress.observe(viewLifecycleOwner) { progress ->
            if (progress == null || progress.currentProgress == progress.total) {
                // the automatic animation by setting animateLayoutChanges looks very buggy
                // so we display a custom animation when the feed finished loading
                // https://stackoverflow.com/questions/37704046/animatelayoutchanges-buggy-when-changing-visibility-to-gone
                binding.feedProgressContainer.animate()
                    .alpha(0.5f)
                    .scaleY(0.5f)
                    .withEndAction {
                        val binding = _binding ?: return@withEndAction
                        binding.feedProgressContainer.isGone = true
                        binding.feedProgressContainer.scaleY = 1f
                        binding.feedProgressContainer.alpha = 1f
                    }
                    .setDuration(200)
                    .start()
            } else {
                binding.feedProgressContainer.isVisible = true
                binding.feedProgressText.text = "${progress.currentProgress}/${progress.total}"
                binding.feedProgressBar.max = progress.total
                binding.feedProgressBar.progress = progress.currentProgress
            }
        }

        binding.subRefresh.setOnRefreshListener {
            viewModel.fetchFeed(
                requireContext(),
                when (val key = selectedGroup) {
                    GROUP_ALL -> FeedRefresh.All
                    GROUP_UNGROUPED -> if (viewModel.subscriptions.value == null) FeedRefresh.All
                    else FeedRefresh.OnlySome(ungroupedChannelIds())
                    else -> FeedRefresh.OnlySome(groupChannels(key).toList())
                }
            )
        }

        binding.toggleSubs.isVisible = true

        binding.toggleSubs.setOnClickListener {
            SubscriptionsBottomSheet()
                .show(childFragmentManager)
        }

        binding.channelGroups.setOnCheckedStateChangeListener { group, checked ->
            if (!rebuildingGroups) {
                val key = checked.firstOrNull()?.let { group.findViewById<Chip>(it)?.tag as? String }
                    ?: return@setOnCheckedStateChangeListener
                selectedGroup = key
                viewLifecycleOwner.lifecycleScope.launch { showFeed(restoreScrollState = false) }
            }
        }

        viewModel.groups.observe(viewLifecycleOwner) {
            initChannelGroups()
            viewLifecycleOwner.lifecycleScope.launch { showFeed(restoreScrollState = false) }
        }

        binding.editGroups.setOnClickListener {
            ChannelGroupsSheet().show(childFragmentManager, null)
        }

        binding.subFeed.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                super.onScrollStateChanged(recyclerView, newState)
                viewModel.subFeedRecyclerViewState =
                    recyclerView.layoutManager?.onSaveInstanceState()?.takeIf {
                        recyclerView.computeVerticalScrollOffset() != 0
                    }
            }
        })

        viewModel.fetchSubscriptions(requireContext())
        viewLifecycleOwner.lifecycleScope.launch {
            Database.subscriptionGroupsDao().observeAll().collect { viewModel.groups.value = it }
        }
        viewModel.subscriptions.observe(viewLifecycleOwner) { updateUngroupedVisibility() }
        viewModel.feedError.observe(viewLifecycleOwner) { failed ->
            if (failed == true) {
                binding.subProgress.isGone = true
                binding.subRefresh.isRefreshing = false
                if (viewModel.videoFeed.value.isNullOrEmpty()) {
                    binding.emptyFeedMessage.setText(R.string.server_error)
                    binding.emptyFeed.isVisible = true
                }
            }
        }
    }

    private fun setupSortAndFilter() {
        binding.filterSort.setOnClickListener {
            childFragmentManager.setFragmentResultListener(
                FILTER_SORT_REQUEST_KEY,
                viewLifecycleOwner
            ) { _, resultBundle ->
                selectedSortOrder = resultBundle.getInt(IntentData.sortOptions)
                hideWatched = resultBundle.getBoolean(IntentData.hideWatched)
                showUpcoming = resultBundle.getBoolean(IntentData.showUpcoming)
                lifecycleScope.launch { showFeed() }
            }

            FilterSortBottomSheet()
                .apply {
                    arguments = bundleOf(
                        IntentData.sortOptions to fetchSortOptions(),
                        IntentData.hideWatched to hideWatched,
                        IntentData.showUpcoming to showUpcoming,
                    )
                }
                .show(childFragmentManager)
        }
    }

    private fun fetchSortOptions(): List<SelectableOption> {
        return resources.getStringArray(R.array.sortOptions)
            .mapIndexed { index, option ->
                SelectableOption(isSelected = index == selectedSortOrder, name = option)
            }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private suspend fun playByGroup(groupKey: String) {
        val streams = viewModel.videoFeed.value.orEmpty()
            .filterByGroup(groupKey)
            .let {
                DatabaseHelper.filterByStreamTypeAndWatchPosition(it, hideWatched, showUpcoming)
            }
            .sortedBySelectedOrder()

        if (streams.isEmpty()) return

        PlayingQueue.setStreams(streams)

        NavigationHelper.navigateVideo(
            requireContext(),
            playerData = PlayerData(
                videoId = streams.first().url,
                keepQueue = true
            )
        )
    }

    private fun channelIdsInGroups() = viewModel.groups.value.orEmpty()
        .flatMap { it.channels }.map { it.toID() }.filter { it.isNotBlank() }.toSet()

    private fun ungroupedChannelIds(): List<String> {
        val grouped = channelIdsInGroups()
        return viewModel.subscriptions.value.orEmpty().map { it.url.toID() }.filterNot(grouped::contains)
    }

    private fun filterUngroupedStreamItems(streamItems: List<StreamItem>): List<StreamItem> {
        val grouped = channelIdsInGroups()
        return streamItems.filter { it.uploaderUrl.orEmpty().toID() !in grouped }
    }

    private fun updateUngroupedVisibility() {
        val binding = _binding ?: return
        binding.chipUngrouped.isVisible = viewModel.groups.value.orEmpty().isNotEmpty() &&
            (selectedGroup == GROUP_UNGROUPED || ungroupedChannelIds().isNotEmpty() ||
                filterUngroupedStreamItems(viewModel.videoFeed.value.orEmpty()).isNotEmpty())
    }

    @SuppressLint("InflateParams")
    private fun initChannelGroups() {
        val binding = _binding ?: return
        val groups = viewModel.groups.value.orEmpty()
        if (PreferenceHelper.getString(PreferenceKeys.SELECTED_CHANNEL_GROUP_KEY, "").isEmpty()) {
            val legacy = PreferenceHelper.getInt(PreferenceKeys.SELECTED_CHANNEL_GROUP, -1)
            selectedGroup = if (legacy == -2) GROUP_UNGROUPED
                else groups.getOrNull(legacy)?.let { keyFor(it.name) } ?: GROUP_ALL
        }
        rebuildingGroups = true
        try {
            binding.channelGroups.removeAllViews()
            val chips = mutableMapOf<String, Chip>()
            fun addChip(key: String, chip: Chip) {
                chip.tag = key
                chip.setOnLongClickListener {
                    viewLifecycleOwner.lifecycleScope.launch { playByGroup(key) }
                    true
                }
                binding.channelGroups.addView(chip)
                chips[key] = chip
            }
            addChip(GROUP_ALL, binding.chipAll)
            for (group in groups) {
                val chip = layoutInflater.inflate(R.layout.filter_chip, binding.channelGroups, false) as Chip
                chip.id = View.generateViewId()
                chip.isCheckable = true
                chip.text = group.name
                addChip(keyFor(group.name), chip)
            }
            addChip(GROUP_UNGROUPED, binding.chipUngrouped)
            updateUngroupedVisibility()
            val checked = chips[selectedGroup]?.takeIf { it.isVisible }
            if (checked == null) selectedGroup = GROUP_ALL
            binding.channelGroups.check((checked ?: binding.chipAll).id)
        } finally {
            rebuildingGroups = false
        }
    }

    private fun groupChannels(key: String): Set<String> = viewModel.groups.value.orEmpty()
        .firstOrNull { keyFor(it.name) == key }?.channels.orEmpty().map { it.toID() }.toSet()

    private fun List<StreamItem>.filterByGroup(key: String): List<StreamItem> = when (key) {
        GROUP_ALL -> this
        GROUP_UNGROUPED -> filterUngroupedStreamItems(this)
        else -> {
            val channels = groupChannels(key)
            filter { it.uploaderUrl.orEmpty().toID() in channels }
        }
    }

    private fun List<StreamItem>.sortedBySelectedOrder() = when (selectedSortOrder) {
        0 -> this
        1 -> this.reversed()
        2 -> this.sortedBy { it.views }.reversed()
        3 -> this.sortedBy { it.views }
        4 -> this.sortedBy { it.uploaderName }
        5 -> this.sortedBy { it.uploaderName }.reversed()
        else -> this
    }

    private suspend fun showFeed(restoreScrollState: Boolean = true) {
        val binding = _binding ?: return
        val videoFeed = viewModel.videoFeed.value ?: return

        val groupKey = selectedGroup
        val feed = videoFeed
            .filterByGroup(groupKey)
            .let {
                DatabaseHelper.filterByStreamTypeAndWatchPosition(it, hideWatched, showUpcoming)
            }

        if (_binding !== binding || groupKey != selectedGroup) return

        val sortedFeed = feed
            .sortedBySelectedOrder()
            .toMutableList()

        // add an "all caught up item"
        if (selectedSortOrder == 0) {
            val lastCheckedFeedTime = PreferenceHelper.getLastCheckedFeedTime(seenByUser = true)
            val caughtUpIndex =
                feed.indexOfFirst { it.uploaded <= lastCheckedFeedTime && !it.isUpcoming }
            if (caughtUpIndex > 0 && !feed[caughtUpIndex - 1].isUpcoming) {
                sortedFeed.add(
                    caughtUpIndex,
                    StreamItem(type = VideoCardsAdapter.CAUGHT_UP_STREAM_TYPE)
                )
            }
        }

        binding.subProgress.isGone = true

        val notLoaded = sortedFeed.isEmpty()
        binding.subFeed.isGone = notLoaded
        binding.emptyFeed.isVisible = notLoaded
        if (notLoaded) {
            val selectedIsEmpty = selectedGroup != GROUP_ALL && selectedGroup != GROUP_UNGROUPED && groupChannels(selectedGroup).isEmpty()
            binding.emptyFeedMessage.setText(if (selectedIsEmpty) R.string.subscription_group_empty else R.string.emptyList)
        }

        binding.toggleSubs.text = getString(R.string.subscriptions)

        binding.subRefresh.isRefreshing = false

        feedAdapter.submitList(sortedFeed) {
            val binding = _binding ?: return@submitList
            if (restoreScrollState) {
                // manually restore the previous feed state
                binding.subFeed.layoutManager?.onRestoreInstanceState(viewModel.subFeedRecyclerViewState)
            } else {
                binding.subFeed.scrollToPosition(0)
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // manually restore the recyclerview state after rotation due to https://github.com/material-components/material-components-android/issues/3473
        binding.subFeed.layoutManager?.onRestoreInstanceState(viewModel.subFeedRecyclerViewState)
    }

    fun removeItem(videoId: String) {
        feedAdapter.removeItemById(videoId)
    }

    companion object {
        private const val GROUP_UNGROUPED = "ungrouped"
        private const val GROUP_ALL = "all"
        private fun keyFor(name: String) = "group:" + name
    }
}
