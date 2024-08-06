package com.xrn1997.common.mvvm

import android.content.Context

/**
 * BaseView接口
 * @author xrn1997
 */
interface IBaseView {
    /**
     * 初始化视图.
     *
     */
    fun initView()

    /**
     * 初始化数据.在initView()之后执行.
     * 与initView不同的是,该方法不仅会在Create的时候调用,也会在显示数据时进行调用.
     * 换句话说,就是initView通常只有一次,而initData会在恰当的生命周期中进行调用.
     * 而对于Fragment来说,initView在onCreateView中,initData在onViewCreated中.
     * @see IBaseView.initView
     * @see IBaseView.showNetWorkErrView
     */
    fun initData()

    /**
     * 关闭activity
     */
    fun finishActivity()

    /**
     * 显示加载视图.
     * @param show Boolean
     */
    fun showLoadingView(show: Boolean = true)

    /**
     * 显示无数据视图
     * @param show Boolean
     * @param resId 可以配置图片
     */
    fun showNoDataView(show: Boolean = true, resId: Int? = null)

    /**
     * 显示网络错误视图,网络正常情况下,不会显示该视图,
     * 即便show为true.
     * 注意,该方法应该在检测网络正常后,调用initData.
     * @see IBaseView.initData
     * @param show Boolean
     */
    fun showNetWorkErrView(show: Boolean = true, resId: Int? = null)
    fun getContext(): Context?

}