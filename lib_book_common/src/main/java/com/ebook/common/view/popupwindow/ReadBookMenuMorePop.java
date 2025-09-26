package com.ebook.common.view.popupwindow;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.PopupWindow;

import androidx.core.content.ContextCompat;

import com.ebook.common.R;


public class ReadBookMenuMorePop extends PopupWindow {
    private final View view;

    private LinearLayout llDownload;
    private LinearLayout llComment;

    public ReadBookMenuMorePop(Context context) {
        super(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        view = LayoutInflater.from(context).inflate(R.layout.view_pop_menumore, null);
        this.setContentView(view);

        initView();

        setBackgroundDrawable(ContextCompat.getDrawable(context, R.drawable.shape_pop_checkaddshelf_bg));
        setFocusable(true);
        setTouchable(true);
        setAnimationStyle(R.style.anim_pop_windowmenumore);
    }

    private void initView() {
        llDownload = view.findViewById(R.id.ll_download);
        llComment = view.findViewById(R.id.ll_comment);
    }

    public void setOnClickDownload(View.OnClickListener clickDownload) {
        llDownload.setOnClickListener(clickDownload);
    }

    public void setOnClickComment(View.OnClickListener clickComment) {
        llComment.setOnClickListener(clickComment);
    }
}
