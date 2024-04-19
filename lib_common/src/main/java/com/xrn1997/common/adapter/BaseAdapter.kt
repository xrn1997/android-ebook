package com.xrn1997.common.adapter

import android.annotation.SuppressLint
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView

/**
 * RecyclerView Adapter基类
 * @author : xrn1997
 */
@Suppress("unused")
abstract class BaseAdapter<E, VH : RecyclerView.ViewHolder>(
    protected var mContext: Context
) :
    RecyclerView.Adapter<VH>() {
    protected var mList: MutableList<E>
    protected var mItemClickListener: OnItemClickListener<E>? = null
    protected var mOnItemLongClickListener: OnItemLongClickListener<E>? = null

    init {
        mList = ArrayList()
    }

    /**
     * 创建并且返回ViewHolder
     */
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val layout = onBindLayout()
        val view = LayoutInflater.from(mContext).inflate(layout, parent, false)
        return onCreateHolder(view)
    }

    /**
     * ViewHolder 绑定数据
     */
    override fun onBindViewHolder(holder: VH, position: Int) {
        val e = mList[position]
        if (mItemClickListener != null) {
            holder.itemView.setOnClickListener { mItemClickListener!!.onItemClick(e, position) }
        }
        if (mOnItemLongClickListener != null) {
            holder.itemView.setOnLongClickListener {
                mOnItemLongClickListener!!.onItemLongClick(e, position)
            }
        }
        onBindData(holder, e, position)
    }

    /**
     * 返回数据数量
     */
    override fun getItemCount(): Int {
        return mList.size
    }

    /**
     * 添加所有数据,不会清空原有数据
     */
    @SuppressLint("NotifyDataSetChanged")
    fun addAll(list: List<E>?) {
        if (!list.isNullOrEmpty()) {
            mList.addAll(list)
            notifyDataSetChanged()
        }
    }

    /**
     * 更新数据,会清空原有数据
     */
    @SuppressLint("NotifyDataSetChanged")
    fun refresh(list: List<E>?) {
        mList.clear()
        if (!list.isNullOrEmpty()) {
            mList.addAll(list)
        }
        notifyDataSetChanged()
    }

    /**
     * 根据位置删除数据
     */
    fun remove(position: Int) {
        mList.removeAt(position)
        notifyItemRemoved(position)
    }

    /**
     * 根据对象删除数据
     */
    fun remove(e: E) {
        val p = mList.indexOf(e)
        mList.remove(e)
        notifyItemRemoved(p)
    }

    /**
     * 根据对象添加数据
     */
    fun add(e: E, position: Int) {
        mList.add(position, e)
        notifyItemInserted(position)
    }

    /**
     * 根据对象添加数据（加在最后）
     */
    fun addLast(e: E) {
        add(e, mList.size)
    }

    /**
     * 根据对象添加数据（加在第一个）
     */
    fun addFirst(e: E) {
        add(e, 0)
    }

    /**
     * 删除所有数据
     */
    @SuppressLint("NotifyDataSetChanged")
    fun clear() {
        mList.clear()
        notifyDataSetChanged()
    }

    val dataList: List<E>
        /**
         * 获取数据列表
         */
        get() = mList
    val listData: List<E>
        get() = mList

    /**
     * item监听
     */
    fun setItemClickListener(itemClickListener: OnItemClickListener<E>?) {
        mItemClickListener = itemClickListener
    }

    /**
     * item长按监听
     */
    fun setOnItemLongClickListener(onItemLongClickListener: OnItemLongClickListener<E>?) {
        mOnItemLongClickListener = onItemLongClickListener
    }

    /**
     * 根据位置获得item类型
     */
    override fun getItemViewType(position: Int): Int {
        return super.getItemViewType(position)
    }

    /**
     * 绑定item Layout
     */
    protected abstract fun onBindLayout(): Int

    /**
     * 直接用
     *
     * @param view itemView
     * @return VH
     */
    protected abstract fun onCreateHolder(view: View): VH

    /**
     * 绑定数据
     *
     * @param holder   viewHolder
     * @param e        item对象
     * @param position 索引
     */
    protected abstract fun onBindData(holder: VH, e: E, position: Int)
    interface OnItemClickListener<E> {
        /**
         * 点按
         *
         * @param e        item对象
         * @param position 索引
         */
        fun onItemClick(e: E, position: Int)
    }

    interface OnItemLongClickListener<E> {
        /**
         * 长按
         *
         * @param e        item对象
         * @param position 索引
         */
        fun onItemLongClick(e: E, position: Int): Boolean
    }
}
