package com.ebook.basebook.view.ImmerseView.viewpager;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;

import com.ebook.basebook.view.ImmerseView.ImmerseManager;
import com.ebook.basebook.view.ImmerseView.ImmerseView;
import com.ebook.basebook.view.ImmerseView.MeasureHeightResult;

import androidx.viewpager.widget.ViewPager;

/**
 * 沉浸式ViewPager
 */
public class ImmerseViewPager extends ViewPager implements ImmerseView {
    private ImmerseManager immerseManager;

    public ImmerseViewPager(Context context) {
        super(context);
        initManager(null);
    }

    public ImmerseViewPager(Context context, AttributeSet attrs) {
        super(context, attrs);
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
            heightMeasureSpec = View.MeasureSpec.makeMeasureSpec(resultHeight.getHeight(), View.MeasureSpec.EXACTLY);
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
