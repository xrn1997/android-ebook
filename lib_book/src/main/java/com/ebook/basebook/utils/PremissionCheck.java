package com.ebook.basebook.utils;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;

import androidx.core.content.PermissionChecker;

public class PremissionCheck {
    public static Boolean checkPremission(Context context,String permission){
        boolean result = false;
        if (getTargetSdkVersion(context) >= Build.VERSION_CODES.M && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            result = context.checkSelfPermission(permission)
                    == PackageManager.PERMISSION_GRANTED;
        } else {
            result = PermissionChecker.checkSelfPermission(context, permission)
                    == PermissionChecker.PERMISSION_GRANTED;
        }
        return result;
    }

    private static int getTargetSdkVersion(Context context) {
        int version = 0;
        try {
            final PackageInfo info = context.getPackageManager().getPackageInfo(
                    context.getPackageName(), 0);
            version = info.applicationInfo.targetSdkVersion;
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
        }
        return version;
    }
    public static void requestPermissionSetting(Context from) {
        try {
            Intent localIntent = new Intent();
            localIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            localIntent.setAction("android.settings.APPLICATION_DETAILS_SETTINGS");
            localIntent.setData(Uri.fromParts("package", from.getPackageName(), null));
            from.startActivity(localIntent);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

}
