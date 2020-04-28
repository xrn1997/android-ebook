package com.ebook.book.mvp.view.libraryview;

import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;


import com.ebook.common.view.flowlayout.FlowLayout;
import com.ebook.common.view.flowlayout.TagAdapter;
import com.ebook.db.entity.LibraryNewBook;
import com.ebook.book.R;

import java.util.ArrayList;

public class LibraryNewBooksAdapter extends TagAdapter<LibraryNewBook> {
    private LibraryNewBooksView.OnClickAuthorListener clickNewBookListener;

    public LibraryNewBooksAdapter() {
        super(new ArrayList<LibraryNewBook>());
    }

    @Override
    public View getView(FlowLayout parent, int position, final LibraryNewBook libraryNewBook) {
        TextView tv = (TextView) LayoutInflater.from(parent.getContext()).inflate(R.layout.adapter_library_hotauthor_item,
                parent, false);
        tv.setText(libraryNewBook.getName());
        tv.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if(null != clickNewBookListener){
                    clickNewBookListener.clickNewBook(libraryNewBook);
                }
            }
        });
        return tv;
    }

    public LibraryNewBook getItemData(int position){
        return mTagDatas.get(position);
    }

    public int getDataSize(){
        return mTagDatas.size();
    }

    public void setClickNewBookListener(LibraryNewBooksView.OnClickAuthorListener clickNewBookListener) {
        this.clickNewBookListener = clickNewBookListener;
    }
}
