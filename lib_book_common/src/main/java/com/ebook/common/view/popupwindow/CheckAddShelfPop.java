package com.ebook.common.view.popupwindow;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.PopupWindow;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import com.ebook.common.R;

public class CheckAddShelfPop extends PopupWindow {
    private final Context mContext;
    private final View view;
    private final OnItemClickListener itemClick;
    private final String bookName;

    public CheckAddShelfPop(Context context, @NonNull String bookName, @NonNull OnItemClickListener itemClick) {
        super(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mContext = context;
        this.bookName = bookName;
        this.itemClick = itemClick;
        view = LayoutInflater.from(mContext).inflate(R.layout.view_pop_checkaddshelf, null);
        this.setContentView(view);

        initView();
        setBackgroundDrawable(ContextCompat.getDrawable(mContext, R.drawable.shape_pop_checkaddshelf_bg));
        setFocusable(true);
        setTouchable(true);
        setAnimationStyle(R.style.anim_pop_checkaddshelf);
    }

    private void initView() {
        TextView tvBookName = view.findViewById(R.id.tv_book_name);
        tvBookName.setText(String.format(mContext.getString(R.string.tv_pop_checkaddshelf), bookName));
        TextView tvExit = view.findViewById(R.id.tv_exit);
        tvExit.setOnClickListener(v -> {
            dismiss();
            itemClick.clickExit();
        });
        TextView tvAddShelf = view.findViewById(R.id.tv_addshelf);
        tvAddShelf.setOnClickListener(v -> itemClick.clickAddShelf());
    }

    public interface OnItemClickListener {
        void clickExit();

        void clickAddShelf();
    }
}
