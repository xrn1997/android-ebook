
package com.ebook.basebook.mvp.view.adapter;

import android.annotation.SuppressLint;
import android.os.Environment;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;


import com.ebook.basebook.R;
import com.ebook.basebook.view.checkbox.SmoothCheckBox;

import java.io.File;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

public class ImportBookAdapter extends RecyclerView.Adapter<ImportBookAdapter.Viewholder> {
    private final List<File> fileList;
    private final List<File> selectFileList;

    public interface OnCheckBookListener {
        void checkBook(int count);
    }

    private final OnCheckBookListener checkBookListener;

    public ImportBookAdapter(@NonNull OnCheckBookListener checkBookListener) {
        fileList = new ArrayList<>();
        selectFileList = new ArrayList<>();
        this.checkBookListener = checkBookListener;
    }

    @NonNull
    @Override
    public Viewholder onCreateViewHolder(ViewGroup parent, int viewType) {
        return new Viewholder(LayoutInflater.from(parent.getContext()).inflate(R.layout.view_adapter_importbook, parent, false));
    }

    @Override
    public void onBindViewHolder(final Viewholder holder, final int position) {
        holder.tvNmae.setText(fileList.get(position).getName());
        holder.tvSize.setText(convertByte(fileList.get(position).length()));
        holder.tvLoc.setText(fileList.get(position).getAbsolutePath().replace(Environment.getExternalStorageDirectory().getAbsolutePath(), "存储空间"));

        holder.scbSelect.setOnCheckedChangeListener((checkBox, isChecked) -> {
            if (isChecked) {
                selectFileList.add(fileList.get(position));
            } else {
                selectFileList.remove(fileList.get(position));
            }
            checkBookListener.checkBook(selectFileList.size());
        });
        if (canCheck) {
            holder.scbSelect.setVisibility(View.VISIBLE);
            holder.llContent.setOnClickListener(v -> holder.scbSelect.setChecked(!holder.scbSelect.isChecked(), true));
        } else {
            holder.scbSelect.setVisibility(View.INVISIBLE);
            holder.llContent.setOnClickListener(null);
        }
    }

    public void addData(File newItem) {
        int position = fileList.size();
        fileList.add(newItem);
        notifyItemInserted(position);
        notifyItemRangeChanged(position, 1);
    }

    private Boolean canCheck = false;

    @SuppressLint("NotifyDataSetChanged")
    public void setCanCheck(Boolean canCheck) {
        this.canCheck = canCheck;
        notifyDataSetChanged();
    }

    @Override
    public int getItemCount() {
        return fileList.size();
    }

    static class Viewholder extends RecyclerView.ViewHolder {
        LinearLayout llContent;
        TextView tvNmae;
        TextView tvSize;
        TextView tvLoc;
        SmoothCheckBox scbSelect;

        public Viewholder(View itemView) {
            super(itemView);
            llContent = itemView.findViewById(R.id.ll_content);
            tvNmae = itemView.findViewById(R.id.tv_name);
            tvSize = itemView.findViewById(R.id.tv_size);
            scbSelect = itemView.findViewById(R.id.scb_select);
            tvLoc = itemView.findViewById(R.id.tv_loc);
        }
    }

    public static String convertByte(long size) {
        DecimalFormat df = new DecimalFormat("###.#");
        float f;
        if (size < 1024) {
            f = size;
            return (df.format(Float.valueOf(f).doubleValue()) + "B");
        } else if (size < 1024 * 1024) {
            f = (float) size / (float) 1024;
            return (df.format(Float.valueOf(f).doubleValue()) + "KB");
        } else if (size < 1024 * 1024 * 1024) {
            f = (float) size / (float) (1024 * 1024);
            return (df.format(Float.valueOf(f).doubleValue()) + "MB");
        } else {
            f = (float) size / (float) (1024 * 1024 * 1024);
            return (df.format(Float.valueOf(f).doubleValue()) + "GB");
        }
    }

    public List<File> getSelectFileList() {
        return selectFileList;
    }

    public List<File> getFileList(){return fileList;}
}
