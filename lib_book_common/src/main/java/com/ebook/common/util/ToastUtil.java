package com.ebook.common.util;

import android.widget.Toast;

import com.ebook.common.BaseApplication;

/**
 * Toast工具类
 */
public class ToastUtil {

    public static void showToast(String message) {
        Toast.makeText(BaseApplication.context, message, Toast.LENGTH_SHORT).show();
    }

    public static void showToast(int resid) {
        Toast.makeText(BaseApplication.context, BaseApplication.context.getString(resid), Toast.LENGTH_SHORT)
                .show();
    }
}