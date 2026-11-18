package com.github.libretube.ui.fragments

import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.github.libretube.R
import com.github.libretube.api.obj.Subscription
import com.github.libretube.constants.PreferenceKeys
import com.github.libretube.databinding.FragmentSubscriptionsBinding
import com.github.libretube.db.DatabaseHolder.Database
import com.github.libretube.db.obj.SubscriptionGroup
import com.github.libretube.extensions.toID
import com.github.libretube.helpers.PreferenceHelper
import com.github.libretube.ui.adapters.SubscriptionChannelAdapter
import com.github.libretube.ui.models.SubscriptionsViewModel
import com.github.libretube.ui.sheets.ChannelGroupsSheet
import com.google.android.material.chip.Chip
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class SubscriptionsFragment : Fragment(R.layout.fragment_subscriptions) {
    private var binding: FragmentSubscriptionsBinding? = null
    private val model: SubscriptionsViewModel by activityViewModels()
    private val adapter = SubscriptionChannelAdapter(quickNavigation = true)
    private var selectedGroup = GROUP_ALL
    private var groups = emptyList<SubscriptionGroup>()
    private var channels = emptyList<Subscription>()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val views = FragmentSubscriptionsBinding.bind(view)
        binding = views
        views.channels.layoutManager = LinearLayoutManager(view.context)
        views.channels.adapter = adapter
        adapter.stateRestorationPolicy = androidx.recyclerview.widget.RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
        views.editGroups.setOnClickListener { ChannelGroupsSheet().show(childFragmentManager, null) }

        viewLifecycleOwner.lifecycleScope.launch {
            combine(Database.localSubscriptionDao().observeAll(), Database.subscriptionGroupsDao().observeAll()) { savedChannels, savedGroups ->
                savedChannels.map { Subscription(it.channelId, it.name.orEmpty(), it.avatar, it.verified) } to savedGroups
            }.collect { (newChannels, newGroups) ->
                if (binding !== views) return@collect
                val firstRender = views.channelGroups.childCount == 0
                channels = newChannels.sortedBy { it.name.ifBlank { it.url }.lowercase() }
                groups = newGroups
                if (firstRender) {
                    selectedGroup = readSelection()
                    rebuildChips()
                    showChannels(restoreScroll = true)
                } else {
                    if (!availableGroup(selectedGroup)) {
                        selectedGroup = readSelection()
                        rememberSelection()
                    }
                    rebuildChips()
                    showChannels()
                }
            }
        }
    }

    private fun readSelection(): String {
        val saved = PreferenceHelper.getString(PreferenceKeys.SELECTED_CHANNEL_GROUP_KEY, "")
        val group = if (saved.isNotEmpty()) saved else {
            when (val legacy = PreferenceHelper.getInt(PreferenceKeys.SELECTED_CHANNEL_GROUP, -1)) {
                -2 -> GROUP_UNGROUPED
                else -> groups.getOrNull(legacy)?.let { groupKey(it.name) } ?: GROUP_ALL
            }
        }
        return group.takeIf(::availableGroup) ?: GROUP_ALL
    }

    private fun availableGroup(key: String) =
        key == GROUP_ALL || key == GROUP_UNGROUPED || groups.any { groupKey(it.name) == key }

    private fun groupMembers(group: SubscriptionGroup) = group.channels.map { it.toID() }

    private fun rebuildChips() {
        val views = binding ?: return
        val row = views.channelGroups
        row.setOnCheckedStateChangeListener(null)
        row.removeAllViews()
        val options = listOf(GROUP_ALL to getString(R.string.all)) +
            groups.map { groupKey(it.name) to it.name } +
            (GROUP_UNGROUPED to getString(R.string.ungrouped_channels))
        for ((key, name) in options) {
            val chip = layoutInflater.inflate(R.layout.filter_chip, row, false) as Chip
            chip.id = View.generateViewId()
            chip.tag = key
            chip.text = name
            chip.isCheckable = true
            row.addView(chip)
            if (key == selectedGroup) row.check(chip.id)
        }
        row.setOnCheckedStateChangeListener { group, checked ->
            val key = checked.firstOrNull()?.let { group.findViewById<Chip>(it)?.tag as? String }
            if (key != null && key != selectedGroup) {
                selectedGroup = key
                rememberSelection()
                model.channelListState = null
                showChannels(scrollToTop = true)
            }
        }
    }

    private fun rememberSelection() =
        PreferenceHelper.putString(PreferenceKeys.SELECTED_CHANNEL_GROUP_KEY, selectedGroup)

    private fun showChannels(restoreScroll: Boolean = false, scrollToTop: Boolean = false) {
        val views = binding ?: return
        val shown = when (selectedGroup) {
            GROUP_ALL -> channels
            GROUP_UNGROUPED -> {
                val grouped = groups.flatMap(::groupMembers).toSet()
                channels.filterNot { it.url.toID() in grouped }
            }
            else -> {
                val group = groups.firstOrNull { groupKey(it.name) == selectedGroup }
                val members = group?.let(::groupMembers).orEmpty().toSet()
                channels.filter { it.url.toID() in members }
            }
        }
        views.emptyChannels.isVisible = shown.isEmpty()
        views.emptyChannels.setText(if (selectedGroup == GROUP_ALL) R.string.emptyList else R.string.subscription_group_empty)
        adapter.submitList(shown) {
            if (binding !== views) return@submitList
            if (scrollToTop) views.channels.scrollToPosition(0)
            if (restoreScroll) views.channels.layoutManager?.onRestoreInstanceState(model.channelListState)
        }
    }

    override fun onDestroyView() {
        model.channelListState = binding?.channels?.layoutManager?.onSaveInstanceState()
        binding?.channels?.adapter = null
        binding = null
        super.onDestroyView()
    }

    companion object {
        private const val GROUP_ALL = "all"
        private const val GROUP_UNGROUPED = "ungrouped"
        private fun groupKey(name: String) = "group:" + name
    }
}
