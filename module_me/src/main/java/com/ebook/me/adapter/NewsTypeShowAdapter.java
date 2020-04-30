package com.ebook.me.adapter;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import com.ebook.api.newstype.entity.NewsType;
import com.ebook.common.adapter.BaseAdapter;
import com.ebook.common.util.DateUtil;
import com.ebook.me.R;


public class NewsTypeShowAdapter extends BaseAdapter<NewsType, NewsTypeShowAdapter.MyViewHolder> {
    private DeleteClickLisenter mDeleteClickLisenter;

    public interface DeleteClickLisenter {
        void onClickDeleteListener(int id);
    }

    public void setDeleteClickLisenter(DeleteClickLisenter deleteClickLisenter) {
        mDeleteClickLisenter = deleteClickLisenter;
    }

    public NewsTypeShowAdapter(Context context) {
        super(context);
    }

    @Override
    protected int onBindLayout() {
        return R.layout.adapter_news_type_show_item;
    }

    @Override
    protected MyViewHolder onCreateHolder(View view) {
        return new MyViewHolder(view);
    }

    @Override
    protected void onBindData(MyViewHolder holder, NewsType newsType, int position) {
        holder.mTxtNewTypeTitle.setText(newsType.getTypename());
        holder.mTxtNewTypeTime.setText(DateUtil.formatDate(newsType.getAddtime(),DateUtil.FormatType.MMddHHmm));
    }

    class MyViewHolder extends RecyclerView.ViewHolder {
        TextView mTxtNewTypeTitle;
        TextView mTxtNewTypeTime;
        Button mBtnNewsTypeDelete;

        public MyViewHolder(@NonNull View itemView) {
            super(itemView);
            mTxtNewTypeTitle = itemView.findViewById(R.id.txt_me_news_type_title);
            mTxtNewTypeTime = itemView.findViewById(R.id.txt_me_news_type_time);
            mBtnNewsTypeDelete = itemView.findViewById(R.id.btn_me_news_type_delete);
            mBtnNewsTypeDelete.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (mDeleteClickLisenter != null) {
                        mDeleteClickLisenter.onClickDeleteListener(mList.get(getLayoutPosition()).getId());
                    }
                }
            });
        }
    }
}
