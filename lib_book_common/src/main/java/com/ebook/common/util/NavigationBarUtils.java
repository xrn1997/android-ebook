package com.ebook.common.util;

import android.content.res.Resources;

/**
 * 导航栏工具
 */
public class NavigationBarUtils {
    private static int navi_height = -1;   //默认值

    private static int getNavigationBarHeight() {
        try {
            navi_height = Resources.getSystem().getDimensionPixelSize(
                    Resources.getSystem().getIdentifier("navigation_bar_height", "dimen", "android"));
        } catch (Exception e) {
            e.printStackTrace();
            navi_height = 0;
        }
        return navi_height;
    }

    public static int getNavigationBar_height() {
        if (-1 == navi_height) {
            synchronized (NavigationBarUtils.class) {
                if (-1 == navi_height) {
                    navi_height = getNavigationBarHeight();
                }
            }
        }
        return navi_height;
    }
}
