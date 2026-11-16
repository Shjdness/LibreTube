package com.github.libretube.ui.models

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.libretube.db.DatabaseHolder
import com.github.libretube.enums.SearchDataType
import com.github.libretube.obj.SearchDataItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlin.collections.map

class SearchViewModel : ViewModel() {
    private val searchQuery = MutableStateFlow<String?>(null)
    private val sharingStarted = SharingStarted.WhileSubscribed(replayExpirationMillis = 0L)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val searchHistoryList = DatabaseHolder.Database.searchHistoryDao()
        .getAllNewestFirstFlow()
        .mapLatest { it.map { it.query } }
        .stateIn(viewModelScope, sharingStarted, emptyList())

    val filteredSearchHistory = combine(searchQuery, searchHistoryList) { query, list ->
        val matches = if (query.isNullOrBlank()) list
        else list.filter { it.startsWith(query, ignoreCase = true) }.take(MAX_FILTERED_SEARCH_HISTORY)
        matches.map { SearchDataItem(it, SearchDataType.HISTORY) }
    }.flowOn(Dispatchers.IO)

    fun setQuery(query: String?) {
        this.searchQuery.value = query
    }

    companion object {
        private const val MAX_FILTERED_SEARCH_HISTORY = 5
    }
}