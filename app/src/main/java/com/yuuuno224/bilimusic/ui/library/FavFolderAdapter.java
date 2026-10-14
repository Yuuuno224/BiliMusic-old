package com.yuuuno224.bilimusic.ui.library;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.yuuuno224.bilimusic.R;
import com.yuuuno224.bilimusic.model.FavoriteFolder;
import com.yuuuno224.bilimusic.util.ImageLoader;

import java.util.List;

/**
 * 收藏夹列表适配器：一行一个收藏夹（封面 + 名字 + 内容数量）。
 *
 * <p>和 {@code SongAdapter} 相比它简单得多，因为它不需要感知播放状态——
 * 点一个收藏夹只是"进入下一层"，跟播放器无关。
 * 这也说明一个原则：适配器只做"把数据绑到视图 + 把点击报出去"，别的都不管。
 */
public class FavFolderAdapter extends RecyclerView.Adapter<FavFolderAdapter.Holder> {

    /** 点击某个收藏夹。 */
    public interface OnFolderClick {
        void onClick(FavoriteFolder folder);
    }

    private final List<FavoriteFolder> folders;
    private final OnFolderClick click;

    public FavFolderAdapter(List<FavoriteFolder> folders, OnFolderClick click) {
        this.folders = folders;
        this.click = click;
    }

    /** 不监听播放器，所以 release 是空实现；保留它是为了让调用方写法统一。 */
    public void release() {
        // 无需注销任何监听
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_fav_folder, parent, false);
        return new Holder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        FavoriteFolder folder = folders.get(position);

        holder.title.setText(folder.title);
        holder.count.setText(folder.countText());

        if (holder.coverRequest != null) {
            holder.coverRequest.cancel();
        }
        holder.coverRequest = ImageLoader.load(folder.cover, bitmap -> {
            if (bitmap != null) {
                holder.cover.setImageBitmap(bitmap);
            } else {
                holder.cover.setImageResource(R.drawable.ic_music_note);
            }
        });

        holder.itemView.setOnClickListener(view -> {
            if (click != null) {
                click.onClick(folder);
            }
        });
    }

    @Override
    public void onViewRecycled(@NonNull Holder holder) {
        super.onViewRecycled(holder);
        if (holder.coverRequest != null) {
            holder.coverRequest.cancel();
            holder.coverRequest = null;
        }
    }

    @Override
    public int getItemCount() {
        return folders.size();
    }

    static class Holder extends RecyclerView.ViewHolder {

        final ImageView cover;
        final TextView title;
        final TextView count;

        ImageLoader.Request coverRequest;

        Holder(@NonNull View itemView) {
            super(itemView);
            cover = itemView.findViewById(R.id.folder_cover);
            title = itemView.findViewById(R.id.folder_title);
            count = itemView.findViewById(R.id.folder_count);
        }
    }
}
