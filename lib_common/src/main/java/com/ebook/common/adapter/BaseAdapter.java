package com.ebook.common.adapter;

import android.annotation.SuppressLint;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/**
 * @author : xrn1997
 * @date :  2024/1/15
 * @description :RecyclerView Adapter基类
 */
public abstract class BaseAdapter<E, VH extends RecyclerView.ViewHolder> extends RecyclerView.Adapter<VH> {
    protected Context mContext;
    protected List<E> mList;
    protected OnItemClickListener<E> mItemClickListener;
    protected OnItemLongClickListener<E> mOnItemLongClickListener;

    public BaseAdapter(Context context) {
        mContext = context;
        mList = new ArrayList<>();
    }

    /**
     * 创建并且返回ViewHolder
     */
    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        int layout = onBindLayout();
        View view = LayoutInflater.from(mContext).inflate(layout, parent, false);
        return onCreateHolder(view);
    }


    /**
     * ViewHolder 绑定数据
     */
    @Override
    public void onBindViewHolder(@NonNull VH holder, final int position) {
        final E e = mList.get(position);
        if (mItemClickListener != null) {
            holder.itemView.setOnClickListener(view -> mItemClickListener.onItemClick(e, position));
        }
        if (mOnItemLongClickListener != null) {
            holder.itemView.setOnLongClickListener(view -> mOnItemLongClickListener.onItemLongClick(e, position));
        }
        onBindData(holder, e, position);
    }

    /**
     * 返回数据数量
     */
    @Override
    public int getItemCount() {
        return mList.size();
    }

    /**
     * 添加所有数据,不会清空原有数据
     */
    @SuppressLint("NotifyDataSetChanged")
    public void addAll(List<E> list) {
        if (list != null && !list.isEmpty()) {
            mList.addAll(list);
            notifyDataSetChanged();
        }
    }

    /**
     * 更新数据,会清空原有数据
     */
    @SuppressLint("NotifyDataSetChanged")
    public void refresh(List<E> list) {
        mList.clear();
        if (list != null && !list.isEmpty()) {
            mList.addAll(list);
        }
        notifyDataSetChanged();
    }

    /**
     * 根据位置删除数据
     */
    public void remove(int position) {
        mList.remove(position);
        notifyItemRemoved(position);
    }

    /**
     * 根据对象删除数据
     */
    public void remove(E e) {
        int p = mList.indexOf(e);
        mList.remove(e);
        notifyItemRemoved(p);
    }

    /**
     * 根据对象添加数据
     */
    public void add(E e, int position) {
        mList.add(position, e);
        notifyItemInserted(position);
    }

    /**
     * 根据对象添加数据（加在最后）
     */
    public void addLast(E e) {
        add(e, mList.size());
    }

    /**
     * 根据对象添加数据（加在第一个）
     */
    public void addFirst(E e) {
        add(e, 0);
    }

    /**
     * 删除所有数据
     */
    @SuppressLint("NotifyDataSetChanged")
    public void clear() {
        mList.clear();
        notifyDataSetChanged();
    }

    /**
     * 获取数据列表
     */
    public List<E> getDataList() {
        return mList;
    }

    public List<E> getListData() {
        return mList;
    }

    /**
     * item监听
     */
    public void setItemClickListener(OnItemClickListener<E> itemClickListener) {
        mItemClickListener = itemClickListener;
    }

    /**
     * item长按监听
     */
    public void setOnItemLongClickListener(OnItemLongClickListener<E> onItemLongClickListener) {
        mOnItemLongClickListener = onItemLongClickListener;
    }

    /**
     * 根据位置获得item类型
     */
    @Override
    public int getItemViewType(int position) {
        return super.getItemViewType(position);
    }

    /**
     * 绑定item Layout
     */
    protected abstract int onBindLayout();

    /**
     * 直接用
     *
     * @param view itemView
     * @return VH
     */
    protected abstract VH onCreateHolder(View view);

    /**
     * 绑定数据
     *
     * @param holder   viewHolder
     * @param e        item对象
     * @param position 索引
     */
    protected abstract void onBindData(VH holder, E e, int position);

    public interface OnItemClickListener<E> {
        /**
         * 点按
         *
         * @param e        item对象
         * @param position 索引
         */
        void onItemClick(E e, int position);
    }

    public interface OnItemLongClickListener<E> {
        /**
         * 长按
         *
         * @param e        item对象
         * @param position 索引
         */
        boolean onItemLongClick(E e, int position);
    }
}
