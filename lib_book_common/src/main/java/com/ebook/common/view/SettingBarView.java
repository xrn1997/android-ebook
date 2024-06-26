package com.ebook.common.view;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.TypedArray;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.AttributeSet;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.RelativeLayout;
import android.widget.TextView;

import androidx.databinding.BindingAdapter;
import androidx.databinding.InverseBindingAdapter;
import androidx.databinding.InverseBindingListener;

import com.ebook.common.R;
import com.xrn1997.common.adapter.binding.BindingCommand;


public class SettingBarView extends RelativeLayout {
    public static final String TAG = "SettingBarView";
    private final ImageView imgLeftIcon;
    private final EditText txtSetContent;
    private final RelativeLayout layoutSettingBar;
    private OnClickSettingBarViewListener mOnClickSettingBarViewListener;
    private OnClickRightImgListener mOnClickRightImgListener;
    private boolean isEdit = false;//是否需要编辑
    private OnViewChangeListener mOnViewChangeListener;

    public SettingBarView(Context context, AttributeSet attrs) {
        super(context, attrs);
        inflate(context, R.layout.view_setting_bar, this);
        layoutSettingBar = findViewById(R.id.layout_setting_bar);
        imgLeftIcon = findViewById(R.id.img_start_icon);
        TextView txtSetTitle = findViewById(R.id.txt_set_title);
        txtSetContent = findViewById(R.id.txt_set_content);
        ImageView imgRightIcon = findViewById(R.id.img_end_icon);
        TypedArray typedArray = context.obtainStyledAttributes(attrs, R.styleable.SettingBarView);
        boolean isVisibleLeftIcon = typedArray.getBoolean(R.styleable.SettingBarView_set_left_icon_visible, false);
        boolean isVisibleRightIcon = typedArray.getBoolean(R.styleable.SettingBarView_set_right_icon_visible, false);
        String title = typedArray.getString(R.styleable.SettingBarView_set_title);
        String hint = typedArray.getString(R.styleable.SettingBarView_set_content_hint);
        String content = typedArray.getString(R.styleable.SettingBarView_set_content);
        int rightIcon = typedArray.getResourceId(R.styleable.SettingBarView_set_right_icon, 0);
        int leftIcon = typedArray.getResourceId(R.styleable.SettingBarView_set_left_icon, 0);

        imgLeftIcon.setVisibility(isVisibleLeftIcon ? View.VISIBLE : View.GONE);
        txtSetTitle.setText(title);
        txtSetContent.setHint(hint);
        txtSetContent.setText(content);
        imgRightIcon.setVisibility(isVisibleRightIcon ? View.VISIBLE : View.GONE);
        if (leftIcon > 0) {
            imgLeftIcon.setImageResource(leftIcon);
        }
        if (rightIcon > 0) {
            imgRightIcon.setImageResource(rightIcon);
        }
        imgRightIcon.setOnClickListener(v -> {
            if (mOnClickRightImgListener != null) {
                mOnClickRightImgListener.onClick();
            }
        });
        layoutSettingBar.setOnClickListener(v -> {
            if (mOnClickSettingBarViewListener != null) {
                mOnClickSettingBarViewListener.onClick();
            }
        });
        txtSetContent.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {

            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                Log.v(TAG, "onTextChanged start....");
                if (mOnViewChangeListener != null) {
                    mOnViewChangeListener.onChange();
                }
            }

            @Override
            public void afterTextChanged(Editable s) {

            }
        });
    }

    @BindingAdapter(value = "content", requireAll = false)
    public static void setContent(SettingBarView view, String value) {
        if (!TextUtils.isEmpty(view.getContent()) && view.getContent().equals(value)) {
            return;
        }
        if (view.txtSetContent != null && !TextUtils.isEmpty(value)) {
            view.txtSetContent.setText(value);
        }
    }

    @InverseBindingAdapter(attribute = "content", event = "contentAttrChanged")
    public static String getContent(SettingBarView view) {
        return view.getContent();
    }

    @BindingAdapter(value = {"contentAttrChanged"}, requireAll = false)
    public static void setDisplayAttrChanged(SettingBarView view, final InverseBindingListener inverseBindingListener) {
        Log.d(TAG, "setDisplayAttrChanged");

        if (inverseBindingListener == null) {
            view.setViewChangeListener(null);
            Log.d(TAG, "setViewChangeListener(null)");
        } else {
            view.setViewChangeListener(() -> {
                Log.d(TAG, "setDisplayAttrChanged -> onChange");
                inverseBindingListener.onChange();
            });
        }
    }

    @BindingAdapter(value = {"onClickSettingBarView"}, requireAll = false)
    public static void onClickSettingBarView(SettingBarView view, final BindingCommand bindingCommand) {
        view.layoutSettingBar.setOnClickListener(v -> {
            if (bindingCommand != null) {
                bindingCommand.execute();
            }
        });
    }

    public void setOnClickRightImgListener(OnClickRightImgListener onClickRightImgListener) {
        mOnClickRightImgListener = onClickRightImgListener;
    }

    public void setOnClickSettingBarViewListener(OnClickSettingBarViewListener onClickSettingBarViewListener) {
        mOnClickSettingBarViewListener = onClickSettingBarViewListener;
    }

    public String getContent() {
        if (txtSetContent != null) {
            return txtSetContent.getText().toString();
        }
        return null;
    }

    public void setContent(String value) {
        if (txtSetContent != null && !TextUtils.isEmpty(value)) {
            txtSetContent.setText(value);
        }
    }

    private void setViewChangeListener(OnViewChangeListener listener) {
        this.mOnViewChangeListener = listener;
    }

    public void setEnableEditContext(boolean b) {
        isEdit = b;
        if (txtSetContent != null) {
            txtSetContent.setEnabled(b);
        }
    }

    public void showImgLeftIcon(boolean show) {
        if (imgLeftIcon != null) {
            imgLeftIcon.setVisibility(show ? View.VISIBLE : View.GONE);
        }
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        return !isEdit;
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        return layoutSettingBar.onTouchEvent(event);
    }

    public interface OnClickSettingBarViewListener {
        void onClick();
    }

    public interface OnClickRightImgListener {
        void onClick();
    }

    private interface OnViewChangeListener {
        void onChange();
    }

}
