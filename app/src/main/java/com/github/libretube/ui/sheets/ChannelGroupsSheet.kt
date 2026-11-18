package com.github.libretube.ui.sheets

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.github.libretube.R
import com.github.libretube.databinding.DialogSubscriptionGroupsBinding
import com.github.libretube.db.obj.SubscriptionGroup
import com.github.libretube.extensions.move
import com.github.libretube.extensions.setOnDraggedListener
import com.github.libretube.ui.adapters.SubscriptionGroupsAdapter
import com.github.libretube.ui.models.SubscriptionsViewModel
import com.github.libretube.repo.UserDataRepositoryHelper
import com.github.libretube.extensions.toastFromMainDispatcher
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.launch

class ChannelGroupsSheet : ExpandedBottomSheet(R.layout.dialog_subscription_groups) {
    private val viewModel: SubscriptionsViewModel by activityViewModels()

    @SuppressLint("NotifyDataSetChanged")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val binding = DialogSubscriptionGroupsBinding.bind(view)
        binding.groupsRV.layoutManager = LinearLayoutManager(context)
        val groups = viewModel.groups.value.orEmpty().toMutableList()
        val adapter = SubscriptionGroupsAdapter(groups, viewModel, parentFragmentManager, viewLifecycleOwner.lifecycleScope)
        binding.groupsRV.adapter = adapter

        binding.newGroup.setOnClickListener {
            viewModel.groupToEdit = SubscriptionGroup("", mutableListOf(), 0)
            EditChannelGroupSheet().show(parentFragmentManager, null)
        }

        viewModel.groups.observe(viewLifecycleOwner) {
            adapter.groups = viewModel.groups.value.orEmpty().toMutableList()
            lifecycleScope.launch { adapter.notifyDataSetChanged() }
        }

        binding.confirm.setOnClickListener {
            val orderedNames = adapter.groups.map { it.name }
            binding.confirm.isEnabled = false
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val repository = UserDataRepositoryHelper.userDataRepository
                    repository.reorderSubscriptionGroups(orderedNames)
                    dismiss()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    context?.toastFromMainDispatcher(e.message.orEmpty())
                    binding.confirm.isEnabled = true
                }
            }
        }

        binding.groupsRV.setOnDraggedListener { from, to ->
            adapter.groups.move(from, to)
            adapter.notifyItemMoved(from, to)
        }
    }
}
