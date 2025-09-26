package com.ebook.common.view.popupwindow;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.PopupWindow;

import androidx.core.content.ContextCompat;

import com.ebook.common.R;
import com.ebook.common.view.checkbox.SmoothCheckBox;
import com.ebook.common.view.mprogressbar.MHorProgressBar;
import com.ebook.common.view.mprogressbar.OnProgressListener;


public class WindowLightPop extends PopupWindow {
    public final static String SP_NAME = "CONFIG";
    private final static String TAG = "WindowLightPop";
    private final Context mContext;
    private final SharedPreferences preferences;
    private final View view;

    private MHorProgressBar hpbLight;
    private LinearLayout llFollowSys;
    private SmoothCheckBox scbFollowSys;

    private Boolean isFollowSys;
    private int light;

    public WindowLightPop(Context context) {
        super(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        this.mContext = context;
        preferences = context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE);
        view = LayoutInflater.from(mContext).inflate(R.layout.view_pop_windowlight, null);
        this.setContentView(view);
        initData();
        initView();
        bindEvent();

        setBackgroundDrawable(ContextCompat.getDrawable(mContext, R.drawable.shape_pop_checkaddshelf_bg));
        setFocusable(true);
        setTouchable(true);
        setAnimationStyle(R.style.anim_pop_windowlight);
    }

    private void initData() {
        isFollowSys = getIsFollowSys();
        light = getLight();
    }

    private void initView() {
        hpbLight = view.findViewById(R.id.hpb_light);
        llFollowSys = view.findViewById(R.id.ll_follow_sys);
        scbFollowSys = view.findViewById(R.id.scb_follow_sys);
    }

    private void bindEvent() {
        llFollowSys.setOnClickListener(v -> scbFollowSys.setChecked(!scbFollowSys.isChecked(), true));
        scbFollowSys.setOnCheckedChangeListener((checkBox, isChecked) -> {
            isFollowSys = isChecked;
            if (isChecked) {
                //跟随系统
                hpbLight.setCanTouch(false);
                setScreenBrightness();
            } else {
                //不跟随系统
                hpbLight.setCanTouch(true);
                hpbLight.setDurProgress(light);
            }
        });
        hpbLight.setProgressListener(new OnProgressListener() {
            @Override
            public void moveStartProgress(float dur) {

            }

            @Override
            public void durProgressChange(float dur) {
                if (!isFollowSys) {
                    light = (int) dur;
                    setScreenBrightness((int) dur);
                }
            }

            @Override
            public void moveStopProgress(float dur) {

            }

            @Override
            public void setDurProgress(float dur) {

            }
        });
    }

    public void setScreenBrightness() {
        WindowManager.LayoutParams params = ((Activity) mContext).getWindow().getAttributes();
        params.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
        ((Activity) mContext).getWindow().setAttributes(params);
    }

    public int getScreenBrightness() {
        int value = 0;
        ContentResolver cr = mContext.getContentResolver();
        try {
            value = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS);
        } catch (Settings.SettingNotFoundException e) {
            Log.e(TAG, "getScreenBrightness: ", e);
        }
        return value;
    }

    public void setScreenBrightness(int value) {
        WindowManager.LayoutParams params = ((Activity) mContext).getWindow().getAttributes();
        params.screenBrightness = value * 1.0f / 255f;
        ((Activity) mContext).getWindow().setAttributes(params);
    }

    private void saveLight() {
        SharedPreferences.Editor editor = preferences.edit();
        editor.putInt("light", light);
        editor.putBoolean("is_follow_sys", isFollowSys);
        editor.apply();
    }

    private int getLight() {
        return preferences.getInt("light", getScreenBrightness());
    }

    private boolean getIsFollowSys() { // Use 'boolean' instead of 'Boolean'
        return preferences.getBoolean("is_follow_sys", true);
    }


    @Override
    public void dismiss() {
        saveLight();
        super.dismiss();
    }

    @Override
    public void showAtLocation(View parent, int gravity, int x, int y) {
        super.showAtLocation(parent, gravity, x, y);
        initData();
        hpbLight.setDurProgress(light);
        scbFollowSys.setChecked(isFollowSys);
    }

    public void initLight() {
        if (!isFollowSys) {
            setScreenBrightness(light);
        }
    }
}
