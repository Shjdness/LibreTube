package com.github.libretube.ui.sheets

import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.github.libretube.R
import com.github.libretube.api.obj.Subscription
import com.github.libretube.databinding.DialogEditChannelGroupBinding
import com.github.libretube.db.DatabaseHolder.Database
import com.github.libretube.extensions.toID
import com.github.libretube.extensions.toastFromMainDispatcher
import kotlin.coroutines.cancellation.CancellationException
import com.github.libretube.repo.UserDataRepositoryHelper
import com.github.libretube.ui.adapters.SubscriptionGroupChannelsAdapter
import com.github.libretube.ui.models.SubscriptionsViewModel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class EditChannelGroupSheet : ExpandedBottomSheet(R.layout.dialog_edit_channel_group) {
    private var _binding: DialogEditChannelGroupBinding? = null
    private val binding get() = _binding!!

    private val viewModel: SubscriptionsViewModel by activityViewModels()
    private var channels = emptyList<Subscription>()
    private var channelsInGroup = emptyList<String>()

    private var saving = false
    private lateinit var channelsAdapter: SubscriptionGroupChannelsAdapter

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _binding = DialogEditChannelGroupBinding.bind(view)

        val original = viewModel.groupToEdit
        if (original == null) {
            dismiss()
            return
        }
        channelsInGroup = (savedInstanceState?.getStringArrayList("selectedChannels") ?: original.channels)
            .map(String::toID).distinct()
        channelsAdapter = SubscriptionGroupChannelsAdapter(channelsInGroup.toMutableSet()) {
            channelsInGroup = it
            updateConfirmStatus()
        }

        binding.channelsRV.adapter = channelsAdapter
        binding.groupName.setText(viewModel.groupToEdit?.name)
        val oldGroupName = viewModel.groupToEdit?.name.orEmpty()

        binding.channelsRV.layoutManager = LinearLayoutManager(context)

        binding.groupName.addTextChangedListener {
            updateConfirmStatus()
        }

        binding.searchInput.addTextChangedListener {
            showChannels(channels, it?.toString())
        }

        binding.cancel.setOnClickListener {
            dismiss()
        }

        updateConfirmStatus()
        binding.confirm.setOnClickListener {
            val name = binding.groupName.text.toString().trim()
            val selected = channelsInGroup.toList()
            saving = true
            updateConfirmStatus()
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val repository = UserDataRepositoryHelper.userDataRepository
                    if (repository.saveSubscriptionGroup(oldGroupName.takeIf { it.isNotBlank() }, name, selected)) {
                        dismiss()
                    } else {
                        binding.groupName.error = getString(R.string.group_name_error_exists)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    context?.toastFromMainDispatcher(e.message.orEmpty())
                } finally {
                    saving = false
                    _binding?.let { it.confirm.isEnabled = it.groupName.error == null }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                Database.localSubscriptionDao().observeAll().collectLatest { subscriptions ->
                    channels = subscriptions.map {
                        Subscription(it.channelId, it.name ?: it.channelId, it.avatar, it.verified)
                    }.sortedBy { it.name.lowercase() }
                    showChannels(channels, binding.searchInput.text?.toString())
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArrayList("selectedChannels", ArrayList(channelsInGroup))
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun showChannels(channels: List<Subscription>, query: String?) {
        binding.subscriptionsContainer.isVisible = true
        binding.progress.isVisible = false

        channelsAdapter.submitList(
            channels.filter { query == null || it.name.lowercase().contains(query.lowercase()) }
        )
    }

    private fun updateConfirmStatus() {
        with(binding) {
            val name = groupName.text.toString()
            groupName.error = getGroupNameError(name)

            confirm.isEnabled = groupName.error == null && !saving
        }
    }

    private fun getGroupNameError(name: String): String? {
        if (name.isBlank()) {
            return getString(R.string.group_name_error_empty)
        }

        return null
    }
}
