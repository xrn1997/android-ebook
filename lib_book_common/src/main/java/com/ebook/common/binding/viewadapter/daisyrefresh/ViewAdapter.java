package com.ebook.common.binding.viewadapter.daisyrefresh;

import androidx.databinding.BindingAdapter;

import com.ebook.common.binding.command.BindingCommand;
import com.refresh.lib.DaisyRefreshLayout;

public class ViewAdapter {
    @BindingAdapter(value = {"onRefreshCommand", "onLoadMoreCommand", "onAutoRefreshCommand"}, requireAll = false)
    public static void onRefreshCommand(DaisyRefreshLayout refreshLayout, final BindingCommand onRefreshCommand, final BindingCommand onLoadMoreCommond, final BindingCommand onAutoRerefeshCommond) {
        refreshLayout.setOnRefreshListener(() -> {
            if (onRefreshCommand != null) {
                onRefreshCommand.execute();
            }
        });
        refreshLayout.setOnLoadMoreListener(() -> {
            if (onLoadMoreCommond != null) {
                onLoadMoreCommond.execute();
            }
        });
        refreshLayout.setOnAutoLoadListener(() -> {
            if (onAutoRerefeshCommond != null) {
                onAutoRerefeshCommond.execute();
            }
        });
    }
}
