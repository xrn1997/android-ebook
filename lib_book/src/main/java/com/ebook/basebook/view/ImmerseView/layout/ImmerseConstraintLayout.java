package com.ebook.basebook.view.ImmerseView.layout;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;

import com.ebook.basebook.view.ImmerseView.ImmerseView;
import com.ebook.basebook.view.ImmerseView.ImmerseManager;
import com.ebook.basebook.view.ImmerseView.MeasureHeightResult;

import androidx.constraintlayout.widget.ConstraintLayout;

/**
 * 沉浸式ConstraintLayout
 */
public class ImmerseConstraintLayout extends ConstraintLayout implements ImmerseView {
    private ImmerseManager immerseManager;

    public ImmerseConstraintLayout(Context context) {
        super(context);
        initManager(null);
    }

    public ImmerseConstraintLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        initManager(attrs);
    }

    public ImmerseConstraintLayout(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
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
