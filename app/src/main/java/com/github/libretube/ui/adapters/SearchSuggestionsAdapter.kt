package com.github.libretube.ui.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.ListAdapter
import com.github.libretube.databinding.SuggestionRowBinding
import com.github.libretube.ui.adapters.callbacks.DiffUtilItemCallback
import com.github.libretube.ui.viewholders.SuggestionsViewHolder
import com.github.libretube.R
import com.github.libretube.db.obj.SearchHistoryItem
import com.github.libretube.obj.SearchDataItem

class SearchSuggestionsAdapter(
    private val onRootClickListener: (String) -> Unit,
    private val onArrowClickListener: (String) -> Unit,
    private val onSearchHistoryItemDeleted: (SearchHistoryItem) -> Unit,
) : ListAdapter<SearchDataItem, SuggestionsViewHolder>(DiffUtilItemCallback<SearchDataItem>()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SuggestionsViewHolder {
        val layoutInflater = LayoutInflater.from(parent.context)
        val binding = SuggestionRowBinding.inflate(layoutInflater, parent, false)
        return SuggestionsViewHolder(binding)
    }

    override fun onBindViewHolder(holder: SuggestionsViewHolder, position: Int) {
        val item = getItem(holder.bindingAdapterPosition)
        val suggestion = item.query

        holder.binding.apply {
            deleteHistory.isVisible = true
            deleteHistory.setOnClickListener {
                onSearchHistoryItemDeleted(SearchHistoryItem(suggestion))
            }
            suggestionText.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_history, 0, 0, 0)
            suggestionText.text = suggestion
            root.setOnClickListener {
                onRootClickListener(suggestion)
            }
            arrow.setOnClickListener {
                onArrowClickListener(suggestion)
            }
        }
    }
}
