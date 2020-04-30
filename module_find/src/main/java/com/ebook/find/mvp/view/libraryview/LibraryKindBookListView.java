package com.ebook.find.mvp.view.libraryview;

import android.annotation.SuppressLint;
import android.content.Context;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;


import com.ebook.common.util.DisplayUtil;
import com.ebook.db.entity.LibraryKindBookList;
import com.ebook.db.entity.SearchBook;
import com.ebook.find.R;


import java.util.List;

import androidx.annotation.Nullable;

public class LibraryKindBookListView extends LinearLayout{
    public interface OnItemListener{
        public void onClickMore(String title, String url);
        public void onClickBook(ImageView animView, SearchBook searchBook);
    }
    public LibraryKindBookListView(Context context) {
        super(context);
        init();
    }

    public LibraryKindBookListView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public LibraryKindBookListView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    @SuppressLint("NewApi")
    public LibraryKindBookListView(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        init();
    }

    private void init(){
        setOrientation(VERTICAL);
        setVisibility(GONE);
        LayoutInflater.from(getContext()).inflate(R.layout.view_library_hotauthor, this, true);
    }

    public void updateData(List<LibraryKindBookList> datas, OnItemListener itemListener){
        removeAllViews();
        if(datas!=null && datas.size()>0){
            setVisibility(VISIBLE);
            for(int i=0;i<datas.size();i++){
                if(i>0){
                    LayoutParams layoutParams = new LayoutParams(LayoutParams.MATCH_PARENT, DisplayUtil.dip2px(1f));
                    layoutParams.setMargins(0,DisplayUtil.dip2px(5),0,0);
                    View view = new View(getContext());
                    view.setBackgroundColor(getContext().getResources().getColor(R.color.bg_library));
                    view.setLayoutParams(layoutParams);
                    addView(view);
                }
                LibraryKindBookView itemView = new LibraryKindBookView(getContext());
                itemView.updateData(datas.get(i),itemListener);
                addView(itemView);
            }
        }else{
            setVisibility(GONE);
        }
    }
}
