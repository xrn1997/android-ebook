package com.ebook.common.view;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.ebook.common.R;
import com.ebook.common.util.DisplayUtil;
import com.ebook.common.util.MultiMediaUtil;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

import java.util.List;

import me.nereo.multi_image_selector.MultiImageSelectorActivity;


public class PhotoSelectDialog extends BottomSheetDialogFragment implements View.OnClickListener {

    public static final String TAG = PhotoSelectDialog.class.getSimpleName();
    private OnPhotoClickListener mOnClickListener;
    private String mPhotoPath;

    public static PhotoSelectDialog newInstance() {
        return new PhotoSelectDialog();
    }

    public void setOnClickListener(OnPhotoClickListener onPhotoClickLisener) {
        mOnClickListener = onPhotoClickLisener;
    }

    @Override
    public void onStart() {
        super.onStart();
        getDialog().getWindow().setLayout(getResources().getDisplayMetrics().widthPixels - DisplayUtil.dip2px(16) * 2, ViewGroup.LayoutParams.WRAP_CONTENT);
        getDialog().getWindow().findViewById(com.google.android.material.R.id.design_bottom_sheet).setBackgroundResource(android.R.color.transparent);
    }

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_photo_select, container, false);
        Button btnSelectPhoto = (Button) view.findViewById(R.id.btn_select_photo);
        Button btnTakePhoto = (Button) view.findViewById(R.id.btn_take_photo);
        Button btnCancel = (Button) view.findViewById(R.id.btn_cancel);

        btnSelectPhoto.setOnClickListener(this);
        btnTakePhoto.setOnClickListener(this);
        btnCancel.setOnClickListener(this);
        return view;
    }

    @Override
    public void onClick(View v) {
        int i = v.getId();
        if (i == R.id.btn_take_photo) {
            mPhotoPath = MultiMediaUtil.getPhotoPath((AppCompatActivity) getActivity());
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
                    if (mOnClickListener != null) {
                        mOnClickListener.onSelectPhotoClick(path);
                    }
                    dismiss();
                }
                break;
            case MultiMediaUtil.TAKE_PHONE:
                Log.v(TAG, "img path:" + mPhotoPath);
                if (mOnClickListener != null) {
                    mOnClickListener.onTakePhotoClick(mPhotoPath);
                }
                dismiss();
                break;
        }
    }

    public interface OnPhotoClickListener {
        void onTakePhotoClick(String path);

        void onSelectPhotoClick(List<String> list);
    }
}
