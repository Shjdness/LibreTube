package com.github.libretube.ui.models

import android.os.Parcelable
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.asLiveData
import com.github.libretube.api.obj.StreamItem
import com.github.libretube.db.DatabaseHolder.Database
import com.github.libretube.db.obj.SubscriptionGroup

class SubscriptionsViewModel : ViewModel() {
    val videoFeed = MutableLiveData<List<StreamItem>?>()
    val groups = Database.subscriptionGroupsDao().observeAll().asLiveData()
    var channelListState: Parcelable? = null
    var groupToEdit: SubscriptionGroup? = null
}
