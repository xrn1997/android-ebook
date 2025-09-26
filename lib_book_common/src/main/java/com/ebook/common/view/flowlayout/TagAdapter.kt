package com.ebook.common.view.flowlayout

import android.view.View


abstract class TagAdapter<T>(data: MutableList<T>) {
    protected var mTagData: MutableList<T> = data
    var preCheckedList: HashSet<Int> = HashSet()
        protected set
    private var mOnDataChangedListener: OnDataChangedListener? = null

    fun setOnDataChangedListener(listener: OnDataChangedListener?) {
        mOnDataChangedListener = listener
    }

    fun setSelectedList(vararg poses: Int) {
        val set: MutableSet<Int> = HashSet()
        for (pos in poses) {
            set.add(pos)
        }
        setSelectedList(set)
    }

    fun setSelectedList(set: Set<Int>) {
        preCheckedList.clear()
        preCheckedList.addAll(set)
        notifyDataChanged()
    }

    @Synchronized
    fun replaceAll(newData: List<T>) {
        mTagData.clear()
        mTagData.addAll(newData)
        notifyDataChanged()
    }

    val count: Int
        get() = mTagData.size

    fun notifyDataChanged() {
        mOnDataChangedListener!!.onChanged()
    }

    fun getItem(position: Int): T {
        return mTagData[position]
    }

    abstract fun getView(parent: FlowLayout, position: Int, item: T): View

    interface OnDataChangedListener {
        fun onChanged()
    }
}