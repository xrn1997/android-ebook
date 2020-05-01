package com.ebook.find.adapter;

import android.content.Context;
import android.content.Intent;

import com.ebook.basebook.mvp.presenter.impl.BookDetailPresenterImpl;
import com.ebook.basebook.mvp.view.impl.BookDetailActivity;
import com.ebook.common.adapter.BaseBindAdapter;
import com.ebook.common.util.ObservableListUtil;
import com.ebook.db.entity.LibraryKindBookList;
import com.ebook.db.entity.SearchBook;
import com.ebook.find.R;
import com.ebook.find.databinding.ViewLibraryKindbookBinding;

import androidx.databinding.ObservableArrayList;
import androidx.recyclerview.widget.LinearLayoutManager;

import static com.blankj.utilcode.util.ActivityUtils.startActivity;


public class LibraryBookListAdapter extends BaseBindAdapter<LibraryKindBookList, ViewLibraryKindbookBinding> {
    private LibraryBookAdapter libraryBookAdapter;
    private ObservableArrayList<SearchBook> searchBooks;
    public LibraryBookListAdapter(Context context, ObservableArrayList<LibraryKindBookList> items) {
        super(context, items);
    }
    @Override
    protected int getLayoutItemId(int viewType) {
        return R.layout.view_library_kindbook;
    }

    @Override
    protected void onBindItem(ViewLibraryKindbookBinding binding, LibraryKindBookList item, int position) {
        binding.setLibraryKindBookList(item);
        searchBooks=new ObservableArrayList<>();
        searchBooks.addAll(item.getBooks());
        libraryBookAdapter=new LibraryBookAdapter(context,searchBooks);
        searchBooks.addOnListChangedCallback(ObservableListUtil.getListChangedCallback(libraryBookAdapter));
        libraryBookAdapter.setItemClickListener(new BaseBindAdapter.OnItemClickListener<SearchBook>() {
            @Override
            public void onItemClick(SearchBook searchBook, int position) {
                Intent intent = new Intent(context, BookDetailActivity.class);
                intent.putExtra("from", BookDetailPresenterImpl.FROM_SEARCH);
                intent.putExtra("data", searchBook);
                startActivity(intent);
            }
        });
        binding.rvBooklist.setAdapter(libraryBookAdapter);
    }
}
