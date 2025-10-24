package com.ebook.common.view.popupwindow;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.PopupWindow;

import androidx.core.content.ContextCompat;

import com.ebook.common.R;
import com.ebook.common.view.ReadBookControl;
import com.kyleduo.switchbutton.SwitchButton;


public class MoreSettingPop extends PopupWindow {
    private Context mContext;
    private View view;

    private SwitchButton sbKey;
    private SwitchButton sbClick;

    private ReadBookControl readBookControl;

    public MoreSettingPop(Context context) {
        super(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mContext = context;

        view = LayoutInflater.from(mContext).inflate(R.layout.view_pop_moresetting, null);
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
        sbKey.setOnCheckedChangeListener((buttonView, isChecked) -> readBookControl.setCanKeyTurn(isChecked));
        sbClick.setOnCheckedChangeListener((buttonView, isChecked) -> readBookControl.setCanClickTurn(isChecked));
    }

    private void bindView() {
        sbKey = view.findViewById(R.id.sb_key);
        sbClick = view.findViewById(R.id.sb_click);

        sbKey.setCheckedImmediatelyNoEvent(readBookControl.getCanKeyTurn());
        sbClick.setCheckedImmediatelyNoEvent(readBookControl.getCanClickTurn());
    }

    private void initData() {
        readBookControl = ReadBookControl.getInstance();
    }
}
