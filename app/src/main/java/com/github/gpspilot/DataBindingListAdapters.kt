@file:Suppress("FunctionName")

package com.github.gpspilot

import android.view.ViewGroup
import androidx.annotation.LayoutRes
import androidx.databinding.DataBindingUtil
import androidx.databinding.ViewDataBinding
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView.ViewHolder
import e
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ObsoleteCoroutinesApi
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.launch


class DataBindingViewHolder(val binding: ViewDataBinding) : ViewHolder(binding.root)

/**
 * Abstraction for item for auto binding.
 * It assumed that layout has 'vm' bind variable, where this item will be assigned automatically.
 * [layout] should contains layout id which will be automatically inflated.
 * [id] is needed to distinct items in [DiffCallback].
 */
interface RecyclerViewItem {
    @get:LayoutRes val layout: Int
    val id: Any
}

private fun DataBindingViewHolder(parent: ViewGroup, layoutId: Int): DataBindingViewHolder {
    val binding = DataBindingUtil.inflate<ViewDataBinding>(
            parent.context.inflater, layoutId, parent, false
    )
    return DataBindingViewHolder(binding)
}

private fun <T> DataBindingViewHolder.bind(item: T) {
    val bidden = binding.setVariable(BR.vm, item)
    if (! bidden) e { "Item for position $adapterPosition was not bidden!" }
    // may be it needed to call executePendingBindings() here, take note if some problems will occur
}

/**
 * Implementation of [ListAdapter] which automatically binds data views.
 */
class DataBindingListAdapter : ListAdapter<RecyclerViewItem, DataBindingViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int)
            = DataBindingViewHolder(parent, viewType)

    override fun onBindViewHolder(holder: DataBindingViewHolder, position: Int)
            = holder.bind(getItem(position))

    override fun getItemViewType(position: Int) = getItem(position).layout

    operator fun get(pos: Int): RecyclerViewItem = getItem(pos)
}

/**
 * Implementation of [DiffUtil.ItemCallback] of [RecyclerViewItem].
 */
private class DiffCallback : DiffUtil.ItemCallback<RecyclerViewItem>() {

    override fun areItemsTheSame(oldItem: RecyclerViewItem, newItem: RecyclerViewItem): Boolean {
        return (oldItem::class == newItem::class) && (oldItem.id == newItem.id)
    }

    /**
     * It assumed that every view model object will have equals() method implemented.
     * Since view model objects mostly are Kotlin's data classes - it already implemented by default.
     */
    override fun areContentsTheSame(
            oldItem: RecyclerViewItem,
            newItem: RecyclerViewItem
    ) = (oldItem == newItem)
}


/**
 * Creates [DataBindingListAdapter] for [RecyclerViewItem],
 * that automatically binds items from [items] using android DataBinding framework.
 * [DiffUtil] is used under the hood for avery new list of items.
 */
@ObsoleteCoroutinesApi
fun CoroutineScope.createListAdapter(items: ReceiveChannel<List<RecyclerViewItem>>): DataBindingListAdapter {
    val adapter = DataBindingListAdapter()
    launch(Dispatchers.Main) {
        items.consumeEach { adapter.submitList(it) }
    }
    return adapter
}