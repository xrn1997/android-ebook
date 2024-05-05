package com.xrn1997.common.adapter

import android.graphics.drawable.Drawable
import android.widget.ImageView
import androidx.databinding.BindingAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.scwang.smart.refresh.layout.SmartRefreshLayout
import com.xrn1997.common.adapter.binding.BindingCommand

/**
 * Binding XML
 * @author xrn1997
 */
object ViewAdapter {
    /**
     * Glide与DataBindingAdapter结合使用
     * @param imageView 对应的图片的上下文，一般不必主动传参（自动传参）
     * @param url 对应的是xml中的app:imageUrl属性，可以绑定网路图片的网址
     * @param holderDrawable 对应的是xml中的app:placeHolder属性可以绑定占位图
     */
    @BindingAdapter(value = ["imageUrl", "placeHolder"], requireAll = false)
    @JvmStatic
    fun loadImage(imageView: ImageView, url: String?, holderDrawable: Drawable?) {
        // Log.d("glide_cover", "loadImage url: "+url);
        Glide.with(imageView.context)
            .load(url)
            .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
            .fitCenter()
            .dontAnimate()
            .placeholder(holderDrawable)
            .into(imageView)
    }

    /**
     *  绑定刷新和加载命令
     * @param refreshLayout RefreshLayout
     * @param onRefreshCommand BindingCommand? 刷新命令
     * @param onLoadMoreCommand BindingCommand? 加载更多命令
     */
    @BindingAdapter(value = ["onRefreshCommand", "onLoadMoreCommand"], requireAll = false)
    @JvmStatic
    fun onCommand(
        refreshLayout: SmartRefreshLayout,
        onRefreshCommand: BindingCommand?,
        onLoadMoreCommand: BindingCommand?,
    ) {
        refreshLayout.setOnRefreshListener {
            onRefreshCommand?.execute()
        }
        refreshLayout.setOnLoadMoreListener {
            onLoadMoreCommand?.execute()
        }
    }

    /**
     * 绑定启停刷新和加载更多
     * @param refreshLayout SmartRefreshLayout
     * @param srlEnableRefresh Boolean
     * @param srlEnableLoadMore Boolean
     */
    @BindingAdapter(value = ["srlEnableRefresh", "srlEnableLoadMore"], requireAll = false)
    @JvmStatic
    fun setEnableRefreshOrLoadMore(
        refreshLayout: SmartRefreshLayout,
        srlEnableRefresh: Boolean,
        srlEnableLoadMore: Boolean
    ) {
        refreshLayout.setEnableRefresh(srlEnableRefresh)
        refreshLayout.setEnableLoadMore(srlEnableLoadMore)
    }

    /**
     * recyclerView的
     */
    @BindingAdapter("linearLayoutManager")
    @JvmStatic
    fun setLinearLayoutManager(recyclerView: RecyclerView, b: Boolean) {
        val layoutManager = LinearLayoutManager(recyclerView.context)
        layoutManager.orientation =
            if (b) LinearLayoutManager.HORIZONTAL else LinearLayoutManager.VERTICAL
        recyclerView.layoutManager = layoutManager
    }
}