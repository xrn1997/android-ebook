package com.ebook.common.view.profilePhoto;

import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.ebook.common.R;
import com.ebook.common.databinding.ActivityClipImageBinding;
import com.xrn1997.common.mvvm.view.BaseActivity;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;


/**
 * 头像裁剪Activity
 */
public class ClipImageActivity extends BaseActivity<ActivityClipImageBinding> {
    private static final String TAG = "ClipImageActivity";
    private ClipViewLayout clipViewLayout1;
    private ClipViewLayout clipViewLayout2;
    //类别 1: 圆形, 2: 正方形
    private int type;


    /**
     * 初始化组件
     */
    @Override
    public void initView() {
        clipViewLayout1 = findViewById(R.id.clipViewLayout1);
        clipViewLayout2 = findViewById(R.id.clipViewLayout2);
        TextView btnCancel = findViewById(R.id.btn_cancel);
        TextView btnOk = findViewById(R.id.bt_ok);
        //设置点击事件监听器
        btnCancel.setOnClickListener(v -> finish());
        btnOk.setOnClickListener(v -> generateUriAndReturn());
    }

    @Override
    public void initData() {
        type = getIntent().getIntExtra("type", 1);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (type == 1) {
            clipViewLayout1.setVisibility(View.VISIBLE);
            clipViewLayout2.setVisibility(View.GONE);
            //设置图片资源
            clipViewLayout1.setImageSrc(getIntent().getData());
        } else {
            clipViewLayout2.setVisibility(View.VISIBLE);
            clipViewLayout1.setVisibility(View.GONE);
            //设置图片资源
            clipViewLayout2.setImageSrc(getIntent().getData());
        }
    }

    @NonNull
    @Override
    public String getToolBarTitle() {
        return "截取";
    }

    @Override
    public boolean enableToolbar() {
        return true;
    }

    /**
     * 生成Uri并且通过setResult返回给打开的activity
     */
    private void generateUriAndReturn() {
        //调用返回剪切图
        Bitmap zoomedCropBitmap;
        if (type == 1) {
            zoomedCropBitmap = clipViewLayout1.clip();
        } else {
            zoomedCropBitmap = clipViewLayout2.clip();
        }
        if (zoomedCropBitmap == null) {
            Log.e("android", "zoomedCropBitmap == null");
            return;
        }
        Uri mSaveUri = Uri.fromFile(new File(getCacheDir(), "cropped_" + System.currentTimeMillis() + ".jpg"));
        if (mSaveUri != null) {
            OutputStream outputStream = null;
            try {
                outputStream = getContentResolver().openOutputStream(mSaveUri);
                if (outputStream != null) {
                    zoomedCropBitmap.compress(Bitmap.CompressFormat.JPEG, 90, outputStream);
                }
            } catch (IOException ex) {
                Log.e("android", "Cannot open file: " + mSaveUri, ex);
            } finally {
                if (outputStream != null) {
                    try {
                        outputStream.close();
                    } catch (IOException e) {
                        Log.e(TAG, "generateUriAndReturn: ", e);
                    }
                }
            }
            Intent intent = new Intent();
            intent.setData(mSaveUri);
            setResult(RESULT_OK, intent);
            finish();
        }
    }

    @NonNull
    @Override
    public ActivityClipImageBinding onBindViewBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, boolean attachToParent) {
        return ActivityClipImageBinding.inflate(inflater, container, attachToParent);
    }
}
