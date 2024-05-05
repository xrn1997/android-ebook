package com.ebook.common.view.profilePhoto;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.ebook.common.R;
import com.ebook.common.util.MultiMediaUtil;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

import java.util.List;

import me.nereo.multi_image_selector.MultiImageSelectorActivity;


public class PhotoCutDialog extends BottomSheetDialogFragment implements View.OnClickListener {

    public static final String TAG = PhotoCutDialog.class.getSimpleName();
    //请求截图
    private static final int REQUEST_CROP_PHOTO = 102;
    private OnPhotoClickListener mOnClickListener;
    private String mPhotoPath;

    public static PhotoCutDialog newInstance() {
        return new PhotoCutDialog();
    }

    public void setOnClickListener(OnPhotoClickListener onPhotoClickListener) {
        mOnClickListener = onPhotoClickListener;
    }

    @Override
    public void onStart() {
        super.onStart();
    }

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_photo_select, container, false);
        Button btnSelectPhoto = view.findViewById(R.id.btn_select_photo);
        Button btnTakePhoto = view.findViewById(R.id.btn_take_photo);
        Button btnCancel = view.findViewById(R.id.btn_cancel);

        btnSelectPhoto.setOnClickListener(this);
        btnTakePhoto.setOnClickListener(this);
        btnCancel.setOnClickListener(this);
        return view;
    }

    @Override
    public void onClick(View v) {
        int i = v.getId();
        if (i == R.id.btn_take_photo) {
            mPhotoPath = MultiMediaUtil.getPhotoPath((AppCompatActivity) requireActivity());
            // Log.e(TAG, "onClick: 照相:  "+mPhotoPath);
            MultiMediaUtil.takePhoto(this, mPhotoPath, MultiMediaUtil.TAKE_PHONE);

        } else if (i == R.id.btn_select_photo) {
            MultiMediaUtil.pohotoSelect(this, 1, MultiMediaUtil.SELECT_IMAGE);

        } else if (i == R.id.btn_cancel) {
            dismiss();

        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        switch (requestCode) {
            case MultiMediaUtil.SELECT_IMAGE:
                if (resultCode == Activity.RESULT_OK) {
                    List<String> path = data.getStringArrayListExtra(MultiImageSelectorActivity.EXTRA_RESULT);
                    if (path != null) {
                        Uri uri = Uri.parse(path.get(0));
                        gotoClipActivity(uri);
                    }

                }
                break;
            case MultiMediaUtil.TAKE_PHONE:
                if (resultCode == Activity.RESULT_OK) {
                    Uri uri = Uri.parse(mPhotoPath);
                    gotoClipActivity(uri);
                }
                break;
            case REQUEST_CROP_PHOTO:
                if (resultCode == Activity.RESULT_OK) {
                    final Uri uri = data.getData();
                    if (mOnClickListener != null) {
                        mOnClickListener.onScreenPhotoClick(uri);
                    }
                    dismiss();
                }

                break;
        }
    }

    /**
     * 打开截图界面
     */
    private void gotoClipActivity(Uri uri) {
        if (uri == null) {
            return;
        }
        Intent intent = new Intent();
        intent.setClass(requireActivity(), ClipImageActivity.class);
        // 1: 圆形 2: 正方形
        int cutType = 1;
        intent.putExtra("type", cutType);
        intent.setData(uri);
        startActivityForResult(intent, REQUEST_CROP_PHOTO);
    }

    public interface OnPhotoClickListener {
        void onScreenPhotoClick(Uri uri);

    }
}
