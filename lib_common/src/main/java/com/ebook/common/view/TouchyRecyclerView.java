package com.ebook.common.view;

import android.content.Context;

import androidx.recyclerview.widget.RecyclerView;

import android.util.AttributeSet;
import android.view.MotionEvent;


public class TouchyRecyclerView extends RecyclerView {
    private OnNoChildClickListener listener;

    public TouchyRecyclerView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public interface OnNoChildClickListener {
        public void onNoChildClick();
    }

    public void setOnNoChildClickListener(OnNoChildClickListener listener) {
        this.listener = listener;
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN
                && findChildViewUnder(event.getX(), event.getY()) == null) {
            if (listener != null) {
                listener.onNoChildClick();
            }
        }
        return super.dispatchTouchEvent(event);
    }
}
