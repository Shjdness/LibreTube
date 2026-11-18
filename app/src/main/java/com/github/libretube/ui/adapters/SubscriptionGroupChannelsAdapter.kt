package com.github.libretube.ui.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.ListAdapter
import com.github.libretube.api.obj.Subscription
import com.github.libretube.databinding.SubscriptionGroupChannelRowBinding
import com.github.libretube.extensions.toID
import com.github.libretube.helpers.ImageHelper
import com.github.libretube.helpers.NavigationHelper
import com.github.libretube.ui.adapters.callbacks.DiffUtilItemCallback
import com.github.libretube.ui.viewholders.SubscriptionGroupChannelRowViewHolder

class SubscriptionGroupChannelsAdapter(
    private val selectedIds: MutableSet<String>,
    private val onChannelsChanged: (List<String>) -> Unit
) : ListAdapter<Subscription, SubscriptionGroupChannelRowViewHolder>(DiffUtilItemCallback()) {

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int
    ): SubscriptionGroupChannelRowViewHolder {
        val layoutInflater = LayoutInflater.from(parent.context)
        val binding = SubscriptionGroupChannelRowBinding.inflate(layoutInflater, parent, false)
        return SubscriptionGroupChannelRowViewHolder(binding)
    }

    override fun onBindViewHolder(holder: SubscriptionGroupChannelRowViewHolder, position: Int) {
        val channel = getItem(position)
        holder.binding.apply {
            root.setOnClickListener {
                NavigationHelper.navigateChannel(root.context, channel.url)
            }
            subscriptionChannelName.text = channel.name
            ImageHelper.loadImage(channel.avatar, subscriptionChannelImage, true)

            val channelId = channel.url.toID()
            channelIncluded.setOnCheckedChangeListener(null)
            channelIncluded.isChecked = channelId in selectedIds
            channelIncluded.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) selectedIds.add(channelId) else selectedIds.remove(channelId)
                onChannelsChanged(selectedIds.toList())
            }
        }
    }
}
