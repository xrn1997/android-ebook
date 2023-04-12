package com.ebook.basebook.view.popupwindow;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.PopupWindow;

import androidx.core.content.ContextCompat;

import com.ebook.basebook.R;


public class ReadBookMenuMorePop extends PopupWindow {
    private Context mContext;
    private View view;

    private LinearLayout llDownload;
    private LinearLayout llComment;

    public ReadBookMenuMorePop(Context context) {
        super(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        this.mContext = context;
        view = LayoutInflater.from(mContext).inflate(R.layout.view_pop_menumore, null);
        this.setContentView(view);

        initView();

        setBackgroundDrawable(ContextCompat.getDrawable(mContext, R.drawable.shape_pop_checkaddshelf_bg));
        setFocusable(true);
        setTouchable(true);
        setAnimationStyle(R.style.anim_pop_windowmenumore);
    }

    private void initView() {
        llDownload = (LinearLayout) view.findViewById(R.id.ll_download);
        llComment = (LinearLayout) view.findViewById(R.id.ll_comment);
    }

    public void setOnClickDownload(View.OnClickListener clickDownload) {
        llDownload.setOnClickListener(clickDownload);
    }

    public void setOnClickComment(View.OnClickListener clickComment) {
        llComment.setOnClickListener(clickComment);
    }
}
