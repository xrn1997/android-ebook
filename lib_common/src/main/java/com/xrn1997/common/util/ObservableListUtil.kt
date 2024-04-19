package com.xrn1997.common.util

import android.annotation.SuppressLint
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.databinding.ObservableArrayList
import androidx.databinding.ObservableList
import androidx.databinding.ObservableList.OnListChangedCallback
import androidx.recyclerview.widget.RecyclerView

/**
 * ObservableList 工具类
 * @author xrn1997
 * @date 2022/3/5 10:09
 */
object ObservableListUtil {
    /**
     * Adapter 的列表变化回调更新方法
     * @param adapter Adapter<*>
     * @return OnListChangedCallback<T>
     */
    fun <T : ObservableList<Any>> getListChangedCallback(adapter: RecyclerView.Adapter<*>): OnListChangedCallback<T> {
        return object : OnListChangedCallback<T>() {
            @SuppressLint("NotifyDataSetChanged")
            override fun onChanged(sender: T) {
                adapter.notifyDataSetChanged()
            }

            override fun onItemRangeChanged(sender: T, positionStart: Int, itemCount: Int) {
                adapter.notifyItemRangeChanged(positionStart, itemCount)
            }

            override fun onItemRangeInserted(sender: T, positionStart: Int, itemCount: Int) {
                adapter.notifyItemRangeInserted(positionStart, itemCount)
            }

            @SuppressLint("NotifyDataSetChanged")
            override fun onItemRangeMoved(
                sender: T,
                fromPosition: Int,
                toPosition: Int,
                itemCount: Int
            ) {
                if (itemCount == 1) {
                    adapter.notifyItemMoved(fromPosition, toPosition)
                } else {
                    adapter.notifyDataSetChanged()
                }
            }

            override fun onItemRangeRemoved(sender: T, positionStart: Int, itemCount: Int) {
                adapter.notifyItemRangeRemoved(positionStart, itemCount)
            }

        }
    }
}

/**
 * ObservableArrayList Compose适配
 */
@Composable
fun <T> ObservableArrayList<T>.observeAsStateList(): SnapshotStateList<T> {
    val TAG = "observeAsStateList"
    val stateList = remember { mutableStateListOf<T>() }
    stateList.addAll(this)
    DisposableEffect(this) {
        val callback = object : OnListChangedCallback<ObservableList<T>>() {
            override fun onChanged(sender: ObservableList<T>?) {
                // 更新整个列表
                stateList.clear()
                stateList.addAll(this@observeAsStateList)
            }

            override fun onItemRangeChanged(
                sender: ObservableList<T>?,
                positionStart: Int,
                itemCount: Int
            ) {
                // 更新指定范围内的项
                for (i in positionStart until positionStart + itemCount) {
                    stateList[i] = this@observeAsStateList[i]
                }
            }

            override fun onItemRangeInserted(
                sender: ObservableList<T>?,
                positionStart: Int,
                itemCount: Int
            ) {
                // 在指定位置插入新项
                val addItems =
                    this@observeAsStateList.subList(positionStart, positionStart + itemCount)
                stateList.addAll(positionStart, addItems)
            }


            override fun onItemRangeMoved(
                sender: ObservableList<T>?,
                fromPosition: Int,
                toPosition: Int,
                itemCount: Int
            ) {
                // 移动指定范围内的项
                val movedItems = stateList.subList(fromPosition, fromPosition + itemCount)
                stateList.removeAll(movedItems)
                stateList.addAll(toPosition, movedItems)
            }

            override fun onItemRangeRemoved(
                sender: ObservableList<T>?,
                positionStart: Int,
                itemCount: Int
            ) {
                // 移除指定范围内的项（反向顺序）
                for (i in (positionStart + itemCount - 1) downTo positionStart) {
                    stateList.removeAt(i)
                }
            }
        }
        addOnListChangedCallback(callback)

        // 移除回调函数
        onDispose {
            removeOnListChangedCallback(callback)
        }
    }

    return stateList
}


