package com.xrn1997.common.adapter

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.LayoutRes
import androidx.databinding.DataBindingUtil
import androidx.databinding.ObservableArrayList
import androidx.databinding.ViewDataBinding
import androidx.recyclerview.widget.RecyclerView

/**
 * 使用DataBinding的BaseAdapter
 * @author xrn1997
 */
@Suppress("unused")
abstract class BaseBindAdapter<T, B : ViewDataBinding>(
    @JvmField
    protected var context: Context,
    protected var items: ObservableArrayList<T>
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    @JvmField
    protected var mOnItemClickListener: OnItemClickListener<T>? = null

    @JvmField
    protected var mOnItemLongClickListener: OnItemLongClickListener<T>? = null
    override fun getItemCount(): Int {
        return if (items.size > 0) items.size else 0
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val binding: B = DataBindingUtil.inflate(
            LayoutInflater.from(context), getLayoutItemId(
                viewType
            ), parent, false
        )
        return BaseBindingViewHolder(binding.root)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val binding: B? = DataBindingUtil.getBinding(holder.itemView)
        items[position]?.let { onBindItem(binding!!, it, position) }
    }

    class BaseBindingViewHolder internal constructor(itemView: View?) : RecyclerView.ViewHolder(
        itemView!!
    )

    /**
     * 获得item的布局id
     */
    @LayoutRes
    protected abstract fun getLayoutItemId(viewType: Int): Int

    protected abstract fun onBindItem(binding: B, item: T, position: Int)

    /**
     * item监听
     */
    open fun setOnItemClickListener(itemClickListener: OnItemClickListener<T>) {
        mOnItemClickListener = itemClickListener
    }

    /**
     * item长按监听
     */
    open fun setOnItemLongClickListener(onItemLongClickListener: OnItemLongClickListener<T>?) {
        mOnItemLongClickListener = onItemLongClickListener
    }

    /**
     * item click监听接口
     */
    interface OnItemClickListener<E> {
        fun onItemClick(e: E, position: Int)
    }

    /**
     * item long click监听接口
     */
    interface OnItemLongClickListener<E> {
        fun onItemLongClick(e: E, position: Int): Boolean
    }

}