package com.refresh.lib.contract;

/**
 * Description: <上拉加载更多的协议><br>
 */
public interface PushContract {
    /**
     * 手指上滑下滑的回调
     *
     * @param enable
     */
    void onPushEnable(boolean enable);

    /**
     * 手指松开的回调
     */
    void onLoadMore();
}
