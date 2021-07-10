package com.ebook.common.util;

import android.annotation.TargetApi;
import android.app.Activity;
import android.content.Context;
import android.content.res.Resources;
import android.os.Build;
import android.view.DisplayCutout;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import androidx.annotation.NonNull;

/**
 * 状态栏工具
 */
public class StatusBarUtils {
    private static int status_height = -1;

    /**
     * 获取状态栏高度
     *
     * @return
     */
    private static int getStatusBarHeight() {
        try {
            status_height = Resources.getSystem().getDimensionPixelSize(
                    Resources.getSystem().getIdentifier("status_bar_height", "dimen", "android"));
        } catch (Exception e) {
            e.printStackTrace();
            status_height = 0;
        }
        return status_height;
    }

    public static int getStatus_height() {
        if (-1 == status_height) {
            synchronized (StatusBarUtils.class) {
                if (-1 == status_height) {
                    status_height = getStatusBarHeight();
                }
            }
        }
        return status_height;
    }

    ///////////////////////////////////////////////////////////////////////////////////////////////
    private static Boolean isNotchScreen = null;

    /**
     * 判断是否是刘海屏
     */
    public static boolean isNotchScreen(Context context) {
        if (null == isNotchScreen) {
            synchronized (StatusBarUtils.class) {
                if (null == isNotchScreen) {
                    isNotchScreen = hasNotchScreen(context);
                }
            }
        }
        return isNotchScreen;
    }

    /**
     * 判断是否是刘海屏
     *
     * @return
     */
    private static boolean hasNotchScreen(Context context) {

        if (getInt("ro.miui.notch", context) == 1 || hasNotchAtHuawei(context) || hasNotchAtOPPO(context)
                || hasNotchAtVivo(context) || isAndroidP(context) != null) {
            return true;
        }

        return false;
    }

    /**
     * Android P 刘海屏判断
     *
     * @param context
     * @return
     */
    private static DisplayCutout isAndroidP(Context context) {
        try {
            View decorView = ((Activity) context).getWindow().getDecorView();
            if (decorView != null && android.os.Build.VERSION.SDK_INT >= 28) {
                WindowInsets windowInsets = decorView.getRootWindowInsets();
                if (windowInsets != null)
                    return windowInsets.getDisplayCutout();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    /**
     * 小米刘海屏判断.
     *
     * @return 0 if it is not notch ; return 1 means notch
     * @throws IllegalArgumentException if the key exceeds 32 characters
     */
    private static int getInt(String key, Context context) {
        int result = 0;
        if ("Xiaomi".equals(Build.MANUFACTURER)) {
            try {
                ClassLoader classLoader = context.getClassLoader();
                @SuppressWarnings("rawtypes")
                Class SystemProperties = classLoader.loadClass("android.os.SystemProperties");
                //参数类型
                @SuppressWarnings("rawtypes")
                Class[] paramTypes = new Class[2];
                paramTypes[0] = String.class;
                paramTypes[1] = int.class;
                Method getInt = SystemProperties.getMethod("getInt", paramTypes);
                //参数
                Object[] params = new Object[2];
                params[0] = new String(key);
                params[1] = Integer.valueOf(0);
                result = (Integer) getInt.invoke(SystemProperties, params);

            } catch (Exception e) {

            }
        }
        return result;
    }

    /**
     * 华为刘海屏判断
     *
     * @return
     */
    private static boolean hasNotchAtHuawei(Context context) {
        boolean ret = false;
        try {
            ClassLoader classLoader = context.getClassLoader();
            Class HwNotchSizeUtil = classLoader.loadClass("com.huawei.android.util.HwNotchSizeUtil");
            Method get = HwNotchSizeUtil.getMethod("hasNotchInScreen");
            ret = (boolean) get.invoke(HwNotchSizeUtil);
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            return ret;
        }
    }

    private static final int VIVO_NOTCH = 0x00000020;//是否有刘海
    private static final int VIVO_FILLET = 0x00000008;//是否有圆角

    /**
     * VIVO刘海屏判断
     *
     * @return
     */
    private static boolean hasNotchAtVivo(Context context) {
        boolean ret = false;
        try {
            ClassLoader classLoader = context.getClassLoader();
            Class FtFeature = classLoader.loadClass("android.util.FtFeature");
            Method method = FtFeature.getMethod("isFeatureSupport", int.class);
            ret = (boolean) method.invoke(FtFeature, VIVO_NOTCH);
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            return ret;
        }
    }

    /**
     * OPPO刘海屏判断
     *
     * @return
     */
    private static boolean hasNotchAtOPPO(Context context) {
        return context.getPackageManager().hasSystemFeature("com.oppo.feature.screen.heteromorphism");
    }

    ////////////////////////////////////////////////////////////////////////////////

    /**
     * 状态栏字体，icon黑色
     */
    @TargetApi(Build.VERSION_CODES.M)
    public static void setDarkMode(Activity activity) {
        setMIUIStatusBarDarkIcon(activity, true);
        setMeizuStatusBarDarkIcon(activity, true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            //将状态栏标记为浅色，字体就会变成黑色
            activity.getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            //无法修改状态栏字体，icon颜色，认为开发者既然需要黑色的，可能主色是浅色，所以为了让状态栏能看清，所以给设置一个深色背景
//            activity.getWindow().setStatusBarColor(0xff999999);
        } else {
            //5.0以下不支持
        }
    }

    /**
     * 状态栏字体，icon白色
     */
    @TargetApi(Build.VERSION_CODES.M)
    public static void setLightMode(Activity activity) {
        setMIUIStatusBarDarkIcon(activity, false);
        setMeizuStatusBarDarkIcon(activity, false);
        //状态栏默认标记为深色，字体就会变成白色
    }

    /**
     * 修改 MIUI V6  以上状态栏颜色
     */
    private static void setMIUIStatusBarDarkIcon(@NonNull Activity activity, boolean darkIcon) {
        Class<? extends Window> clazz = activity.getWindow().getClass();
        try {
            Class<?> layoutParams = Class.forName("android.view.MiuiWindowManager$LayoutParams");
            Field field = layoutParams.getField("EXTRA_FLAG_STATUS_BAR_DARK_MODE");
            int darkModeFlag = field.getInt(layoutParams);
            Method extraFlagField = clazz.getMethod("setExtraFlags", int.class, int.class);
            extraFlagField.invoke(activity.getWindow(), darkIcon ? darkModeFlag : 0, darkModeFlag);
        } catch (Exception e) {
            //e.printStackTrace();
        }
    }

    /**
     * 修改魅族状态栏字体颜色 Flyme 4.0
     */
    private static void setMeizuStatusBarDarkIcon(@NonNull Activity activity, boolean darkIcon) {
        try {
            WindowManager.LayoutParams lp = activity.getWindow().getAttributes();
            Field darkFlag = WindowManager.LayoutParams.class.getDeclaredField("MEIZU_FLAG_DARK_STATUS_BAR_ICON");
            Field meizuFlags = WindowManager.LayoutParams.class.getDeclaredField("meizuFlags");
            darkFlag.setAccessible(true);
            meizuFlags.setAccessible(true);
            int bit = darkFlag.getInt(null);
            int value = meizuFlags.getInt(lp);
            if (darkIcon) {
                value |= bit;
            } else {
                value &= ~bit;
            }
            meizuFlags.setInt(lp, value);
            activity.getWindow().setAttributes(lp);
        } catch (Exception e) {
            //e.printStackTrace();
        }
    }
}
