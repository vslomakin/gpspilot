@file:Suppress("FunctionName")

package com.github.gpspilot

import android.view.ViewGroup
import androidx.annotation.LayoutRes
import androidx.databinding.DataBindingUtil
import androidx.databinding.ObservableList
import androidx.databinding.ObservableList.OnListChangedCallback
import androidx.databinding.ViewDataBinding
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView.Adapter
import androidx.recyclerview.widget.RecyclerView.ViewHolder
import e
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ObsoleteCoroutinesApi
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.launch

abstract class DataBindingAdapter<T>(val list: ObservableList<T>) : Adapter<DataBindingViewHolder>() {

    init { handleChangesFrom(list) }

    override fun getItemCount(): Int = list.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int)
            = DataBindingViewHolder(parent, viewType)

    override fun onBindViewHolder(holder: DataBindingViewHolder, position: Int)
            = holder.bind(list[position])

    override fun getItemViewType(position: Int) = getLayout(list[position], position)

    @LayoutRes abstract fun getLayout(item: T, position: Int): Int

}

class DataBindingViewHolder(val binding: ViewDataBinding) : ViewHolder(binding.root)

private fun DataBindingViewHolder(parent: ViewGroup, viewType: Int): DataBindingViewHolder {
    val binding = DataBindingUtil.inflate<ViewDataBinding>(
            parent.context.inflater, viewType, parent, false
    )
    return DataBindingViewHolder(binding)
}

private fun <T> DataBindingViewHolder.bind(item: T) {
    val bidden = binding.setVariable(BR.vm, item)
    if (! bidden) e { "Item for position $adapterPosition was not bidden!" }
    // may be it needed to call executePendingBindings() here, take note if some problems will occur
}


private fun <T> Adapter<*>.handleChangesFrom(list: ObservableList<T>) {
    list.addOnListChangedCallback(createAdapterNotifier())
}

private fun <T> Adapter<*>.createAdapterNotifier(): OnListChangedCallback<ObservableList<T>> {
    return object : OnListChangedCallback<ObservableList<T>>() {
        override fun onChanged(sender: ObservableList<T>?) {
            notifyDataSetChanged()
        }

        override fun onItemRangeRemoved(sender: ObservableList<T>?, positionStart: Int, itemCount: Int) {
            notifyItemRangeRemoved(positionStart, itemCount)
        }

        override fun onItemRangeMoved(sender: ObservableList<T>?, fromPosition: Int, toPosition: Int, itemCount: Int) {
            notifyItemMoved(fromPosition, toPosition)
        }

        override fun onItemRangeInserted(sender: ObservableList<T>?, positionStart: Int, itemCount: Int) {
            notifyItemRangeInserted(positionStart, itemCount)
        }

        override fun onItemRangeChanged(sender: ObservableList<T>?, positionStart: Int, itemCount: Int) {
            notifyItemRangeChanged(positionStart, itemCount)
        }
    }
}


inline fun <T> buildsAdapter(
        list: ObservableList<T>,
        @LayoutRes crossinline getLayout: (item: T, position: Int) -> Int
): DataBindingAdapter<T> = object : DataBindingAdapter<T>(list) {
    override fun getLayout(item: T, position: Int) = getLayout(item, position)
}

inline fun <T> ObservableList<T>.createAdapter(
        @LayoutRes crossinline getLayout: (item: T, position: Int) -> Int
): DataBindingAdapter<T> = buildsAdapter(this, getLayout)

inline fun <T> ObservableList<T>.createAdapter(
        @LayoutRes crossinline getLayout: (item: T) -> Int
): DataBindingAdapter<T> = buildsAdapter(this) { item, _ -> getLayout(item) }


interface DataBindingListItem {
    @get:LayoutRes val layout: Int
}

interface RecyclerViewItem : DataBindingListItem {
    val id: Any
}


class DataBindingListAdapter : ListAdapter<RecyclerViewItem, DataBindingViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int)
            = DataBindingViewHolder(parent, viewType)

    override fun onBindViewHolder(holder: DataBindingViewHolder, position: Int)
            = holder.bind(getItem(position))

    override fun getItemViewType(position: Int) = getItem(position).layout

    operator fun get(pos: Int): RecyclerViewItem = getItem(pos)
}

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


@ObsoleteCoroutinesApi
fun CoroutineScope.createListAdapter(items: ReceiveChannel<List<RecyclerViewItem>>): DataBindingListAdapter {
    val adapter = DataBindingListAdapter()
    launch(Dispatchers.Main) {
        items.consumeEach { adapter.submitList(it) }
    }
    return adapter
}