package com.ebook.basebook.view.ImmerseView;

import android.app.Activity;
import android.content.res.TypedArray;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;

import com.ebook.basebook.R;
import com.ebook.common.util.StatusBarUtils;

/**
 * 类描述：沉浸布局管理器
 */
public class ImmerseManager {
    private final ViewGroup viewGroup;
    private final ImmerseView immerseView;
    private boolean allImmerse = false;    //默认内部内容不沉浸  默认会设置paddingTop
    private boolean immerseNotchScreen = true;   //默认如果是异形屏 也要沉浸式

    private int paddingTop = 0;
    private int realHeight = 0;
    private FrameLayout rootView;

    public ImmerseManager(@NonNull ViewGroup viewGroup, AttributeSet attrs) {
        if (viewGroup instanceof ImmerseView) {
            this.viewGroup = viewGroup;
            this.immerseView = (ImmerseView) this.viewGroup;
            init(attrs);
        } else {
            throw new RuntimeException("ViewGroup并未实现ImmerseView接口");
        }
    }

    private void init(AttributeSet attrs) {
        if (attrs != null) {
            TypedArray typedArray = viewGroup.getContext().obtainStyledAttributes(attrs, R.styleable.ImmerseTitleLayout);
            allImmerse = typedArray.getBoolean(R.styleable.ImmerseTitleLayout_need_immerse, allImmerse);
            immerseNotchScreen = typedArray.getBoolean(R.styleable.ImmerseTitleLayout_need_immerse_notchscreen, immerseNotchScreen);
            typedArray.recycle();
        }
        paddingTop = viewGroup.getPaddingTop();
        if (immerseNotchScreen || !StatusBarUtils.isNotchScreen(viewGroup.getContext())) {
            rootView = ((Activity) viewGroup.getContext()).findViewById(android.R.id.content);
            ((Activity) viewGroup.getContext()).getWindow().setFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS,
                    WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
            immerseView.setImmersePadding(viewGroup.getPaddingLeft(), getPaddingTop(paddingTop), viewGroup.getPaddingRight(), viewGroup.getPaddingBottom());
        }
    }

    public void setImmersePadding(int left, int top, int right, int bottom) {
        immerseView.setImmersePadding(left, getPaddingTop(top), right, bottom);
    }

    public MeasureHeightResult onMeasureHeight(int heightMeasureSpec) {
        MeasureHeightResult measureHeightResult = new MeasureHeightResult();
        int heightMode = View.MeasureSpec.getMode(heightMeasureSpec);
        int tempHeight = View.MeasureSpec.getSize(heightMeasureSpec);
        if ((immerseNotchScreen || !StatusBarUtils.isNotchScreen(viewGroup.getContext())) && rootView.getChildAt(0) != viewGroup && heightMode == View.MeasureSpec.EXACTLY) {
            if (realHeight != tempHeight) {
                realHeight = tempHeight + StatusBarUtils.getStatus_height();
                measureHeightResult.setHeight(realHeight);
                measureHeightResult.setSuccess(true);
            }
        }
        return measureHeightResult;
    }

    private int getPaddingTop(int paddingTop) {
        this.paddingTop = paddingTop;
        if (!allImmerse && (immerseNotchScreen || !StatusBarUtils.isNotchScreen(viewGroup.getContext()))) {
            return this.paddingTop + StatusBarUtils.getStatus_height();
        } else {
            return this.paddingTop;
        }
    }
}