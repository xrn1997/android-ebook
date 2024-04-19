package com.ebook.common.util;

import android.Manifest;

import androidx.fragment.app.FragmentActivity;

import com.tbruyelle.rxpermissions2.RxPermissions;

import io.reactivex.functions.Consumer;

/**
 * Description: <PermissionCheckUtil><br>
 */
public class PermissionCheckUtil {
    public static void check(FragmentActivity activity) {
        new RxPermissions(activity).request(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE).subscribe(new Consumer<Boolean>() {
            @Override
            public void accept(Boolean aBoolean) throws Exception {
                if (!aBoolean) {
                    ToastUtil.showToast("缺少定位权限、存储权限，这会导致地图、导航、拍照等部分功能无法使用");
                }
            }
        });
    }
}
