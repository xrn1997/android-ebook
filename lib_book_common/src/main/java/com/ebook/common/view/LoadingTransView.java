package com.ebook.common.view;

import android.content.Context;
import android.graphics.drawable.AnimationDrawable;
import android.util.AttributeSet;
import android.widget.ImageView;
import android.widget.RelativeLayout;

import com.ebook.common.R;


public class LoadingTransView extends RelativeLayout {
    private final AnimationDrawable mAnimationDrawable;

    public LoadingTransView(Context context, AttributeSet attrs) {
        super(context, attrs);
        inflate(context, R.layout.view_trans_loading, this);
        ImageView imgLoading = findViewById(R.id.img_trans_loading);
        mAnimationDrawable = (AnimationDrawable) imgLoading.getDrawable();
    }

    public void startLoading() {
        mAnimationDrawable.start();
    }

    public void stopLoading() {
        mAnimationDrawable.stop();
    }

    public void loading(boolean b) {
        if (b) {
            startLoading();
        } else {
            stopLoading();
        }
    }
}
