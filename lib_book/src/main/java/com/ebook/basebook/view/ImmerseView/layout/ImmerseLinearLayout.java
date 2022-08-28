package com.ebook.basebook.view.ImmerseView.layout;


import android.content.Context;
import android.util.AttributeSet;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import com.ebook.basebook.view.ImmerseView.ImmerseManager;
import com.ebook.basebook.view.ImmerseView.ImmerseView;
import com.ebook.basebook.view.ImmerseView.MeasureHeightResult;

/**
 * 沉浸式LinearLayout
 */
public class ImmerseLinearLayout extends LinearLayout implements ImmerseView {

    private ImmerseManager immerseManager;

    public ImmerseLinearLayout(Context context) {
        super(context);
        initManager(null);
    }

    public ImmerseLinearLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        initManager(attrs);
    }

    public ImmerseLinearLayout(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        initManager(attrs);
    }


    public ImmerseLinearLayout(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        initManager(attrs);
    }

    public void initManager(AttributeSet attrs) {
        immerseManager = new ImmerseManager(this, attrs);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        MeasureHeightResult resultHeight = immerseManager.onMeasureHeight(heightMeasureSpec);
        ViewGroup.LayoutParams layoutParams = getLayoutParams();
        if (resultHeight.isSuccess() && layoutParams != null && layoutParams.height != ViewGroup.LayoutParams.MATCH_PARENT) {
            heightMeasureSpec = MeasureSpec.makeMeasureSpec(resultHeight.getHeight(), MeasureSpec.EXACTLY);
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }

    @Override
    public void setPadding(int left, int top, int right, int bottom) {
        immerseManager.setImmersePadding(left, top, right, bottom);
    }

    @Override
    public void setImmersePadding(int left, int top, int right, int bottom) {
        super.setPadding(left, top, right, bottom);
    }
}