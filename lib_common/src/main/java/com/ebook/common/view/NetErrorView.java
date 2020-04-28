package com.ebook.common.view;

import android.content.Context;
import androidx.annotation.ColorRes;

import android.os.Build;
import android.util.AttributeSet;
import android.view.View;
import android.widget.RelativeLayout;

import com.ebook.common.R;


public class NetErrorView extends RelativeLayout {
    private OnClickListener mOnClickListener;
    private final RelativeLayout mRlNetWorkError;

    public NetErrorView(Context context, AttributeSet attrs) {
        super(context, attrs);
        inflate(context, R.layout.view_net_error,this);
        findViewById(R.id.btn_net_refresh).setOnClickListener(new OnClickListener(){

            @Override
            public void onClick(View v) {
                if(mOnClickListener != null){
                    mOnClickListener.onClick(v);
                }
            }
        });
        mRlNetWorkError = findViewById(R.id.rl_net_error_root);
    }
    public void setRefreshBtnClickListener(OnClickListener listener){
        mOnClickListener = listener;
    }

    public void setNetErrorBackground(@ColorRes int  colorResId){
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            mRlNetWorkError.setBackgroundColor(getResources().getColor(colorResId,getContext().getTheme()));
        } else {
            mRlNetWorkError.setBackgroundColor(getResources().getColor(colorResId));
        }

    }
}
