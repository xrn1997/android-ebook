package com.ebook.basebook.view.popupwindow;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.PopupWindow;

import androidx.core.content.ContextCompat;

import com.ebook.basebook.R;
import com.ebook.basebook.view.ReadBookControl;
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
        sbKey.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                readBookControl.setCanKeyTurn(isChecked);
            }
        });
        sbClick.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                readBookControl.setCanClickTurn(isChecked);
            }
        });
    }

    private void bindView() {
        sbKey = (SwitchButton) view.findViewById(R.id.sb_key);
        sbClick = (SwitchButton) view.findViewById(R.id.sb_click);

        if (readBookControl.getCanKeyTurn())
            sbKey.setCheckedImmediatelyNoEvent(true);
        else sbKey.setCheckedImmediatelyNoEvent(false);
        if (readBookControl.getCanClickTurn())
            sbClick.setCheckedImmediatelyNoEvent(true);
        else sbClick.setCheckedImmediatelyNoEvent(false);
    }

    private void initData() {
        readBookControl = ReadBookControl.getInstance();
    }
}
