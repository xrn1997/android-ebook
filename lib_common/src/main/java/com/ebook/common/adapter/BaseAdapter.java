package com.ebook.common.adapter;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import java.util.ArrayList;
import java.util.List;


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
        int layoutid = onBindLayout();
        View view = LayoutInflater.from(mContext).inflate(layoutid, parent, false);
        return onCreateHolder(view);
    }


    /**
     * ViewHolder 绑定数据
     */
    @Override
    public void onBindViewHolder(@NonNull VH holder, final int position) {
        final E e = mList.get(position);
        if (mItemClickListener != null) {
            holder.itemView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    mItemClickListener.onItemClick(e, position);
                }
            });
        }
        if (mOnItemLongClickListener != null) {
            holder.itemView.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View view) {
                    return mOnItemLongClickListener.onItemLongClick(e, position);
                }
            });
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
     * 添加所有数据
     */
    public void addAll(List<E> list) {
        if (list != null && list.size() > 0) {
            mList.addAll(list);
            notifyDataSetChanged();
        }
    }

    /**
     * 更新数据
     */
    public void refresh(List<E> list) {
        mList.clear();
        if (list != null && list.size() > 0) {
            mList.addAll(list);
        }
        notifyDataSetChanged();
    }

    /**
     * 根据位置删除数据
     */
    public void remove(int positon) {
        mList.remove(positon);
        notifyDataSetChanged();
    }

    /**
     * 根据对象删除数据
     */
    public void remove(E e) {
        mList.remove(e);
        notifyDataSetChanged();
    }

    /**
     * 根据对象添加数据
     */
    public void add(E e) {
        mList.add(e);
        notifyDataSetChanged();
    }

    /**
     * 根据对象添加数据（加在最后）
     */
    public void addLast(E e) {
        add(e);
    }

    /**
     * 根据对象添加数据（加在第一个）
     */
    public void addFirst(E e) {
        mList.add(0, e);
        notifyDataSetChanged();
    }

    /**
     * 删除所有数据
     */
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

    public interface OnItemClickListener<E> {
        void onItemClick(E e, int position);
    }

    public interface OnItemLongClickListener<E> {
        boolean onItemLongClick(E e, int postion);
    }

    protected abstract int onBindLayout();

    protected abstract VH onCreateHolder(View view);

    protected abstract void onBindData(VH holder, E e, int position);
}
