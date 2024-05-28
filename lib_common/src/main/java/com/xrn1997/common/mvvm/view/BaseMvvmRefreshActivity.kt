package com.xrn1997.common.mvvm.view

import androidx.databinding.ViewDataBinding
import com.scwang.smart.refresh.layout.api.RefreshLayout
import com.xrn1997.common.adapter.BaseAdapter
import com.xrn1997.common.mvvm.viewmodel.BaseRefreshViewModel


/**
 * 基于MVVM DataBinding的可刷新的Activity基类
 * @author xrn1997
 */
@Suppress("unused")
abstract class BaseMvvmRefreshActivity<V : ViewDataBinding, VM : BaseRefreshViewModel<*, *>> :
    BaseMvvmActivity<V, VM>() {
    protected lateinit var mRefreshLayout: RefreshLayout

    @JvmField
    protected var mOnItemClickListener: BaseAdapter.OnItemClickListener<V>? = null

    @JvmField
    protected var mOnItemLongClickListener: BaseAdapter.OnItemLongClickListener<V>? = null
    override fun initContentView() {
        super.initContentView()
        initRefreshView()
    }

    override fun initBaseViewObservable() {
        super.initBaseViewObservable()
        initBaseViewRefreshObservable()
    }

    private fun initBaseViewRefreshObservable() {
        mViewModel.mUIChangeRefreshLiveData.mAutoRefreshLiveEvent
            .observe(this) { autoLoadData() }
        mViewModel.mUIChangeRefreshLiveData.mStopRefreshLiveEvent
            .observe(this) { success -> stopRefresh(success!!) }
        mViewModel.mUIChangeRefreshLiveData.mStopLoadMoreLiveEvent
            .observe(this) { success -> stopLoadMore(success!!) }
    }

    abstract fun getRefreshLayout(): RefreshLayout
    private fun initRefreshView() {
        mRefreshLayout = getRefreshLayout()
    }

    /**
     * 完成加载
     * @param success Boolean 数据是否成功刷新 （会影响到上次更新时间的改变）
     * @see RefreshLayout.finishRefresh
     */
    open fun stopRefresh(success: Boolean) {
        mRefreshLayout.finishRefresh(success)
    }

    /**
     * 完成加载
     * @param success Boolean 数据是否成功
     * @see RefreshLayout.finishLoadMore
     */
    open fun stopLoadMore(success: Boolean) {
        mRefreshLayout.finishLoadMore(success)
    }

    /**
     * 显示刷新动画并且触发刷新事件
     * @see RefreshLayout.autoRefresh
     */
    open fun autoLoadData() {
        mRefreshLayout.autoRefresh()
    }

    open fun setOnItemClickListener(onItemClickListener: BaseAdapter.OnItemClickListener<V>) {
        mOnItemClickListener = onItemClickListener
    }

    open fun setOnItemLongClickListener(onItemLongClickListener: BaseAdapter.OnItemLongClickListener<V>) {
        mOnItemLongClickListener = onItemLongClickListener
    }
}