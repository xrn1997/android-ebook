package com.ebook.book.adapter;

import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;


import com.ebook.book.R;
import com.ebook.basebook.view.ChapterListView;
import com.ebook.db.entity.BookShelf;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

public class ChapterListAdapter extends RecyclerView.Adapter<ChapterListAdapter.Viewholder> {
    private BookShelf bookShelf;
    private ChapterListView.OnItemClickListener itemClickListener;
    private int index = 0;
    private Boolean isAsc = true;

    public ChapterListAdapter(BookShelf bookShelf, @NonNull ChapterListView.OnItemClickListener itemClickListener) {
        this.bookShelf = bookShelf;
        this.itemClickListener = itemClickListener;
    }

    @Override
    public Viewholder onCreateViewHolder(ViewGroup parent, int viewType) {
        return new Viewholder(LayoutInflater.from(parent.getContext()).inflate(R.layout.view_adapter_chapterlist, parent, false));
    }

    @Override
    public void onBindViewHolder(Viewholder holder, final int posiTion) {
        if (posiTion == getItemCount() - 1) {
            holder.vLine.setVisibility(View.INVISIBLE);
        } else
            holder.vLine.setVisibility(View.VISIBLE);

        final int position;
        if (isAsc) {
            position = posiTion;
        } else {
            position = getItemCount() - 1 - posiTion;
        }
        holder.tvName.setText(bookShelf.getBookInfo().getChapterlist().get(position).getDurChapterName());
        holder.flContent.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setIndex(position);
                itemClickListener.itemClick(position);
            }
        });
        if (position == index) {
            holder.flContent.setBackgroundColor(Color.parseColor("#cfcfcf"));
            holder.flContent.setClickable(false);
        } else {
            holder.flContent.setBackgroundResource(R.drawable.bg_ib_pre2);
            holder.flContent.setClickable(true);
        }
    }

    @Override
    public int getItemCount() {
        if (bookShelf == null)
            return 0;
        else
            return bookShelf.getBookInfo().getChapterlist().size();
    }

    public class Viewholder extends RecyclerView.ViewHolder {
        private FrameLayout flContent;
        private TextView tvName;
        private View vLine;

        public Viewholder(View itemView) {
            super(itemView);
            flContent = (FrameLayout) itemView.findViewById(R.id.fl_content);
            tvName = (TextView) itemView.findViewById(R.id.tv_name);
            vLine = itemView.findViewById(R.id.v_line);
        }
    }

    public int getIndex() {
        return index;
    }

    public void setIndex(int index) {
        notifyItemChanged(this.index);
        this.index = index;
        notifyItemChanged(this.index);
    }
}
