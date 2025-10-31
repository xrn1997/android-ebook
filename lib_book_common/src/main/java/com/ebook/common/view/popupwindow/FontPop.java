package com.ebook.common.view.popupwindow;

import android.content.Context;
import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import com.ebook.common.R;
import com.ebook.common.view.ReadBookControl;
import com.ebook.common.view.profilePhoto.CircleImageView;


public class FontPop extends PopupWindow {
    private Context mContext;
    private View view;
    private FrameLayout flSmaller;
    private FrameLayout flBigger;
    private TextView tvTextSizedDefault;
    private TextView tvTextSize;
    private CircleImageView civBgWhite;
    private CircleImageView civBgYellow;
    private CircleImageView civBgGreen;
    private CircleImageView civBgBlack;

    private ReadBookControl readBookControl;
    private OnChangeProListener changeProListener;

    public FontPop(Context context, @NonNull OnChangeProListener changeProListener) {
        super(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        this.mContext = context;
        this.changeProListener = changeProListener;

        view = LayoutInflater.from(mContext).inflate(R.layout.view_pop_font, null);
        this.setContentView(view);
        initData();
        bindView();
        bindEvent();

        setBackgroundDrawable(ContextCompat.getDrawable(mContext, R.drawable.shape_pop_checkaddshelf_bg));
        setFocusable(true);
        setTouchable(true);
        setAnimationStyle(R.style.anim_pop_windowlight);
    }

    private void bindEvent() {
        flSmaller.setOnClickListener(v -> {
            updateText(readBookControl.getTextKindIndex() - 1);
            changeProListener.textChange(readBookControl.getTextKindIndex());
        });
        flBigger.setOnClickListener(v -> {
            updateText(readBookControl.getTextKindIndex() + 1);
            changeProListener.textChange(readBookControl.getTextKindIndex());
        });
        tvTextSizedDefault.setOnClickListener(v -> {
            updateText(ReadBookControl.DEFAULT_TEXT);
            changeProListener.textChange(readBookControl.getTextKindIndex());
        });

        civBgWhite.setOnClickListener(v -> {
            updateBg(0);
            changeProListener.bgChange(readBookControl.getTextDrawableIndex());
        });
        civBgYellow.setOnClickListener(v -> {
            updateBg(1);
            changeProListener.bgChange(readBookControl.getTextDrawableIndex());
        });
        civBgGreen.setOnClickListener(v -> {
            updateBg(2);
            changeProListener.bgChange(readBookControl.getTextDrawableIndex());
        });
        civBgBlack.setOnClickListener(v -> {
            updateBg(3);
            changeProListener.bgChange(readBookControl.getTextDrawableIndex());
        });
    }

    private void bindView() {
        flSmaller = view.findViewById(R.id.fl_smaller);
        flBigger = view.findViewById(R.id.fl_bigger);
        tvTextSizedDefault = view.findViewById(R.id.tv_textsize_default);
        tvTextSize = view.findViewById(R.id.tv_dur_textsize);
        updateText(readBookControl.getTextKindIndex());

        civBgWhite = view.findViewById(R.id.civ_bg_white);
        civBgYellow = view.findViewById(R.id.civ_bg_yellow);
        civBgGreen = view.findViewById(R.id.civ_bg_green);
        civBgBlack = view.findViewById(R.id.civ_bg_black);
        updateBg(readBookControl.getTextDrawableIndex());
    }

    private void updateText(int textKindIndex) {
        if (textKindIndex == 0) {
            flSmaller.setEnabled(false);
            flBigger.setEnabled(true);
        } else if (textKindIndex == readBookControl.getTextKindList().size() - 1) {
            flSmaller.setEnabled(true);
            flBigger.setEnabled(false);
        } else {
            flSmaller.setEnabled(true);
            flBigger.setEnabled(true);

        }
        tvTextSizedDefault.setEnabled(textKindIndex != ReadBookControl.DEFAULT_TEXT);
        tvTextSize.setText(String.valueOf(readBookControl.getTextKindList().get(textKindIndex).getTextSize()));
        readBookControl.updateTextKindIndex(textKindIndex);
    }

    private void updateBg(int index) {
        civBgWhite.setBorderColor(Color.parseColor("#00000000"));
        civBgYellow.setBorderColor(Color.parseColor("#00000000"));
        civBgGreen.setBorderColor(Color.parseColor("#00000000"));
        civBgBlack.setBorderColor(Color.parseColor("#00000000"));
        switch (index) {
            case 0:
                civBgWhite.setBorderColor(Color.parseColor("#F3B63F"));
                break;
            case 1:
                civBgYellow.setBorderColor(Color.parseColor("#F3B63F"));
                break;
            case 2:
                civBgGreen.setBorderColor(Color.parseColor("#F3B63F"));
                break;
            default:
                civBgBlack.setBorderColor(Color.parseColor("#F3B63F"));
                break;
        }
        readBookControl.updateTextDrawableIndex(index);
    }

    private void initData() {
        readBookControl = ReadBookControl.INSTANCE;
    }

    public interface OnChangeProListener {
        void textChange(int index);

        void bgChange(int index);
    }
}