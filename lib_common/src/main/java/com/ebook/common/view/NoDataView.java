package com.ebook.common.view;

import android.content.Context;

import androidx.annotation.ColorRes;
import androidx.annotation.DrawableRes;

import android.os.Build;
import android.util.AttributeSet;
import android.widget.ImageView;
import android.widget.RelativeLayout;

import com.ebook.common.R;


public class NoDataView extends RelativeLayout {

    private final RelativeLayout mRlNoDataRoot;
    private final ImageView mImgNoDataView;

    public NoDataView(Context context, AttributeSet attrs) {
        super(context, attrs);
        inflate(context, R.layout.view_no_data, this);
        mRlNoDataRoot = findViewById(R.id.rl_no_data_root);
        mImgNoDataView = findViewById(R.id.img_no_data);
    }

    public void setNoDataBackground(@ColorRes int colorResId) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            mRlNoDataRoot.setBackgroundColor(getResources().getColor(colorResId, getContext().getTheme()));
        } else {
            mRlNoDataRoot.setBackgroundColor(getResources().getColor(colorResId));
        }
    }

    public void setNoDataView(@DrawableRes int imgResId) {
        mImgNoDataView.setImageResource(imgResId);
    }
}