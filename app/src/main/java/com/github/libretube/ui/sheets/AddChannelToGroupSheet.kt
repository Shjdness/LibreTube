package com.github.libretube.ui.sheets

import android.os.Bundle
import android.view.View
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.github.libretube.R
import com.github.libretube.constants.IntentData
import com.github.libretube.databinding.DialogAddChannelToGroupBinding
import com.github.libretube.extensions.toID
import com.github.libretube.extensions.toastFromMainDispatcher
import com.github.libretube.repo.UserDataRepositoryHelper
import com.github.libretube.ui.adapters.AddChannelToGroupAdapter
import com.github.libretube.ui.models.SubscriptionsViewModel
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

class AddChannelToGroupSheet : ExpandedBottomSheet(R.layout.dialog_add_channel_to_group) {
    private val viewModel: SubscriptionsViewModel by activityViewModels()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = DialogAddChannelToGroupBinding.bind(view)
        val id = arguments?.getString(IntentData.channelId)?.toID() ?: run {
            dismiss()
            return
        }
        binding.cancel.setOnClickListener { dismiss() }
        binding.okay.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            val repository = UserDataRepositoryHelper.userDataRepository
            val groups = try {
                repository.getSubscriptionGroups()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                context?.toastFromMainDispatcher(e.message.orEmpty())
                return@launch
            }
            val selected = groups.filter { group -> group.channels.any { it.toID() == id } }
                .mapTo(mutableSetOf()) { it.name }
            val adapter = AddChannelToGroupAdapter(selected)
            binding.groupsRV.adapter = adapter
            adapter.submitList(groups)
            binding.okay.isEnabled = groups.isNotEmpty()
            binding.okay.setOnClickListener {
                val chosen = selected.toSet()
                binding.okay.isEnabled = false
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        repository.updateChannelGroups(id, groups.mapTo(mutableSetOf()) { it.name }, chosen)
                        viewModel.groups.value = repository.getSubscriptionGroups()
                        dismiss()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        context?.toastFromMainDispatcher(e.message.orEmpty())
                        binding.okay.isEnabled = true
                    }
                }
            }
        }
    }
}
