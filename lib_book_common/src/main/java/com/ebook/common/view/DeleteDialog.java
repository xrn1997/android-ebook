package com.ebook.common.view;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;

import androidx.annotation.Nullable;

import com.ebook.common.R;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;


public class DeleteDialog extends BottomSheetDialogFragment implements View.OnClickListener {

    public static final String TAG = DeleteDialog.class.getSimpleName();
    private OnDeleteClickListener mOnClickListener;

    public static DeleteDialog newInstance() {
        return new DeleteDialog();
    }

    public void setOnClickListener(OnDeleteClickListener onDeleteClickListener) {
        mOnClickListener = onDeleteClickListener;
    }

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_delete_dialog, container, false);
        Button btnDelete = view.findViewById(R.id.btn_delete);
        Button btnCancel = view.findViewById(R.id.btn_cancel);
        btnDelete.setOnClickListener(this);
        btnCancel.setOnClickListener(this);
        return view;
    }

    @Override
    public void onClick(View v) {
        int i = v.getId();
        if (i == R.id.btn_delete) {
            if (mOnClickListener != null) {
                mOnClickListener.onItemClick();
            }
            dismiss();
        } else if (i == R.id.btn_cancel) {
            dismiss();
        }
    }

    public interface OnDeleteClickListener {
        void onItemClick();

    }

}
